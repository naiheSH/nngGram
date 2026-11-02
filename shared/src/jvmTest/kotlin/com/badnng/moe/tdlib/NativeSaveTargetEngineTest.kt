package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.media.NativeSaveTargetRegistry
import com.badnng.moe.media.SaveTargetIssue
import com.badnng.moe.status.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** No native client is opened: this opt-in fake exercises real Engine lifecycle/selected lease wiring. */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeSaveTargetEngineTest {
    @Test fun officialTransportDeclaresNativeOwnershipWhileAnOrdinaryToolFakeDoesNot() {
        assertTrue(OfficialTdTransport(offline = true).hasNativeFileScanRoots)
        val fake = Fixture(native = false)
        try { assertFalse(fake.hasNativeFileScanRoots) } finally { cleanup(fake.root) }
    }

    @Test fun initializationIsUnknownAndAcceptedRootsBelongToTheActuallySelectedAccount() = runTest {
        withEngine { engine, f, registry ->
            val selected = f.selected()
            assertTrue(engine.start()); runCurrent()
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, registry.capture().check(f.root.resolve("outside/copy.bin")))
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            assertEquals(selected.resolve("files"), f.parameters!!.files)
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, registry.capture().check(f.root.resolve("outside")))
            f.acceptParameters!!(null); runCurrent()
            val guard = registry.capture()
            assertEquals(SaveTargetIssue.NATIVE_SCAN_TREE, guard.check(selected.resolve("database/temp/copy.bin")))
            assertEquals(SaveTargetIssue.NATIVE_SCAN_TREE, guard.check(selected.resolve("files/video/copy.bin")))
            assertNull(guard.check(f.configured.resolve("files/copy.bin")), "The configured parent is not the selected native account.")
        }
    }

    @Test fun createThrowCannotBeMistakenForConfirmedNativeClosure() = runTest {
        withEngine { engine, f, registry ->
            f.throwCreate = true
            assertTrue(engine.start()); runCurrent()
            assertTrue(engine.state.value.clientActive, "A native create exception retains an unconfirmed client until Closed")
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, registry.capture().check(f.root.resolve("outside")))
            val closed = async(start = CoroutineStart.UNDISPATCHED) { engine.close(timeoutMillis = 0) }
            runCurrent()
            assertFalse(closed.await(), "An indeterminate native create cannot be reported as a confirmed close")
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, registry.capture().check(f.root.resolve("outside")),
                "An exception after entering create is not evidence that a native client never existed.")
            f.auth(AuthorizationPhase.CLOSED); runCurrent()
        }
    }

    @Test fun capturedClosedAfterCreateThrowsRetiresItsUnknownEvenWithoutSession() = runTest {
        withEngine { engine, f, registry ->
            f.throwCreate = true
            assertTrue(engine.start()); runCurrent()
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, registry.capture().check(f.root.resolve("outside")))
            f.auth(AuthorizationPhase.CLOSED); runCurrent()
            assertNull(registry.capture().check(f.root.resolve("outside")))
        }
    }

    @Test fun capturedOldClosedAfterFailedCreateCannotRetireTheNewClient() = runTest {
        withEngine { engine, f, registry ->
            f.throwCreate = true
            assertTrue(engine.start()); runCurrent()
            val first = f.authorizationCallback()
            f.auth(AuthorizationPhase.CLOSED); runCurrent()
            f.throwCreate = false
            assertTrue(engine.start()); runCurrent()
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, registry.capture().check(f.root.resolve("outside")))
            first(AuthorizationDetails(AuthorizationPhase.CLOSED)); runCurrent()
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, registry.capture().check(f.root.resolve("outside")))
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.acceptParameters!!(null); runCurrent()
            assertEquals(SaveTargetIssue.NATIVE_SCAN_TREE, registry.capture().check(f.parameters!!.files.resolve("new.bin")))
        }
    }

    @Test fun stopAndTimeoutKeepTheKnownFenceUntilTheRealClosedCallback() = runTest {
        withEngine { engine, f, registry ->
            assertTrue(engine.start()); runCurrent()
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.acceptParameters!!(null); runCurrent()
            f.auth(AuthorizationPhase.READY); runCurrent()
            val nativeFile = f.parameters!!.files.resolve("new.bin")
            f.closeAutomatically = false
            assertFalse(engine.stop(timeoutMillis = 0))
            runCurrent()
            assertEquals(SaveTargetIssue.NATIVE_SCAN_TREE, registry.capture().check(nativeFile))
            val old = registry.capture()
            f.auth(AuthorizationPhase.CLOSED); runCurrent()
            assertEquals(SaveTargetIssue.RETIRED, old.check(nativeFile))
            assertNull(registry.capture().check(nativeFile), "Only the confirmed Closed removed this synthetic client's fence.")
        }
    }

    @Test fun rejectedParametersAndFailedCloseCannotPretendThatNativeRootsAreClosed() = runTest {
        withEngine { engine, f, registry ->
            assertTrue(engine.start()); runCurrent()
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.throwClose = true
            f.acceptParameters!!(400); runCurrent()
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, registry.capture().check(f.root.resolve("outside")))
            assertFalse(engine.stop(timeoutMillis = 0)); runCurrent()
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, registry.capture().check(f.root.resolve("outside")))
            f.throwClose = false
            f.auth(AuthorizationPhase.CLOSED); runCurrent()
            assertNull(registry.capture().check(f.root.resolve("outside")))
        }
    }

    @Test fun aPreviousGenerationClosedCallbackCannotRetireTheNewClientFence() = runTest {
        withEngine { engine, f, registry ->
            assertTrue(engine.start()); runCurrent()
            val oldCallback = f.authorizationCallback()
            f.closeAutomatically = false
            val stop = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            runCurrent()
            f.auth(AuthorizationPhase.CLOSED); runCurrent()
            assertTrue(stop.await())
            assertTrue(engine.start()); runCurrent()
            oldCallback(AuthorizationDetails(AuthorizationPhase.CLOSED)); runCurrent()
            assertEquals(SaveTargetIssue.UNKNOWN_NATIVE_ROOTS, registry.capture().check(f.root.resolve("outside")))
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.acceptParameters!!(null); runCurrent()
            assertEquals(SaveTargetIssue.NATIVE_SCAN_TREE, registry.capture().check(f.parameters!!.files.resolve("new.bin")))
        }
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture, NativeSaveTargetRegistry) -> Unit) {
        val f = Fixture()
        val registry = NativeSaveTargetRegistry()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler), saveTargetRegistry = registry)
        try { block(engine, f, registry) } finally {
            f.closeAutomatically = true
            val close = async(start = CoroutineStart.UNDISPATCHED) { engine.close() }
            runCurrent()
            if (!close.isCompleted && f.created) { f.auth(AuthorizationPhase.CLOSED); runCurrent() }
            assertTrue(close.await())
            cleanup(f.root)
        }
    }

    private class Fixture(private val native: Boolean = true) : TdTransport {
        val root = Files.createTempDirectory("native-save-target-engine-")
        val configured = root.resolve("configured")
        val store = ConfigurationStore(root.resolve("config.properties"), configured)
        override val hasNativeFileScanRoots get() = native
        var throwCreate = false
        var throwClose = false
        var closeAutomatically = true
        var created = false
        var parameters: DataDirectoryLease? = null
        var acceptParameters: ((Int?) -> Unit)? = null
        private lateinit var authorization: (AuthorizationDetails) -> Unit
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }
        fun selected(): Path {
            DataDirectoryLease.acquire(configured).use {
                val choices = AuthenticationDatabaseSelectionStore(configured)
                return choices.directory(choices.beginPhoneAttempt(choices.read()))
            }
        }
        fun auth(phase: AuthorizationPhase) = authorization(AuthorizationDetails(phase))
        fun authorizationCallback(): (AuthorizationDetails) -> Unit = authorization
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit,
        ): TdSession {
            authorization = onAuthorization
            if (throwCreate) throw IllegalStateException("synthetic indeterminate create")
            created = true
            return object : SyntheticOpenKeySession {
                override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) {
                    parameters = directories; acceptParameters = result
                }
                override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
                override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login in synthetic path tests")
                override fun close() {
                    if (throwClose) throw IllegalStateException("synthetic unconfirmed close")
                    if (closeAutomatically) auth(AuthorizationPhase.CLOSED)
                }
            }
        }
    }

    private companion object {
        fun cleanup(root: Path) {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
                Files.deleteIfExists(path)
            } }
        }
    }
}
