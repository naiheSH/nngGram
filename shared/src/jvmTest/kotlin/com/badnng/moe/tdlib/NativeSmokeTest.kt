package com.badnng.moe.tdlib

import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.ProxyStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.util.Properties
import kotlin.test.*

/** Opt in with -PnativeSmoke. Uses synthetic parameters and NetworkTypeNone, never a real account. */
class NativeSmokeTest {
    @Test
    fun realEngineInitializesAnOfflineUnicodeDatabaseAndClosesCleanly() = runBlocking {
        val directory = Files.createTempDirectory("真实原生 中文 路径 ")
        val configPath = directory.resolve("local.properties")
        Files.writeString(configPath, "api_id=1\napi_hash=${"0".repeat(32)}\nuse_test_dc=true\n")
        val data = directory.resolve("用户 数据")
        val engine = TdEngine(ConfigurationStore(configPath, data), OfficialTdTransport(offline = true))
        try {
            engine.start()
            val setup = assertNotNull(withTimeoutOrNull(40_000) {
                engine.localLockState.first {
                    it.phase == com.badnng.moe.settings.LocalLockPhase.NEW_ROOT_SETUP ||
                        it.phase == com.badnng.moe.settings.LocalLockPhase.RECOVERY
                }
            })
            assertEquals(com.badnng.moe.settings.LocalLockPhase.NEW_ROOT_SETUP, setup.phase)
            engine.localLockActions.skipSetup()
            val snapshot = assertNotNull(withTimeoutOrNull(40_000) {
                engine.state.first {
                    (it.status.authorization == AuthorizationPhase.WAITING_LOGIN && it.status.engine == EnginePhase.INITIALIZED && it.proxy == ProxyStatus.DIRECT) ||
                        it.status.engine == EnginePhase.FAILED
                }
            }, "Initialization timed out: ${engine.state.value.status}; ${engine.state.value.note}")
            assertEquals(EnginePhase.INITIALIZED, snapshot.status.engine, snapshot.status.failure?.toString())
            assertEquals(AuthorizationPhase.WAITING_LOGIN, snapshot.status.authorization)
            assertTrue(snapshot.clientActive)
            val native = assertNotNull(snapshot.native)
            val pin = Properties().apply { NativeSmokeTest::class.java.getResourceAsStream("/tdlib-source.properties")!!.use(::load) }
            assertEquals(pin.getProperty("commit"), native.commit)
            assertTrue(native.version.isNotBlank())
            assertTrue(Files.list(data.resolve("database")).use { it.findAny().isPresent })
            assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(data) }
            println("Native engine verified: TDLib ${native.version}, commit ${native.commit}; offline WaitPhoneNumber; UTF-8 directories")
        } finally {
            assertTrue(engine.close(15_000), "TDLib did not report AuthorizationStateClosed")
        }
        assertEquals(EnginePhase.CLOSED, engine.state.value.status.engine)
        assertFalse(engine.state.value.clientActive)
        DataDirectoryLease.acquire(data).close()
    }
}
