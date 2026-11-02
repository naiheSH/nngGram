package com.badnng.moe.security

import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Synthetic PowrProf boundary only: never registers Windows, sleeps the OS, or forces GC. */
class LocalLockWindowsResumeHostTest {
    @Test
    fun actualWindowsCallbackCanProduceItsNativeFunctionPointer() {
        org.junit.Assume.assumeTrue("Actual Windows stdcall trampoline", com.sun.jna.Platform.isWindows())
        val parameters = LocalLockWindowsResumeParameters(LocalLockWindowsResumeCallback { _, _, _ -> 0 }, Pointer(1L))
        assertTrue(Pointer.nativeValue(parameters.pointer.getPointer(0)) != 0L)
    }

    @Test
    fun registrationUsesCallbackFlagTwoPointersAndDwordOutHandleContract() {
        val abi = FakeAbi()
        val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, supported = true)
        val lease = host.acquire { error("no notification expected") }
        assertEquals(LocalLockWindowsResumeAvailability.AVAILABLE, lease.availability)
        assertEquals(1, abi.registerCalls)
        assertEquals(2, abi.flags)
        val parameters = assertNotNull(abi.parameters)
        assertEquals(2L * Native.POINTER_SIZE, parameters.pointer.size())
        assertTrue(Pointer.nativeValue(parameters.pointer.getPointer(0)) != 0L)
        assertEquals(Pointer.nativeValue(parameters.context),
            Pointer.nativeValue(parameters.pointer.getPointer(Native.POINTER_SIZE.toLong())))
        assertTrue(Pointer.nativeValue(parameters.context) != 0L)
        host.close()
        assertEquals(listOf(abi.handle), abi.unregistered)
        assertEquals(LocalLockWindowsResumeAvailability.CLOSED, lease.availability)
    }

    @Test
    fun resumeSevenAndEighteenCoalesceAndEachSignalConsumesOnlyOnce() {
        val abi = FakeAbi()
        val queue = mutableListOf<LocalLockWindowsResumeSignal>()
        val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, true)
        host.acquire(queue::add)
        assertEquals(0, abi.emit(7))
        repeat(40) { assertEquals(0, abi.emit(18)) }
        assertEquals(1, queue.size, "The native callback cannot grow an unbounded owner queue.")
        assertTrue(queue[0].consume())
        assertFalse(queue[0].consume())
        abi.emit(18)
        assertEquals(2, queue.size)
        assertTrue(queue[1].consume())
        host.close()
    }

    @Test
    fun suspendUnknownAndWrongContextCannotPublishAResume() {
        val abi = FakeAbi()
        val queue = mutableListOf<LocalLockWindowsResumeSignal>()
        val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, true)
        host.acquire(queue::add)
        for (type in listOf(4, 6, 0, -1, Int.MAX_VALUE)) assertEquals(0, abi.emit(type))
        assertEquals(0, abi.emit(7, Pointer(99)))
        assertEquals(0, abi.emit(18, null))
        assertTrue(queue.isEmpty())
        host.close()
    }

    @Test
    fun duplicateLeaseCannotReplaceOwnerAndRetirementRevokesQueuedEpoch() {
        val abi = FakeAbi()
        val oldQueue = mutableListOf<LocalLockWindowsResumeSignal>()
        val newQueue = mutableListOf<LocalLockWindowsResumeSignal>()
        val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, true)
        val old = host.acquire(oldQueue::add)
        assertEquals(LocalLockWindowsResumeAvailability.IN_USE,
            host.acquire { error("rejected owner") }.availability)
        abi.emit(7)
        old.close()
        old.close()
        assertFalse(oldQueue.single().consume())
        assertEquals(LocalLockWindowsResumeAvailability.CLOSED, old.availability)
        val replacement = host.acquire(newQueue::add)
        assertEquals(LocalLockWindowsResumeAvailability.AVAILABLE, replacement.availability)
        assertEquals(1, abi.registerCalls, "One process subscription survives lease rebinding.")
        assertTrue(abi.unregistered.isEmpty())
        old.close() // old lease cannot close its replacement
        abi.emit(18)
        assertTrue(newQueue.single().consume())
        host.close()
    }

    @Test
    fun registerErrorNullSuccessAndLinkageFailureAreExplicitlyUnavailableWithoutRetry() {
        for (abi in listOf(FakeAbi(result = 5), FakeAbi(nullHandle = true), FakeAbi(linkageFailure = true))) {
            val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, true)
            val queue = mutableListOf<LocalLockWindowsResumeSignal>()
            val lease = host.acquire(queue::add)
            assertEquals(LocalLockWindowsResumeAvailability.REGISTRATION_FAILED, lease.availability)
            assertNotNull(host.nativeError())
            abi.emit(7)
            assertTrue(queue.isEmpty())
            assertEquals(LocalLockWindowsResumeAvailability.REGISTRATION_FAILED, host.acquire(queue::add).availability)
            assertEquals(1, abi.registerCalls)
            host.close()
            assertTrue(abi.unregistered.isEmpty())
        }
    }

    @Test
    fun unsupportedPlatformNeverCallsNativeBoundary() {
        val abi = FakeAbi()
        val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, false)
        assertEquals(LocalLockWindowsResumeAvailability.UNSUPPORTED, host.acquire {}.availability)
        host.close()
        assertEquals(0, abi.registerCalls)
        assertTrue(abi.unregistered.isEmpty())
    }

    @Test
    fun notificationDuringRegisterWaitsForSuccessfulLeaseBeforeItCanBeConsumed() {
        val queue = mutableListOf<LocalLockWindowsResumeSignal>()
        val abi = FakeAbi()
        abi.duringRegister = { parameters ->
            parameters.callback.invoke(parameters.context, 18, null)
            assertTrue(queue.isEmpty(), "Only successful registration can publish to the owner.")
        }
        val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, true)
        val lease = host.acquire(queue::add)
        assertEquals(LocalLockWindowsResumeAvailability.AVAILABLE, lease.availability)
        assertTrue(queue.single().consume())
        host.close()
    }

    @Test
    fun postExceptionCannotCrossCallbackOrClaimSupportEvenDuringRegistration() {
        for (duringRegister in listOf(false, true)) {
            val abi = FakeAbi()
            if (duringRegister) abi.duringRegister = { it.callback.invoke(it.context, 7, null) }
            val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, true)
            val lease = host.acquire { throw IllegalStateException("synthetic rejected owner queue") }
            if (!duringRegister) assertEquals(0, abi.emit(7))
            assertEquals(LocalLockWindowsResumeAvailability.DELIVERY_FAILED, lease.availability)
            host.close()
            assertEquals(listOf(abi.handle), abi.unregistered)
        }
    }

    @Test
    fun failedUnregisterRevokesSignalsAndKeepsCallbackStorageUsableForLateNativeCallers() {
        val abi = FakeAbi(unregisterResult = 87)
        val queue = mutableListOf<LocalLockWindowsResumeSignal>()
        val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, true)
        val lease = host.acquire(queue::add)
        abi.emit(18)
        val retainedParameters = assertNotNull(abi.parameters)
        host.close()
        assertEquals(LocalLockWindowsResumeAvailability.UNREGISTER_FAILED, lease.availability)
        assertEquals(87, host.nativeError())
        assertFalse(queue.single().consume())
        repeat(4) { assertEquals(0, abi.emit(7)) }
        assertEquals(1, queue.size)
        assertSame(retainedParameters, abi.parameters)
        assertTrue(Pointer.nativeValue(retainedParameters.pointer.getPointer(0)) != 0L)
        host.close()
        assertEquals(1, abi.unregistered.size, "Close is idempotent, no retry loop.")
        assertEquals(LocalLockWindowsResumeAvailability.UNREGISTER_FAILED, host.acquire {}.availability)
    }

    @Test
    fun closeDoesNotWaitForInFlightPostAndLateSignalCannotAuthorizeReplacementLease() {
        val abi = FakeAbi()
        val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, true)
        val entered = CountDownLatch(1)
        val continuePost = CountDownLatch(1)
        val late = AtomicReference<LocalLockWindowsResumeSignal>()
        val failure = AtomicReference<Throwable>()
        val lease = host.acquire { signal ->
            entered.countDown()
            check(continuePost.await(5, TimeUnit.SECONDS))
            late.set(signal)
        }
        val callbackThread = Thread {
            try { abi.emit(18) } catch (problem: Throwable) { failure.set(problem) }
        }
        callbackThread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            lease.close() // post is blocked outside the host monitor
            val next = mutableListOf<LocalLockWindowsResumeSignal>()
            host.acquire(next::add)
            continuePost.countDown()
            callbackThread.join(5_000)
            assertFalse(callbackThread.isAlive)
            assertEquals(null, failure.get())
            assertFalse(assertNotNull(late.get()).consume())
            abi.emit(7)
            assertTrue(next.single().consume())
        } finally {
            continuePost.countDown()
            host.close()
            callbackThread.join(5_000)
        }
    }

    @Test
    fun closeDuringRegistrationRetiresReturnedHandleWithoutResurrectingOwner() {
        val abi = FakeAbi()
        val entered = CountDownLatch(1)
        val continueRegister = CountDownLatch(1)
        abi.duringRegister = {
            entered.countDown()
            check(continueRegister.await(5, TimeUnit.SECONDS))
        }
        val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, true)
        val lease = AtomicReference<LocalLockWindowsResumeLease>()
        val failure = AtomicReference<Throwable>()
        val registerThread = Thread {
            try { lease.set(host.acquire { error("closed owner") }) }
            catch (problem: Throwable) { failure.set(problem) }
        }
        registerThread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            host.close() // native register does not run under the host monitor
            continueRegister.countDown()
            registerThread.join(5_000)
            assertFalse(registerThread.isAlive)
            assertEquals(null, failure.get())
            assertEquals(LocalLockWindowsResumeAvailability.CLOSED, assertNotNull(lease.get()).availability)
            assertEquals(listOf(abi.handle), abi.unregistered)
            assertEquals(0, abi.emit(18))
        } finally {
            continueRegister.countDown()
            registerThread.join(5_000)
            host.close()
        }
    }

    @Test
    fun noFocusChangeResumeRechecksOriginalDeadlineAndNeverCountsAsActivity() {
        val source = object : LocalLockIdleSource {
            var now = 0L
            override fun tickMillis(): Long = now
            override fun lastInputDword(): Long = 0L
        }
        val delays = mutableListOf<Long>()
        val due = mutableListOf<LocalLockIdleDue>()
        val idle = LocalLockIdleController(source, LocalLockDeadlineScheduler { delay, _ ->
            delays.add(delay)
            LocalLockDeadlineHandle {}
        }, due::add)
        val abi = FakeAbi()
        val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, true)
        val queue = mutableListOf<LocalLockWindowsResumeSignal>()
        host.acquire(queue::add)
        idle.update(LocalLockIdleConfig(true, true, epoch = 9, timeoutSeconds = 60))
        source.now = 10_000
        abi.emit(18)
        if (queue.removeAt(0).consume()) idle.recheck()
        assertEquals(listOf(60_000L, 50_000L), delays, "Resume must not restart a fresh idle interval.")
        source.now = 64_001 // the sleeping timer has not fired, and focus never changed
        abi.emit(7)
        if (queue.removeAt(0).consume()) idle.recheck()
        assertEquals(LocalLockIdleDueReason.LATE, due.single().reason)
        assertEquals(9L, due.single().epoch)
        assertTrue(idle.claimDue(due.single()))
        host.close()
        idle.close()
    }

    private class FakeAbi(
        private val result: Int = 0,
        private val nullHandle: Boolean = false,
        private val linkageFailure: Boolean = false,
        private val unregisterResult: Int = 0,
    ) : WindowsLocalLockResumeAccess {
        var registerCalls = 0
        var flags = 0
        var parameters: LocalLockWindowsResumeParameters? = null
        val handle = Pointer(0x1234)
        val unregistered = mutableListOf<Pointer>()
        var duringRegister: ((LocalLockWindowsResumeParameters) -> Unit)? = null

        override fun register(flags: Int, recipient: LocalLockWindowsResumeParameters, output: Pointer): Int {
            registerCalls++
            this.flags = flags
            parameters = recipient
            if (linkageFailure) throw UnsatisfiedLinkError("synthetic missing PowrProf")
            duringRegister?.invoke(recipient)
            if (!nullHandle && result == 0) output.setPointer(0, handle)
            return result
        }

        override fun unregister(handle: Pointer): Int {
            unregistered.add(handle)
            return unregisterResult
        }

        fun emit(type: Int, context: Pointer? = parameters?.context): Int =
            assertNotNull(parameters).callback.invoke(context, type, null)
    }
}
