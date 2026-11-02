package com.badnng.moe.security

import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockPhase
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Ownership tests inject every disk, crypto, worker, clock, timer and native boundary. */
class LocalLockCoordinatorTest {
    @Test
    fun cancelClearsQueuedInputButKeepsTheSingleSlotUntilTheRunnableFinishes() {
        val f = Fixture(fresh = true)
        f.controller.initialize()
        assertEquals(LocalLockPhase.NEW_ROOT_SETUP, f.state.phase)
        val password = "synthetic password".toCharArray()
        val confirmation = password.copyOf()
        f.controller.actions.create(password, confirmation)
        assertTrue(f.state.busy)
        assertTrue(confirmation.all { it == '\u0000' })
        f.controller.actions.cancel()
        assertTrue(password.all { it == '\u0000' }, "Queued passwords can be cleared immediately on cancel.")
        val later = "later".toCharArray()
        val laterConfirmation = later.copyOf()
        f.controller.actions.create(later, laterConfirmation)
        assertTrue(later.all { it == '\u0000' } && laterConfirmation.all { it == '\u0000' })
        assertEquals(LocalLockIssue.BUSY, f.state.issue)
        assertEquals(1, f.worker.pending.size)
        assertEquals(0, f.store.commits)

        f.worker.runNext()
        assertTrue(password.all { it == '\u0000' })
        assertEquals(0, f.crypto.creates, "Canceled work must not enter the KDF when its runnable starts later.")
        assertEquals(null, f.crypto.createdKey)
        assertFalse(f.state.busy)
        assertEquals(0, f.store.commits)
        assertTrue(f.starts.isEmpty())
    }

    @Test
    fun cancelOfAnAlreadyRunningKdfKeepsItsSlotAndClosesTheLateKey() {
        val f = Fixture(fresh = false)
        f.controller.initialize()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        f.crypto.openBarrier = entered to release
        val password = "synthetic".toCharArray()
        f.controller.actions.unlock(password)
        val worker = Thread({
            try { f.worker.runNext() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { finished.countDown() }
        }, "synthetic-running-kdf").apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.controller.actions.cancel()
            assertTrue(f.state.busy)
            val duplicate = "other".toCharArray()
            f.controller.actions.unlock(duplicate)
            assertEquals(LocalLockIssue.BUSY, f.state.issue)
            assertTrue(duplicate.all { it == '\u0000' })
            assertEquals(1L, finished.count)
        } finally {
            release.countDown()
            worker.join(2_000)
        }
        assertEquals(0L, finished.count)
        assertEquals(null, failure.get())
        assertTrue(password.all { it == '\u0000' })
        assertEquals(LocalCryptoResult.KeyUnavailable, f.crypto.lastKey!!.borrow { LocalCryptoResult.Success(Unit) })
        assertTrue(f.starts.isEmpty())
        assertFalse(f.state.busy)
    }

    @Test
    fun cancelOfQueuedRemovalImmediatelyClosesTheTransferredProof() {
        val f = Fixture(fresh = false)
        f.unlockCold()
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.worker.runNext()
        val proof = f.crypto.lastKey!!
        assertIs<LocalCryptoResult.Success<Unit>>(proof.borrow { LocalCryptoResult.Success(Unit) })
        f.controller.actions.remove()
        assertTrue(f.state.busy)
        f.controller.actions.cancel()
        assertEquals(LocalCryptoResult.KeyUnavailable, proof.borrow { LocalCryptoResult.Success(Unit) })
        assertTrue(f.state.busy, "A cleared proof does not make the physically queued worker disappear.")
        f.worker.runNext()
        assertFalse(f.state.busy)
        assertEquals(0, f.store.commits)
        assertEquals(0, f.store.finalizations)
        assertEquals(LocalLockPhase.UNLOCKED, f.state.phase)
    }

    @Test
    fun cancelWhileRootRevisionReadBlocksPreventsTheQueuedUnlockFromStarting() {
        val f = Fixture(fresh = false)
        f.controller.initialize()
        val password = "synthetic".toCharArray()
        blockAdmissionAndCancel(f, { f.controller.actions.unlock(password) }, { f.controller.actions.cancel() })
        if (f.worker.pending.isNotEmpty()) f.worker.runNext()
        assertTrue(password.all { it == '\u0000' })
        assertEquals(0, f.crypto.opens)
        assertTrue(f.starts.isEmpty())
        assertTrue(f.controller.currentWitness().blocked)
    }

    @Test
    fun managementDepartureWhileRootRevisionReadBlocksCannotMintProof() {
        val f = Fixture(fresh = false)
        f.unlockCold()
        val opensBefore = f.crypto.opens
        val password = "synthetic".toCharArray()
        blockAdmissionAndCancel(f, { f.controller.actions.verifyManagement(password) }, { f.controller.actions.closeManagement() })
        if (f.worker.pending.isNotEmpty()) f.worker.runNext()
        assertTrue(password.all { it == '\u0000' })
        assertEquals(opensBefore, f.crypto.opens)
        assertFalse(f.state.managementAuthorized)
        assertFalse(f.state.canChange || f.state.canRemove)
    }

    @Test
    fun rootRevisionAndRecordReplacementInvalidateLateUnlockAndClearItsKey() {
        for (replaceRecord in listOf(false, true)) {
            val f = Fixture(fresh = false)
            f.controller.initialize()
            assertEquals(LocalLockPhase.COLD_LOCKED, f.state.phase)
            val password = "synthetic".toCharArray()
            f.controller.actions.unlock(password)
            if (replaceRecord) f.store.present(bytes = byteArrayOf(9, 9), generation = 1)
            else f.rootRevision++
            f.worker.runNext()
            assertTrue(password.all { it == '\u0000' })
            assertEquals(LocalCryptoResult.KeyUnavailable, f.crypto.lastKey!!.borrow { LocalCryptoResult.Success(Unit) })
            assertEquals(LocalLockPhase.RECOVERY, f.state.phase)
            assertEquals(LocalLockIssue.STALE, f.state.issue)
            assertTrue(f.controller.currentWitness().blocked)
            assertTrue(f.starts.isEmpty())
        }
    }

    @Test
    fun unreadableRootRevisionRejectsUnlockBeforeAnyWorkerOrNativeRequest() {
        val f = Fixture(fresh = false)
        f.controller.initialize()
        f.revisionAvailable = false
        val password = "synthetic".toCharArray()
        f.controller.actions.unlock(password)
        assertTrue(password.all { it == '\u0000' })
        assertEquals(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE, f.state.issue)
        assertEquals(LocalLockPhase.COLD_LOCKED, f.state.phase)
        assertTrue(f.worker.pending.isEmpty())
        assertTrue(f.starts.isEmpty())
    }

    @Test
    fun closingManagementDoesNotCancelAnUnrelatedColdUnlock() {
        val f = Fixture(fresh = false)
        f.controller.initialize()
        f.controller.actions.unlock("synthetic".toCharArray())
        f.controller.actions.closeManagement()
        f.worker.runNext()
        assertEquals(LocalLockPhase.UNLOCKING, f.state.phase)
        assertEquals(1, f.starts.size)
    }

    @Test
    fun failureFromAnOldStartRequestCannotOverwriteANewerLockEpoch() {
        val f = Fixture(fresh = false)
        f.startHook = { epoch, _ ->
            assertTrue(f.controller.borrowStartupKey(epoch) { assertEquals(32, it.size) })
            f.controller.nativeStartupResult(epoch, success = true, mismatch401 = false)
            f.controller.actions.lockNow()
            error("synthetic failure after replacement")
        }
        f.controller.initialize()
        f.controller.actions.unlock("synthetic".toCharArray())
        f.worker.runNext()
        assertEquals(LocalLockPhase.HOT_LOCKED, f.state.phase)
        assertTrue(f.controller.currentWitness().blocked)
    }

    @Test
    fun coldStartBorrowsKeySynchronouslyAndA401CannotBecomeAnEmptyKeyRetry() {
        val f = Fixture(fresh = false)
        f.controller.initialize()
        f.controller.actions.unlock("synthetic".toCharArray())
        f.worker.runNext()
        assertEquals(LocalLockPhase.UNLOCKING, f.state.phase)
        assertEquals(1, f.starts.size)
        val (epoch, protected) = f.starts.single()
        assertTrue(protected)
        var borrowed: ByteArray? = null
        assertTrue(f.controller.borrowStartupKey(epoch) { bytes ->
            assertContentEquals(ByteArray(32) { 42 }, bytes)
            borrowed = bytes
        })
        assertTrue(borrowed!!.all { it == 0.toByte() }, "The synchronous borrowed array is cleared on return.")
        assertFalse(f.controller.borrowStartupKey(epoch) { error("second submission") })
        f.controller.nativeStartupResult(epoch + 1, success = false, mismatch401 = true)
        assertEquals(LocalLockPhase.UNLOCKING, f.state.phase)
        f.controller.nativeStartupResult(epoch, success = false, mismatch401 = true)
        assertEquals(LocalLockPhase.RECOVERY, f.state.phase)
        assertEquals(LocalLockIssue.DATABASE_MISMATCH, f.state.issue)
        assertTrue(f.controller.currentWitness().blocked)
        assertFalse(f.controller.borrowStartupKey(epoch) { error("no fallback") })
        assertEquals(listOf(epoch to true), f.starts)
    }

    @Test
    fun creationCommitsAndReadsBackBeforeProtectedNativeStarts() {
        val f = Fixture(fresh = true)
        f.controller.initialize()
        val pass = "  中文 🔒  ".toCharArray()
        f.controller.actions.create(pass, pass.copyOf())
        assertTrue(f.starts.isEmpty())
        assertEquals(0, f.store.commits)
        f.worker.runNext()
        assertTrue(pass.all { it == '\u0000' })
        assertEquals(1, f.store.commits)
        assertEquals(1, f.store.finalizations)
        assertIs<LocalRecordRead.Present>(f.store.read())
        assertEquals(LocalLockPhase.UNLOCKING, f.state.phase)
        assertTrue(f.starts.single().second)

        val rejected = Fixture(fresh = true)
        rejected.store.commitResult = LocalCommitResult.INDETERMINATE
        rejected.controller.initialize()
        rejected.controller.actions.create("synthetic".toCharArray(), "synthetic".toCharArray())
        rejected.worker.runNext()
        assertEquals(LocalLockPhase.RECOVERY, rejected.state.phase)
        assertTrue(rejected.starts.isEmpty())
        assertEquals(LocalCryptoResult.KeyUnavailable, rejected.crypto.createdKey!!.borrow { LocalCryptoResult.Success(Unit) })
    }

    @Test
    fun skippingFreshSetupPersistsOneNonemptyKeyBeforeStartupAndReusesItAfterRestartAndCreate() {
        val f = Fixture(fresh = true)
        f.controller.initialize()
        f.controller.actions.skipSetup()
        assertTrue(f.starts.isEmpty(), "A new root cannot start with the legacy empty key.")
        assertEquals(0, f.store.commits)
        assertEquals(1, f.worker.pending.size)
        f.worker.runNext()
        assertEquals(1, f.store.commits)
        assertEquals(1, f.store.finalizations)
        val stored = assertIs<LocalRecordRead.Present>(f.store.read())
        assertEquals(LocalStoredKind.OPEN, stored.envelope.kind)
        val storedKey = stored.takeOpenKey()!!
        stored.close()
        assertIs<LocalCryptoResult.Success<Unit>>(storedKey.borrow { bytes ->
            assertContentEquals(ByteArray(32) { 42 }, bytes)
            assertTrue(bytes.any { it != 0.toByte() })
            LocalCryptoResult.Success(Unit)
        })
        storedKey.close()
        val (firstEpoch, needsKey) = f.starts.single()
        assertTrue(needsKey)
        assertTrue(f.controller.borrowStartupKey(firstEpoch) { assertContentEquals(ByteArray(32) { 42 }, it) })
        f.controller.nativeStartupResult(firstEpoch, success = true, mismatch401 = false)
        assertEquals(LocalLockPhase.UNPROTECTED, f.state.phase)
        assertTrue(f.state.canCreate)
        var stopped = false
        f.controller.stop { stopped = true } // Synthetic owner models confirmed native Closed.
        assertTrue(stopped)

        val restartedStarts = mutableListOf<Pair<Long, Boolean>>()
        val restarted = f.restarted(restartedStarts)
        restarted.initialize()
        val (restartEpoch, restartNeedsKey) = restartedStarts.single()
        assertTrue(restartNeedsKey)
        assertTrue(restarted.borrowStartupKey(restartEpoch) { assertContentEquals(ByteArray(32) { 42 }, it) })
        restarted.nativeStartupResult(restartEpoch, success = true, mismatch401 = false)
        assertEquals(LocalLockPhase.UNPROTECTED, restarted.state.value.phase)
        restarted.actions.create("new password".toCharArray(), "new password".toCharArray())
        f.worker.runNext()
        assertEquals(LocalLockPhase.UNLOCKED, restarted.state.value.phase)
        assertEquals(1, f.crypto.rewraps, "Enabling protection must wrap the existing D rather than replace it.")
        assertEquals(0, f.crypto.creates)
        assertEquals(1, restartedStarts.size, "Rewrapping must not start or rekey the live database again.")
    }

    @Test
    fun skippingFreshSetupCannotStartOnEntropyStageFinalizationReadbackOrQueuedCancellationFailure() {
        for (mode in listOf("entropy", "stage", "finalize", "readback", "cancel")) {
            val f = Fixture(fresh = true)
            when (mode) {
                "entropy" -> f.entropyFails = true
                "stage" -> f.store.commitResult = LocalCommitResult.INDETERMINATE
                "finalize" -> f.store.finalizeResult = LocalCommitResult.INDETERMINATE
                "readback" -> f.store.leaveOldValueOnSuccess = true
            }
            f.controller.initialize()
            f.controller.actions.skipSetup()
            assertTrue(f.starts.isEmpty())
            if (mode == "cancel") f.controller.actions.cancel()
            f.worker.runNext()
            assertTrue(f.starts.isEmpty(), "The $mode path cannot start native initialization.")
            assertTrue(f.controller.currentWitness().blocked)
            if (mode == "cancel" || mode == "entropy") {
                assertEquals(0, f.store.commits)
                assertEquals(0, f.store.finalizations)
                if (mode == "entropy") {
                    assertEquals(LocalLockPhase.NEW_ROOT_SETUP, f.state.phase)
                    assertEquals(LocalLockIssue.CRYPTO_UNAVAILABLE, f.state.issue)
                    f.entropyFails = false
                    f.controller.actions.skipSetup()
                    f.worker.runNext()
                    assertTrue(f.starts.single().second, "A later explicit retry may use a fresh nonempty key.")
                }
            } else {
                assertEquals(LocalLockPhase.RECOVERY, f.state.phase)
                if (mode == "stage" || mode == "readback") assertEquals(0, f.store.finalizations)
            }
        }
    }

    @Test
    fun cancellingFreshSkipWhileItsStageIsBlockedPreventsFinalizationAndStartup() {
        val f = Fixture(fresh = true)
        f.controller.initialize()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        f.store.commitBarrier = entered to release
        f.controller.actions.skipSetup()
        val worker = Thread({
            try { f.worker.runNext() } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }, "synthetic-new-root-stage").apply { isDaemon = true; start() }
        var cancellation: Thread? = null
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            cancellation = Thread({
                try { f.controller.actions.cancel() } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { canceled.countDown() }
            }, "synthetic-new-root-cancel").apply { isDaemon = true; start() }
            assertTrue(canceled.await(2, TimeUnit.SECONDS), "Cancel must not wait for the staged write.")
        } finally {
            release.countDown()
            worker.join(2_000)
            cancellation?.join(2_000)
        }
        assertEquals(null, failure.get())
        assertEquals(0, f.store.finalizations)
        assertTrue(f.starts.isEmpty())
        assertTrue(f.controller.currentWitness().blocked)
        assertEquals(LocalLockPhase.RECOVERY, f.state.phase)
    }

    @Test
    fun lyingCommitSuccessWithoutMatchingReadbackCannotStartProtectedNative() {
        val f = Fixture(fresh = true)
        f.store.leaveOldValueOnSuccess = true
        f.controller.initialize()
        f.controller.actions.create("synthetic".toCharArray(), "synthetic".toCharArray())
        f.worker.runNext()
        assertEquals(1, f.store.commits)
        assertEquals(LocalLockPhase.RECOVERY, f.state.phase)
        assertTrue(f.starts.isEmpty())
        assertEquals(LocalCryptoResult.KeyUnavailable, f.crypto.createdKey!!.borrow { LocalCryptoResult.Success(Unit) })
    }

    @Test
    fun uncertainFinalizationAfterReportedCommitCannotStartOrPublishSuccess() {
        val f = Fixture(fresh = true)
        f.store.finalizeResult = LocalCommitResult.INDETERMINATE
        f.controller.initialize()
        f.controller.actions.create("synthetic".toCharArray(), "synthetic".toCharArray())
        f.worker.runNext()
        assertEquals(1, f.store.commits)
        assertEquals(1, f.store.finalizations)
        assertEquals(LocalLockPhase.RECOVERY, f.state.phase)
        assertTrue(f.controller.currentWitness().blocked)
        assertTrue(f.starts.isEmpty())
        assertEquals(LocalCryptoResult.KeyUnavailable, f.crypto.createdKey!!.borrow { LocalCryptoResult.Success(Unit) })
    }

    @Test
    fun existingUnprotectedRootRefusesEnablementWithoutMigrationOrRecordWrites() {
        val f = Fixture(fresh = false)
        f.store.absent()
        f.controller.initialize()
        assertEquals(LocalLockPhase.UNPROTECTED, f.state.phase)
        assertEquals(LocalLockIssue.MIGRATION_UNAVAILABLE, f.state.issue)
        assertFalse(f.state.canCreate)
        val existingStartup = f.starts.toList()
        assertEquals(1, existingStartup.size)
        assertFalse(existingStartup.single().second)
        val password = "synthetic".toCharArray()
        f.controller.actions.create(password, password.copyOf())
        assertTrue(password.all { it == '\u0000' })
        assertEquals(0, f.store.commits)
        assertTrue(f.worker.pending.isEmpty())
        assertEquals(existingStartup, f.starts, "Refused migration must not start a new native/rekey path.")
    }

    @Test
    fun wrongPasswordThrottlingAndRepeatedRejectionsHaveObservableRevisions() {
        val f = Fixture(fresh = false)
        f.crypto.rejectPasswords = true
        f.controller.initialize()
        repeat(4) {
            val pass = "wrong".toCharArray()
            val before = f.state.operationRevision
            f.controller.actions.unlock(pass)
            assertTrue(f.state.operationRevision > before)
            f.worker.runNext()
            assertTrue(pass.all { it == '\u0000' })
            assertEquals(LocalLockIssue.WRONG_PASSWORD, f.state.issue)
        }
        assertEquals(5_000L, f.state.retryAfterMillis)
        val revision = f.state.operationRevision
        val blocked = "wrong".toCharArray()
        f.controller.actions.unlock(blocked)
        assertEquals(LocalLockIssue.WAITING, f.state.issue)
        assertTrue(f.state.operationRevision > revision)
        assertTrue(blocked.all { it == '\u0000' })
        assertTrue(f.worker.pending.isEmpty())
        f.now += 5_000
        f.fireTimer()
        assertEquals(0L, f.state.retryAfterMillis)
        f.controller.actions.unlock("wrong".toCharArray())
        f.worker.runNext()
        assertEquals(10_000L, f.state.retryAfterMillis)
        var previousWait = 10_000L
        for (expected in listOf(15_000L, 20_000L, 25_000L, 30_000L, 30_000L)) {
            f.now += previousWait
            f.fireTimer()
            f.controller.actions.unlock("wrong".toCharArray())
            f.worker.runNext()
            assertEquals(expected, f.state.retryAfterMillis)
            previousWait = expected
        }
        f.now += previousWait
        f.fireTimer()
        f.crypto.rejectPasswords = false
        f.controller.actions.unlock("correct".toCharArray())
        f.worker.runNext()
        val epoch = f.starts.single().first
        assertTrue(f.controller.borrowStartupKey(epoch) {})
        f.controller.nativeStartupResult(epoch, success = true, mismatch401 = false)
        f.controller.actions.lockNow()
        f.crypto.rejectPasswords = true
        f.controller.actions.unlock("wrong".toCharArray())
        f.worker.runNext()
        assertEquals(0L, f.state.retryAfterMillis, "A successful unlock resets the in-process failure count.")
    }

    @Test
    fun managementProofExpiresEvenIfItsTimerHasNotRunAndCannotBeRevivedByActivity() {
        val f = Fixture(fresh = false)
        f.unlockCold()
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.worker.runNext()
        assertTrue(f.state.managementAuthorized)
        assertTrue(f.state.canChange)
        f.now += 60_001 // Deliberately do not run the scheduled callback.
        f.controller.actions.managementActivity()
        val replacement = "different".toCharArray()
        f.controller.actions.change(replacement, replacement.copyOf())
        assertTrue(replacement.all { it == '\u0000' })
        assertFalse(f.state.managementAuthorized)
        assertFalse(f.state.canChange)
        assertTrue(f.worker.pending.isEmpty(), "An expired proof must be checked at use, not just by its timer.")
        assertEquals(0, f.crypto.rewraps)
    }

    @Test
    fun changingToTheSamePasswordConsumesTheProofWithoutWritingARecord() {
        val f = Fixture(fresh = false)
        f.unlockCold()
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.worker.runNext()
        f.controller.actions.change("synthetic".toCharArray(), "synthetic".toCharArray())
        f.worker.runNext()
        assertEquals(LocalLockIssue.PASSWORD_UNCHANGED, f.state.issue)
        assertFalse(f.state.managementAuthorized)
        assertEquals(0, f.store.commits)
        val another = "another".toCharArray()
        f.controller.actions.change(another, another.copyOf())
        assertTrue(another.all { it == '\u0000' })
        assertTrue(f.worker.pending.isEmpty(), "The sensitive operation requires a new proof after any attempt.")
    }

    @Test
    fun removalRequiresFreshProofAndOpenWrapKeepsTheSameKeyForReenabling() {
        val f = Fixture(fresh = false)
        f.unlockCold()
        val startsBefore = f.starts.toList()
        f.controller.actions.remove()
        assertTrue(f.worker.pending.isEmpty(), "Removal cannot proceed without a fresh proof.")
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.worker.runNext()
        assertTrue(f.state.canRemove)
        f.controller.actions.remove()
        f.worker.runNext()
        assertEquals(LocalLockPhase.UNPROTECTED, f.state.phase)
        assertFalse(f.state.enabled)
        assertTrue(f.state.canCreate)
        val opened = assertIs<LocalRecordRead.Present>(f.store.read())
        assertEquals(LocalStoredKind.OPEN, opened.envelope.kind)
        val key = opened.takeOpenKey()!!
        opened.close()
        assertIs<LocalCryptoResult.Success<Unit>>(key.borrow { bytes ->
            assertContentEquals(ByteArray(32) { 42 }, bytes)
            LocalCryptoResult.Success(Unit)
        })
        key.close()

        f.controller.actions.create("replacement".toCharArray(), "replacement".toCharArray())
        f.worker.runNext()
        assertEquals(LocalLockPhase.UNLOCKED, f.state.phase)
        assertTrue(f.state.enabled)
        assertEquals(startsBefore, f.starts, "Rewrapping a live session does not initialize a second native database.")
        assertEquals(1, f.crypto.rewraps)
    }

    @Test
    fun restartAfterPasswordRemovalUsesTheSameNonemptyDatabaseKey() {
        val f = Fixture(fresh = false)
        f.unlockCold()
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.worker.runNext()
        f.controller.actions.remove()
        f.worker.runNext()
        assertEquals(LocalLockPhase.UNPROTECTED, f.state.phase)
        var stopped = false
        f.controller.stop { stopped = true } // Synthetic owner calls this only after confirmed Closed.
        assertTrue(stopped)

        val newStarts = mutableListOf<Pair<Long, Boolean>>()
        val restarted = f.restarted(newStarts)
        restarted.initialize()
        assertEquals(LocalLockPhase.UNLOCKING, restarted.state.value.phase)
        val (epoch, needsKey) = newStarts.single()
        assertTrue(needsKey)
        assertTrue(restarted.borrowStartupKey(epoch) { bytes ->
            assertContentEquals(ByteArray(32) { 42 }, bytes)
            assertTrue(bytes.any { it != 0.toByte() })
        })
        restarted.nativeStartupResult(epoch, success = true, mismatch401 = false)
        assertEquals(LocalLockPhase.UNPROTECTED, restarted.state.value.phase)
        assertFalse(restarted.state.value.enabled)
    }

    @Test
    fun activityExtendsCurrentManagementProofButOnlyItsLatestDeadlineCanExpireIt() {
        val f = Fixture(fresh = false)
        f.unlockCold()
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.worker.runNext()
        f.now += 59_000
        f.controller.actions.managementActivity()
        f.now += 1_000
        f.fireTimer() // The first scheduled callback checks the extended deadline.
        assertTrue(f.state.managementAuthorized)
        assertEquals(1, f.timers.size)
        f.now += 59_000
        f.fireTimer()
        assertFalse(f.state.managementAuthorized)
        assertFalse(f.state.canChange)
    }

    @Test
    fun autoLockOptionsRequireFreshManagementAndOnlyPublishConfirmedSaves() {
        val f = Fixture(fresh = false)
        f.unlockCold()
        assertEquals(3_600L, f.state.autoLockSeconds)
        f.controller.actions.setAutoLockSeconds(60)
        assertEquals(LocalLockIssue.STALE, f.state.issue)
        assertTrue(f.preferences.writes.isEmpty())
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.worker.runNext()
        assertTrue(f.state.canAutoLock)
        f.controller.actions.setAutoLockSeconds(300)
        assertEquals(300L, f.state.autoLockSeconds)
        assertEquals(listOf(300L), f.preferences.writes)
        f.controller.actions.setAutoLockSeconds(61)
        assertEquals(LocalLockIssue.INVALID_AUTO_LOCK, f.state.issue)
        assertEquals(listOf(300L), f.preferences.writes)
        f.preferences.writeSucceeds = false
        f.controller.actions.setAutoLockSeconds(3_600)
        assertEquals(LocalLockIssue.IO_FAILURE, f.state.issue)
        assertEquals(300L, f.state.autoLockSeconds, "An uncertain write cannot be presented as saved.")
    }

    @Test
    fun lockingOrStoppingDuringVerificationDoesNotReleaseTheWorkerOrReviveAProof() {
        val f = Fixture(fresh = false)
        f.unlockCold()
        val opensBefore = f.crypto.opens
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.controller.actions.lockNow()
        assertEquals(LocalLockPhase.HOT_LOCKED, f.state.phase)
        assertTrue(f.state.busy)
        f.worker.runNext()
        assertEquals(LocalLockPhase.HOT_LOCKED, f.state.phase)
        assertFalse(f.state.managementAuthorized)
        assertEquals(opensBefore, f.crypto.opens, "Queued verification was canceled before KDF execution.")
        assertEquals(LocalCryptoResult.KeyUnavailable, f.crypto.lastKey!!.borrow { LocalCryptoResult.Success(Unit) })

        f.controller.actions.unlock("synthetic".toCharArray())
        var stopped = false
        f.controller.stop { stopped = true }
        assertFalse(stopped, "The root owner cannot release while its KDF is still physically running.")
        f.worker.runNext()
        assertTrue(stopped)
        assertEquals(opensBefore, f.crypto.opens, "Stopped queued unlock must not start a KDF.")
        assertEquals(LocalCryptoResult.KeyUnavailable, f.crypto.lastKey!!.borrow { LocalCryptoResult.Success(Unit) })
        assertTrue(f.controller.currentWitness().blocked)
    }

    @Test
    fun manualLockRevokesWitnessBeforeAnInProgressCommitIsReleased() {
        val f = Fixture(fresh = false)
        f.unlockCold()
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.worker.runNext()
        val enteredCommit = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val witnessPublished = CountDownLatch(1)
        val workerDone = CountDownLatch(1)
        val lockDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        f.store.commitBarrier = enteredCommit to releaseCommit
        f.witnessHook = { if (it.blocked) witnessPublished.countDown() }
        f.controller.actions.remove()
        val worker = Thread({
            try { f.worker.runNext() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { workerDone.countDown() }
        }, "synthetic-local-lock-commit").apply { isDaemon = true; start() }
        var locking: Thread? = null
        try {
            assertTrue(enteredCommit.await(2, TimeUnit.SECONDS), "The fake commit must actually be in progress.")
            locking = Thread({
                try { f.controller.actions.lockNow() } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { lockDone.countDown() }
            }, "synthetic-local-lock-request").apply { isDaemon = true; start() }
            assertTrue(witnessPublished.await(2, TimeUnit.SECONDS), "The synchronous lock witness must precede IO completion.")
            assertTrue(f.controller.currentWitness().blocked)
            assertEquals(1L, workerDone.count)
            assertTrue(lockDone.await(2, TimeUnit.SECONDS), "Lock must return without waiting for the slow write.")
        } finally {
            releaseCommit.countDown()
            worker.join(2_000)
            locking?.join(2_000)
        }
        assertEquals(null, failure.get())
        assertEquals(0L, workerDone.count)
        assertEquals(0L, lockDone.count)
        assertTrue(f.controller.currentWitness().blocked)
        assertTrue(f.state.rootBlocked, "A late commit cannot reopen protected content after lock.")
        assertFalse(f.state.phase == LocalLockPhase.UNLOCKED || f.state.phase == LocalLockPhase.UNPROTECTED)
        assertEquals(0, f.store.finalizations, "A lock published before commit returns must prevent finalization.")
        assertTrue(f.store.previousRetained, "The old synthetic wrap remains available for recovery.")
        assertIs<LocalRecordRead.Failed>(f.store.read())
    }

    @Test
    fun cancellationAndManagementDepartureBeforeStageReturnsNeverFinalizeOrLoseTheBackup() {
        for (leaveManagement in listOf(false, true)) {
            val f = Fixture(fresh = false)
            f.unlockCold()
            f.controller.actions.verifyManagement("synthetic".toCharArray())
            f.worker.runNext()
            val enteredCommit = CountDownLatch(1)
            val releaseCommit = CountDownLatch(1)
            val workerDone = CountDownLatch(1)
            val cancelDone = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            f.store.commitBarrier = enteredCommit to releaseCommit
            f.controller.actions.remove()
            val worker = Thread({
                try { f.worker.runNext() } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { workerDone.countDown() }
            }, "synthetic-staged-wrap").apply { isDaemon = true; start() }
            var cancellation: Thread? = null
            try {
                assertTrue(enteredCommit.await(2, TimeUnit.SECONDS))
                cancellation = Thread({
                    try {
                        if (leaveManagement) f.controller.actions.closeManagement() else f.controller.actions.cancel()
                    } catch (error: Throwable) { failure.compareAndSet(null, error) }
                    finally { cancelDone.countDown() }
                }, "synthetic-operation-cancel").apply { isDaemon = true; start() }
                assertTrue(cancelDone.await(2, TimeUnit.SECONDS), "Cancellation must return without waiting for the slow write.")
                assertEquals(1L, workerDone.count)
            } finally {
                releaseCommit.countDown()
                worker.join(2_000)
                cancellation?.join(2_000)
            }
            assertEquals(null, failure.get())
            assertEquals(0L, workerDone.count)
            assertEquals(0L, cancelDone.count)
            assertEquals(0, f.store.finalizations, "No finalize may begin after the cancellation won admission.")
            assertTrue(f.store.previousRetained)
            assertIs<LocalRecordRead.Failed>(f.store.read())
            assertEquals(LocalLockPhase.RECOVERY, f.state.phase)
            assertTrue(f.controller.currentWitness().blocked)
        }
    }

    @Test
    fun lockingDuringAnAlreadyEnteredFinalizeStillRevokesTheWitnessAndCannotReopenUi() {
        val f = Fixture(fresh = false)
        f.unlockCold()
        f.controller.actions.verifyManagement("synthetic".toCharArray())
        f.worker.runNext()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val witnessPublished = CountDownLatch(1)
        val workerDone = CountDownLatch(1)
        val lockDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        f.store.finalizeBarrier = entered to release
        f.witnessHook = { if (it.blocked) witnessPublished.countDown() }
        f.controller.actions.remove()
        val worker = Thread({
            try { f.worker.runNext() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { workerDone.countDown() }
        }, "synthetic-finalize-in-flight").apply { isDaemon = true; start() }
        var locking: Thread? = null
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS), "The irreversible synthetic finalize is already entered.")
            locking = Thread({
                try { f.controller.actions.lockNow() } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { lockDone.countDown() }
            }, "synthetic-lock-during-finalize").apply { isDaemon = true; start() }
            assertTrue(witnessPublished.await(2, TimeUnit.SECONDS), "A lock cannot be lost merely because finalization started.")
            assertTrue(f.controller.currentWitness().blocked)
            assertEquals(1L, workerDone.count)
            assertTrue(lockDone.await(2, TimeUnit.SECONDS), "Lock must not wait for an entered OS finalize.")
        } finally {
            release.countDown()
            worker.join(2_000)
            locking?.join(2_000)
        }
        assertEquals(null, failure.get())
        assertEquals(0L, workerDone.count)
        assertEquals(0L, lockDone.count)
        assertTrue(f.controller.currentWitness().blocked)
        assertTrue(f.state.rootBlocked, "A completed, already-entered finalize cannot reopen an owner after a lock request.")
    }

    private class Fixture(fresh: Boolean) {
        private val identity = ByteArray(32) { (it + 1).toByte() }
        var now = 1_000L
        var rootRevision = 1L
        var revisionAvailable = true
        var entropyFails = false
        @Volatile var revisionBarrier: Pair<CountDownLatch, CountDownLatch>? = null
        val worker = FakeWorker()
        val store = FakePersistence(identity).apply { if (!fresh) present(byteArrayOf(1, 2, 3), 1) }
        val crypto = FakeCrypto()
        val preferences = FakePreferences()
        val starts = mutableListOf<Pair<Long, Boolean>>()
        var startHook: ((Long, Boolean) -> Unit)? = null
        var witnessHook: ((LocalLockWitness) -> Unit)? = null
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        private val root = LocalProtectionRoot(
            Path.of("synthetic-root-no-files"), { identity.copyOf() }, { true }, { fresh },
            revision = {
                revisionBarrier?.let { (entered, release) ->
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS)) { "Synthetic root revision read timed out" }
                }
                if (revisionAvailable) rootRevision else error("synthetic revision failure")
            },
            durableCommitAvailable = { true },
        )
        val controller = LocalLockCoordinator(
            root = root,
            worker = worker,
            onStartRequested = { epoch, protected -> starts += epoch to protected; startHook?.invoke(epoch, protected) },
            nowMillis = { now },
            onWitnessChanged = { witnessHook?.invoke(it) },
            schedule = { delay, callback -> timers += delay to callback },
            persistence = store,
            crypto = crypto,
            preferences = preferences,
            entropy = CryptoEntropy { bytes -> if (entropyFails) error("synthetic entropy failure") else bytes.fill(42) },
        )
        val state get() = controller.state.value

        fun fireTimer() { timers.removeAt(0).second() }

        fun restarted(started: MutableList<Pair<Long, Boolean>>) = LocalLockCoordinator(
            root = root,
            worker = worker,
            onStartRequested = { epoch, protected -> started += epoch to protected },
            nowMillis = { now },
            schedule = { delay, callback -> timers += delay to callback },
            persistence = store,
            crypto = crypto,
            preferences = preferences,
            entropy = CryptoEntropy { bytes -> if (entropyFails) error("synthetic entropy failure") else bytes.fill(42) },
        )

        fun unlockCold() {
            controller.initialize()
            controller.actions.unlock("synthetic".toCharArray())
            worker.runNext()
            val epoch = starts.single().first
            assertTrue(controller.borrowStartupKey(epoch) { assertEquals(32, it.size) })
            controller.nativeStartupResult(epoch, success = true, mismatch401 = false)
            assertEquals(LocalLockPhase.UNLOCKED, state.phase)
        }
    }

    private class FakeWorker : Executor {
        val pending = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { pending += command }
        fun runNext() { pending.removeAt(0).run() }
    }

    private fun blockAdmissionAndCancel(f: Fixture, submit: () -> Unit, cancel: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val canceled = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        f.revisionBarrier = entered to release
        val submitting = Thread({
            try { submit() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { done.countDown() }
        }, "synthetic-blocked-admission").apply { isDaemon = true; start() }
        var cancelling: Thread? = null
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val request = Thread({
                try { cancel() } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { done.countDown(); canceled.countDown() }
            }, "synthetic-cancel-admission").apply { isDaemon = true; start() }
            cancelling = request
            assertTrue(canceled.await(2, TimeUnit.SECONDS), "Cancel must return while the root revision read is still blocked.")
            assertEquals(1L, done.count, "Only the blocked submission should still be in progress.")
        } finally {
            release.countDown()
            submitting.join(2_000)
            cancelling?.join(2_000)
            f.revisionBarrier = null
        }
        assertEquals(0L, done.count)
        assertEquals(null, failure.get())
    }

    private class FakePersistence(private val identity: ByteArray) : LocalProtectionPersistence {
        private var value: LocalRecordRead = LocalRecordRead.Absent
        private var openRecord: ByteArray? = null
        var commits = 0
        var finalizations = 0
        var commitResult = LocalCommitResult.COMMITTED
        var finalizeResult = LocalCommitResult.COMMITTED
        var leaveOldValueOnSuccess = false
        var commitBarrier: Pair<CountDownLatch, CountDownLatch>? = null
        var finalizeBarrier: Pair<CountDownLatch, CountDownLatch>? = null
        var previousRetained = false

        fun absent() { openRecord?.fill(0); openRecord = null; value = LocalRecordRead.Absent }

        fun present(bytes: ByteArray, generation: Long) {
            openRecord?.fill(0)
            openRecord = null
            value = LocalRecordRead.Present(LocalStoredEnvelope(bytes.copyOf(), generation,
                MessageDigest.getInstance("SHA-256").digest(bytes)))
        }

        override fun read(): LocalRecordRead = if (previousRetained) LocalRecordRead.Failed(LocalLockIssue.IO_FAILURE)
            else openRecord?.let { LocalOpenWrap.inspect(it, identity) } ?: value
        override fun commit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult {
            commits++
            commitBarrier?.let { (entered, release) ->
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Synthetic commit barrier timed out" }
            }
            if (commitResult == LocalCommitResult.COMMITTED && !leaveOldValueOnSuccess) {
                if (expected != null) previousRetained = true
                if (candidate.size == LocalOpenWrap.RECORD_BYTES) {
                    openRecord?.fill(0)
                    openRecord = candidate.copyOf()
                } else present(candidate, (expected?.generation ?: 0) + 1)
            }
            return commitResult
        }
        override fun finalizeCommit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult {
            finalizations++
            finalizeBarrier?.let { (entered, release) ->
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Synthetic finalize barrier timed out" }
            }
            if (finalizeResult == LocalCommitResult.COMMITTED) previousRetained = false
            return finalizeResult
        }
    }

    private class FakeCrypto : LocalLockCryptoOperations {
        var lastKey: LocalDatabaseKey? = null
        var createdKey: LocalDatabaseKey? = null
        var creates = 0
        var opens = 0
        var openBarrier: Pair<CountDownLatch, CountDownLatch>? = null
        var rejectPasswords = false
        var rewraps = 0
        private fun key(): LocalDatabaseKey = LocalDatabaseKey.copyOf(ByteArray(32) { 42 }).also { lastKey = it }
        override fun create(password: CharArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<WrappedDatabaseKey> {
            creates++
            val created = key().also { createdKey = it }
            return LocalCryptoResult.Success(WrappedDatabaseKey(byteArrayOf(7, 8, 9), created))
        }
        override fun open(password: CharArray, record: ByteArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<LocalDatabaseKey> {
            opens++
            openBarrier?.let { (entered, release) ->
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "Synthetic KDF barrier timed out" }
            }
            return if (rejectPasswords) LocalCryptoResult.AuthenticationFailed else LocalCryptoResult.Success(key())
        }
        override fun rewrap(password: CharArray, key: LocalDatabaseKey, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<ByteArray> {
            rewraps++
            return LocalCryptoResult.Success(byteArrayOf(4, 5, 6))
        }
    }

    private class FakePreferences : LocalLockPreferences {
        var value = 3_600L
        var writeSucceeds = true
        val writes = mutableListOf<Long>()
        override fun read(): LocalLockPreferenceRead = LocalLockPreferenceRead.Value(value)
        override fun write(seconds: Long): Boolean {
            writes += seconds
            if (writeSucceeds) value = seconds
            return writeSucceeds
        }
    }
}
