package com.badnng.moe.media

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

class AutoMediaCopyJournalTest {
    private class Crash : Error()
    private class FakeIo : AutoMediaCopyIo {
        data class File(val identity: String?, val bytes: ByteArray)
        val root: Path = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("synthetic-auto-copy")
        val data = mutableMapOf<Path, File>()
        val records = mutableMapOf<Path, AutoMediaCopyRecord>()
        val operations = mutableListOf<String>()
        var sequence = 0
        var crashAfter: String? = null
        var failWrite: AutoMediaCopyState? = null
        var corrupt = false
        var raceCollision = false
        var stableIdentity = true
        var upgradedDirectoryIdentity = false
        var prepareCalls = 0
        val bytes = "native-completed synthetic bytes".encodeToByteArray()
        fun request(unique: String? = "native-unique", name: String = "report.pdf", account: Long = 123,
            directory: Path = root.resolve("downloads")) = AutoMediaCopyRequest(AutoMediaCopyAccount(account, root.resolve("data")),
            44, unique, root.resolve("cache"), bytes.size.toLong(), directory, name)
        override fun scope(account: AutoMediaCopyAccount, destination: Path, active: () -> Boolean): AutoMediaCopyScope {
            val accountScope = hash("${account.userId}|${account.dataRoot}".encodeToByteArray())
            val destinationScope = hash(destination.toString().encodeToByteArray())
            return if (!upgradedDirectoryIdentity) AutoMediaCopyScope(accountScope, destinationScope, destination, root.resolve("journal/${account.userId}"))
            else AutoMediaCopyScope(hash("stable|$accountScope".encodeToByteArray()), hash("stable|$destinationScope".encodeToByteArray()), destination,
                root.resolve("journal/${account.userId}"), accountScope, destinationScope)
        }
        override fun prepareCopy(source: Path, destination: Path, nativeSize: Long, active: () -> Boolean): AutoMediaPreparedCopy {
            check(active())
            check(nativeSize == 0L || nativeSize == bytes.size.toLong())
            prepareCalls++
            val stage = destination.resolve(".nnggram-auto-${UUID.nameUUIDFromBytes((++sequence).toString().encodeToByteArray())}.part")
            val identity = if (stableIdentity) "identity-$sequence" else null
            data[stage] = File(identity, bytes.copyOf())
            operations += "prepare"
            return AutoMediaPreparedCopy(stage, identity, hash(bytes), bytes.size.toLong())
        }
        override fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String? {
            check(active())
            if (raceCollision) {
                raceCollision = false
                data[target] = File("user", "user file".encodeToByteArray())
            }
            if (data.containsKey(target)) throw AutoMediaCommitCollision()
            check(records.values.any { it.state == AutoMediaCopyState.PREPARED && it.target == target })
            operations += "commit"
            data[target] = data.getValue(stage)
            if (crashAfter == "commit") throw Crash()
            return data.getValue(target).identity
        }
        override fun verifyRecordedCopy(path: Path, identity: String?, length: Long, contentHash: String, active: () -> Boolean): Boolean =
            active() && data[path]?.let { identity != null && it.identity == identity && it.bytes.size.toLong() == length && hash(it.bytes) == contentHash } == true
        override fun exists(path: Path) = data.containsKey(path)
        override fun loadJournal(path: Path): AutoMediaCopyRecord? {
            if (corrupt) error("synthetic corrupt journal")
            return records[path]
        }
        override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
            operations += record.state.name
            if (failWrite == record.state) error("synthetic journal failure")
            records[path] = record
            if (crashAfter == record.state.name) throw Crash()
        }
        override fun cleanupOwnedStage(stage: Path, identity: String?) {
            if (identity != null && data[stage]?.identity == identity) data.remove(stage)
        }
        fun publicFiles() = data.filterKeys { !it.fileName.toString().startsWith(".nnggram-auto-") }
        companion object {
            fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }

    @Test fun prepareJournalPublishJournalOrderAndStableIdentityDeduplicateAcrossControllers() {
        val io = FakeIo()
        val first = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }))
        assertEquals(listOf("prepare", "PREPARED", "commit", "COMMITTED"), io.operations)
        val second = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(
            io.request(name = "changed-name.pdf").copy(fileId = 99), { true }, { true }))
        assertEquals(first.path, second.path)
        assertTrue(second.alreadyPresent)
        assertEquals(1, io.prepareCalls)
        assertEquals(1, io.publicFiles().size)
    }

    @Test fun preparedWriteFailureCreatesNoPublicFileAndLeavesSourceUntouched() {
        val io = FakeIo().apply { failWrite = AutoMediaCopyState.PREPARED }
        val result = assertIs<AutoMediaCopyResult.Failed>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.JOURNAL_UNAVAILABLE, result.issue)
        assertTrue(io.publicFiles().isEmpty())
        assertTrue(io.data.isEmpty())
        assertTrue(io.records.isEmpty())
    }

    @Test fun everyPersistedCrashPointRecoversTheSameIntentAndNeverCreatesAnotherPublicFile() {
        listOf("PREPARED", "commit", "COMMITTED").forEach { point ->
            val io = FakeIo().apply { crashAfter = point }
            assertFailsWith<Crash>("crash after $point") { AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }) }
            val intent = io.records.values.single().intent
            io.crashAfter = null
            assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }))
            assertEquals(intent, io.records.values.single().intent)
            assertEquals(AutoMediaCopyState.COMMITTED, io.records.values.single().state)
            assertEquals(1, io.publicFiles().size, point)
            assertEquals(1, io.prepareCalls, point)
            assertEquals(1, io.data.size, point)
        }
    }

    @Test fun publicWriteThenJournalFailureRetainsPreparedAndReportsPrecisePendingState() {
        val io = FakeIo().apply { failWrite = AutoMediaCopyState.COMMITTED }
        val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.PUBLIC_WRITTEN_RECORD_PENDING, result.issue)
        assertTrue(result.publicFileMayExist)
        assertEquals(1, io.publicFiles().size)
        assertEquals(AutoMediaCopyState.PREPARED, io.records.values.single().state)
        io.failWrite = null
        assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }))
        assertEquals(1, io.publicFiles().size)
    }

    @Test fun userDeletionOrModificationAfterCommittedNeverRebuildsOrOverwrites() {
        listOf(false, true).forEach { modify ->
            val io = FakeIo()
            val saved = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }))
            if (modify) io.data[saved.path] = FakeIo.File("user", "changed".encodeToByteArray()) else io.data.remove(saved.path)
            val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.USER_CHANGED_TARGET, result.issue)
            assertEquals(if (modify) 1 else 0, io.publicFiles().size)
            assertEquals(1, io.prepareCalls)
        }
    }

    @Test fun recoveryCollisionOrMissingIdentityNeverChoosesAnotherName() {
        listOf(false, true).forEach { missingIdentity ->
            val io = FakeIo().apply { stableIdentity = !missingIdentity; crashAfter = "PREPARED" }
            assertFailsWith<Crash> { AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }) }
            io.crashAfter = null
            val record = io.records.values.single()
            if (!missingIdentity) io.data[record.target] = FakeIo.File("user", "same name".encodeToByteArray())
            val result = AutoMediaCopyJournal(io).copy(io.request(), { true }, { true })
            assertIs<AutoMediaCopyResult.Pending>(result)
            assertEquals(record.target, io.records.values.single().target)
            assertEquals(1, io.prepareCalls)
            assertTrue(io.publicFiles().size <= 1)
        }
    }

    @Test fun liveCollisionIsCreateNewAndPersistsItsNewCandidateBeforeRetry() {
        val io = FakeIo().apply { raceCollision = true }
        val result = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }))
        assertEquals("report (2).pdf", result.path.fileName.toString())
        assertEquals("user file", io.data.getValue(io.request().destination.resolve("report.pdf")).bytes.decodeToString())
        assertEquals(2, io.operations.count { it == "PREPARED" })
        assertEquals(2, io.publicFiles().size)
    }

    @Test fun currentActorRefusalAndRetirementNeverPublishAndRecoveryRechecksPermission() {
        val io = FakeIo()
        assertIs<AutoMediaCopyResult.Retired>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { false }))
        assertTrue(io.publicFiles().isEmpty())
        assertIs<AutoMediaCopyResult.Retired>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { false }))
        assertTrue(io.publicFiles().isEmpty())
        assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }))
        assertEquals(1, io.prepareCalls)
        val retired = FakeIo()
        assertIs<AutoMediaCopyResult.Retired>(AutoMediaCopyJournal(retired).copy(retired.request(), { false }, { true }))
        assertEquals(0, retired.prepareCalls)
    }

    @Test fun corruptRecordsFailClosedAndDoNotPretendTheyWereMissing() {
        val io = FakeIo().apply { corrupt = true }
        val pending = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(io.request(), { true }, { true }))
        assertTrue(pending.publicFileMayExist)
        assertEquals(0, io.prepareCalls)
        assertTrue(io.publicFiles().isEmpty())
    }

    @Test fun windowsDirectoryIdentityUpgradeNeverIgnoresTheExactLegacyJournalOrCreatesAnotherCopy() {
        listOf<String?>("native-unique", null).forEach { unique ->
            val io = FakeIo()
            assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(io.request(unique), { true }, { true }))
            val original = io.records.values.single()
            io.upgradedDirectoryIdentity = true
            val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(io.request(unique), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, result.issue)
            assertTrue(result.publicFileMayExist)
            assertEquals(original, io.records.values.single())
            assertEquals(1, io.publicFiles().size)
            assertEquals(1, io.data.size, "Any newly prepared content-key stage must be discarded without publication.")
            assertEquals(if (unique == null) 2 else 1, io.prepareCalls)
        }
    }

    @Test fun fallbackUsesContentLengthSafeNameAndDestinationButNeverTransientFileId() {
        val io = FakeIo()
        assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(io.request(unique = null), { true }, { true }))
        assertTrue(assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(
            io.request(unique = null).copy(fileId = 999), { true }, { true })).alreadyPresent)
        assertEquals(1, io.publicFiles().size)
        assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(io.request(unique = null, name = "second.pdf"), { true }, { true }))
        assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(io.request(account = 456), { true }, { true }))
        assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(io).copy(io.request(directory = io.root.resolve("other")), { true }, { true }))
        assertEquals(4, io.publicFiles().size)
    }

    @Test fun actualNioCopiesHashAndSourceIdentityWithUnknownNativeLengthAndRealJournalRestart() = temporary { root ->
        val account = Files.createDirectories(root.resolve("account"))
        val source = root.resolve("native-completed")
        val bytes = ByteArray(350_017) { (it * 31).toByte() }
        Files.write(source, bytes)
        val request = AutoMediaCopyRequest(AutoMediaCopyAccount(123, account), 1, "unique-synthetic", source, 0,
            root.resolve("downloads"), "合成.pdf")
        val result = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(request, { true }, { true }))
        assertContentEquals(bytes, Files.readAllBytes(result.path))
        assertContentEquals(bytes, Files.readAllBytes(source))
        val record = Files.walk(account).use { paths ->
            val path = paths.filter { it.fileName.toString().endsWith(".properties") }.findFirst().orElseThrow()
            assertNotNull(PortableAutoMediaCopyIo.loadJournal(path))
        }
        assertEquals(bytes.size.toLong(), record.length)
        assertEquals(FakeIo.hash(bytes), record.contentHash)
        assertNotNull(record.targetIdentity, "A committed Windows copy needs FILE_ID_INFO when NIO fileKey is null.")
        assertEquals(record.targetIdentity, stableMediaFileIdentity(result.path))
        assertTrue(assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(request, { true }, { true })).alreadyPresent)
        Files.list(request.destination).use { assertEquals(1L, it.count()) }
        // Equal bytes at the same name are not the original publication. Create the
        // replacement before unlinking the target so its native ID cannot be reused.
        val replacement = root.resolve("same-bytes-different-file")
        Files.copy(result.path, replacement)
        assertNotEquals(record.targetIdentity, stableMediaFileIdentity(replacement))
        Files.delete(result.path)
        Files.move(replacement, result.path)
        assertEquals(AutoMediaCopyIssue.USER_CHANGED_TARGET,
            assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(request, { true }, { true })).issue)
        Files.writeString(result.path, "user changed")
        assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(request, { true }, { true }))
        assertEquals("user changed", Files.readString(result.path))
        Files.list(request.destination).use { assertEquals(1L, it.count()) }
    }

    @Test fun windowsIdentityBoundaryRetainsTheVolumeAndFull128BitsAndClosesEachHandle() {
        val path = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("synthetic-id-only")
        var bytes = ByteArray(24) { it.toByte() }
        var closed = 0
        val opens = mutableListOf<String>()
        val reader = WindowsMediaFileIdentityReader(object : WindowsFileIdentityAccess {
            override fun openAttributes(path: String): Long { opens += path; return 42 }
            override fun fileIdInfo(handle: Long): ByteArray { assertEquals(42L, handle); return bytes.copyOf() }
            override fun close(handle: Long) { assertEquals(42L, handle); closed++ }
        })
        val first = assertNotNull(reader.identity(path))
        assertEquals("win-file-id-v1:" + bytes.joinToString("") { "%02x".format(it) }, first)
        assertEquals(first, reader.identity(path))
        bytes = bytes.copyOf().also { it[23] = (it[23].toInt() xor 0x80).toByte() }
        assertNotEquals(first, reader.identity(path), "High file-id bits cannot be truncated to a 64-bit index.")
        assertEquals(3, closed)
        assertTrue(opens.all { it.startsWith("\\\\?\\") })
    }

    @Test fun windowsIdentityUnavailableAndMalformedInfoFailClosedWithoutLeakingOpenedHandles() {
        val path = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("synthetic-id-only")
        listOf<Long?>(null, 0, -1).forEach { returned ->
            var closed = false
            val reader = WindowsMediaFileIdentityReader(object : WindowsFileIdentityAccess {
                override fun openAttributes(path: String) = returned
                override fun fileIdInfo(handle: Long): ByteArray? = error("No valid handle was opened")
                override fun close(handle: Long) { closed = true }
            })
            assertNull(reader.identity(path))
            assertFalse(closed)
        }
        listOf<ByteArray?>(null, ByteArray(23), ByteArray(25)).forEach { info ->
            var closed = 0
            val reader = WindowsMediaFileIdentityReader(object : WindowsFileIdentityAccess {
                override fun openAttributes(path: String) = 42L
                override fun fileIdInfo(handle: Long) = info
                override fun close(handle: Long) { closed++ }
            })
            assertNull(reader.identity(path))
            assertEquals(1, closed)
        }
        var closed = 0
        val reader = WindowsMediaFileIdentityReader(object : WindowsFileIdentityAccess {
            override fun openAttributes(path: String) = 42L
            override fun fileIdInfo(handle: Long): ByteArray? = error("Synthetic native read failure")
            override fun close(handle: Long) { closed++ }
        })
        assertNull(reader.identity(path))
        assertEquals(1, closed)
    }

    @Test fun actualNioRejectsKnownSizeMismatchAndUnsafeNamesBeforePublicWrite() = temporary { root ->
        val account = Files.createDirectories(root.resolve("account"))
        val source = root.resolve("cache")
        Files.writeString(source, "actual native bytes")
        val request = AutoMediaCopyRequest(AutoMediaCopyAccount(123, account), 1, "synthetic", source, 1,
            root.resolve("downloads"), "document.pdf")
        assertIs<AutoMediaCopyResult.Failed>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(request, { true }, { true }))
        Files.list(request.destination).use { assertEquals(0L, it.count()) }
        val unsafe = request.copy(suggestedName = "document.exe ", destination = root.resolve("never-created"))
        assertIs<AutoMediaCopyResult.Failed>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(unsafe, { true }, { true }))
        assertFalse(Files.exists(unsafe.destination))
    }

    private fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("nnggram-auto-copy-test-")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}
