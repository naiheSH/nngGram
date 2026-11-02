package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Real Engine actor and security controller, with a synthetic TD boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDeviceNameEngineOwnerTest {
    @Test fun securityTokenAndCurrentDeviceIdentityAreReadFromTheRealEngineSnapshot() = runTest {
        withEngine { engine, fake ->
            val security = engine.state.value.settings.security
            val current = security.sessions.single()
            val owner = engine.captureDeviceNameSecurityOwner(security.token, current)
            assertTrue(owner())
            assertFalse(engine.captureDeviceNameSecurityOwner(security.token.copy(owner = security.token.owner + 1), current)())
            assertFalse(engine.captureDeviceNameSecurityOwner(security.token, current.copy(deviceModel = "Changed"))())
            fake.settings.model = "Changed"
            engine.refreshSettingsSecurity(security.token); runCurrent()
            assertFalse(owner())
            fake.settings.model = "Native"
            engine.refreshSettingsSecurity(security.token); runCurrent()
            assertFalse(owner(), "An observed changed target cannot revive when its old fields return")
            engine.closeSettingsSecurity(security.token); runCurrent()
            assertFalse(owner())
            engine.openSettingsSecurity(engine.state.value.settings.token, SettingsSecuritySection.SESSIONS); runCurrent()
            val next = engine.state.value.settings.security
            assertNotEquals(security.token, next.token)
            assertTrue(engine.captureDeviceNameSecurityOwner(next.token, next.sessions.single())())
            assertFalse(owner(), "The old witness cannot revive after a later owner opens")
        }
    }

    @Test fun nativeAuthorizationLockAndStopRevokeWitnessBeforeTheActorProcessesEvents() = runTest {
        for (case in listOf("authorization", "mandatory", "stop")) withEngine { engine, fake ->
            val security = engine.state.value.settings.security
            val owner = engine.captureDeviceNameSecurityOwner(security.token, security.sessions.single())
            assertTrue(owner())
            when (case) {
                "authorization" -> fake.auth(AuthorizationPhase.OTHER_STEP)
                "mandatory" -> fake.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
                else -> async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
            }
            assertTrue(engine.state.value.settings.enabled, "The synthetic actor has not consumed the event yet")
            assertFalse(owner())
            if (case != "stop")
                assertFalse(engine.captureDeviceNameSecurityOwner(security.token, security.sessions.single())())
            runCurrent()
        }
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture) -> Unit) {
        val fixture = Fixture()
        val engine = syntheticOpenEngine(fixture.store, fixture, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(engine.start()); runCurrent()
            fixture.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            fixture.auth(AuthorizationPhase.READY); runCurrent()
            engine.openSettingsSecurity(engine.state.value.settings.token, SettingsSecuritySection.SESSIONS); runCurrent()
            assertFalse(engine.state.value.settings.security.loading)
            assertEquals(1, engine.state.value.settings.security.sessions.size)
            block(engine, fixture)
        } finally {
            assertTrue(engine.close())
            fixture.root.toFile().deleteRecursively()
        }
    }

    private class Fixture : TdTransport {
        val root = Files.createTempDirectory("device-name-engine-")
        val store = ConfigurationStore(root.resolve("local.properties"), root.resolve("data"))
        val gate = TdUserInteractionGate()
        val settings = FakeSettings()
        var ready = false
        private lateinit var authorization: (AuthorizationDetails) -> Unit
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }
        fun auth(phase: AuthorizationPhase) { ready = phase == AuthorizationPhase.READY; authorization(AuthorizationDetails(phase)) }
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit): TdSession {
            authorization = onAuthorization
            return object : SyntheticOpenKeySession {
                override fun authorizationReady() = ready
                override val interactionGate = gate
                override val settings: TdSettingsSession = this@Fixture.settings
                override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = result(null)
                override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
                override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("Synthetic fixture")
                override fun close() = auth(AuthorizationPhase.CLOSED)
            }
        }
    }

    private class FakeSettings : TdSettingsSession {
        var model = "Native"
        override fun securitySessionsData(isCurrent: () -> Boolean, result: (SettingsResult<SettingsSessionsData>) -> Unit) {
            result(SettingsResult(SettingsSessionsData(listOf(SettingsSession(0, true, model, "App", "1", "OS", "1", 4, "", "", 3)))))
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
