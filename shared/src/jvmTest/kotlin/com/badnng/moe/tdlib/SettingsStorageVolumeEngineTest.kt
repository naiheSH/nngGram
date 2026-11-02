package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.media.NativeSaveTargetRegistry
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Real Engine and visit/controller wiring, with only fake native and filesystem callbacks. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStorageVolumeEngineTest {
    @Test fun onlyTheAcceptedSelectedNativeFilesDirectoryMayReachTheWorker() = runTest {
        withEngine(nativeRoots = true, selectAttempt = true) { engine, f, worker, queried ->
            val expected = checkNotNull(f.selected).resolve("files")
            assertTrue(engine.start()); runCurrent()
            f.current.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            assertEquals(expected, f.current.parameters!!.files)
            f.current.auth(AuthorizationPhase.READY); runCurrent()
            assertNull(engine.openSettingsStorageOwned { true }); runCurrent()
            assertEquals(SettingsStorageVolume.Unavailable, engine.state.value.storageSettings.volume)
            assertTrue(worker.tasks.isEmpty(), "A submitted but unaccepted parameter is not a volume source.")
            f.current.acceptParameters!!(null); runCurrent()
            val handle = assertNotNull(engine.openSettingsStorageOwned { true }); runCurrent()
            assertEquals(SettingsStorageVolume.Loading, engine.state.value.storageSettings.volume)
            assertEquals(1, worker.tasks.size)
            worker.runNext(); runCurrent()
            assertEquals(listOf(expected), queried)
            assertEquals(SettingsStorageVolume.Available(1000, 300), engine.state.value.storageSettings.volume)
            assertNotEquals(f.configured.resolve("files"), queried.single())
            handle.close(); runCurrent()
        }
    }

    @Test fun ordinaryFakeNeverInfersNativeFilesFromItsLeaseOrSnapshot() = runTest {
        withEngine(nativeRoots = false) { engine, f, worker, queried ->
            assertTrue(engine.start()); runCurrent()
            f.current.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.current.acceptParameters!!(null); runCurrent()
            f.current.auth(AuthorizationPhase.READY); runCurrent()
            val handle = assertNotNull(engine.openSettingsStorageOwned { true }); runCurrent()
            assertEquals(SettingsStorageVolume.Unavailable, engine.state.value.storageSettings.volume)
            assertTrue(worker.tasks.isEmpty())
            assertTrue(engine.refreshSettingsStorageVolume(engine.state.value.storageSettings.token)); runCurrent()
            assertTrue(queried.isEmpty())
            assertTrue(worker.tasks.isEmpty())
            handle.close(); runCurrent()
        }
    }

    @Test fun oldVisitLateResultCannotReplaceAReopenedVisit() = runTest {
        withEngine(nativeRoots = true) { engine, f, worker, queried ->
            ready(engine, f)
            val first = assertNotNull(engine.openSettingsStorageOwned { true }); runCurrent()
            val firstToken = engine.state.value.storageSettings.token
            first.close()
            val second = assertNotNull(engine.openSettingsStorageOwned { true }); runCurrent()
            val secondToken = engine.state.value.storageSettings.token
            assertNotEquals(firstToken, secondToken)
            assertEquals(1, worker.tasks.size)
            worker.runNext(); runCurrent()
            assertTrue(queried.isEmpty(), "The revoked first task was not yet in physical IO.")
            assertEquals(SettingsStorageVolume.Loading, engine.state.value.storageSettings.volume)
            assertEquals(1, worker.tasks.size)
            worker.runNext(); runCurrent()
            assertEquals(SettingsStorageVolume.Available(1000, 300), engine.state.value.storageSettings.volume)
            assertEquals(secondToken, engine.state.value.storageSettings.token)
            assertEquals(1, queried.size)
            second.close(); runCurrent()
        }
    }

    @Test fun authorizationMandatoryAndStopRevokeAlreadyQueuedResults() = runTest {
        for (loss in listOf("authorization", "mandatory", "stop")) withEngine(nativeRoots = true) { engine, f, worker, _ ->
            ready(engine, f)
            val handle = assertNotNull(engine.openSettingsStorageOwned { true }); runCurrent()
            worker.runNext() // Physical read is finished; actor publication is still queued.
            val stopped = if (loss == "stop") async(start = CoroutineStart.UNDISPATCHED) { engine.stop() } else null
            when (loss) {
                "authorization" -> f.current.auth(AuthorizationPhase.OTHER_STEP)
                "mandatory" -> f.current.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            }
            runCurrent()
            if (stopped != null) assertTrue(stopped.await())
            assertNotEquals(SettingsStorageVolume.Available(1000, 300), engine.state.value.storageSettings.volume,
                "A result queued before $loss cannot publish after the synchronous owner gate closes.")
            handle.close(); runCurrent()
        }
    }

    @Test fun observedAdmissionReportsTheExactTokenAndRejectsQueuedOpenAfterGateCloses() = runTest {
        withEngine(nativeRoots = true) { engine, f, worker, queried ->
            ready(engine, f)
            val rejected = mutableListOf<SettingsStorageToken?>()
            val stale = assertNotNull(engine.openSettingsStorageOwnedObserved({ true }, rejected::add))
            assertTrue(rejected.isEmpty(), "The actor has not admitted the visit")
            f.current.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            assertFalse(stale.current(), "The gate synchronously revokes this queued visit")
            runCurrent()
            assertEquals(listOf<SettingsStorageToken?>(null), rejected)
            assertTrue(worker.tasks.isEmpty())
            assertTrue(queried.isEmpty())
            stale.close()
            runCurrent()
            assertEquals(listOf<SettingsStorageToken?>(null), rejected, "Discard, gate revocation and close report once")

            f.current.gate.satisfyRequired(1)
            runCurrent()
            val accepted = mutableListOf<SettingsStorageToken?>()
            val current = assertNotNull(engine.openSettingsStorageOwnedObserved({ true }, accepted::add))
            runCurrent()
            val token = engine.state.value.storageSettings.token
            assertEquals(listOf<SettingsStorageToken?>(token), accepted, "The receipt belongs to this exact actor-owned visit")
            assertTrue(engine.state.value.storageSettings.opened)
            assertEquals(1, worker.tasks.size)
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            runCurrent()
            assertEquals(listOf(token, null), accepted, "Stop revokes the admitted page exactly once")
            assertTrue(stopping.await())
            worker.runNext()
            runCurrent()
            assertTrue(queried.isEmpty(), "The revoked queued read cannot enter its filesystem callback")
            assertEquals(listOf(token, null), accepted)
            current.close()
        }
    }

    @Test fun aRejectedNativeParameterNeverBecomesAVolumeSource() = runTest {
        withEngine(nativeRoots = true) { engine, f, worker, queried ->
            assertTrue(engine.start()); runCurrent()
            f.current.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.current.auth(AuthorizationPhase.READY); runCurrent()
            val status = mutableListOf<SettingsStorageToken?>()
            assertNull(engine.openSettingsStorageOwnedObserved({ true }, status::add))
            runCurrent()
            assertTrue(status.isEmpty(), "No page owner is admitted before the local startup key is accepted")
            assertEquals(SettingsStorageVolume.Unavailable, engine.state.value.storageSettings.volume)
            f.current.acceptParameters!!(400)
            runCurrent()
            assertTrue(worker.tasks.isEmpty())
            assertTrue(queried.isEmpty())
            assertTrue(status.isEmpty())
        }
    }

    @Test fun anOldGenerationCannotPublishIntoTheNewGeneration() = runTest {
        withEngine(nativeRoots = true) { engine, f, worker, queried ->
            ready(engine, f)
            val oldStatus = mutableListOf<SettingsStorageToken?>()
            val old = assertNotNull(engine.openSettingsStorageOwnedObserved({ true }, oldStatus::add))
            runCurrent()
            val oldToken = assertNotNull(oldStatus.single())
            assertEquals(1, worker.tasks.size)
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            runCurrent()
            assertEquals(listOf(oldToken, null), oldStatus)
            assertTrue(stopping.await())

            ready(engine, f)
            val freshStatus = mutableListOf<SettingsStorageToken?>()
            val fresh = assertNotNull(engine.openSettingsStorageOwnedObserved({ true }, freshStatus::add))
            runCurrent()
            val freshToken = assertNotNull(freshStatus.single())
            assertNotEquals(oldToken.generation, freshToken.generation)
            assertEquals(1, worker.tasks.size, "The old physical slot still owns the queue")
            worker.runNext(); runCurrent()
            assertTrue(queried.isEmpty(), "The dead task never entered physical I/O")
            assertEquals(SettingsStorageVolume.Loading, engine.state.value.storageSettings.volume)
            assertEquals(freshToken, engine.state.value.storageSettings.token)
            worker.runNext(); runCurrent()
            assertEquals(1, queried.size)
            assertEquals(SettingsStorageVolume.Available(1000, 300), engine.state.value.storageSettings.volume)
            assertEquals(freshToken, engine.state.value.storageSettings.token)
            old.close(); fresh.close(); runCurrent()
        }
    }

    @Test fun aStartedOldGenerationReadKeepsItsPhysicalSlotAcrossConfirmedStopAndRestart() = runTest {
        withEngine(nativeRoots = true) { engine, f, _, _ ->
            val worker = ManualExecutor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reads = CopyOnWriteArrayList<Path>()
            val failure = AtomicReference<Throwable?>()
            val oldValue = SettingsStorageVolume.Available(999, 888)
            val freshValue = SettingsStorageVolume.Available(500, 125)
            engine.settingsStorageVolumeCoordinator = SettingsStorageVolumeCoordinator(SettingsStorageVolumeAccess { path ->
                reads.add(path)
                if (reads.size == 1) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    oldValue
                } else freshValue
            }, worker)
            var oldWorker: Thread? = null
            var old: SettingsStorageHandle? = null
            var fresh: SettingsStorageHandle? = null
            try {
                ready(engine, f)
                val oldSession = f.current
                val oldPath = assertNotNull(oldSession.parameters).files
                val oldStatus = mutableListOf<SettingsStorageToken?>()
                old = assertNotNull(engine.openSettingsStorageOwnedObserved({ true }, oldStatus::add))
                runCurrent()
                val oldToken = assertNotNull(oldStatus.single())
                assertEquals(1, worker.tasks.size)
                val started = thread(isDaemon = true, name = "synthetic-storage-old-generation") {
                    try { worker.runNext() } catch (error: Throwable) { failure.set(error) }
                }
                oldWorker = started
                assertTrue(entered.await(2, TimeUnit.SECONDS), "The old generation is already inside physical IO")
                assertEquals(listOf(oldPath), reads.toList())

                oldSession.autoConfirmClose = false
                val stopping = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
                assertFalse(old.current(), "Stop synchronously revokes the old visit")
                runCurrent()
                assertTrue(oldSession.closeRequested)
                assertFalse(stopping.isCompleted, "A close request does not substitute for native CLOSED")
                oldSession.auth(AuthorizationPhase.CLOSED)
                runCurrent()
                assertTrue(stopping.await())
                assertEquals(listOf(oldToken, null), oldStatus)

                ready(engine, f)
                assertNotSame(oldSession, f.current)
                val freshPath = assertNotNull(f.current.parameters).files
                val freshStatus = mutableListOf<SettingsStorageToken?>()
                fresh = assertNotNull(engine.openSettingsStorageOwnedObserved({ true }, freshStatus::add))
                runCurrent()
                val freshToken = assertNotNull(freshStatus.single())
                assertNotEquals(oldToken.generation, freshToken.generation)
                assertTrue(fresh.current())
                assertTrue(started.isAlive, "The old physical read remains blocked after native CLOSED and restart")
                assertTrue(worker.tasks.isEmpty(), "Restart cannot free the old generation's physical IO slot")
                assertEquals(listOf(oldPath), reads.toList(), "No new generation read may enter before the old read exits")
                assertEquals(freshToken, engine.state.value.storageSettings.token)
                assertEquals(SettingsStorageVolume.Loading, engine.state.value.storageSettings.volume)

                release.countDown()
                started.join(2000)
                assertFalse(started.isAlive)
                assertNull(failure.get())
                assertEquals(1, worker.tasks.size, "Exactly one current request is scheduled after physical completion")
                runCurrent()
                assertEquals(SettingsStorageVolume.Loading, engine.state.value.storageSettings.volume,
                    "The old generation's completed value cannot publish into the new visit")
                assertEquals(freshToken, engine.state.value.storageSettings.token)
                assertEquals(listOf(oldToken, null), oldStatus)
                assertFalse(old.current())
                worker.runNext()
                runCurrent()
                assertEquals(listOf(oldPath, freshPath), reads.toList())
                assertEquals(freshToken, engine.state.value.storageSettings.token)
                assertEquals(freshValue, engine.state.value.storageSettings.volume)
                assertEquals(listOf<SettingsStorageToken?>(freshToken), freshStatus)
                assertTrue(worker.tasks.isEmpty())
            } finally {
                release.countDown()
                oldWorker?.join(2000)
                assertFalse(oldWorker?.isAlive == true, "The synthetic read must exit before temporary-directory cleanup")
                old?.close()
                fresh?.close()
                runCurrent()
            }
        }
    }

    @Test fun aQueuedAdmissionRetainsARevokedHandleAfterStop() = runTest {
        withEngine(nativeRoots = true) { engine, f, worker, queried ->
            // The engine actor and UI-status receiver are deliberately scheduled independently.
            val statusesScheduler = TestCoroutineScheduler()
            engine.settingsStorageStatusDispatcher = StandardTestDispatcher(statusesScheduler)
            ready(engine, f)
            val statuses = mutableListOf<SettingsStorageToken?>()
            val handle = assertNotNull(engine.openSettingsStorageOwnedObserved({ true }, statuses::add))
            runCurrent()
            assertTrue(statuses.isEmpty(), "The successful token is still waiting for the status receiver")
            val acceptedToken = engine.state.value.storageSettings.token
            assertTrue(handle.current())
            assertEquals(1, worker.tasks.size)
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            assertFalse(handle.current(), "Stop revokes the native owner before the queued token can be delivered")
            runCurrent()
            assertTrue(stopping.await())
            statusesScheduler.runCurrent()
            assertEquals(listOf(acceptedToken, null), statuses, "The receipt and revocation retain their per-visit order")
            assertFalse(handle.current(), "A delayed receipt cannot revive a stopped native owner")
            worker.runNext(); runCurrent()
            assertTrue(queried.isEmpty())
            handle.close()
        }
    }

    @Test fun aBlockedExternalStatusCallbackCannotDelayStopOrKeepItsHandleCurrent() = runTest {
        withEngine(nativeRoots = true) { engine, f, _, _ ->
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "synthetic-storage-status").apply { isDaemon = true }
            }
            val dispatcher = executor.asCoroutineDispatcher()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val terminal = CountDownLatch(1)
            val currentAfterRelease = AtomicBoolean(true)
            val failure = AtomicReference<Throwable?>()
            val handleRef = AtomicReference<SettingsStorageHandle?>()
            val statuses = CopyOnWriteArrayList<SettingsStorageToken?>()
            var stopper: Thread? = null
            try {
                engine.settingsStorageStatusDispatcher = dispatcher
                ready(engine, f)
                val handle = assertNotNull(engine.openSettingsStorageOwnedObserved({ true }) { token ->
                    if (token != null) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        currentAfterRelease.set(checkNotNull(handleRef.get()).current())
                    }
                    statuses += token
                    if (token == null) terminal.countDown()
                })
                handleRef.set(handle)
                runCurrent()
                assertTrue(entered.await(2, TimeUnit.SECONDS), "The external callback is actually blocked")
                val stopping = thread(isDaemon = true) {
                    try { runBlocking { engine.stop(timeoutMillis = 0) } }
                    catch (error: Throwable) { failure.set(error) }
                    finally { stopped.countDown() }
                }
                stopper = stopping
                assertTrue(stopped.await(2, TimeUnit.SECONDS), "Logical stop cannot wait for external callback code")
                assertNull(failure.get())
                assertFalse(handle.current())
                release.countDown()
                assertTrue(terminal.await(2, TimeUnit.SECONDS))
                assertFalse(currentAfterRelease.get(), "The delayed success carries a revoked handle")
                assertEquals(2, statuses.size)
                assertNotNull(statuses[0])
                assertNull(statuses[1])
                runCurrent()
                handle.close()
            } finally {
                release.countDown()
                stopper?.join(2000)
                dispatcher.close()
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
    }

    private suspend fun TestScope.ready(engine: TdEngine, f: Fixture) {
        assertTrue(engine.start()); runCurrent()
        f.current.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
        f.current.acceptParameters!!(null); runCurrent()
        f.current.auth(AuthorizationPhase.READY); runCurrent()
    }

    private suspend fun TestScope.withEngine(nativeRoots: Boolean, selectAttempt: Boolean = false,
        block: suspend TestScope.(TdEngine, Fixture, ManualExecutor, MutableList<Path>) -> Unit,
    ) {
        val f = Fixture(nativeRoots)
        if (selectAttempt) f.selectAttempt()
        val worker = ManualExecutor()
        val queried = mutableListOf<Path>()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler), saveTargetRegistry = NativeSaveTargetRegistry())
        engine.settingsStorageStatusDispatcher = StandardTestDispatcher(testScheduler)
        engine.settingsStorageVolumeCoordinator = SettingsStorageVolumeCoordinator(SettingsStorageVolumeAccess {
            queried.add(it)
            SettingsStorageVolume.Available(1000, 300)
        }, worker)
        try { block(engine, f, worker, queried) } finally {
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.close() }
            runCurrent()
            if (!closing.isCompleted && f.sessions.isNotEmpty()) { f.current.auth(AuthorizationPhase.CLOSED); runCurrent() }
            assertTrue(closing.await())
            val root = f.root.toAbsolutePath().normalize()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(path)
            } }
        }
    }

    private class ManualExecutor : Executor {
        val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun runNext() = tasks.removeAt(0).run()
    }

    private class Fixture(private val nativeRoots: Boolean) : TdTransport {
        val root: Path = Files.createTempDirectory("storage-volume-engine-")
        val configured = root.resolve("configured")
        val store = ConfigurationStore(root.resolve("config.properties"), configured)
        val sessions = mutableListOf<Session>()
        val current: Session get() = sessions.last()
        var selected: Path? = null
        override val hasNativeFileScanRoots: Boolean get() = nativeRoots
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }
        fun selectAttempt() {
            DataDirectoryLease.acquire(configured).use {
                val choices = AuthenticationDatabaseSelectionStore(configured)
                selected = choices.directory(choices.beginPhoneAttempt(choices.read()))
            }
        }
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit,
        ): TdSession = Session(onAuthorization).also { sessions += it }
    }

    private class Session(private val authorization: (AuthorizationDetails) -> Unit) : SyntheticOpenKeySession {
        private val ready = AtomicBoolean()
        val gate = TdUserInteractionGate()
        override val interactionGate = gate
        override val settings: TdSettingsSession = Settings()
        var parameters: DataDirectoryLease? = null
        var acceptParameters: ((Int?) -> Unit)? = null
        var autoConfirmClose = true
        var closeRequested = false
        fun auth(phase: AuthorizationPhase) {
            gate.updateAuthorizationReady { ready.set(phase == AuthorizationPhase.READY) }
            authorization(AuthorizationDetails(phase))
        }
        override fun authorizationReady() = ready.get()
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) {
            parameters = directories; acceptParameters = result
        }
        override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
        override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login")
        override fun close() {
            closeRequested = true
            if (autoConfirmClose) auth(AuthorizationPhase.CLOSED)
        }
    }

    private class Settings : TdSettingsSession {
        override val storage: TdSettingsStorageSession = object : TdSettingsStorageSession {
            override fun storageDetails(active: () -> Boolean, result: (SettingsResult<SettingsStorageDetails>) -> Unit) =
                result(SettingsResult(SettingsStorageDetails(0, 0, emptyList())))
            override fun clearStorage(types: Set<SettingsStorageFileType>, active: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
                error("No cleanup in volume tests")
        }
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) = unsupported(result)
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) = unsupported(result)
        override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) = unsupported(result)
        override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) = unsupported(result)
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = unsupported(result)
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }
}
