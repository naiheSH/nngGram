package com.badnng.moe.media

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.io.IOException
import java.util.Properties
import kotlin.test.*

/** Self-owned synthetic filesystem only. No user-directory traversal or native session. */
class AutoMediaStageOwnershipTest {
    @Test fun refusalAfterPrepareRetainsOneIdentifiedStageAndSafeRetryReclaimsIt() = fixture { f ->
        var unknown: NativeSaveTargetRegistry.Registration? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun prepareOwnedCopy(source: Path, destination: Path, nativeSize: Long, slot: AutoMediaStageSlot?,
                active: () -> Boolean): AutoMediaPreparedCopy {
                val prepared = PortableAutoMediaCopyIo.prepareOwnedCopy(source, destination, nativeSize, slot, active)
                unknown = f.registry.registerUnknown(2)
                return prepared
            }
        }
        assertEquals(AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS),
            AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        val (oldStage, identity) = f.owned()
        assertEquals(stableMediaFileIdentity(oldStage), identity)
        assertTrue(Files.size(oldStage) > 0)
        repeat(3) {
            assertEquals(AutoMediaCopyResult.Failed(AutoMediaCopyIssue.TARGET_UNKNOWN),
                AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
            assertEquals(listOf(oldStage), f.stages())
        }
        unknown!!.confirmedClosed()
        val saved = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertFalse(Files.exists(oldStage))
        assertEquals("source bytes", Files.readString(saved.path))
        assertEquals("source bytes", Files.readString(f.source))
        assertTrue(f.stages().isEmpty())
        assertFalse(Files.exists(f.marker))
    }

    @Test fun suppliedStageJournalReadRefusalStaysBoundedAcrossDestinationChanges() = fixture { f ->
        var unknown: NativeSaveTargetRegistry.Registration? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun loadJournal(path: Path): AutoMediaCopyRecord? {
                unknown = f.registry.registerUnknown(3)
                return null
            }
        }
        val noRemote = f.request.copy(remoteUniqueId = null)
        assertEquals(AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS),
            AutoMediaCopyJournal(io).copy(noRemote, f.guard(), { true }, { true }))
        val (stage, identity) = f.owned()
        assertEquals(stableMediaFileIdentity(stage), identity)
        val other = f.root.resolve("other-output")
        repeat(3) { AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(noRemote.copy(destination = other), f.guard(), { true }, { true }) }
        assertEquals(listOf(stage), f.stages())
        assertFalse(Files.exists(other))
        unknown!!.confirmedClosed()
        assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(noRemote.copy(destination = other), f.guard(), { true }, { true }))
        assertFalse(Files.exists(stage))
        assertTrue(f.stages().isEmpty())
        assertFalse(Files.exists(f.marker))
    }

    @Test fun rejectionBeforeFirstPreparedRecordKeepsOneStageAndItsExactIdentity() = fixture { f ->
        var unknown: NativeSaveTargetRegistry.Registration? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun exists(path: Path): Boolean {
                val exists = PortableAutoMediaCopyIo.exists(path)
                if (path.parent == f.output && !path.fileName.toString().startsWith(".nnggram-auto-"))
                    unknown = f.registry.registerUnknown(4)
                return exists
            }
        }
        assertEquals(AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS),
            AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        val (stage, identity) = f.owned()
        assertEquals(stableMediaFileIdentity(stage), identity)
        assertEquals(listOf(stage), f.stages())
        assertEquals("source bytes", Files.readString(f.source))
        unknown!!.confirmedClosed()
        assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertFalse(Files.exists(stage))
    }

    @Test fun linkedPreparedRecordTakesOwnershipBeforeTheSlotIsReleased() = fixture { f ->
        var unknown: NativeSaveTargetRegistry.Registration? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
                PortableAutoMediaCopyIo.writeJournalAtomically(path, record)
                if (record.state == AutoMediaCopyState.PREPARED) unknown = f.registry.registerUnknown(5)
            }
        }
        assertEquals(AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS),
            AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        val (stage, identity) = f.owned()
        val binding = Properties().apply { Files.newBufferedReader(f.binding).use(::load) }
        val journal = Path.of(binding.getProperty("journal"))
        val before = assertNotNull(PortableAutoMediaCopyIo.loadJournal(journal))
        assertEquals(stage, before.stage)
        assertEquals(identity, before.stageIdentity)
        assertEquals(binding.getProperty("intent"), before.intent)
        assertEquals("source bytes", Files.readString(stage))
        unknown!!.confirmedClosed()
        val saved = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        val after = assertNotNull(PortableAutoMediaCopyIo.loadJournal(journal))
        assertEquals(before.intent, after.intent)
        assertEquals(before.stageIdentity, after.stageIdentity)
        assertEquals(before.target, saved.path)
        assertEquals(AutoMediaCopyState.COMMITTED, after.state)
        assertFalse(Files.exists(stage))
        assertFalse(Files.exists(f.marker))
    }

    @Test fun aReplacedStageIsNeverDeletedOrAdoptedAsTheOwnedFile() = fixture { f ->
        var unknown: NativeSaveTargetRegistry.Registration? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun prepareOwnedCopy(source: Path, destination: Path, nativeSize: Long, slot: AutoMediaStageSlot?,
                active: () -> Boolean): AutoMediaPreparedCopy {
                val prepared = PortableAutoMediaCopyIo.prepareOwnedCopy(source, destination, nativeSize, slot, active)
                unknown = f.registry.registerUnknown(6)
                return prepared
            }
        }
        assertEquals(AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS),
            AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        val (stage, identity) = f.owned()
        val replacement = f.root.resolve("user-replacement").also { Files.writeString(it, "user data") }
        assertNotEquals(identity, stableMediaFileIdentity(replacement))
        Files.delete(stage)
        Files.move(replacement, stage)
        unknown!!.confirmedClosed()
        repeat(2) {
            val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, result.issue)
            assertEquals("user data", Files.readString(stage))
            assertEquals(listOf(stage), f.stages())
        }
        assertEquals("source bytes", Files.readString(f.source))
    }

    @Test fun identityMetadataReplacementCannotAuthorizeStageCleanup() = fixture { f ->
        var unknown: NativeSaveTargetRegistry.Registration? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun prepareOwnedCopy(source: Path, destination: Path, nativeSize: Long, slot: AutoMediaStageSlot?,
                active: () -> Boolean): AutoMediaPreparedCopy {
                val prepared = PortableAutoMediaCopyIo.prepareOwnedCopy(source, destination, nativeSize, slot, active)
                unknown = f.registry.registerUnknown(15)
                return prepared
            }
        }
        assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        val (stage, id) = f.owned()
        val bytes = Files.readAllBytes(f.identityFile)
        val replacement = f.root.resolve("replacement-metadata").also { Files.write(it, bytes) }
        assertNotEquals(stableMediaFileIdentity(f.identityFile), stableMediaFileIdentity(replacement))
        Files.delete(f.identityFile)
        Files.move(replacement, f.identityFile)
        unknown!!.confirmedClosed()
        val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, result.issue)
        assertContentEquals(bytes, Files.readAllBytes(f.identityFile))
        assertEquals(id, stableMediaFileIdentity(stage))
        assertEquals(listOf(stage), f.stages())
        assertTrue(Files.exists(f.marker))
    }

    @Test fun preparedJournalWrittenBeforeAnOrdinaryExceptionStillOwnsItsStage() = fixture { f ->
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
                PortableAutoMediaCopyIo.writeJournalAtomically(path, record)
                if (record.state == AutoMediaCopyState.PREPARED) throw IOException("synthetic failure after journal move")
            }
        }
        assertEquals(AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS),
            AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        val journalRoot = f.marker.parent
        val journal = Files.walk(journalRoot).use { paths ->
            paths.filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.properties")) }.findFirst().orElseThrow()
        }
        val before = assertNotNull(PortableAutoMediaCopyIo.loadJournal(journal))
        assertEquals(AutoMediaCopyState.PREPARED, before.state)
        assertEquals("source bytes", Files.readString(before.stage))
        assertFalse(Files.exists(before.target))
        assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(before.intent, PortableAutoMediaCopyIo.loadJournal(journal)!!.intent)
    }

    @Test fun missingBoundJournalAfterPublicationPreservesBothFilesAndBlocksEveryRetry() = fixture { f ->
        var unknown: NativeSaveTargetRegistry.Registration? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String? {
                val result = PortableAutoMediaCopyIo.commitNoReplace(stage, target, active)
                unknown = f.registry.registerUnknown(12)
                return result
            }
        }
        val first = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertTrue(first.publicFileMayExist)
        val properties = Properties().apply { Files.newBufferedReader(f.binding).use(::load) }
        val journal = Path.of(properties.getProperty("journal"))
        val record = assertNotNull(PortableAutoMediaCopyIo.loadJournal(journal))
        assertEquals("source bytes", Files.readString(record.target))
        assertEquals("source bytes", Files.readString(record.stage))
        unknown!!.confirmedClosed()
        Files.delete(journal) // This file and all contents belong to this synthetic fixture.
        repeat(3) {
            val pending = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, pending.issue)
            assertTrue(pending.publicFileMayExist)
            assertTrue(Files.exists(f.marker))
            assertTrue(Files.exists(f.binding))
            assertEquals("source bytes", Files.readString(record.stage))
            assertEquals("source bytes", Files.readString(record.target))
            Files.list(f.output).use { assertEquals(2L, it.count()) }
        }
    }

    @Test fun failedFirstJournalWriteAfterBindingKeepsTheStageAndReportsPending() = fixture { f ->
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
                if (record.state == AutoMediaCopyState.PREPARED) throw IOException("synthetic failure before journal write")
                PortableAutoMediaCopyIo.writeJournalAtomically(path, record)
            }
        }
        val first = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, first.issue)
        val binding = Properties().apply { Files.newBufferedReader(f.binding).use(::load) }
        val journal = Path.of(binding.getProperty("journal"))
        val stage = Path.of(binding.getProperty("stage"))
        assertFalse(Files.exists(journal))
        repeat(2) {
            val pending = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, pending.issue)
            assertEquals(listOf(stage), f.stages())
            assertEquals("source bytes", Files.readString(stage))
            assertTrue(Files.exists(f.marker))
        }
    }

    @Test fun unknownPublishedTargetIdentityKeepsPreparedStageAndIntent() = fixture { f ->
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String? {
                PortableAutoMediaCopyIo.commitNoReplace(stage, target, active)
                return null
            }
        }
        val first = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.COMMIT_UNAVAILABLE, first.issue)
        assertTrue(first.publicFileMayExist)
        val journalRoot = f.marker.parent
        val journal = Files.walk(journalRoot).use { paths ->
            paths.filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.properties")) }.findFirst().orElseThrow()
        }
        val record = assertNotNull(PortableAutoMediaCopyIo.loadJournal(journal))
        assertEquals(AutoMediaCopyState.PREPARED, record.state)
        assertEquals("source bytes", Files.readString(record.stage))
        assertEquals("source bytes", Files.readString(record.target))
        val recovered = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(record.target, recovered.path)
        assertEquals(record.intent, PortableAutoMediaCopyIo.loadJournal(journal)!!.intent)
    }

    @Test fun semanticJournalCorruptionNeverTransfersTheSlotOrCreatesANewStage() {
        for (mutation in listOf("key", "scope", "both-scope", "target", "hash", "stage-bytes")) fixture { f ->
            var unknown: NativeSaveTargetRegistry.Registration? = null
            val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
                override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
                    PortableAutoMediaCopyIo.writeJournalAtomically(path, record)
                    if (record.state == AutoMediaCopyState.PREPARED) unknown = f.registry.registerUnknown(13)
                }
            }
            assertEquals(AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS),
                AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
            val binding = Properties().apply { Files.newBufferedReader(f.binding).use(::load) }
            val journal = Path.of(binding.getProperty("journal"))
            val old = assertNotNull(PortableAutoMediaCopyIo.loadJournal(journal))
            val beforeStageId = stableMediaFileIdentity(old.stage)
            unknown!!.confirmedClosed()
            if (mutation == "stage-bytes") Files.writeString(old.stage, "changed stage bytes")
            else {
                val p = Properties().apply { Files.newBufferedReader(journal).use(::load) }
                when (mutation) {
                    "key" -> p.setProperty("key", "a".repeat(64))
                    "scope" -> p.setProperty("destination-scope", "b".repeat(64))
                    "both-scope" -> {
                        p.setProperty("destination-scope", "b".repeat(64))
                        binding.setProperty("destination-scope", "b".repeat(64))
                        Files.newBufferedWriter(f.binding).use { binding.store(it, "synthetic corrupt binding") }
                    }
                    "target" -> p.setProperty("target", f.output.resolve("changed.bin").toString())
                    "hash" -> p.setProperty("sha256", "c".repeat(64))
                }
                Files.newBufferedWriter(journal).use { p.store(it, "synthetic corrupt record") }
            }
            repeat(2) {
                val pending = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
                assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, pending.issue, mutation)
                assertTrue(Files.exists(f.marker), mutation)
                assertTrue(Files.exists(f.binding), mutation)
                assertEquals(beforeStageId, stableMediaFileIdentity(old.stage), mutation)
                assertEquals(listOf(old.stage), f.stages(), mutation)
                assertFalse(Files.exists(old.target), mutation)
            }
            assertEquals("source bytes", Files.readString(f.source))
        }
    }

    @Test fun corruptCommittedJournalPreservesThePublishedCopyAndStageBeforeTransfer() = fixture { f ->
        var journal: Path? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
                PortableAutoMediaCopyIo.writeJournalAtomically(path, record)
                if (record.state == AutoMediaCopyState.COMMITTED) {
                    journal = path
                    val properties = Properties().apply { Files.newBufferedReader(path).use(::load) }
                    properties.setProperty("sha256", "f".repeat(64))
                    Files.newBufferedWriter(path).use { properties.store(it, "synthetic corrupt record") }
                }
            }
        }
        val first = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, first.issue)
        assertTrue(first.publicFileMayExist)
        val binding = Properties().apply { Files.newBufferedReader(f.binding).use(::load) }
        val stage = Path.of(binding.getProperty("stage"))
        val target = Path.of(binding.getProperty("target"))
        assertEquals("source bytes", Files.readString(stage))
        assertEquals("source bytes", Files.readString(target))
        repeat(2) {
            val pending = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, pending.issue)
            assertTrue(Files.exists(f.marker))
            assertTrue(Files.exists(checkNotNull(journal)))
            assertEquals(listOf(stage), f.stages())
            assertEquals("source bytes", Files.readString(target))
        }
    }

    @Test fun missingBindingAfterItWasStartedCannotBeTreatedAsAnUnboundStage() = fixture { f ->
        var unknown: NativeSaveTargetRegistry.Registration? = null
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun writeJournalAtomically(path: Path, record: AutoMediaCopyRecord) {
                PortableAutoMediaCopyIo.writeJournalAtomically(path, record)
                if (record.state == AutoMediaCopyState.PREPARED) unknown = f.registry.registerUnknown(14)
            }
        }
        assertEquals(AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS),
            AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        val (stage, id) = f.owned()
        val binding = Properties().apply { Files.newBufferedReader(f.binding).use(::load) }
        val journal = Path.of(binding.getProperty("journal"))
        val original = assertNotNull(PortableAutoMediaCopyIo.loadJournal(journal))
        unknown!!.confirmedClosed()
        Files.delete(f.binding)
        repeat(2) {
            val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, result.issue)
            assertTrue(Files.exists(f.marker))
            assertEquals(id, stableMediaFileIdentity(stage))
            assertEquals(original.intent, PortableAutoMediaCopyIo.loadJournal(journal)!!.intent)
            assertEquals(listOf(stage), f.stages())
        }
    }

    @Test fun replacingTheStageNameBeforeTheFirstWriteCannotWriteTheReplacement() = fixture { f ->
        org.junit.Assume.assumeTrue("Actual Win32 handle integration", com.sun.jna.Platform.isWindows())
        val replacement = f.root.resolve("user-replacement").also { Files.writeString(it, "user file") }
        val displaced = f.root.resolve("original-stage-object")
        var swapped = false
        val files = object : AutoMediaOwnedFiles by portableAutoMediaOwnedFiles {
            override fun createNew(path: Path): AutoMediaOwnedFile {
                if (!path.fileName.toString().startsWith(".nnggram-auto-")) return portableAutoMediaOwnedFiles.createNew(path)
                // Deliberately permits rename to model an adversarial filesystem; the writer
                // still holds the originally created channel and never reopens the pathname.
                val channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)
                val id = assertNotNull(stableMediaFileIdentity(path))
                return object : AutoMediaOwnedFile {
                    override val identity = id
                    override fun write(bytes: ByteArray, length: Int) {
                        if (!swapped) {
                            Files.move(path, displaced)
                            Files.move(replacement, path)
                            swapped = true
                        }
                        val buffer = ByteBuffer.wrap(bytes, 0, length)
                        while (buffer.hasRemaining()) channel.write(buffer)
                    }
                    override fun read(bytes: ByteArray): Int = channel.read(ByteBuffer.wrap(bytes))
                    override fun replace(bytes: ByteArray) = error("Not a metadata handle")
                    override fun force() = channel.force(true)
                    override fun markDelete() = error("Not a delete handle")
                    override fun close() = channel.close()
                }
            }
        }
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo { override val ownedFiles = files }
        val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, result.issue)
        assertTrue(swapped)
        val (stage, _) = f.owned()
        assertEquals("user file", Files.readString(stage))
        assertEquals("source bytes", Files.readString(displaced))
        assertEquals("source bytes", Files.readString(f.source))
        assertTrue(Files.exists(f.marker))
    }

    @Test fun replacementAfterDeleteHandleIdentityCheckIsNotTheObjectDeleted() = fixture { f ->
        val stage = Files.createDirectories(f.output).resolve(".nnggram-auto-123.part")
        Files.writeString(stage, "original stage")
        val replacement = f.root.resolve("replacement").also { Files.writeString(it, "user file") }
        val original = f.root.resolve("held-original")
        var marked = false
        val files = object : AutoMediaOwnedFiles {
            override fun createNew(path: Path): AutoMediaOwnedFile = error("not needed")
            override fun openRead(path: Path): AutoMediaOwnedFile = error("not needed")
            override fun openReadWrite(path: Path): AutoMediaOwnedFile = error("not needed")
            override fun openReadDelete(path: Path): AutoMediaOwnedFile = object : AutoMediaOwnedFile {
                override val identity = "held-object-identity"
                override fun write(bytes: ByteArray, length: Int) = error("not needed")
                override fun read(bytes: ByteArray): Int = error("not needed")
                override fun replace(bytes: ByteArray) = error("not needed")
                override fun force() = error("not needed")
                override fun markDelete() {
                    // The fake file object continues to represent the original after path replacement.
                    Files.move(stage, original)
                    Files.move(replacement, stage, StandardCopyOption.REPLACE_EXISTING)
                    Files.delete(original)
                    marked = true
                }
                override fun close() {}
            }
        }
        deleteExactAutoMediaStage(stage, "held-object-identity", files)
        assertTrue(marked)
        assertEquals("user file", Files.readString(stage))
        assertFalse(Files.exists(original))
        assertEquals("source bytes", Files.readString(f.source))
    }

    @Test fun windowsHandlesDenyReplacementThroughoutWriteAndDeletion() = fixture { f ->
        org.junit.Assume.assumeTrue("Actual Win32 handle integration", com.sun.jna.Platform.isWindows())
        val stage = Files.createDirectories(f.output).resolve(".nnggram-auto-locked.part")
        val replacement = f.root.resolve("replacement").also { Files.writeString(it, "user file") }
        val identity = WindowsAutoMediaOwnedFiles.createNew(stage).use { held ->
            assertFails { Files.move(replacement, stage, StandardCopyOption.REPLACE_EXISTING) }
            held.write("stage bytes".encodeToByteArray(), "stage bytes".length)
            held.force()
            held.identity
        }
        assertEquals("stage bytes", Files.readString(stage))
        WindowsAutoMediaOwnedFiles.openReadDelete(stage).use { held ->
            assertEquals(identity, held.identity)
            assertFails { Files.move(replacement, stage, StandardCopyOption.REPLACE_EXISTING) }
            held.markDelete()
        }
        assertFalse(Files.exists(stage))
        assertEquals("user file", Files.readString(replacement))
        assertEquals("source bytes", Files.readString(f.source))
    }

    @Test fun windowsProtectedStageCanBeLinkedAndDeletingItsNameKeepsThePublicLink() = fixture { f ->
        org.junit.Assume.assumeTrue("Actual Win32 handle integration", com.sun.jna.Platform.isWindows())
        val stage = Files.createDirectories(f.output).resolve(".nnggram-auto-link.part")
        val target = f.output.resolve("public.bin")
        val id = WindowsAutoMediaOwnedFiles.createNew(stage).use { held ->
            val bytes = "stage bytes".encodeToByteArray()
            held.write(bytes, bytes.size)
            held.force()
            held.identity
        }
        WindowsAutoMediaOwnedFiles.openRead(stage).use { held ->
            assertEquals(id, held.identity)
            Files.createLink(target, stage)
        }
        deleteExactAutoMediaStage(stage, id, WindowsAutoMediaOwnedFiles)
        assertFalse(Files.exists(stage))
        assertEquals("stage bytes", Files.readString(target))
        assertEquals("source bytes", Files.readString(f.source))
    }

    @Test fun preparedPublishedTargetRemainsHeldThroughAuthorizationAndCommit() = fixture { f ->
        org.junit.Assume.assumeTrue("Actual Win32 handle integration", com.sun.jna.Platform.isWindows())
        val (journal, record) = publishedPrepared(f)
        val replacement = f.root.resolve("replacement-target").also { Files.writeString(it, "user replacement") }
        var refused = false
        val saved = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }) {
            refused = runCatching { Files.move(replacement, record.target, StandardCopyOption.REPLACE_EXISTING) }.isFailure
            true
        })
        assertTrue(refused, "The held target must deny replacement until COMMITTED is persisted")
        assertEquals(record.target, saved.path)
        assertEquals("source bytes", Files.readString(saved.path))
        assertEquals("user replacement", Files.readString(replacement))
        assertEquals(AutoMediaCopyState.COMMITTED, PortableAutoMediaCopyIo.loadJournal(journal)!!.state)
        assertFalse(Files.exists(record.stage), "The held target is closed before stage deletion")
    }

    @Test fun forcedTargetReplacementDuringAuthorizationCannotBeCommitted() = fixture { f ->
        org.junit.Assume.assumeTrue("Actual Win32 handle integration", com.sun.jna.Platform.isWindows())
        val (journal, record) = publishedPrepared(f)
        val replacement = f.root.resolve("replacement-target").also { Files.writeString(it, "user replacement") }
        val displaced = f.root.resolve("displaced-public-link")
        val files = object : AutoMediaOwnedFiles by portableAutoMediaOwnedFiles {
            override fun openRead(path: Path): AutoMediaOwnedFile {
                if (path != record.target) return portableAutoMediaOwnedFiles.openRead(path)
                // Deliberately relax sharing to model a nonconforming adapter; the pathname
                // recheck still must not adopt the replacement's different file identity.
                val channel = FileChannel.open(path, StandardOpenOption.READ)
                val id = assertNotNull(stableMediaFileIdentity(path))
                return object : AutoMediaOwnedFile {
                    override val identity = id
                    override fun write(bytes: ByteArray, length: Int) = error("read only")
                    override fun read(bytes: ByteArray): Int = channel.read(ByteBuffer.wrap(bytes))
                    override fun replace(bytes: ByteArray) = error("read only")
                    override fun force() = error("read only")
                    override fun markDelete() = error("read only")
                    override fun close() = channel.close()
                }
            }
        }
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo { override val ownedFiles = files }
        val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }) {
            Files.move(record.target, displaced)
            Files.move(replacement, record.target)
            true
        })
        assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, result.issue)
        assertTrue(result.publicFileMayExist)
        assertEquals(AutoMediaCopyState.PREPARED, PortableAutoMediaCopyIo.loadJournal(journal)!!.state)
        assertEquals("source bytes", Files.readString(record.stage))
        assertEquals("source bytes", Files.readString(displaced))
        assertEquals("user replacement", Files.readString(record.target))
    }

    @Test fun publishedPreparedAuthorizationRefusalPreservesStageAndIntent() = fixture { f ->
        val (journal, record) = publishedPrepared(f)
        val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { false }))
        assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, result.issue)
        assertTrue(result.publicFileMayExist)
        assertEquals(record, PortableAutoMediaCopyIo.loadJournal(journal))
        assertEquals("source bytes", Files.readString(record.stage))
        assertEquals("source bytes", Files.readString(record.target))
        assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertFalse(Files.exists(record.stage))
    }

    @Test fun publishedPreparedCommitWriteFailureRetainsTheStageBeforeOrAfterMove() {
        for (afterWrite in listOf(false, true)) fixture { f ->
            val (journal, record) = publishedPrepared(f)
            val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
                override fun writeJournalAtomically(path: Path, value: AutoMediaCopyRecord) {
                    if (value.state == AutoMediaCopyState.COMMITTED) {
                        if (afterWrite) PortableAutoMediaCopyIo.writeJournalAtomically(path, value)
                        throw IOException("synthetic commit write failure")
                    }
                    PortableAutoMediaCopyIo.writeJournalAtomically(path, value)
                }
            }
            val pending = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.PUBLIC_WRITTEN_RECORD_PENDING, pending.issue)
            assertTrue(pending.publicFileMayExist)
            val persisted = assertNotNull(PortableAutoMediaCopyIo.loadJournal(journal))
            assertEquals(if (afterWrite) AutoMediaCopyState.COMMITTED else AutoMediaCopyState.PREPARED, persisted.state)
            assertEquals(record.intent, persisted.intent)
            assertEquals("source bytes", Files.readString(record.stage))
            assertEquals("source bytes", Files.readString(record.target))
            assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
            assertFalse(Files.exists(record.stage))
        }
    }

    @Test fun committedIntentCannotAdoptAnotherPreparedIntentStageAndIdentity() = fixture { f ->
        wrongBoundIntent(f, replaceIdentity = true)
    }

    @Test fun committedIntentCannotDeleteAnotherPreparedIntentStageAtTheWrongPath() = fixture { f ->
        wrongBoundIntent(f, replaceIdentity = false)
    }

    @Test fun transferredPreparedRecordWithTargetIdentityIsNotPublishedOrCleaned() = fixture { f ->
        assertIs<AutoMediaCopyResult.Retired>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { false }))
        val (journal, prepared) = f.journals().single()
        assertEquals(AutoMediaCopyState.PREPARED, prepared.state)
        val corrupted = prepared.copy(targetIdentity = prepared.stageIdentity)
        PortableAutoMediaCopyIo.writeJournalAtomically(journal, corrupted)
        repeat(2) {
            val pending = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, pending.issue)
            assertTrue(pending.publicFileMayExist)
            assertEquals(corrupted, PortableAutoMediaCopyIo.loadJournal(journal))
            assertEquals(prepared.stageIdentity, stableMediaFileIdentity(prepared.stage))
            assertEquals("source bytes", Files.readString(prepared.stage))
            assertFalse(Files.exists(prepared.target))
            assertEquals(listOf(prepared.stage), f.stages())
        }
    }

    @Test fun baselineNamedCommittedJournalRecognizesItsExistingCopy() = fixture { f ->
        val (journal, prepared) = baselineNamedPrepared(f, f.request)
        Files.createLink(prepared.target, prepared.stage)
        val committed = prepared.copy(state = AutoMediaCopyState.COMMITTED, targetIdentity = prepared.stageIdentity)
        PortableAutoMediaCopyIo.writeJournalAtomically(journal, committed)
        val first = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertTrue(first.alreadyPresent)
        assertEquals(prepared.target, first.path)
        assertEquals(committed, PortableAutoMediaCopyIo.loadJournal(journal))
        assertFalse(Files.exists(prepared.stage))
        assertEquals("source bytes", Files.readString(first.path))
        assertTrue(assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true })).alreadyPresent)
        Files.list(f.output).use { assertEquals(1L, it.count()) }
    }

    @Test fun baselineNamedUnpublishedPreparedJournalResumesItsOriginalIntent() = fixture { f ->
        val (journal, prepared) = baselineNamedPrepared(f, f.request)
        assertFalse(Files.exists(prepared.target))
        val saved = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(prepared.target, saved.path)
        val committed = assertNotNull(PortableAutoMediaCopyIo.loadJournal(journal))
        assertEquals(prepared.intent, committed.intent)
        assertEquals(prepared.stageIdentity, committed.targetIdentity)
        assertEquals(AutoMediaCopyState.COMMITTED, committed.state)
        assertFalse(Files.exists(prepared.stage))
        assertEquals("source bytes", Files.readString(saved.path))
        assertTrue(assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true })).alreadyPresent)
        Files.list(f.output).use { assertEquals(1L, it.count()) }
    }

    @Test fun baselineNamedPublishedPreparedJournalCommitsWithoutAnotherCopy() = fixture { f ->
        val (journal, prepared) = baselineNamedPrepared(f, f.request)
        Files.createLink(prepared.target, prepared.stage)
        val saved = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        assertEquals(prepared.target, saved.path)
        val committed = assertNotNull(PortableAutoMediaCopyIo.loadJournal(journal))
        assertEquals(prepared.intent, committed.intent)
        assertEquals(prepared.stageIdentity, committed.targetIdentity)
        assertEquals(AutoMediaCopyState.COMMITTED, committed.state)
        assertFalse(Files.exists(prepared.stage))
        assertEquals("source bytes", Files.readString(saved.path))
        assertTrue(assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true })).alreadyPresent)
        Files.list(f.output).use { assertEquals(1L, it.count()) }
    }

    @Test fun baselineNamedCommittedJournalCannotCleanAnotherIntentsStage() {
        for (replaceIdentity in listOf(false, true)) fixture { f ->
            val (journalA, preparedA) = baselineNamedPrepared(f, f.request)
            Files.createLink(preparedA.target, preparedA.stage)
            val committedA = preparedA.copy(state = AutoMediaCopyState.COMMITTED, targetIdentity = preparedA.stageIdentity)
            PortableAutoMediaCopyIo.writeJournalAtomically(journalA, committedA)
            val requestB = f.request.copy(remoteUniqueId = "second-old-intent", suggestedName = "other.txt")
            val (journalB, preparedB) = baselineNamedPrepared(f, requestB)
            assertNotEquals(preparedA.intent, preparedB.intent)
            assertNotEquals(preparedA.stageIdentity, preparedB.stageIdentity)
            val corruptA = committedA.copy(stage = preparedB.stage,
                stageIdentity = if (replaceIdentity) preparedB.stageIdentity else committedA.stageIdentity)
            PortableAutoMediaCopyIo.writeJournalAtomically(journalA, corruptA)
            repeat(2) {
                val pending = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
                assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, pending.issue)
                assertTrue(pending.publicFileMayExist)
                assertEquals(corruptA, PortableAutoMediaCopyIo.loadJournal(journalA))
                assertEquals(preparedB, PortableAutoMediaCopyIo.loadJournal(journalB))
                assertEquals(preparedB.stageIdentity, stableMediaFileIdentity(preparedB.stage))
                assertEquals("source bytes", Files.readString(preparedB.stage))
                assertEquals("source bytes", Files.readString(preparedA.target))
                assertFalse(Files.exists(preparedB.target))
                assertTrue(Files.exists(preparedA.stage))
            }
        }
    }

    @Test fun baselineNamedJournalStillRejectsWrongScopeKeyPathIdentityOrContent() {
        for (mutation in listOf("scope", "key", "path", "identity", "hash")) fixture { f ->
            val (journal, prepared) = baselineNamedPrepared(f, f.request)
            val bad = when (mutation) {
                "scope" -> prepared.copy(destinationScope = "a".repeat(64))
                "key" -> prepared.copy(key = "b".repeat(64))
                "path" -> prepared.copy(stage = f.root.resolve(prepared.stage.fileName))
                "identity" -> prepared.copy(stageIdentity = "wrong-identity")
                else -> prepared.copy(contentHash = "c".repeat(64))
            }
            PortableAutoMediaCopyIo.writeJournalAtomically(journal, bad)
            val pending = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, pending.issue, mutation)
            assertEquals(bad, PortableAutoMediaCopyIo.loadJournal(journal), mutation)
            assertEquals(prepared.stageIdentity, stableMediaFileIdentity(prepared.stage), mutation)
            assertEquals("source bytes", Files.readString(prepared.stage), mutation)
            assertFalse(Files.exists(prepared.target), mutation)
            assertEquals(listOf(prepared.stage), f.stages(), mutation)
        }
    }

    @Test fun crashGapWithNoRecordedIdentityIsBoundedAndNeverDeletesTheUnknownFile() = fixture { f ->
        val guarded = object : SaveTargetBoundary {
            override fun check(target: Path): SaveTargetIssue? {
                val name = target.fileName?.toString().orEmpty()
                return if (name.startsWith(".nnggram-auto-") && Files.exists(f.marker)) SaveTargetIssue.UNKNOWN_NATIVE_ROOTS
                else f.guard().check(target)
            }
        }
        assertEquals(AutoMediaCopyResult.Pending(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS),
            AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, guarded, { true }, { true }))
        val (stage, identity) = f.owned()
        assertNull(identity)
        Files.createFile(stage) // Simulate process death between CREATE_NEW and identity persistence.
        repeat(3) {
            val result = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, result.issue)
            assertEquals(listOf(stage), f.stages())
            assertEquals(0L, Files.size(stage))
        }
        assertEquals("source bytes", Files.readString(f.source))
    }

    private class Fixture(val root: Path) {
        val account = Files.createDirectories(root.resolve("account"))
        val database = Files.createDirectories(account.resolve("database"))
        val files = Files.createDirectories(account.resolve("files"))
        val output = root.resolve("output")
        val source = files.resolve("source.bin").also { Files.writeString(it, "source bytes") }
        val marker = account.resolve("ui-settings/user-43/auto-copies-v1/stage-owner-v1.properties")
        val identityFile = marker.parent.resolve("stage-owner-v1.identity")
        val binding = marker.parent.resolve("stage-owner-v1.binding")
        val registry = NativeSaveTargetRegistry().also { it.registerUnknown(1).accepted(database, files) }
        val request get() = AutoMediaCopyRequest(AutoMediaCopyAccount(43, account), 7, "unique", source,
            Files.size(source), output, "copy.txt")
        fun guard() = registry.capture()
        fun owned(): Pair<Path, String?> {
            val p = Properties().apply { Files.newBufferedReader(marker).use(::load) }
            val identity = if (Files.exists(identityFile)) Properties().apply { Files.newBufferedReader(identityFile).use(::load) }
                .getProperty("stage-id") else null
            return Path.of(p.getProperty("stage")) to identity
        }
        fun stages(): List<Path> = if (!Files.exists(output)) emptyList() else Files.list(output).use { paths ->
            paths.filter { it.fileName.toString().startsWith(".nnggram-auto-") }.toList()
        }
        fun journals(): List<Pair<Path, AutoMediaCopyRecord>> = if (!Files.exists(marker.parent)) emptyList() else
            Files.walk(marker.parent).use { paths -> paths.filter {
                it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.properties"))
            }.map { it to checkNotNull(PortableAutoMediaCopyIo.loadJournal(it)) }.toList() }
    }

    private fun publishedPrepared(f: Fixture): Pair<Path, AutoMediaCopyRecord> {
        val io = object : AutoMediaCopyIo by PortableAutoMediaCopyIo {
            override fun commitNoReplace(stage: Path, target: Path, active: () -> Boolean): String? {
                PortableAutoMediaCopyIo.commitNoReplace(stage, target, active)
                throw IOException("synthetic failure after public link")
            }
        }
        val pending = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(io).copy(f.request, f.guard(), { true }, { true }))
        assertTrue(pending.publicFileMayExist)
        val entry = f.journals().single()
        assertEquals(AutoMediaCopyState.PREPARED, entry.second.state)
        assertTrue(Files.exists(entry.second.stage))
        assertTrue(Files.exists(entry.second.target))
        assertFalse(Files.exists(f.marker), "The journal has already accepted the slot")
        return entry
    }

    /** Construct the exact previous createTempFile stage shape in this synthetic fixture. */
    private fun baselineNamedPrepared(f: Fixture, request: AutoMediaCopyRequest): Pair<Path, AutoMediaCopyRecord> {
        assertIs<AutoMediaCopyResult.Retired>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(request, f.guard(), { true }, { false }))
        val (journal, generated) = f.journals().single {
            it.second.state == AutoMediaCopyState.PREPARED && it.second.target.fileName.toString() == request.suggestedName
        }
        val stage = Files.createTempFile(f.output, ".nnggram-auto-", ".part")
        assertFalse(validAutoMediaStageName(stage), "The compatibility fixture must use a non-UUID baseline name")
        Files.write(stage, Files.readAllBytes(f.source))
        val identity = assertNotNull(stableMediaFileIdentity(stage))
        Files.delete(generated.stage)
        val originalStyle = generated.copy(stage = stage, stageIdentity = identity)
        PortableAutoMediaCopyIo.writeJournalAtomically(journal, originalStyle)
        return journal to originalStyle
    }

    private fun wrongBoundIntent(f: Fixture, replaceIdentity: Boolean) {
        val first = assertIs<AutoMediaCopyResult.Saved>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
        val secondRequest = f.request.copy(remoteUniqueId = "second-intent", suggestedName = "second.txt")
        assertIs<AutoMediaCopyResult.Retired>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(secondRequest, f.guard(), { true }, { false }))
        val entries = f.journals()
        val (committedPath, committed) = entries.single { it.second.target == first.path }
        val (preparedPath, prepared) = entries.single { it.second.state == AutoMediaCopyState.PREPARED }
        assertNotEquals(committed.intent, prepared.intent)
        assertNotEquals(committed.targetIdentity, prepared.stageIdentity)
        val corrupted = committed.copy(stage = prepared.stage,
            stageIdentity = if (replaceIdentity) prepared.stageIdentity else committed.stageIdentity)
        PortableAutoMediaCopyIo.writeJournalAtomically(committedPath, corrupted)
        repeat(2) {
            val pending = assertIs<AutoMediaCopyResult.Pending>(AutoMediaCopyJournal(PortableAutoMediaCopyIo).copy(f.request, f.guard(), { true }, { true }))
            assertEquals(AutoMediaCopyIssue.RECOVERY_AMBIGUOUS, pending.issue)
            assertTrue(pending.publicFileMayExist)
            assertEquals(corrupted, PortableAutoMediaCopyIo.loadJournal(committedPath))
            assertEquals(prepared, PortableAutoMediaCopyIo.loadJournal(preparedPath))
            assertEquals(prepared.stageIdentity, stableMediaFileIdentity(prepared.stage))
            assertEquals("source bytes", Files.readString(prepared.stage))
            assertEquals("source bytes", Files.readString(first.path))
            assertFalse(Files.exists(prepared.target))
            assertEquals(listOf(prepared.stage), f.stages())
        }
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("auto-stage-owner-")
        val fixture = Fixture(root)
        try { block(fixture) } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
                Files.deleteIfExists(path)
            } }
        }
    }
}
