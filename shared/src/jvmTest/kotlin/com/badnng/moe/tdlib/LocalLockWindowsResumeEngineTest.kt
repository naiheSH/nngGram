package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.security.*
import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.status.*
import com.sun.jna.Pointer
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Engine, root, clock and Win32 callback are synthetic; no DLL or account is opened. */
class LocalLockWindowsResumeEngineTest {
    @Test
    fun unprotectedReadySessionDoesNotSubscribeToPowerEvents() = runBlocking {
        val f = Fixture(unprotected = true)
        try {
            assertTrue(f.engine.start())
            f.authorizeUnprotected()
            assertFalse(f.engine.localLockState.value.enabled)
            assertFalse(f.engine.localLockState.value.resumeNotificationsUnavailable)
            assertTrue(f.engine.stop(), "Stopping drains the READY action before the no-registration assertion.")
            assertTrue(f.resumeTasks.isEmpty())
            assertEquals(0, f.abi.registrations.get())
        } finally {
            f.disposeAndFinalExit()
        }
    }

    @Test
    fun earlyRegistrationSignalRechecksOriginalDeadlineAndSleepDoesNotCountAsActivity() = runBlocking {
        val f = Fixture()
        f.abi.earlyResume = true
        try {
            f.openReady()
            val original = assertNotNull(f.timer.activeDelay())
            assertEquals(3_600_000L, original)
            f.source.now = 11_000L
            f.runQueued()
            withTimeout(5_000) { while (f.timer.activeDelay() != original - 10_000L) delay(5) }
            withTimeout(5_000) { f.engine.localLockState.first { !it.resumeNotificationsUnavailable } }
            assertFalse(f.engine.localLockState.value.resumeNotificationsUnavailable)
            assertFalse(f.engine.localLockState.value.idleFallback)
            assertEquals(1, f.abi.registrations.get())

            f.source.now = 3_605_001L
            f.abi.emit(18)
            withTimeout(5_000) { f.engine.localLockState.first { it.phase == LocalLockPhase.HOT_LOCKED } }
            assertTrue(f.engine.localLockState.value.rootBlocked)
            assertEquals(1, f.abi.registrations.get())
        } finally {
            f.disposeAndFinalExit()
        }
    }

    @Test
    fun registrationFailureAndSystemInputFallbackProjectAsIndependentCapabilities() = runBlocking {
        for (registrationFails in listOf(false, true)) {
            for (inputFails in listOf(false, true)) {
                val f = Fixture(registrationFails = registrationFails, inputFails = inputFails)
                try {
                    f.openReady()
                    f.runQueued()
                    if (!registrationFails) withTimeout(5_000) {
                        f.engine.localLockState.first { !it.resumeNotificationsUnavailable }
                    }
                    assertEquals(1, f.abi.registrations.get())
                    val state = f.engine.localLockState.value
                    assertEquals(registrationFails, state.resumeNotificationsUnavailable)
                    assertEquals(inputFails, state.idleFallback)
                    assertEquals(3_600L, state.autoLockSeconds)
                    assertEquals(LocalLockPhase.UNLOCKED, state.phase)
                } finally {
                    f.disposeAndFinalExit()
                }
            }
        }
    }

    @Test
    fun disposeAndFinalExitOnEitherSideOfLateRegistrationRevokeOwnerAndUnregisterOnce() = runBlocking {
        for (exitBeforeRegistrationReturns in listOf(false, true)) {
            val f = Fixture()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.abi.registerBarrier = entered to release
            try {
                f.openReady()
                val registering = Thread({ f.runQueued() }, "synthetic-resume-register").apply { isDaemon = true; start() }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    withTimeout(10_000) { assertTrue(f.engine.close()) }
                    if (exitBeforeRegistrationReturns) f.engine.closeLocalLockResumeHostForExit()
                    release.countDown()
                    registering.join(5_000)
                    assertFalse(registering.isAlive)
                    if (!exitBeforeRegistrationReturns) f.engine.closeLocalLockResumeHostForExit()
                    f.drainQueued()
                    assertEquals(1, f.abi.unregistrations.get())
                    val reads = f.source.reads.get()
                    f.abi.emit(7)
                    delay(20)
                    assertEquals(reads, f.source.reads.get(), "A callback for the disposed owner cannot recheck idle.")
                } finally {
                    release.countDown()
                    registering.join(5_000)
                }
            } finally {
                f.disposeAndFinalExit()
            }
        }
    }

    @Test
    fun ordinaryRestartKeepsOneRegistrationAcrossAStoppedSession() = runBlocking {
        val f = Fixture()
        try {
            f.openReady()
            f.runQueued()
            withTimeout(5_000) { f.engine.localLockState.first { !it.resumeNotificationsUnavailable } }
            assertTrue(f.engine.stop())
            assertEquals(0, f.abi.unregistrations.get(), "A normal stop retains the process registration.")
            f.abi.emit(7)
            assertTrue(f.engine.start())
            f.unlockAndAuthorize(sessionIndex = 1)
            withTimeout(5_000) { while (f.resumeTasks.isEmpty()) delay(5) }
            f.runQueued() // New session gets a new lease on the same registered host.
            withTimeout(5_000) { f.engine.localLockState.first { !it.resumeNotificationsUnavailable } }
            assertEquals(1, f.abi.registrations.get())
            assertEquals(0, f.abi.unregistrations.get())
            assertTrue(f.resumeTasks.isEmpty(), "The replacement lease must not create a second native registration.")
        } finally {
            f.disposeAndFinalExit()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun queuedOldSignalIsConsumedWithoutRecheckingAfterStopRevokesTheSession() = runTest {
        val f = Fixture(engineDispatcher = StandardTestDispatcher(testScheduler))
        try {
            f.openReady()
            f.runQueued()
            runCurrent()
            assertFalse(f.engine.localLockState.value.resumeNotificationsUnavailable)
            f.source.now = 30_000L
            f.abi.emit(7) // Queued to the actor; its dispatcher has not been advanced.
            val readsBeforeStop = f.source.reads.get()
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { f.engine.stop() }
            // awaitStop's synchronous prefix revoked the old lease before the actor can consume its signal.
            runCurrent()
            assertTrue(stopping.await())
            assertEquals(readsBeforeStop, f.source.reads.get(), "The revoked ticket must not call idle.recheck().")
            assertTrue(f.engine.start())
            f.unlockAndAuthorize(sessionIndex = 1)
            withTimeout(5_000) { while (f.resumeTasks.isEmpty()) delay(5) }
            f.runQueued()
            runCurrent()
            assertEquals(LocalLockPhase.UNLOCKED, f.engine.localLockState.value.phase)
            assertEquals(3_600_000L, f.timer.activeDelay(), "The new session owns its own idle deadline.")
            assertEquals(1, f.abi.registrations.get())
        } finally {
            f.disposeAndFinalExit()
        }
    }

    private class Fixture(registrationFails: Boolean = false, inputFails: Boolean = false, unprotected: Boolean = false,
        engineDispatcher: CoroutineDispatcher = Dispatchers.Default) {
        private val root = Files.createTempDirectory("nnggram-resume-engine-test-")
        private val configPath = root.resolve("tdlib.properties").also {
            Files.writeString(it, "api_id=12345\napi_hash=${"a".repeat(32)}\n")
        }
        private val transport = SyntheticTransport()
        private val store = SyntheticPersistence(unprotected)
        val source = SyntheticIdleSource(inputFails)
        val timer = SyntheticDeadlines()
        val abi = FakeAbi(registrationFails)
        private val host = com.badnng.moe.security.syntheticWindowsResumeHost(abi, supported = true)
        val resumeTasks = ConcurrentLinkedQueue<Runnable>()
        val engine = TdEngine(ConfigurationStore(configPath, root.resolve("data")), transport, engineDispatcher)

        init {
            engine.localLockCoordinatorFactory = { lease, worker, start, changed ->
                store.identity = lease.identity()
                LocalLockCoordinator(lease, worker, start, onWitnessChanged = changed, persistence = store,
                    crypto = SyntheticCrypto(), preferences = SyntheticPreferences())
            }
            engine.localLockIdleFactory = { due, availability -> LocalLockIdleController(source, timer, due, availability) }
            engine.localLockResumeHostFactory = { host }
            engine.localLockResumeDispatch = { task -> resumeTasks.add(task) }
        }

        suspend fun openReady() {
            assertTrue(engine.start())
            withTimeout(5_000) { engine.localLockState.first { it.phase == LocalLockPhase.COLD_LOCKED } }
            assertTrue(resumeTasks.isEmpty(), "Protected cold state is not an authorized session.")
            unlockAndAuthorize(sessionIndex = 0)
            withTimeout(5_000) { while (resumeTasks.isEmpty()) delay(5) }
        }

        suspend fun unlockAndAuthorize(sessionIndex: Int) {
            withTimeout(5_000) { engine.localLockState.first { it.phase == LocalLockPhase.COLD_LOCKED } }
            engine.localLockActions.unlock("synthetic".toCharArray())
            withTimeout(5_000) { while (transport.sessions.size <= sessionIndex) delay(5) }
            val session = transport.sessions[sessionIndex]
            session.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            withTimeout(5_000) { while (session.parameterCallback == null) delay(5) }
            session.parameterCallback!!(TdParametersResult(code = null))
            withTimeout(5_000) { engine.localLockState.first { it.phase == LocalLockPhase.UNLOCKED } }
            assertTrue(resumeTasks.isEmpty(), "The preauthorization phase is not a READY session.")
            session.authorization(AuthorizationPhase.READY)
            withTimeout(5_000) { engine.state.first { it.status.authorization == AuthorizationPhase.READY } }
            withTimeout(5_000) { while (timer.activeDelay() == null) delay(5) }
        }

        suspend fun authorizeUnprotected() {
            withTimeout(5_000) { while (transport.sessions.isEmpty()) delay(5) }
            val session = transport.sessions.single()
            session.authorization(AuthorizationPhase.WAITING_PARAMETERS)
            withTimeout(5_000) { while (session.parameterCallback == null) delay(5) }
            session.parameterCallback!!(TdParametersResult(code = null))
            withTimeout(5_000) { engine.localLockState.first { it.phase == LocalLockPhase.UNPROTECTED } }
            session.authorization(AuthorizationPhase.READY)
            withTimeout(5_000) { engine.state.first { it.status.authorization == AuthorizationPhase.READY } }
        }

        fun runQueued() { assertNotNull(resumeTasks.poll()).run() }
        fun drainQueued() { while (true) (resumeTasks.poll() ?: break).run() }
        suspend fun disposeAndFinalExit() {
            withTimeout(10_000) { engine.close() }
            engine.closeLocalLockResumeHostForExit()
            drainQueued()
        }
    }

    private class FakeAbi(private val fails: Boolean) : WindowsLocalLockResumeAccess {
        val registrations = AtomicInteger()
        val unregistrations = AtomicInteger()
        @Volatile var parameters: LocalLockWindowsResumeParameters? = null
        @Volatile var earlyResume = false
        @Volatile var registerBarrier: Pair<CountDownLatch, CountDownLatch>? = null
        override fun register(flags: Int, recipient: LocalLockWindowsResumeParameters, output: Pointer): Int {
            registrations.incrementAndGet()
            parameters = recipient
            registerBarrier?.let { (entered, release) ->
                entered.countDown()
                check(release.await(7, TimeUnit.SECONDS))
            }
            if (earlyResume) recipient.callback.invoke(recipient.context, 18, null)
            if (fails) return 5
            output.setPointer(0, Pointer(0x1234))
            return 0
        }
        override fun unregister(handle: Pointer): Int { unregistrations.incrementAndGet(); return 0 }
        fun emit(type: Int) { assertNotNull(parameters).let { it.callback.invoke(it.context, type, null) } }
    }

    private class SyntheticIdleSource(private val inputFails: Boolean) : LocalLockIdleSource {
        @Volatile var now = 1_000L
        val reads = AtomicInteger()
        override fun tickMillis(): Long { reads.incrementAndGet(); return now }
        override fun lastInputDword(): Long? = if (inputFails) null else 1_000L
    }

    private class SyntheticDeadlines : LocalLockDeadlineScheduler {
        private class Task(val delay: Long, val canceled: AtomicBoolean = AtomicBoolean(false))
        private val tasks = CopyOnWriteArrayList<Task>()
        override fun schedule(delayMillis: Long, callback: () -> Unit): LocalLockDeadlineHandle {
            val task = Task(delayMillis)
            tasks += task
            return LocalLockDeadlineHandle { task.canceled.set(true) }
        }
        fun activeDelay(): Long? = tasks.lastOrNull { !it.canceled.get() }?.delay
    }

    private class SyntheticPersistence(private val unprotected: Boolean) : LocalProtectionPersistence {
        @Volatile var identity: ByteArray? = null
        private val record = LocalStoredEnvelope(byteArrayOf(7, 8), 1, ByteArray(32) { 1 })
        override fun read(): LocalRecordRead {
            if (!unprotected) return LocalRecordRead.Present(record)
            val key = LocalDatabaseKey.copyOf(ByteArray(32) { 42 })
            val encoded = try {
                (LocalOpenWrap.encode(key, checkNotNull(identity), 1) as LocalCryptoResult.Success<ByteArray>).value
            } finally { key.close() }
            return try { LocalOpenWrap.inspect(encoded, checkNotNull(identity)) } finally { encoded.fill(0) }
        }
        override fun commit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult = error("No test writes a record")
        override fun finalizeCommit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult = error("No test finalizes a record")
    }

    private class SyntheticCrypto : LocalLockCryptoOperations {
        override fun create(password: CharArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<WrappedDatabaseKey> = error("Not used")
        override fun open(password: CharArray, record: ByteArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<LocalDatabaseKey> =
            LocalCryptoResult.Success(LocalDatabaseKey.copyOf(ByteArray(32) { 42 }))
        override fun rewrap(password: CharArray, key: LocalDatabaseKey, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<ByteArray> = error("Not used")
    }

    private class SyntheticPreferences : LocalLockPreferences {
        override fun read(): LocalLockPreferenceRead = LocalLockPreferenceRead.Value(3_600)
        override fun write(seconds: Long) = error("No test writes preferences")
    }

    private class SyntheticTransport : TdTransport {
        val sessions = CopyOnWriteArrayList<SyntheticSession>()
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit): TdSession =
            SyntheticSession(onAuthorization).also { sessions += it }
    }

    private class SyntheticSession(private val onAuthorization: (AuthorizationDetails) -> Unit) : TdSession {
        @Volatile var parameterCallback: ((TdParametersResult) -> Unit)? = null
        fun authorization(phase: AuthorizationPhase) = onAuthorization(AuthorizationDetails(phase))
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = error("Protected roots require a key")
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, key: ByteArray,
            result: (TdParametersResult) -> Unit) { assertEquals(32, key.size); parameterCallback = result }
        override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
        override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login")
        override fun close() = authorization(AuthorizationPhase.CLOSED)
    }
}
