package com.badnng.moe.media

import com.badnng.moe.DownloadPreferencesWrite
import com.badnng.moe.SettingsDownloadPreferences
import com.badnng.moe.writeDownloadPreferences
import com.badnng.moe.tdlib.autoMediaCopyNotice
import com.badnng.moe.chat.AutoMediaCopyNotice
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileAlreadyExistsException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real NIO copies/journals in self-owned temporary trees, with synthetic native-root resolution. */
class AutoMediaSaveTargetBoundaryTest {
    private class Crash : Error()

    @Test fun forbiddenPreparationCreatesNeitherACopyNorAJournalAndKeepsTheSource() = fixture { f ->
        val result = AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request.copy(destination = f.files.resolve("copies")), f.guard(), { true }, { true })
        assertEquals(AutoMediaCopyResult.Failed(AutoMediaCopyIssue.TARGET_UNSAFE), result)
        assertFalse(Files.exists(f.files.resolve("copies")))
        assertFalse(Files.exists(f.journalRoot))
        assertEquals("native bytes", Files.readString(f.source))
    }

    @Test fun preparedRecoveryRefusalPreservesTheExactIntentStageSourceAndJournal() = fixture { f ->
        val crashIo = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
                PortableAutoMediaCopyIo.writeJournalAtomically(path, record)
                if (record.state == AutoMediaCopyState.PREPARED) throw Crash()
            }
        }
        assertFailsWith<Crash> { AutoMediaCopyJournal(crashIo).copy(f.request, f.guard(), { true }, { true }) }
        val path = f.journal()
        val before = Files.readAllBytes(path)
        val record = assertNotNull(PortableAutoMediaCopyIo.loadJournal(path))
        assertEquals(AutoMediaCopyState.PREPARED, record.state)
        f.redirectFilesToOutput = true
        assertEquals(AutoMediaCopyResult.Failed(AutoMediaCopyIssue.TARGET_UNSAFE),
            AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertContentEquals(before, Files.readAllBytes(path))
        assertTrue(Files.exists(record.stage))
        assertFalse(Files.exists(record.target))
        assertEquals("native bytes", Files.readString(f.source))
        f.redirectFilesToOutput = false
        val restored = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(record.target, restored.path)
        val committed = assertNotNull(PortableAutoMediaCopyIo.loadJournal(path))
        assertEquals(record.intent, committed.intent)
        assertEquals(record.stageIdentity, committed.stageIdentity)
        assertEquals(AutoMediaCopyState.COMMITTED, committed.state)
    }

    @Test fun forbiddenFinalJournalCommitLeavesPreparedIdentityAndTheAlreadyPublishedCopy() = fixture { f ->
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String? {
                val identity = PortableAutoMediaCopyIo.commitNoReplace(stage, target, active)
                f.redirectFilesToOutput = true
                return identity
            }
        }
        val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.TARGET_UNSAFE, result.issue)
        assertTrue(result.publicFileMayExist)
        val path = f.journal()
        val prepared = assertNotNull(PortableAutoMediaCopyIo.loadJournal(path))
        assertEquals(AutoMediaCopyState.PREPARED, prepared.state)
        assertTrue(Files.exists(prepared.stage))
        assertEquals("native bytes", Files.readString(prepared.target))
        assertEquals("native bytes", Files.readString(f.source))
        f.redirectFilesToOutput = false
        val recovered = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(prepared.target, recovered.path)
        assertEquals(prepared.intent, PortableAutoMediaCopyIo.loadJournal(path)!!.intent)
        Files.list(f.output).use { assertEquals(1L, it.count()) }
    }

    @Test fun unknownRootsAndGenerationChangeNeverReauthorizePreparedPublicWrites() = fixture { f ->
        val unknown = f.registry.registerUnknown(2)
        assertEquals(AutoMediaCopyResult.Failed(AutoMediaCopyIssue.TARGET_UNKNOWN),
            AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertFalse(Files.exists(f.output))
        unknown.confirmedClosed()
        val guard = f.guard()
        var next: NativeSaveTargetRegistry.Registration? = null
        val stopped = AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, guard, { true }, {
            next = f.registry.registerUnknown(3)
            true
        })
        assertIs<AutoMediaCopyResult.Pending>(stopped)
        assertEquals(AutoMediaCopyIssue.TARGET_UNKNOWN, stopped.issue)
        val record = PortableAutoMediaCopyIo.loadJournal(f.journal())!!
        assertEquals(AutoMediaCopyState.PREPARED, record.state)
        assertFalse(Files.exists(record.target))
        assertTrue(Files.exists(record.stage))
        assertEquals("native bytes", Files.readString(f.source))
        next!!.confirmedClosed()
    }

    @Test fun forbiddenCleanupKeepsTheCommittedJournalAndBothOwnedFilesForLaterRecovery() = fixture { f ->
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
                PortableAutoMediaCopyIo.writeJournalAtomically(path, record)
                if (record.state == AutoMediaCopyState.COMMITTED) f.redirectFilesToOutput = true
            }
        }
        val stopped = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, stopped.issue)
        assertTrue(stopped.publicFileMayExist)
        val path = f.journal()
        val record = assertNotNull(PortableAutoMediaCopyIo.loadJournal(path))
        val before = Files.readAllBytes(path)
        assertEquals(AutoMediaCopyState.COMMITTED, record.state)
        assertTrue(Files.exists(record.stage))
        assertEquals("native bytes", Files.readString(record.target))
        assertEquals("native bytes", Files.readString(f.source))
        assertContentEquals(before, Files.readAllBytes(path))
        f.redirectFilesToOutput = false
        val recovered = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertTrue(recovered.alreadyPresent)
        assertEquals(record.target, recovered.path)
        assertEquals(record.intent, PortableAutoMediaCopyIo.loadJournal(path)!!.intent)
        assertFalse(Files.exists(record.stage))
    }

    @Test fun registrationChangeDuringPublicationKeepsPreparedIdentityAndThePossiblePublicFile() = fixture { f ->
        var newClient: NativeSaveTargetRegistry.Registration? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String? {
                val identity = PortableAutoMediaCopyIo.commitNoReplace(stage, target, active)
                newClient = f.registry.registerUnknown(8)
                return identity
            }
        }
        val stopped = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.TARGET_UNKNOWN, stopped.issue)
        assertTrue(stopped.publicFileMayExist)
        val path = f.journal()
        val prepared = assertNotNull(PortableAutoMediaCopyIo.loadJournal(path))
        val bytes = Files.readAllBytes(path)
        assertEquals(AutoMediaCopyState.PREPARED, prepared.state)
        assertEquals("native bytes", Files.readString(prepared.target))
        assertTrue(Files.exists(prepared.stage))
        assertEquals("native bytes", Files.readString(f.source))
        assertContentEquals(bytes, Files.readAllBytes(path))
        newClient!!.confirmedClosed()
        val recovered = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(prepared.target, recovered.path)
        assertEquals(prepared.intent, PortableAutoMediaCopyIo.loadJournal(path)!!.intent)
        Files.list(f.output).use { assertEquals(1L, it.count()) }
    }

    @Test fun identityReadFailureAfterPublicationAndEpochChangeRemainsPendingWithOriginalIntent() = fixture { f ->
        var newClient: NativeSaveTargetRegistry.Registration? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String? {
                PortableAutoMediaCopyIo.commitNoReplace(stage, target, active)
                newClient = f.registry.registerUnknown(10)
                throw IOException("synthetic identity read failed after publication")
            }
        }
        val stopped = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.COMMIT_UNAVAILABLE, stopped.issue)
        assertTrue(stopped.publicFileMayExist)
        assertEquals(AutoMediaCopyNotice.RECOVERY_AMBIGUOUS, autoMediaCopyNotice(stopped))
        val path = f.journal()
        val prepared = assertNotNull(PortableAutoMediaCopyIo.loadJournal(path))
        assertEquals(AutoMediaCopyState.PREPARED, prepared.state)
        assertEquals("native bytes", Files.readString(prepared.target))
        assertTrue(Files.isSameFile(prepared.stage, prepared.target))
        assertEquals("native bytes", Files.readString(f.source))
        newClient!!.confirmedClosed()
        val recovered = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(prepared.target, recovered.path)
        assertEquals(prepared.intent, PortableAutoMediaCopyIo.loadJournal(path)!!.intent)
        Files.list(f.output).use { assertEquals(1L, it.count()) }
    }

    @Test fun aCollisionExceptionAfterPublicationIsNotMistakenForAnUnpublishedCollision() = fixture { f ->
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String? {
                PortableAutoMediaCopyIo.commitNoReplace(stage, target, active)
                throw FileAlreadyExistsException("synthetic post-publication failure")
            }
        }
        val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.COMMIT_UNAVAILABLE, result.issue)
        assertTrue(result.publicFileMayExist)
        val record = assertNotNull(PortableAutoMediaCopyIo.loadJournal(f.journal()))
        assertEquals(AutoMediaCopyState.PREPARED, record.state)
        assertEquals("native bytes", Files.readString(record.target))
        assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        Files.list(f.output).use { assertEquals(1L, it.count()) }
    }

    @Test fun identityFailureThenUnsafeResolutionDoesNotEraseThePossiblePublicWrite() = fixture { f ->
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String? {
                PortableAutoMediaCopyIo.commitNoReplace(stage, target, active)
                f.redirectFilesToOutput = true
                throw IOException("synthetic failure after publication")
            }
        }
        val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.COMMIT_UNAVAILABLE, result.issue)
        assertTrue(result.publicFileMayExist)
        assertEquals(AutoMediaCopyNotice.RECOVERY_AMBIGUOUS, autoMediaCopyNotice(result))
        val record = assertNotNull(PortableAutoMediaCopyIo.loadJournal(f.journal()))
        assertEquals("native bytes", Files.readString(record.target))
        assertTrue(Files.exists(record.stage))
        f.redirectFilesToOutput = false
        val restored = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(record.target, restored.path)
        assertEquals(record.intent, PortableAutoMediaCopyIo.loadJournal(f.journal())!!.intent)
    }

    @Test fun sourceLeaseCloseFailureAfterPublicationKeepsTheCommittedResultPending() = fixture { f ->
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun prepareOwnedCopy(source: Path, destination: Path, nativeSize: Long, slot: AutoMediaStageSlot?,
                active: () -> Boolean): AutoMediaPreparedCopy {
                val prepared = PortableAutoMediaCopyIo.prepareOwnedCopy(source, destination, nativeSize, slot, active)
                return AutoMediaPreparedCopy(prepared.stage, prepared.identity, prepared.contentHash, prepared.length) {
                    prepared.close()
                    throw IOException("synthetic close failure")
                }
            }
        }
        val stopped = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, stopped.issue)
        assertTrue(stopped.publicFileMayExist)
        val record = assertNotNull(PortableAutoMediaCopyIo.loadJournal(f.journal()))
        assertEquals(AutoMediaCopyState.COMMITTED, record.state)
        assertEquals("native bytes", Files.readString(record.target))
        assertEquals("native bytes", Files.readString(f.source))
        val recovered = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(record.target, recovered.path)
        assertTrue(recovered.alreadyPresent)
    }

    @Test fun registrationChangeDuringPreparedJournalWriteRetainsItsIntentForExplicitRecovery() = fixture { f ->
        var newClient: NativeSaveTargetRegistry.Registration? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
                PortableAutoMediaCopyIo.writeJournalAtomically(path, record)
                if (record.state == AutoMediaCopyState.PREPARED) newClient = f.registry.registerUnknown(9)
            }
        }
        assertEquals(AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS),
            AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        val path = f.journal()
        val prepared = assertNotNull(PortableAutoMediaCopyIo.loadJournal(path))
        assertEquals(AutoMediaCopyState.PREPARED, prepared.state)
        assertFalse(Files.exists(prepared.target))
        assertTrue(Files.exists(prepared.stage))
        newClient!!.confirmedClosed()
        val recovered = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(prepared.target, recovered.path)
        assertEquals(prepared.intent, PortableAutoMediaCopyIo.loadJournal(path)!!.intent)
        assertEquals("native bytes", Files.readString(f.source))
    }

    @Test fun journalDirectoryItselfMustBeOutsideBothKnownRootAliases() = fixture { f ->
        f.redirectDatabaseToAccount = true
        val result = AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true })
        assertEquals(AutoMediaCopyResult.Failed(AutoMediaCopyIssue.TARGET_UNSAFE), result)
        assertFalse(Files.exists(f.journalRoot))
        assertFalse(Files.exists(f.output))
    }

    @Test fun actualAutomaticDestinationResolverRejectsUnsafeAndUnknownOnItsWorker() = fixture { f ->
        val preferences = f.root.resolve("ui-downloads.properties")
        assertEquals(DownloadPreferencesWrite.SAVED,
            writeDownloadPreferences(preferences, SettingsDownloadPreferences(), SettingsDownloadPreferences(false)))
        val selected = AtomicReference(f.files.resolve("redirected-downloads"))
        val store = AutoMediaPreferencesStore(f.account, preferences) { _, _ ->
            DownloadDestinationRoots(selected.get(), f.account.resolve("downloads-temp/user-43"))
        }
        val actual = NativeSaveTargets.registry.registerUnknown(14)
        try {
            actual.accepted(f.database, f.files)
            fun destination(): Pair<AutoMediaDestination?, String> {
                val latch = CountDownLatch(1)
                val value = AtomicReference<AutoMediaDestination?>()
                val thread = AtomicReference<String>()
                store.destination(43) { resolved ->
                    value.set(resolved); thread.set(Thread.currentThread().name); latch.countDown()
                }
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                return value.get() to thread.get()
            }
            val (unsafe, worker) = destination()
            assertTrue(worker.startsWith("auto-media-preferences"))
            assertEquals(false, unsafe?.targetAvailable)
            assertNull(unsafe?.directory)
            assertFalse(Files.exists(selected.get()))
            selected.set(f.root.resolve("safe-downloads"))
            val safe = destination().first
            assertEquals(true, safe?.targetAvailable)
            assertEquals(selected.get(), safe?.directory)
            val unknown = NativeSaveTargets.registry.registerUnknown(15)
            try {
                val unavailable = destination().first
                assertEquals(false, unavailable?.targetAvailable)
                assertNull(unavailable?.directory)
                assertFalse(Files.exists(selected.get()))
            } finally { unknown.confirmedClosed() }
        } finally { actual.confirmedClosed() }
    }

    private class Fixture {
        val root = Files.createTempDirectory("auto-save-target-boundary-")
        val account = Files.createDirectories(root.resolve("account"))
        val database = Files.createDirectories(account.resolve("database"))
        val files = Files.createDirectories(account.resolve("files"))
        val output = root.resolve("output")
        val source = files.resolve("source.bin").also { Files.writeString(it, "native bytes") }
        val journalRoot = account.resolve("ui-settings/user-43/auto-copies-v1")
        var redirectFilesToOutput = false
        var redirectDatabaseToAccount = false
        val registry = NativeSaveTargetRegistry(object : SaveTargetPathAccess by NioSaveTargetPathAccess {
            override fun realPath(path: Path): Path = when {
                path == files && redirectFilesToOutput -> output.toAbsolutePath().normalize()
                path == database && redirectDatabaseToAccount -> account.toRealPath()
                else -> path.toRealPath()
            }
        })
        init { registry.registerUnknown(1).accepted(database, files) }
        val request get() = AutoMediaCopyRequest(AutoMediaCopyAccount(43, account), 7, "synthetic-native-unique",
            source, Files.size(source), output, "document.txt")
        fun guard() = registry.capture()
        fun journal(): Path = Files.walk(journalRoot).use { paths ->
            paths.filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.properties")) }.findFirst().orElseThrow()
        }
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally {
            Files.walk(f.root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(f.root.toAbsolutePath().normalize()))
                Files.deleteIfExists(path)
            } }
        }
    }
}
