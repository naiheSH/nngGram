package com.badnng.moe.tdlib

import com.badnng.moe.security.*
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Legacy business fixtures own only fresh temporary roots. Supply their explicit
 * synthetic OPEN record without touching production defaults, DPAPI or live data.
 * The coordinator still binds the key to the engine's real temporary root lease,
 * checks its revision and record, and waits for the original fake parameter result.
 */
internal fun syntheticOpenEngine(
    configuration: ConfigurationStore,
    transport: TdTransport,
    dispatcher: CoroutineDispatcher,
    journal: SafeEventJournal = SafeEventJournal.besideConfiguration(configuration.path),
    nowMillis: () -> Long = System::currentTimeMillis,
    saveTargetRegistry: com.badnng.moe.media.NativeSaveTargetRegistry = com.badnng.moe.media.NativeSaveTargets.registry,
): TdEngine = TdEngine(configuration, transport, dispatcher, journal, nowMillis, saveTargetRegistry = saveTargetRegistry).also { engine ->
    engine.localLockCoordinatorFactory = { root, worker, start, changed ->
        LocalLockCoordinator(root, worker, start, onWitnessChanged = changed,
            persistence = SyntheticOpenPersistence(), preferences = SyntheticOpenPreferences)
    }
    engine.localLockIdleFactory = { due, availability ->
        LocalLockIdleController(object : LocalLockIdleSource {
            override fun tickMillis() = 1_000L
            override fun lastInputDword() = 1_000L
        }, LocalLockDeadlineScheduler { _, _ -> LocalLockDeadlineHandle {} }, due, availability)
    }
}

/** These fakes model native results, rather than the native key serialization. */
internal interface SyntheticOpenKeySession : TdSession {
    override fun setParameters(
        config: TdConfiguration,
        directories: DataDirectoryLease,
        key: ByteArray,
        result: (TdParametersResult) -> Unit,
    ) {
        assertEquals(32, key.size)
        assertTrue(key.contentEquals(ByteArray(32) { 42 }))
        setParameters(config, directories) { code -> result(TdParametersResult(code)) }
    }
}

private class SyntheticOpenPersistence : LocalProtectionPersistence {
    private val bytes = byteArrayOf(7, 8)
    private val envelope = LocalStoredEnvelope(bytes, 1,
        MessageDigest.getInstance("SHA-256").digest(bytes), LocalStoredKind.OPEN)

    override fun read(): LocalRecordRead = LocalRecordRead.Present(envelope,
        LocalDatabaseKey.copyOf(ByteArray(32) { 42 }))
    override fun commit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult =
        error("Legacy business fixtures never change local protection")
    override fun finalizeCommit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult =
        error("Legacy business fixtures never finalize local protection")
}

private object SyntheticOpenPreferences : LocalLockPreferences {
    override fun read(): LocalLockPreferenceRead = LocalLockPreferenceRead.Value(3_600)
    override fun write(seconds: Long) = error("Legacy business fixtures never change local lock preferences")
}
