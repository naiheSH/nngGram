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
import kotlin.concurrent.thread
import kotlin.test.*

/** Actual Engine/controller actor boundary; the native facade deliberately performs no admission checks. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProfilePhotoHistoryMediaEngineTest {
    @Test fun synchronousCloseBeforeActorAdmissionPreventsAnyNativeFileRequest() = runTest {
        withEngine { engine, f ->
            val request = openHistory(engine, f)
            val results = mutableListOf<SettingsResult<MediaFile>>()
            val lease = engine.acquireSettingsProfileHistoryFile(request, { true }, results::add)
            assertTrue(f.extras.files.isEmpty())
            lease.close(); lease.close()
            assertEquals(SettingsError.NOT_READY, results.single().error)
            runCurrent()
            assertTrue(f.extras.files.isEmpty())
            assertEquals(1, results.size)
        }
    }

    @Test fun publicAndControllerIdentityGuardsRejectBeforeNativeButCurrentTicketIsAdmitted() = runTest {
        withEngine { engine, f ->
            val request = openHistory(engine, f)
            val results = mutableListOf<SettingsResult<MediaFile>>()
            val invalid = listOf(request.copy(epoch = 0), request.copy(photoId = 0),
                request.copy(targetWidthPx = 0), request.copy(targetHeightPx = -1),
                request.copy(targetWidthPx = 4097), request.copy(targetHeightPx = Int.MAX_VALUE))
            invalid.forEach {
                engine.acquireSettingsProfileHistoryFile(it, { true }, results::add)
                assertEquals(SettingsError.INVALID_VALUE, results.last().error)
            }
            val oldParents = listOf(request.parent.copy(generation = request.parent.generation - 1),
                request.parent.copy(owner = request.parent.owner - 1), request.parent.copy(userId = 44))
            oldParents.forEach {
                engine.acquireSettingsProfileHistoryFile(request.copy(parent = it), { true }, results::add)
                assertEquals(SettingsError.NOT_READY, results.last().error)
            }
            // Positive but wrong epoch, unknown photo and a legacy zero-ticket entry reach actor validation.
            listOf(request.copy(epoch = request.epoch + 1), request.copy(photoId = 999), request.copy(photoId = 12))
                .forEach { engine.acquireSettingsProfileHistoryFile(it, { true }, results::add) }
            runCurrent()
            assertEquals(invalid.size + oldParents.size + 3, results.size)
            assertTrue(results.takeLast(3).all { it.error == SettingsError.CONFLICT })
            assertTrue(f.extras.files.isEmpty())
            val lease = engine.acquireSettingsProfileHistoryFile(request.copy(targetWidthPx = 576), { true }, results::add)
            runCurrent()
            val native = f.extras.files.single()
            assertEquals(SettingsProfilePhotoHistoryAuthorizedFileRequest(43, 71, 11, 0,
                SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL, 576, 130), native.request)
            assertTrue(native.active())
            lease.close()
            assertEquals(1, native.closes)
        }
    }

    @Test fun queuedAcquisitionRechecksNativeReadyAndMandatoryGateBeforeNativeRead() = runTest {
        for (mandatory in listOf(false, true)) {
            withEngine { engine, f ->
                val request = openHistory(engine, f)
                val results = mutableListOf<SettingsResult<MediaFile>>()
                engine.acquireSettingsProfileHistoryFile(request, { true }, results::add)
                if (mandatory) f.required() else f.auth(AuthorizationPhase.OTHER_STEP)
                assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization,
                    "Native state changed synchronously; the actor has not processed its notification.")
                assertEquals(mandatory, f.ready)
                assertEquals(!mandatory, f.gate.allowsOrdinary())
                runCurrent()
                assertTrue(f.extras.files.isEmpty())
                assertEquals(SettingsError.NOT_READY, results.single().error)
            }
        }
    }

    @Test fun workerCompletionRequiresActorPublicationAndDuplicateCallbacksNeverReplaceReadyFile() = runTest {
        withEngine { engine, f ->
            val request = openHistory(engine, f)
            val results = mutableListOf<SettingsResult<MediaFile>>()
            val lease = engine.acquireSettingsProfileHistoryFile(request, { true }, results::add)
            runCurrent()
            val native = f.extras.files.single()
            var activeOnWorker = false
            thread {
                activeOnWorker = native.active()
                native.result(SettingsResult(ready))
                native.result(SettingsResult(ready.copy(localPath = "ignored-duplicate.jpg")))
            }.join()
            assertTrue(activeOnWorker)
            assertTrue(results.isEmpty(), "A native callback cannot publish directly into UI ownership.")
            runCurrent()
            assertEquals(listOf(ready), results.map { it.value })
            assertEquals(0, native.closes, "Ready ownership remains borrowed until its root lease closes.")
            assertNull(engine.state.value.settings.profileExtras.history.entries.first().variants.single().file.localPath)
            lease.close(); lease.close()
            assertEquals(1, native.closes)
        }
    }

    @Test fun stopRequestSynchronouslyInvalidatesQueuedAcquireBeforeStopActorRuns() = runTest {
        withEngine { engine, f ->
            val request = openHistory(engine, f)
            val results = mutableListOf<SettingsResult<MediaFile>>()
            engine.acquireSettingsProfileHistoryFile(request, { true }, results::add)
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            assertFalse(stopped.isCompleted)
            assertTrue(engine.state.value.settings.enabled, "The stop action has not run yet.")
            assertTrue(f.extras.files.isEmpty())
            runCurrent()
            assertTrue(stopped.await())
            assertTrue(f.extras.files.isEmpty(), "The earlier acquire sees the newer stop revision before native admission.")
            assertEquals(SettingsError.NOT_READY, results.single().error)
        }
    }

    @Test fun refreshedEpochCancelsOldLeaseAndLateFileCannotPublishIntoSamePhotoReplacement() = runTest {
        withEngine { engine, f ->
            val request = openHistory(engine, f)
            val oldResults = mutableListOf<SettingsResult<MediaFile>>()
            val oldLease = engine.acquireSettingsProfileHistoryFile(request, { true }, oldResults::add)
            runCurrent()
            val old = f.extras.files.single()
            assertTrue(engine.refreshSettingsProfilePhotoHistory(request.parent)); runCurrent()
            assertFalse(old.active())
            assertEquals(1, old.closes)
            f.extras.completePage(ticket = 72); runCurrent()
            val history = engine.state.value.settings.profileExtras.history
            assertTrue(history.epoch > request.epoch)
            val freshResults = mutableListOf<SettingsResult<MediaFile>>()
            val freshLease = engine.acquireSettingsProfileHistoryFile(request.copy(epoch = history.epoch), { true }, freshResults::add)
            runCurrent()
            val fresh = f.extras.files.last()
            assertEquals(72L, fresh.request.authorization)
            old.result(SettingsResult(ready)); old.result(SettingsResult(error = SettingsError.FORBIDDEN))
            runCurrent()
            assertTrue(oldResults.isEmpty())
            assertTrue(freshResults.isEmpty())
            assertTrue(fresh.active())
            fresh.result(SettingsResult(ready)); runCurrent()
            assertEquals(listOf(ready), freshResults.map { it.value })
            oldLease.close(); freshLease.close()
            assertEquals(1, old.closes)
            assertEquals(1, fresh.closes)
        }
    }

    @Test fun parentCloseCancelsNativeAndLateSuccessCannotPopulateReopenedParent() = runTest {
        withEngine { engine, f ->
            val request = openHistory(engine, f)
            val results = mutableListOf<SettingsResult<MediaFile>>()
            val lease = engine.acquireSettingsProfileHistoryFile(request, { true }, results::add)
            runCurrent()
            val native = f.extras.files.single()
            assertTrue(engine.closeSettingsProfileExtras(request.parent)); runCurrent()
            assertFalse(native.active())
            assertEquals(1, native.closes)
            val freshRequest = openHistory(engine, f)
            assertNotEquals(request.parent, freshRequest.parent)
            val before = engine.state.value.settings
            native.result(SettingsResult(ready)); runCurrent()
            assertTrue(results.isEmpty())
            assertEquals(before, engine.state.value.settings)
            lease.close(); lease.close()
            assertEquals(1, native.closes)
        }
    }

    @Test fun rootLeaseCloseReturnsOnlyItsReadyOwnerAndLeavesAnotherSameFileOwnerActive() = runTest {
        withEngine { engine, f ->
            val request = openHistory(engine, f)
            val firstResults = mutableListOf<SettingsResult<MediaFile>>()
            val secondResults = mutableListOf<SettingsResult<MediaFile>>()
            val first = engine.acquireSettingsProfileHistoryFile(request, { true }, firstResults::add)
            val second = engine.acquireSettingsProfileHistoryFile(request, { true }, secondResults::add)
            runCurrent()
            val (one, two) = f.extras.files
            one.result(SettingsResult(ready)); two.result(SettingsResult(ready)); runCurrent()
            assertEquals(listOf(ready), firstResults.map { it.value })
            assertEquals(listOf(ready), secondResults.map { it.value })
            assertEquals(0, one.closes)
            assertEquals(0, two.closes)
            first.close(); first.close()
            assertEquals(1, one.closes)
            assertFalse(one.active())
            assertEquals(0, two.closes)
            assertTrue(two.active())
            runCurrent()
            assertTrue(two.active())
            assertTrue(engine.closeSettingsProfilePhotoHistory(request.parent)); runCurrent()
            assertEquals(1, two.closes)
            second.close()
            assertEquals(1, two.closes)
            assertEquals(1, firstResults.size)
            assertEquals(1, secondResults.size)
        }
    }

    private fun TestScope.openHistory(engine: TdEngine, f: Fixture): SettingsProfilePhotoHistoryMediaRequest {
        assertTrue(engine.openSettingsProfileExtras(engine.state.value.settings.token, 43)); runCurrent()
        val parent = engine.state.value.settings.profileExtras.token
        assertEquals(43L, parent.userId)
        assertTrue(engine.openSettingsProfilePhotoHistory(parent) { true }); runCurrent()
        f.extras.completePage(); runCurrent()
        val history = engine.state.value.settings.profileExtras.history
        assertTrue(history.opened)
        assertFalse(history.loading)
        assertEquals(71L, history.entries.first().authorization)
        return SettingsProfilePhotoHistoryMediaRequest(parent, history.epoch, 11,
            SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL, 130, 130)
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture) -> Unit) {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(engine.start()); runCurrent()
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.state.value.settings.enabled)
            assertTrue(engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE)); runCurrent()
            assertEquals(43L, engine.state.value.settings.profile?.userId)
            block(engine, f)
        } finally {
            assertTrue(engine.close())
            val root = f.root.toAbsolutePath().normalize()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(path)
            } }
        }
    }

    private class Fixture : TdTransport {
        val root = Files.createTempDirectory("profile-photo-history-media-engine-")
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val extras = Extras()
        val settings = Settings(extras)
        val gate = TdUserInteractionGate()
        var ready = false
        private lateinit var authorization: (AuthorizationDetails) -> Unit
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }
        fun auth(phase: AuthorizationPhase) {
            ready = phase == AuthorizationPhase.READY
            authorization(AuthorizationDetails(phase))
        }
        fun required() = gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit,
        ): TdSession {
            authorization = onAuthorization
            return object : SyntheticOpenKeySession {
                override fun authorizationReady() = this@Fixture.ready
                override val settings: TdSettingsSession = this@Fixture.settings
                override val interactionGate = gate
                override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = result(null)
                override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
                override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login in this fixture")
                override fun close() = auth(AuthorizationPhase.CLOSED)
            }
        }
    }

    /** No native gate, ticket, owner, epoch, active or callback-once enforcement. */
    private class Extras : TdSettingsProfileExtrasSession {
        class File(val request: SettingsProfilePhotoHistoryAuthorizedFileRequest, val active: () -> Boolean,
            val result: (SettingsResult<MediaFile>) -> Unit,
        ) : SettingsProfilePhotoHistoryFileLease {
            var closes = 0
            override fun close() { closes++ }
        }
        val files = mutableListOf<File>()
        val pages = mutableListOf<(SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit>()
        override fun acquireHistoryFile(request: SettingsProfilePhotoHistoryAuthorizedFileRequest, active: () -> Boolean,
            result: (SettingsResult<MediaFile>) -> Unit,
        ): SettingsProfilePhotoHistoryFileLease = File(request, active, result).also { files += it }
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) =
            result(SettingsResult(SettingsProfileExtrasData(userId, 91, null, SettingsCalendarDate(27, 10, 2026))))
        override fun photoHistoryPage(userId: Long, offset: Int, limit: Int, isCurrent: () -> Boolean,
            result: (SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit,
        ) { pages += result }
        fun completePage(ticket: Long = 71) {
            val variant = SettingsProfilePhotoVariant(ready.copy(localPath = null), 130, 130)
            pages.last()(SettingsResult(SettingsProfilePhotoHistoryPage(SettingsProfilePhotoIdentity(43, 91),
                listOf(SettingsProfilePhotoHistoryEntry(11, 0, 0, listOf(variant), authorization = ticket),
                    SettingsProfilePhotoHistoryEntry(12, 0, 1, listOf(variant))), 2, 2, 0)))
        }
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) = error("No upload in this fixture")
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) = error("No birthdate mutation in this fixture")
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
            error("No photo mutation in this fixture")
    }

    private class Settings(override val profileExtras: Extras) : TdSettingsSession {
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) =
            result(SettingsResult(SettingsProfile(43, "Synthetic", "", "", "", "")))
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
        val ready = MediaFile(8, size = 100, localPath = "synthetic-ready.jpg", downloaded = true, uniqueId = "native-8")
    }
}
