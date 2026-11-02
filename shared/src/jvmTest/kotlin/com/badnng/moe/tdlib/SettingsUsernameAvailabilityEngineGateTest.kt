package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
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
import kotlin.concurrent.thread
import kotlin.test.*

/** Real Engine/controller dispatch with a deliberately unguarded synthetic username session. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsUsernameAvailabilityEngineGateTest {
    @Test fun readyRequestReachesSessionUnchangedAndWorkerCompletionWaitsForActorExactlyOnce() = runTest {
        withEngine { engine, f ->
            val token = engine.state.value.settings.token
            val before = engine.state.value.settings
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            assertTrue(engine.checkSettingsUsernameAvailability(token, 43, "Mixed_Name", { true }, results::add))
            assertTrue(f.settings.checks.isEmpty())
            runCurrent()
            val check = f.settings.checks.single()
            assertEquals(43L, check.userId)
            assertEquals("Mixed_Name", check.username)
            var workerCurrent = false
            thread {
                workerCurrent = check.current()
                check.reply(available("Mixed_Name"))
                check.reply(available("ignored_duplicate"))
            }.join()
            assertTrue(workerCurrent)
            assertTrue(results.isEmpty())
            runCurrent()
            assertEquals(listOf(available("Mixed_Name")), results)
            assertEquals(before, engine.state.value.settings, "A read-only check cannot mutate the profile or save revisions.")
        }
    }

    @Test fun immediateNativeReadyLossAndMandatoryGateRejectWithoutCallingSession() = runTest {
        for (mandatory in listOf(false, true)) withEngine { engine, f ->
            val token = engine.state.value.settings.token
            if (mandatory) f.required() else f.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertEquals(mandatory, f.ready)
            assertEquals(!mandatory, f.gate.allowsOrdinary())
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            assertFalse(engine.checkSettingsUsernameAvailability(token, 43, "candidate", { true }, results::add))
            assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error })
            runCurrent()
            assertTrue(f.settings.checks.isEmpty())
            assertEquals(1, results.size)
        }
    }

    @Test fun queuedRequestRechecksBothNativeGatesAndDiscardsExactlyOnceBeforeSessionDispatch() = runTest {
        for (mandatory in listOf(false, true)) withEngine { engine, f ->
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            assertTrue(engine.checkSettingsUsernameAvailability(engine.state.value.settings.token, 43, "candidate", { true }, results::add))
            if (mandatory) f.required() else f.auth(AuthorizationPhase.OTHER_STEP)
            assertTrue(results.isEmpty())
            runCurrent()
            assertTrue(f.settings.checks.isEmpty())
            assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error })
            runCurrent()
            assertEquals(1, results.size)
        }
    }

    @Test fun staleTokenWrongSelfAndInactiveOrThrowingOwnersNeverReachSession() = runTest {
        withEngine { engine, f ->
            val token = engine.state.value.settings.token
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            for (stale in listOf(token.copy(generation = token.generation - 1), token.copy(revision = token.revision + 1))) {
                assertFalse(engine.checkSettingsUsernameAvailability(stale, 43, "candidate", { true }, results::add))
            }
            for (owner in listOf<() -> Boolean>({ false }, { error("synthetic owner failure") })) {
                assertFalse(engine.checkSettingsUsernameAvailability(token, 43, "candidate", owner, results::add))
            }
            assertTrue(engine.checkSettingsUsernameAvailability(token, 44, "candidate", { true }, results::add))
            val queuedOwner = AtomicBoolean(true)
            assertTrue(engine.checkSettingsUsernameAvailability(token, 43, "candidate", queuedOwner::get, results::add))
            queuedOwner.set(false)
            runCurrent()
            assertTrue(f.settings.checks.isEmpty())
            assertEquals(6, results.size)
            assertTrue(results.all { it.error == SettingsError.NOT_READY && it.value == null })
        }
    }

    @Test fun stopRevisionInvalidatesAnEarlierQueuedRequestBeforeStopActorRuns() = runTest {
        withEngine { engine, f ->
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            assertTrue(engine.checkSettingsUsernameAvailability(engine.state.value.settings.token, 43, "candidate", { true }, results::add))
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            assertFalse(stopped.isCompleted)
            assertTrue(engine.state.value.settings.enabled)
            runCurrent()
            assertTrue(stopped.await())
            assertTrue(f.settings.checks.isEmpty())
            assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error })
            runCurrent()
            assertEquals(1, results.size)
        }
    }

    @Test fun inFlightStopRevokesNativePredicateAndLateDuplicateSuccessCannotRepublish() = runTest {
        withEngine { engine, f ->
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            assertTrue(engine.checkSettingsUsernameAvailability(engine.state.value.settings.token, 43, "candidate", { true }, results::add))
            runCurrent()
            val check = f.settings.checks.single()
            assertTrue(check.current())
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            assertFalse(check.current(), "Stop revision invalidates existing work before actor retirement.")
            runCurrent()
            assertTrue(stopped.await())
            assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error })
            check.reply(available("candidate")); check.reply(available("candidate"))
            runCurrent()
            assertEquals(1, results.size)
            assertEquals(1, f.settings.checks.size)
        }
    }

    @Test fun publicParameterLimitsRejectWithoutDispatchAndNativeOwnsSyntaxWithinTheLimit() = runTest {
        withEngine { engine, f ->
            val token = engine.state.value.settings.token
            val invalid = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            for ((user, name) in listOf(0L to "candidate", -1L to "candidate", 43L to "a".repeat(33))) {
                assertFalse(engine.checkSettingsUsernameAvailability(token, user, name, { true }, invalid::add))
            }
            assertEquals(List(3) { SettingsError.INVALID_VALUE }, invalid.map { it.error })
            assertTrue(f.settings.checks.isEmpty())
            for (name in listOf("", "a".repeat(32), "has space")) {
                val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
                assertTrue(engine.checkSettingsUsernameAvailability(token, 43, name, { true }, results::add))
                runCurrent()
                assertEquals(name, f.settings.checks.last().username)
                f.settings.checks.last().reply(SettingsResult(SettingsUsernameAvailability(43, name,
                    SettingsUsernameAvailabilityStatus.INVALID)))
                runCurrent()
                assertEquals(SettingsUsernameAvailabilityStatus.INVALID, results.single().value?.status)
            }
        }
    }

    @Test fun throwingConsumerIsCalledOnceAndCannotKillActorOrBlockTheNextRequest() = runTest {
        withEngine { engine, f ->
            val token = engine.state.value.settings.token
            var calls = 0
            assertTrue(engine.checkSettingsUsernameAvailability(token, 43, "candidate", { true }) {
                calls++
                throw IllegalStateException("synthetic consumer failure")
            })
            runCurrent()
            val first = f.settings.checks.single()
            first.reply(available("candidate")); first.reply(available("candidate"))
            runCurrent()
            assertEquals(1, calls)
            val next = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            assertTrue(engine.checkSettingsUsernameAvailability(token, 43, "next_name", { true }, next::add))
            runCurrent()
            f.settings.checks.last().reply(available("next_name")); runCurrent()
            assertEquals(listOf(available("next_name")), next)
            assertEquals(2, f.settings.checks.size)
        }
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture) -> Unit) {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(engine.start()); runCurrent()
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.auth(AuthorizationPhase.READY); runCurrent()
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
        val root = Files.createTempDirectory("username-engine-gate-")
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val settings = Settings()
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

    private class Settings : TdSettingsSession {
        data class Check(val userId: Long, val username: String, val current: () -> Boolean,
            val reply: (SettingsResult<SettingsUsernameAvailability>) -> Unit)
        val checks = mutableListOf<Check>()
        override fun checkUsername(userId: Long, username: String, isCurrent: () -> Boolean,
            result: (SettingsResult<SettingsUsernameAvailability>) -> Unit,
        ) { checks += Check(userId, username, isCurrent, result) }
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) =
            result(SettingsResult(SettingsProfile(43, "Synthetic", "", "old_name", "", "")))
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
        fun available(username: String) = SettingsResult(SettingsUsernameAvailability(43, username,
            SettingsUsernameAvailabilityStatus.CAN_BE_SET))
    }
}
