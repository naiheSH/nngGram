package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Engine/actor fake only; real TDLib and UI are not exercised. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsCurrentProfilePhotoEngineTest {
    @Test fun openingAcknowledgesTheActualActorEpochBeforeNativeSuccess() = runTest {
        withEngine { engine, f, parent ->
            val replies = mutableListOf<Long?>()
            assertTrue(engine.openSettingsCurrentProfilePhoto(parent, { true }, replies::add))
            assertTrue(replies.isEmpty()); runCurrent()
            val epoch = assertNotNull(replies.single())
            assertEquals(epoch, engine.state.value.settings.profileExtras.currentPhoto.epoch)
            assertEquals(SettingsCurrentProfilePhotoStatus.LOADING, engine.state.value.settings.profileExtras.currentPhoto.status)
            assertEquals(1, f.extras.reads.size)
        }
    }

    @Test fun revokedOpeningBeforeActorAdmissionReturnsNullOnceWithoutNativeRead() = runTest {
        withEngine { engine, f, parent ->
            val active = AtomicBoolean(true); val replies = mutableListOf<Long?>()
            assertTrue(engine.openSettingsCurrentProfilePhoto(parent, active::get, replies::add))
            active.set(false); runCurrent()
            assertEquals(listOf<Long?>(null), replies); assertTrue(f.extras.reads.isEmpty())
        }
    }

    @Test fun competingRefreshesAcknowledgeOnlyTheActuallyCreatedEpoch() = runTest {
        withEngine { engine, f, parent ->
            val opened = mutableListOf<Long?>()
            assertTrue(engine.openSettingsCurrentProfilePhoto(parent, { true }, opened::add)); runCurrent()
            val epoch = assertNotNull(opened.single())
            f.extras.reads.single()(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY, photo)))
            runCurrent()
            val refreshes = mutableListOf<Long?>()
            assertTrue(engine.refreshSettingsCurrentProfilePhoto(parent, epoch, refreshes::add))
            assertTrue(engine.refreshSettingsCurrentProfilePhoto(parent, epoch, refreshes::add)); runCurrent()
            val next = assertNotNull(refreshes[0]); assertNotEquals(epoch, next)
            assertNull(refreshes[1]); assertEquals(next, engine.state.value.settings.profileExtras.currentPhoto.epoch)
        }
    }

    @Test fun closingQueuedCurrentFileLeasePreventsNativeAdmission() = runTest {
        withEngine { engine, f, parent ->
            val opened = mutableListOf<Long?>()
            engine.openSettingsCurrentProfilePhoto(parent, { true }, opened::add); runCurrent()
            val epoch = assertNotNull(opened.single())
            f.extras.reads.single()(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY, photo))); runCurrent()
            val request = SettingsCurrentProfilePhotoMediaRequest(parent, epoch, 90,
                SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW, 160, 160)
            val results = mutableListOf<SettingsResult<MediaFile>>()
            val lease = engine.acquireSettingsCurrentProfilePhotoFile(request, { true }, results::add)
            lease.close(); lease.close(); assertEquals(SettingsError.NOT_READY, results.single().error)
            runCurrent(); assertTrue(f.extras.files.isEmpty()); assertEquals(1, results.size)
        }
    }

    @Test fun queuedOpenRechecksTheNativeAndMandatoryInteractionGates() = runTest {
        for (mandatory in listOf(false, true)) withEngine { engine, f, parent ->
            val replies = mutableListOf<Long?>()
            assertTrue(engine.openSettingsCurrentProfilePhoto(parent, { true }, replies::add))
            if (mandatory) f.required() else f.auth(AuthorizationPhase.OTHER_STEP)
            runCurrent()
            assertEquals(listOf<Long?>(null), replies); assertTrue(f.extras.reads.isEmpty())
        }
    }

    @Test fun queuedCurrentFileCannotStartAfterStopWasRequested() = runTest {
        withEngine { engine, f, parent ->
            val opened = mutableListOf<Long?>()
            engine.openSettingsCurrentProfilePhoto(parent, { true }, opened::add); runCurrent()
            val epoch = assertNotNull(opened.single())
            f.extras.reads.single()(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY, photo))); runCurrent()
            val request = SettingsCurrentProfilePhotoMediaRequest(parent, epoch, 90,
                SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW, 160, 160)
            val results = mutableListOf<SettingsResult<MediaFile>>()
            engine.acquireSettingsCurrentProfilePhotoFile(request, { true }, results::add)
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            runCurrent(); assertTrue(stopped.await())
            assertTrue(f.extras.files.isEmpty()); assertEquals(SettingsError.NOT_READY, results.single().error)
        }
    }

    @Test fun admittedFilePredicateStopsSynchronouslyOnNativeAuthorizationOrMandatoryLock() = runTest {
        for (mandatory in listOf(false, true)) withEngine { engine, f, parent ->
            val opened = mutableListOf<Long?>()
            engine.openSettingsCurrentProfilePhoto(parent, { true }, opened::add); runCurrent()
            val epoch = assertNotNull(opened.single())
            f.extras.reads.single()(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY, photo))); runCurrent()
            val request = SettingsCurrentProfilePhotoMediaRequest(parent, epoch, 90,
                SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW, 160, 160)
            val lease = engine.acquireSettingsCurrentProfilePhotoFile(request, { true }) {}; runCurrent()
            val native = f.extras.files.single(); assertTrue(native.active())
            assertTrue(native.sourceActive()); assertTrue(native.requestCurrent())
            assertTrue(lease.active()); assertTrue(lease.requestCurrent())
            var invalidations = 0
            lease.onInvalidated { assertFalse(lease.active()); invalidations++ }
            if (mandatory) f.required() else f.auth(AuthorizationPhase.OTHER_STEP)
            assertFalse(native.active(), "Native ownership must stop before actor or Compose processes the update.")
            assertFalse(native.sourceActive()); assertFalse(native.requestCurrent()); assertFalse(lease.requestCurrent())
            assertFalse(lease.active()); assertEquals(0, invalidations)
            runCurrent()
            assertEquals(1, invalidations)
        }
    }

    @Test fun bridgeCannotDeliverAfterItsBoundActorOutputGateCloses() {
        val active = AtomicBoolean(true); val results = mutableListOf<SettingsResult<MediaFile>>()
        var invalidations = 0
        val child = object : SettingsCurrentProfilePhotoFileLease {
            var closed = false
            override fun active() = active.get() && !closed
            override fun onInvalidated(callback: () -> Unit): () -> Unit = {}
            override fun close() { closed = true }
        }
        val bridge = CurrentProfilePhotoFileBridge({ true }, results::add)
        bridge.bind(child); bridge.onInvalidated { assertFalse(bridge.active()); invalidations++ }
        active.set(false)
        bridge.finish(SettingsResult(file))
        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertTrue(child.closed); assertEquals(1, invalidations)
    }

    @Test fun normalActorFileFailurePreservesItsErrorAndRequestWitnessThroughTheBridge() = runTest {
        withEngine { engine, f, parent ->
            val opened = mutableListOf<Long?>()
            engine.openSettingsCurrentProfilePhoto(parent, { true }, opened::add); runCurrent()
            val epoch = assertNotNull(opened.single())
            f.extras.reads.single()(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY, photo))); runCurrent()
            val request = SettingsCurrentProfilePhotoMediaRequest(parent, epoch, 90,
                SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW, 160, 160)
            val results = mutableListOf<SettingsResult<MediaFile>>()
            val lease = engine.acquireSettingsCurrentProfilePhotoFile(request, { true }, results::add); runCurrent()
            var invalidations = 0; lease.onInvalidated { invalidations++ }
            f.extras.files.single().result(SettingsResult(error = SettingsError.REQUEST_FAILED)); runCurrent()
            assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
            assertFalse(lease.active()); assertTrue(lease.requestCurrent()); assertEquals(0, invalidations)
            lease.onInvalidated { invalidations++ }; assertEquals(0, invalidations)
            f.required()
            assertFalse(lease.requestCurrent(), "A later native lock invalidates an unconsumed error synchronously.")
            runCurrent(); lease.close()
        }
    }

    @Test fun earlyActorFailureBindsAnAuthorizedWitnessBeforeUiConsumesTheError() = runTest {
        withEngine { engine, f, parent ->
            val opened = mutableListOf<Long?>()
            engine.openSettingsCurrentProfilePhoto(parent, { true }, opened::add); runCurrent()
            val epoch = assertNotNull(opened.single())
            f.extras.reads.single()(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY, photo))); runCurrent()
            val request = SettingsCurrentProfilePhotoMediaRequest(parent, epoch, 90,
                SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW, 0, 160)
            val results = mutableListOf<SettingsResult<MediaFile>>()
            val lease = engine.acquireSettingsCurrentProfilePhotoFile(request, { true }, results::add); runCurrent()
            assertEquals(SettingsError.INVALID_VALUE, results.single().error); assertTrue(f.extras.files.isEmpty())
            assertFalse(lease.active()); assertTrue(lease.requestCurrent())
            lease.close(); assertFalse(lease.requestCurrent())
        }
    }

    @Test fun sourceRevocationInvalidatesAnUnconsumedOrdinaryErrorWitness() = runTest {
        withEngine { engine, f, parent ->
            val opened = mutableListOf<Long?>()
            engine.openSettingsCurrentProfilePhoto(parent, { true }, opened::add); runCurrent()
            val epoch = assertNotNull(opened.single())
            f.extras.reads.single()(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY, photo))); runCurrent()
            val request = SettingsCurrentProfilePhotoMediaRequest(parent, epoch, 90,
                SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW, 160, 160)
            val results = mutableListOf<SettingsResult<MediaFile>>()
            val lease = engine.acquireSettingsCurrentProfilePhotoFile(request, { true }, results::add); runCurrent()
            f.extras.files.single().result(SettingsResult(error = SettingsError.REQUEST_FAILED)); runCurrent()
            assertEquals(SettingsError.REQUEST_FAILED, results.single().error); assertTrue(lease.requestCurrent())
            f.extras.invalidations.single()()
            assertFalse(lease.requestCurrent()); runCurrent()
            assertEquals(SettingsCurrentProfilePhotoStatus.CONFLICT, engine.state.value.settings.profileExtras.currentPhoto.status)
        }
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture, SettingsProfileExtrasToken) -> Unit) {
        val f = Fixture(); val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(engine.start()); runCurrent()
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE)); runCurrent()
            assertTrue(engine.openSettingsProfileExtras(engine.state.value.settings.token, 43)); runCurrent()
            block(engine, f, engine.state.value.settings.profileExtras.token)
        } finally {
            assertTrue(engine.close())
            val root = f.root.toAbsolutePath().normalize()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                check(it.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(it)
            } }
        }
    }

    private class Fixture : TdTransport {
        val root = Files.createTempDirectory("current-profile-photo-engine-")
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val extras = Extras(); val settings = Settings(extras); val gate = TdUserInteractionGate(); var ready = false
        private lateinit var authorization: (AuthorizationDetails) -> Unit
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }
        fun auth(phase: AuthorizationPhase) { ready = phase == AuthorizationPhase.READY; authorization(AuthorizationDetails(phase)) }
        fun required() = gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit): TdSession {
            authorization = onAuthorization
            return object : SyntheticOpenKeySession {
                override fun authorizationReady() = this@Fixture.ready
                override val interactionGate = gate
                override val settings: TdSettingsSession = this@Fixture.settings
                override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = result(null)
                override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
                override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login")
                override fun close() = auth(AuthorizationPhase.CLOSED)
            }
        }
    }

    private class Extras : TdSettingsProfileExtrasSession {
        val reads = mutableListOf<(SettingsResult<SettingsCurrentProfilePhotoRead>) -> Unit>()
        val invalidations = mutableListOf<() -> Unit>()
        class File(val request: SettingsCurrentProfilePhotoAuthorizedFileRequest, val sourceActive: () -> Boolean,
            val result: (SettingsResult<MediaFile>) -> Unit) : SettingsCurrentProfilePhotoFileLease {
            private val open = AtomicBoolean(true)
            override fun active() = open.get() && sourceActive()
            override fun requestCurrent() = open.get() && sourceActive()
            override fun close() { open.set(false) }
        }
        val files = mutableListOf<File>()
        override fun currentProfilePhoto(userId: Long, active: () -> Boolean, invalidated: () -> Unit,
            result: (SettingsResult<SettingsCurrentProfilePhotoRead>) -> Unit) { reads += result; invalidations += invalidated }
        override fun acquireCurrentProfilePhotoFile(request: SettingsCurrentProfilePhotoAuthorizedFileRequest, active: () -> Boolean,
            result: (SettingsResult<MediaFile>) -> Unit): SettingsCurrentProfilePhotoFileLease {
            return File(request, active, result).also { files += it }
        }
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) =
            result(SettingsResult(SettingsProfileExtrasData(userId, 11, null, SettingsCalendarDate(27, 10, 2026))))
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) = Unit
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) = Unit
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) = Unit
    }

    private class Settings(override val profileExtras: Extras) : TdSettingsSession {
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) = result(SettingsResult(SettingsProfile(43, "Synthetic", "", "", "", "")))
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

    private companion object {
        val file = MediaFile(8, size = 100, uniqueId = "native-8")
        val photo = SettingsCurrentProfilePhoto(90, 0, listOf(SettingsProfilePhotoVariant(file, 160, 160)), authorization = 71)
    }
}
