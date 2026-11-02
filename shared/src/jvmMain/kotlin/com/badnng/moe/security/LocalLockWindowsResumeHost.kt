package com.badnng.moe.security

import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary.StdCallCallback

/** PowrProf DWORD status, not User32 BOOL/NULL semantics. Never loads a DLL in fake tests. */
internal interface WindowsLocalLockResumeAccess {
    fun register(flags: Int, recipient: LocalLockWindowsResumeParameters, output: Pointer): Int
    fun unregister(handle: Pointer): Int
}

internal fun interface LocalLockWindowsResumeCallback : StdCallCallback {
    fun invoke(context: Pointer?, type: Int, setting: Pointer?): Int
}

/** Native layout is exactly two pointers. The opaque context is never dereferenced. */
internal class LocalLockWindowsResumeParameters(
    val callback: LocalLockWindowsResumeCallback,
    val context: Pointer,
    callbackAddress: (LocalLockWindowsResumeCallback) -> Pointer = CallbackReference::getFunctionPointer,
) {
    val pointer = Memory(2L * Native.POINTER_SIZE).apply {
        setPointer(0, callbackAddress(callback))
        setPointer(Native.POINTER_SIZE.toLong(), context)
    }
}

private class JnaWindowsLocalLockResumeAccess : WindowsLocalLockResumeAccess {
    override fun register(flags: Int, recipient: LocalLockWindowsResumeParameters, output: Pointer): Int =
        NativeLibrary.getInstance("PowrProf")
            .getFunction("PowerRegisterSuspendResumeNotification", Function.ALT_CONVENTION)
            .invokeInt(arrayOf(flags, recipient.pointer, output))

    override fun unregister(handle: Pointer): Int = NativeLibrary.getInstance("PowrProf")
        .getFunction("PowerUnregisterSuspendResumeNotification", Function.ALT_CONVENTION)
        .invokeInt(arrayOf(handle))
}

internal enum class LocalLockWindowsResumeAvailability {
    AVAILABLE, UNSUPPORTED, REGISTRATION_FAILED, DELIVERY_FAILED, IN_USE, CLOSED, UNREGISTER_FAILED,
}

/** Only the current actor may consume a queued notification, at most once. */
internal class LocalLockWindowsResumeSignal internal constructor(
    private val host: LocalLockWindowsResumeHost,
    internal val epoch: Long,
) {
    fun consume(): Boolean = host.consume(this)
}

internal class LocalLockWindowsResumeLease internal constructor(
    private val host: LocalLockWindowsResumeHost,
    internal val epoch: Long,
    internal val post: (LocalLockWindowsResumeSignal) -> Unit,
    private val rejected: LocalLockWindowsResumeAvailability? = null,
) : AutoCloseable {
    val availability: LocalLockWindowsResumeAvailability
        get() = rejected ?: host.availability(this)

    override fun close() {
        if (rejected == null) host.release(this)
    }
}

/**
 * One native registration and one current lease, for the whole process. Engine disposal
 * closes its lease, not this host. The process singleton strongly retains the callback,
 * subscription memory and context forever, including after unregister failure/success:
 * Windows does not document that unregister waits for callbacks already in flight.
 *
 * Native callbacks only enqueue an opaque signal (at most one pending per lease).
 * [acquire]'s post must only enqueue to an actor, never run UI/idle/IO inline. That actor
 * checks its own lifecycle, consumes the signal and calls idle.recheck(), without
 * recording activity. Lease retirement and consumption are synchronous and serialized
 * here; the owner's final idle due/witness check remains its own lock boundary.
 *
 * Neither native calls nor application callbacks run under [guard]. A callback which
 * copied a post just before close may still enqueue, but its signal is already revoked.
 * There is no timer, focus dependency, HWND subclass, or implicit retry/polling.
 */
internal class LocalLockWindowsResumeHost(
    private val access: WindowsLocalLockResumeAccess,
    private val supported: Boolean,
    private val callbackAddress: (LocalLockWindowsResumeCallback) -> Pointer = CallbackReference::getFunctionPointer,
) : AutoCloseable {
    private val guard = Any()
    private var attempted = false
    private var registering = false
    private var resumeDuringRegistration = false
    private var closed = false
    private var epoch = 0L
    private var active: LocalLockWindowsResumeLease? = null
    private var pending: LocalLockWindowsResumeSignal? = null
    private var handle: Pointer? = null
    private var status = if (supported) LocalLockWindowsResumeAvailability.REGISTRATION_FAILED
        else LocalLockWindowsResumeAvailability.UNSUPPORTED
    private var error: Int? = null
    private val context = Pointer(1L)
    private val callback = LocalLockWindowsResumeCallback { suppliedContext, type, _ ->
        // Never propagate Kotlin/JNA failures through a Windows callback boundary.
        try {
            if (Pointer.nativeValue(suppliedContext) == Pointer.nativeValue(context) &&
                (type == RESUME_SUSPEND || type == RESUME_AUTOMATIC)) publish()
        } catch (_: Throwable) {
            // This has no authority to unlock, alter a deadline, or acquire a new owner.
        }
        0
    }
    // Lazy for unsupported platforms. The production singleton retains this object too.
    private val parameters by lazy { LocalLockWindowsResumeParameters(callback, context, callbackAddress) }

    fun acquire(post: (LocalLockWindowsResumeSignal) -> Unit): LocalLockWindowsResumeLease {
        val lease: LocalLockWindowsResumeLease
        val needsRegistration: Boolean
        synchronized(guard) {
            val rejection = when {
                closed -> status
                !supported -> LocalLockWindowsResumeAvailability.UNSUPPORTED
                active != null || registering -> LocalLockWindowsResumeAvailability.IN_USE
                attempted && status != LocalLockWindowsResumeAvailability.AVAILABLE -> status
                else -> null
            }
            if (rejection != null) return LocalLockWindowsResumeLease(this, epoch, post, rejection)
            lease = LocalLockWindowsResumeLease(this, ++epoch, post)
            active = lease
            needsRegistration = !attempted
            if (needsRegistration) {
                attempted = true
                registering = true
            }
        }
        if (needsRegistration) register()
        return lease
    }

    private fun register() {
        var registered: Pointer? = null
        val result = nativeStatus {
            Memory(Native.POINTER_SIZE.toLong()).use { output ->
                output.clear()
                val value = access.register(DEVICE_NOTIFY_CALLBACK, parameters, output)
                if (value == 0) registered = output.getPointer(0)
                // A success without a usable handle is a broken ABI, never supported.
                if (value == 0 && Pointer.nativeValue(registered) == 0L) INVALID_HANDLE else value
            }
        }
        val retire: Pointer?
        val replay: Boolean
        synchronized(guard) {
            registering = false
            error = result.takeIf { it != 0 }
            if (closed) {
                retire = registered
                replay = false
            } else {
                retire = null
                handle = registered
                status = when {
                    result != 0 -> LocalLockWindowsResumeAvailability.REGISTRATION_FAILED
                    status == LocalLockWindowsResumeAvailability.DELIVERY_FAILED -> status
                    else -> LocalLockWindowsResumeAvailability.AVAILABLE
                }
                if (result != 0) {
                    active = null
                    pending = null
                    ++epoch
                }
                replay = result == 0 && resumeDuringRegistration
            }
            resumeDuringRegistration = false
        }
        if (retire != null) unregister(retire)
        else if (replay) publish()
    }

    private fun publish() {
        val delivery = synchronized(guard) {
            val owner = active
            if (closed || owner == null || pending != null) null
            else if (registering) {
                // A callback may arrive synchronously inside register. Do not send a
                // ticket which an eager actor would reject before success is known.
                resumeDuringRegistration = true
                null
            }
            else LocalLockWindowsResumeSignal(this, owner.epoch).let { signal ->
                pending = signal
                owner to signal
            }
        } ?: return
        try {
            delivery.first.post(delivery.second)
        } catch (_: Throwable) {
            synchronized(guard) {
                if (active === delivery.first) {
                    status = LocalLockWindowsResumeAvailability.DELIVERY_FAILED
                    active = null
                    pending = null
                    ++epoch
                }
            }
        }
    }

    internal fun consume(signal: LocalLockWindowsResumeSignal): Boolean = synchronized(guard) {
        if (closed || status != LocalLockWindowsResumeAvailability.AVAILABLE ||
            signal !== pending || active?.epoch != signal.epoch) false
        else {
            pending = null
            true
        }
    }

    internal fun availability(lease: LocalLockWindowsResumeLease): LocalLockWindowsResumeAvailability =
        synchronized(guard) {
            if (closed || status != LocalLockWindowsResumeAvailability.AVAILABLE) status
            else if (active === lease) status else LocalLockWindowsResumeAvailability.CLOSED
        }

    internal fun release(lease: LocalLockWindowsResumeLease) {
        synchronized(guard) {
            if (active !== lease) return
            active = null
            pending = null
            ++epoch
        }
    }

    /** Final application shutdown only. Does not wait for an in-flight register/callback. */
    override fun close() {
        val retire = synchronized(guard) {
            if (closed) return
            closed = true
            active = null
            pending = null
            ++epoch
            status = LocalLockWindowsResumeAvailability.CLOSED
            handle.also { handle = null }
        }
        if (retire != null) unregister(retire)
    }

    private fun unregister(value: Pointer) {
        val result = nativeStatus { access.unregister(value) }
        synchronized(guard) {
            if (result != 0) {
                status = LocalLockWindowsResumeAvailability.UNREGISTER_FAILED
                error = result
            }
        }
        // Deliberately no Memory.close/CallbackReference.dispose: late native callers
        // still point at process-pinned storage and a callback which sees closed=true.
    }

    internal fun nativeError(): Int? = synchronized(guard) { error }

    private inline fun nativeStatus(call: () -> Int): Int = try {
        call()
    } catch (_: LinkageError) {
        NATIVE_UNAVAILABLE
    } catch (_: Exception) {
        NATIVE_UNAVAILABLE
    }

    companion object {
        private const val DEVICE_NOTIFY_CALLBACK = 2
        private const val RESUME_SUSPEND = 7
        private const val RESUME_AUTOMATIC = 18
        private const val INVALID_HANDLE = 6
        private const val NATIVE_UNAVAILABLE = -1

        /** Only production constructor path: bounded process lifetime native storage. */
        val process: LocalLockWindowsResumeHost by lazy {
            LocalLockWindowsResumeHost(JnaWindowsLocalLockResumeAccess(),
                System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true))
        }
    }
}
