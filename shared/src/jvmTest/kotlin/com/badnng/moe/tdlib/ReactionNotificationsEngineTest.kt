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

/** The facade does no admission checks: these exercise the actual Engine actor and child owner. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactionNotificationsEngineTest {
    @Test fun queuedOpenRechecksExternalLeaseBeforeAnyCacheReadAndUnknownStaysUnknown() = runTest {
        withEngine { engine, f ->
            val account = engine.state.value.settings.token
            val visible = AtomicBoolean(true)
            assertFalse(engine.openSettingsReactionNotifications(account.copy(generation = account.generation - 1), visible::get))
            assertFalse(engine.openSettingsReactionNotifications(account) { false })
            assertTrue(engine.openSettingsReactionNotifications(account, visible::get))
            visible.set(false)
            runCurrent()
            assertEquals(0, f.reactions.reads)
            assertFalse(engine.state.value.settings.reactionNotifications.opened)
            visible.set(true)
            val page = open(engine, visible::get)
            assertNull(page.native.value)
            assertEquals(1, f.reactions.reads)
            assertTrue(engine.refreshSettingsReactionNotificationsFromCache(page.token)); runCurrent()
            assertNull(engine.state.value.settings.reactionNotifications.native.value)
            assertEquals(2, f.reactions.reads)
            assertTrue(engine.saveSettingsReactionNotifications(page.token, edit(page))); runCurrent()
            assertTrue(f.reactions.saves.isEmpty())
            assertEquals(SettingsError.NOT_READY, engine.state.value.settings.reactionNotifications.outcome?.error)
        }
    }

    @Test fun queuedSaveAndRefreshRecheckNativeReadyIndependentlyOfMandatoryEmailAndRecoverNormally() = runTest {
        withEngine { engine, f ->
            f.reactions.update(initial)
            val page = open(engine)
            val request = edit(page)
            val reads = f.reactions.reads
            assertTrue(engine.saveSettingsReactionNotifications(page.token, request))
            assertTrue(engine.refreshSettingsReactionNotificationsFromCache(page.token))
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertFalse(f.ready)
            assertTrue(f.gate.allowsOrdinary())
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertFalse(engine.saveSettingsReactionNotifications(page.token, request))
            assertFalse(engine.refreshSettingsReactionNotificationsFromCache(page.token))
            assertFalse(engine.openSettingsReactionNotifications(engine.state.value.settings.token) { true })
            assertTrue(engine.closeSettingsReactionNotifications(page.token), "Exact cleanup bypasses the ordinary READY gate.")
            runCurrent()
            assertEquals(reads, f.reactions.reads)
            assertTrue(f.reactions.saves.isEmpty())
            assertFalse(engine.state.value.loginEmail.mandatory)
            assertFalse(engine.state.value.settings.reactionNotifications.opened)
            f.ready = true
            assertFalse(engine.openSettingsReactionNotifications(engine.state.value.settings.token) { true },
                "The stale actor authorization snapshot also gates admission.")
            f.auth(AuthorizationPhase.READY); runCurrent()
            val fresh = open(engine)
            assertNotEquals(page.token, fresh.token)
            assertTrue(engine.saveSettingsReactionNotifications(fresh.token, edit(fresh))); runCurrent()
            assertTrue(f.reactions.saves.single().active())
        }
    }

    @Test fun mandatoryLockRejectsQueuedWorkWhileReadyRemainsTrueAndUnlockNeedsAFreshOwner() = runTest {
        withEngine { engine, f ->
            f.reactions.update(initial)
            val page = open(engine)
            val reads = f.reactions.reads
            assertTrue(engine.saveSettingsReactionNotifications(page.token, edit(page)))
            assertTrue(engine.refreshSettingsReactionNotificationsFromCache(page.token))
            f.required()
            assertTrue(f.ready)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertFalse(f.gate.allowsOrdinary())
            assertFalse(engine.openSettingsReactionNotifications(engine.state.value.settings.token) { true })
            assertFalse(engine.saveSettingsReactionNotifications(page.token, edit(page)))
            assertFalse(engine.refreshSettingsReactionNotificationsFromCache(page.token))
            assertTrue(engine.closeSettingsReactionNotifications(page.token))
            runCurrent()
            assertEquals(reads, f.reactions.reads)
            assertTrue(f.reactions.saves.isEmpty())
            assertTrue(engine.state.value.loginEmail.mandatory)
            assertFalse(engine.state.value.settings.reactionNotifications.opened)
            f.unlock(); runCurrent()
            val fresh = open(engine)
            assertNotEquals(page.token, fresh.token)
            assertFalse(engine.saveSettingsReactionNotifications(page.token, edit(page)))
            assertTrue(engine.saveSettingsReactionNotifications(fresh.token, edit(fresh))); runCurrent()
            assertEquals(1, f.reactions.saves.size, "Unlock must admit a real new-owner native save without replaying rejected work.")
            assertTrue(f.reactions.saves.single().active())
        }
    }

    @Test fun exactGenerationAndOwnerAreRequiredAndExpiredLeaseBlocksQueuedCacheAndSaveWork() = runTest {
        withEngine { engine, f ->
            f.reactions.update(initial)
            val visible = AtomicBoolean(true)
            val page = open(engine, visible::get)
            for (stale in listOf(page.token.copy(generation = page.token.generation - 1), page.token.copy(owner = page.token.owner - 1))) {
                assertFalse(engine.refreshSettingsReactionNotificationsFromCache(stale))
                assertFalse(engine.saveSettingsReactionNotifications(stale, edit(page)))
                assertFalse(engine.closeSettingsReactionNotifications(stale))
            }
            val reads = f.reactions.reads
            assertTrue(engine.refreshSettingsReactionNotificationsFromCache(page.token))
            assertTrue(engine.saveSettingsReactionNotifications(page.token, edit(page)))
            visible.set(false); runCurrent()
            assertEquals(reads, f.reactions.reads)
            assertTrue(f.reactions.saves.isEmpty())
            assertTrue(engine.closeSettingsReactionNotifications(page.token)); runCurrent()
            visible.set(true)
            val fresh = open(engine, visible::get)
            assertNotEquals(page.token, fresh.token)
            assertFalse(engine.closeSettingsReactionNotifications(page.token))
            assertTrue(engine.saveSettingsReactionNotifications(fresh.token, edit(fresh))); runCurrent()
            assertTrue(f.reactions.saves.single().active())
        }
    }

    @Test fun nativeObserverPublishesOnlyOnActorAndFreshCacheRejectsAQueuedOldRevision() = runTest {
        withEngine { engine, f ->
            val page = open(engine)
            f.reactions.update(initial)
            assertNull(engine.state.value.settings.reactionNotifications.native.value)
            runCurrent()
            val loaded = engine.state.value.settings.reactionNotifications
            assertEquals(initial, loaded.native)
            val external = initial.copy(value = value.copy(soundId = 991), revision = 2)
            // The command is ahead of the observer in the actual Engine channel, but behind its native cache.
            assertTrue(engine.saveSettingsReactionNotifications(page.token, edit(loaded)))
            f.reactions.update(external)
            runCurrent()
            assertTrue(f.reactions.saves.isEmpty())
            assertEquals(external, engine.state.value.settings.reactionNotifications.native)
            assertEquals(SettingsError.CONFLICT, engine.state.value.settings.reactionNotifications.outcome?.error)
            val before = engine.state.value.settings.reactionNotifications
            f.reactions.observer?.invoke(initial)
            runCurrent()
            assertEquals(before, engine.state.value.settings.reactionNotifications)
        }
    }

    @Test fun oldInflightAcceptanceCannotBecomeTheUnlockedReplacementPagesOutcome() = runTest {
        withEngine { engine, f ->
            f.reactions.update(initial)
            val page = open(engine)
            val request = edit(page, 91)
            assertTrue(engine.saveSettingsReactionNotifications(page.token, request)); runCurrent()
            val write = f.reactions.saves.single()
            assertTrue(write.active())
            f.required(); runCurrent()
            assertFalse(write.active())
            assertFalse(engine.state.value.settings.reactionNotifications.opened)
            f.unlock(); runCurrent()
            val reads = f.reactions.reads
            val fresh = open(engine)
            assertNotEquals(page.token, fresh.token)
            assertTrue(fresh.saving)
            assertNull(fresh.activeAttempt)
            assertEquals(reads, f.reactions.reads, "A replacement owner waits for the real in-flight result before reading.")
            val desired = request.patch.applyTo(value)
            val accepted = initial.copy(value = desired, revision = 2)
            f.reactions.update(accepted)
            val result = ReactionNotificationSaveResult(true, desired, initial)
            write.result(result); write.result(result); runCurrent()
            val settled = engine.state.value.settings.reactionNotifications
            assertEquals(fresh.token, settled.token)
            assertEquals(accepted, settled.native)
            assertFalse(settled.saving)
            assertFalse(settled.awaitingUpdate)
            assertNull(settled.outcome, "The old owner's local acceptance cannot be claimed by the new page.")
            assertEquals(1, f.reactions.saves.size)
            val next = edit(settled, 92).copy(patch = ReactionNotificationPatch(showSender = true))
            assertTrue(engine.saveSettingsReactionNotifications(settled.token, next)); runCurrent()
            assertEquals(2, f.reactions.saves.size)
        }
    }

    @Test fun localAcceptanceWaitsForNativeUpdateAndNeverTreatsItsOldActualAsAServerAcknowledgement() = runTest {
        withEngine { engine, f ->
            f.reactions.update(initial)
            val page = open(engine)
            val edit = edit(page, 101)
            assertTrue(engine.saveSettingsReactionNotifications(page.token, edit)); runCurrent()
            val write = f.reactions.saves.single()
            val desired = edit.patch.applyTo(value)
            assertEquals(initial, write.expected)
            assertEquals(edit.patch, write.patch)
            write.result(ReactionNotificationSaveResult(true, desired, initial)); runCurrent()
            val accepted = engine.state.value.settings.reactionNotifications
            assertTrue(accepted.outcome?.locallyAccepted == true)
            assertEquals(101L, accepted.outcome?.edit?.contentRevision)
            assertTrue(accepted.awaitingUpdate)
            assertEquals(initial, accepted.native)
            assertTrue(engine.refreshSettingsReactionNotificationsFromCache(page.token)); runCurrent()
            assertTrue(engine.state.value.settings.reactionNotifications.awaitingUpdate)
            val external = initial.copy(value = value.copy(poll = ReactionNotificationSource.CONTACTS, soundId = 992), revision = 2)
            f.reactions.update(external); runCurrent()
            val conflicted = engine.state.value.settings.reactionNotifications
            assertEquals(external, conflicted.native)
            assertFalse(conflicted.awaitingUpdate)
            assertEquals(SettingsError.CONFLICT, conflicted.error)
            assertTrue(conflicted.refreshRequired)
            assertEquals(1, f.reactions.saves.size)
        }
    }

    @Test fun stopRetiresObserverAndLateNativeCallbacksCannotPublishOrReadRetiredCache() = runTest {
        withEngine { engine, f ->
            f.reactions.update(initial)
            val page = open(engine)
            assertTrue(engine.saveSettingsReactionNotifications(page.token, edit(page))); runCurrent()
            val write = f.reactions.saves.single()
            val observer = assertNotNull(f.reactions.observer)
            assertTrue(engine.stop())
            assertFalse(write.active())
            assertNull(f.reactions.observer)
            assertEquals(1, f.reactions.retireCount)
            val stopped = engine.state.value.settings
            val reads = f.reactions.reads
            observer(initial.copy(value = value.copy(soundId = 993), revision = 2))
            write.result(ReactionNotificationSaveResult(true, write.patch.applyTo(value), initial))
            runCurrent()
            assertEquals(stopped, engine.state.value.settings)
            assertEquals(reads, f.reactions.reads)
            assertFalse(engine.saveSettingsReactionNotifications(page.token, edit(page)))
            assertFalse(engine.refreshSettingsReactionNotificationsFromCache(page.token))
            assertEquals(1, f.reactions.saves.size)
        }
    }

    private fun TestScope.open(engine: TdEngine, active: () -> Boolean = { true }): ReactionNotificationsSnapshot {
        assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
        assertFalse(engine.state.value.loginEmail.mandatory)
        assertTrue(engine.openSettingsReactionNotifications(engine.state.value.settings.token, active))
        runCurrent()
        return engine.state.value.settings.reactionNotifications.also { assertTrue(it.opened) }
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture) -> Unit) {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
        try {
            engine.start(); runCurrent()
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.state.value.settings.enabled)
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
        val root = Files.createTempDirectory("reaction-notifications-engine-")
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val reactions = Reactions()
        val settings = Settings(reactions)
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

    /** No gate, lease, revision, READY or callback-once logic: the real actor must enforce these. */
    private class Reactions : TdReactionNotificationsSession {
        data class Save(val expected: ReactionNotificationNativeState, val patch: ReactionNotificationPatch,
            val active: () -> Boolean, val result: (ReactionNotificationSaveResult) -> Unit)
        var cached = ReactionNotificationNativeState()
        var reads = 0
        var observer: ((ReactionNotificationNativeState) -> Unit)? = null
        var retireCount = 0
        val saves = mutableListOf<Save>()
        override fun state(): ReactionNotificationNativeState { reads++; return cached }
        override fun observe(observer: ((ReactionNotificationNativeState) -> Unit)?) { this.observer = observer }
        override fun save(expected: ReactionNotificationNativeState, patch: ReactionNotificationPatch,
            active: () -> Boolean, result: (ReactionNotificationSaveResult) -> Unit,
        ) { saves += Save(expected, patch, active, result) }
        override fun retire() { retireCount++ }
        fun update(state: ReactionNotificationNativeState) { cached = state; observer?.invoke(state) }
    }

    private class Settings(override val reactionNotifications: Reactions) : TdSettingsSession {
        override fun retire() = reactionNotifications.retire()
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

    private companion object {
        val value = ReactionNotificationValue(ReactionNotificationSource.ALL, ReactionNotificationSource.CONTACTS,
            ReactionNotificationSource.NONE, 901, true)
        val initial = ReactionNotificationNativeState(value, 1)
        fun edit(page: ReactionNotificationsSnapshot, revision: Long = 1) = ReactionNotificationsEdit(page.native,
            ReactionNotificationPatch(message = ReactionNotificationSource.CONTACTS, showSender = false), revision)
    }
}
