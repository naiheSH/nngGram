package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Actual Engine/actor admission, with deliberately ungated settings and profile-extras facades. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProfilePhotoHistoryEngineTest {
    @Test fun queuedHistoryOpenRechecksExternalSelectorLeaseBeforeAnyNativePage() = runTest {
        withEngine { engine, f ->
            val parent = openExtras(engine)
            val visible = AtomicBoolean(true)
            assertFalse(engine.openSettingsProfilePhotoHistory(parent) { false })
            assertTrue(engine.openSettingsProfilePhotoHistory(parent, visible::get))
            visible.set(false); runCurrent()
            assertTrue(f.extras.pages.isEmpty())
            assertFalse(engine.state.value.settings.profileExtras.history.opened)
            visible.set(true)
            val history = openHistory(engine, f, parent, visible::get)
            val page = f.extras.pages.single()
            assertEquals(43L, page.userId)
            assertEquals(0, page.offset)
            assertEquals(32, page.limit)
            assertTrue(page.active())
            assertEquals(listOf(-71L), history.entries.map { it.photoId })
            assertTrue(history.hasMore)
            assertEquals(1, history.nextOffset)
            assertTrue(engine.closeSettingsProfilePhotoHistory(parent)); runCurrent()
            assertFalse(page.active())
            assertEquals(parent, engine.state.value.settings.profileExtras.token)
        }
    }

    @Test fun queuedReadsAndWritesRecheckNativeReadyAndMandatoryLockIndependentlyWhileCleanupStillWorks() = runTest {
        for (mandatory in listOf(false, true)) {
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val history = openHistory(engine, f, parent)
                val frozenTarget = target(history)
                assertTrue(engine.moreSettingsProfilePhotoHistory(parent))
                assertTrue(engine.refreshSettingsProfilePhotoHistory(parent))
                assertTrue(engine.reuseSettingsProfileHistoryPhoto(parent, history.epoch, frozenTarget) { true })
                assertTrue(engine.removeSettingsProfileHistoryPhoto(parent, history.epoch, frozenTarget) { true })
                if (mandatory) f.required() else f.auth(AuthorizationPhase.OTHER_STEP)
                assertEquals(mandatory, f.ready)
                assertEquals(!mandatory, f.gate.allowsOrdinary())
                assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization,
                    "The actual actor has not processed the synchronous native change yet.")
                assertFalse(engine.openSettingsProfilePhotoHistory(parent) { true })
                assertFalse(engine.moreSettingsProfilePhotoHistory(parent))
                assertFalse(engine.refreshSettingsProfilePhotoHistory(parent))
                assertFalse(engine.reuseSettingsProfileHistoryPhoto(parent, history.epoch, frozenTarget) { true })
                assertFalse(engine.removeSettingsProfileHistoryPhoto(parent, history.epoch, frozenTarget) { true })
                assertTrue(engine.closeSettingsProfilePhotoHistory(parent), "Exact selector cleanup bypasses ordinary admission.")
                assertTrue(engine.closeSettingsProfileExtras(parent), "Parent cleanup remains callable under either lock.")
                runCurrent()
                assertEquals(1, f.extras.pages.size)
                assertTrue(f.extras.historyWrites.isEmpty())
                assertEquals(mandatory, engine.state.value.loginEmail.mandatory)
                assertFalse(engine.state.value.settings.profileExtras.history.opened)
                if (mandatory) f.unlock() else f.auth(AuthorizationPhase.READY)
                runCurrent()
                val fresh = openExtras(engine)
                assertNotEquals(parent, fresh)
                val freshHistory = openHistory(engine, f, fresh)
                assertFalse(engine.reuseSettingsProfileHistoryPhoto(parent, history.epoch, frozenTarget) { true })
                assertTrue(engine.reuseSettingsProfileHistoryPhoto(fresh, freshHistory.epoch, target(freshHistory)) { true })
                runCurrent()
                assertEquals(1, f.extras.historyWrites.size)
                assertTrue(f.extras.historyWrites.single().active())
            }
        }
    }

    @Test fun staleTokensEpochAndSelectionLeasesCannotDispatchEvenWhenWorkWasAlreadyQueued() = runTest {
        withEngine { engine, f ->
            val parent = openExtras(engine)
            val history = openHistory(engine, f, parent)
            val frozen = target(history)
            for (old in listOf(parent.copy(generation = parent.generation - 1), parent.copy(owner = parent.owner - 1))) {
                assertFalse(engine.openSettingsProfilePhotoHistory(old) { true })
                assertFalse(engine.closeSettingsProfilePhotoHistory(old))
                assertFalse(engine.refreshSettingsProfilePhotoHistory(old))
                assertFalse(engine.moreSettingsProfilePhotoHistory(old))
                assertFalse(engine.reuseSettingsProfileHistoryPhoto(old, history.epoch, frozen) { true })
                assertFalse(engine.removeSettingsProfileHistoryPhoto(old, history.epoch, frozen) { true })
            }
            assertFalse(engine.reuseSettingsProfileHistoryPhoto(parent, 0, frozen) { true })
            assertFalse(engine.removeSettingsProfileHistoryPhoto(parent, history.epoch, frozen) { false })
            val selected = AtomicBoolean(true)
            assertTrue(engine.reuseSettingsProfileHistoryPhoto(parent, history.epoch, frozen, selected::get))
            assertTrue(engine.removeSettingsProfileHistoryPhoto(parent, history.epoch, frozen, selected::get))
            selected.set(false); runCurrent()
            assertTrue(f.extras.historyWrites.isEmpty())
            assertTrue(engine.refreshSettingsProfilePhotoHistory(parent)); runCurrent()
            val query = f.extras.pages.last()
            query.result(SettingsResult(page(query))); runCurrent()
            val fresh = engine.state.value.settings.profileExtras.history
            assertTrue(fresh.epoch > history.epoch)
            assertTrue(engine.reuseSettingsProfileHistoryPhoto(parent, history.epoch, frozen) { true })
            assertTrue(engine.removeSettingsProfileHistoryPhoto(parent, history.epoch, frozen) { true })
            runCurrent()
            assertTrue(f.extras.historyWrites.isEmpty())
            assertEquals(SettingsError.CONFLICT, engine.state.value.settings.profileExtras.history.error)
            selected.set(true)
            assertTrue(engine.removeSettingsProfileHistoryPhoto(parent, fresh.epoch, target(fresh), selected::get)); runCurrent()
            val write = f.extras.historyWrites.single()
            assertFalse(write.reuse)
            assertTrue(write.active())
            selected.set(false)
            assertFalse(write.active(), "Native continuations retain the actual external selection lease.")
        }
    }

    @Test fun oldSelectorPageCallbackCannotPopulateItsReplacementHistoryOwner() = runTest {
        withEngine { engine, f ->
            val parent = openExtras(engine)
            assertTrue(engine.openSettingsProfilePhotoHistory(parent) { true }); runCurrent()
            val oldPage = f.extras.pages.single()
            val oldEpoch = engine.state.value.settings.profileExtras.history.epoch
            assertTrue(engine.closeSettingsProfilePhotoHistory(parent)); runCurrent()
            assertFalse(oldPage.active())
            assertTrue(engine.openSettingsProfilePhotoHistory(parent) { true }); runCurrent()
            val replacement = engine.state.value.settings.profileExtras.history
            val freshPage = f.extras.pages.last()
            assertTrue(replacement.epoch > oldEpoch)
            oldPage.result(SettingsResult(page(oldPage)))
            oldPage.result(SettingsResult(error = SettingsError.FORBIDDEN))
            runCurrent()
            assertEquals(replacement, engine.state.value.settings.profileExtras.history)
            assertTrue(replacement.loading)
            freshPage.result(SettingsResult(page(freshPage))); runCurrent()
            val loaded = engine.state.value.settings.profileExtras.history
            assertEquals(replacement.epoch, loaded.epoch)
            assertFalse(loaded.loading)
            assertEquals(listOf(-71L), loaded.entries.map { it.photoId })
            assertTrue(f.extras.historyWrites.isEmpty())
        }
    }

    @Test fun historyAndExistingCurrentPhotoDeleteShareTheActualExtrasMutationGateInBothDirections() = runTest {
        withEngine { engine, f ->
            val parent = openExtras(engine)
            val history = openHistory(engine, f, parent)
            assertTrue(engine.removeSettingsProfilePhoto(parent, 91)); runCurrent()
            assertTrue(engine.state.value.settings.profileExtras.busy)
            assertEquals(1, f.extras.currentDeletes.size)
            assertTrue(engine.reuseSettingsProfileHistoryPhoto(parent, history.epoch, target(history)) { true })
            assertTrue(engine.removeSettingsProfileHistoryPhoto(parent, history.epoch, target(history)) { true })
            assertTrue(engine.moreSettingsProfilePhotoHistory(parent))
            assertTrue(engine.refreshSettingsProfilePhotoHistory(parent)); runCurrent()
            assertTrue(f.extras.historyWrites.isEmpty())
            assertEquals(1, f.extras.pages.size)
            f.extras.currentDeletes.single().result(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
            assertFalse(engine.state.value.settings.profileExtras.busy)
            assertTrue(engine.reuseSettingsProfileHistoryPhoto(parent, history.epoch, target(history)) { true }); runCurrent()
            val write = f.extras.historyWrites.single()
            assertTrue(write.reuse)
            assertTrue(write.active())
            assertEquals(SettingsProfileExtrasOperation.REUSE_PHOTO, engine.state.value.settings.profileExtras.operation)
            assertTrue(engine.removeSettingsProfilePhoto(parent, 91)); runCurrent()
            assertEquals(1, f.extras.currentDeletes.size, "History mutation must also block the existing current-delete path.")
            write.result(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
            assertFalse(engine.state.value.settings.profileExtras.busy)
            assertTrue(engine.removeSettingsProfilePhoto(parent, 91)); runCurrent()
            assertEquals(2, f.extras.currentDeletes.size)
            assertEquals(1, f.extras.historyWrites.size, "Unlocking the shared gate never retries the failed history mutation.")
        }
    }

    @Test fun stopAndRestartKeepOldInflightAcceptanceOutOfTheNewGenerationAndItsPendingWrite() = runTest {
        withEngine { engine, f ->
            val parent = openExtras(engine)
            val history = openHistory(engine, f, parent)
            assertTrue(engine.reuseSettingsProfileHistoryPhoto(parent, history.epoch, target(history)) { true }); runCurrent()
            val oldWrite = f.extras.historyWrites.single()
            assertTrue(engine.stop())
            assertFalse(oldWrite.active())
            startReady(engine, f)
            val freshParent = openExtras(engine)
            assertNotEquals(parent.generation, freshParent.generation)
            val freshHistory = openHistory(engine, f, freshParent)
            assertFalse(engine.closeSettingsProfilePhotoHistory(parent))
            assertFalse(engine.removeSettingsProfileHistoryPhoto(parent, history.epoch, target(history)) { true })
            assertTrue(engine.removeSettingsProfileHistoryPhoto(freshParent, freshHistory.epoch, target(freshHistory)) { true }); runCurrent()
            val freshWrite = f.extras.historyWrites.last()
            assertTrue(freshWrite.active())
            val pending = engine.state.value.settings
            val reads = f.extras.reads
            val pages = f.extras.pages.size
            oldWrite.result(SettingsResult(Unit)); oldWrite.result(SettingsResult(Unit)); runCurrent()
            assertEquals(pending, engine.state.value.settings)
            assertEquals(reads, f.extras.reads)
            assertEquals(pages, f.extras.pages.size)
            assertTrue(engine.state.value.settings.profileExtras.busy)
            assertTrue(freshWrite.active())
            assertEquals(2, f.extras.historyWrites.size)
        }
    }

    private fun TestScope.openExtras(engine: TdEngine): SettingsProfileExtrasToken {
        assertTrue(engine.openSettingsProfileExtras(engine.state.value.settings.token, 43)); runCurrent()
        return engine.state.value.settings.profileExtras.also {
            assertEquals(43L, it.token.userId)
            assertEquals(91L, it.data?.photoId)
            assertFalse(it.loading)
        }.token
    }

    private fun TestScope.openHistory(engine: TdEngine, f: Fixture, parent: SettingsProfileExtrasToken,
        active: () -> Boolean = { true },
    ): SettingsProfilePhotoHistorySnapshot {
        val before = f.extras.pages.size
        assertTrue(engine.openSettingsProfilePhotoHistory(parent, active)); runCurrent()
        assertEquals(before + 1, f.extras.pages.size)
        val call = f.extras.pages.last()
        assertTrue(call.active())
        call.result(SettingsResult(page(call))); runCurrent()
        return engine.state.value.settings.profileExtras.history.also {
            assertTrue(it.opened)
            assertFalse(it.loading)
            assertEquals(identity, it.identity)
        }
    }

    private fun TestScope.startReady(engine: TdEngine, f: Fixture) {
        engine.start(); runCurrent()
        f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
        f.auth(AuthorizationPhase.READY); runCurrent()
        assertTrue(engine.state.value.settings.enabled)
        engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE); runCurrent()
        assertEquals(43L, engine.state.value.settings.profile?.userId)
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture) -> Unit) {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
        try {
            startReady(engine, f)
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
        val root = Files.createTempDirectory("profile-photo-history-engine-")
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
        fun unlock() = gate.update(LoginEmailSuggestions(2))
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

    /** No gate, owner, epoch, lease or callback-once checks: production Engine/controller owns them. */
    private class Extras : TdSettingsProfileExtrasSession {
        data class Page(val userId: Long, val offset: Int, val limit: Int, val active: () -> Boolean,
            val result: (SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit)
        data class Write(val userId: Long, val target: SettingsProfilePhotoHistoryTarget, val reuse: Boolean,
            val active: () -> Boolean, val result: (SettingsResult<Unit>) -> Unit)
        data class CurrentDelete(val active: () -> Boolean, val result: (SettingsResult<Unit>) -> Unit)
        var reads = 0
        val pages = mutableListOf<Page>()
        val historyWrites = mutableListOf<Write>()
        val currentDeletes = mutableListOf<CurrentDelete>()
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) {
            reads++
            result(SettingsResult(SettingsProfileExtrasData(userId, 91, null, SettingsCalendarDate(26, 10, 2026))))
        }
        override fun photoHistoryPage(userId: Long, offset: Int, limit: Int, isCurrent: () -> Boolean,
            result: (SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit,
        ) { pages += Page(userId, offset, limit, isCurrent, result) }
        override fun reuseHistoryPhoto(userId: Long, target: SettingsProfilePhotoHistoryTarget, isCurrent: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit,
        ) { historyWrites += Write(userId, target, true, isCurrent, result) }
        override fun deleteHistoryPhoto(userId: Long, target: SettingsProfilePhotoHistoryTarget, isCurrent: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit,
        ) { historyWrites += Write(userId, target, false, isCurrent, result) }
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            currentDeletes += CurrentDelete(isCurrent, result)
        }
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) = error("No file upload in this fixture")
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) = error("No birthdate mutation in this fixture")
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
        val identity = SettingsProfilePhotoIdentity(43, currentPhotoId = 91, personalPhotoId = 92, publicPhotoId = 93)
        fun page(call: Extras.Page) = SettingsProfilePhotoHistoryPage(identity,
            listOf(SettingsProfilePhotoHistoryEntry(-71, 1, call.offset, emptyList())), 1, 40, call.offset)
        fun target(history: SettingsProfilePhotoHistorySnapshot) = history.entries.first().let {
            SettingsProfilePhotoHistoryTarget(assertNotNull(history.identity), it.photoId, it.rawOffset)
        }
    }
}
