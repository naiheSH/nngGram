package com.badnng.moe.security

import java.nio.file.Files
import kotlin.test.*

class LocalLockPosixCommitTest {
    @Test fun publishesAndReplacesWhileRetainingThePreviousBytes() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val root = Files.createTempDirectory("posix-commit-中文")
        val pending = root.resolve("local-protection.pending")
        val record = root.resolve("local-protection.wrap")
        val backup = root.resolve("local-protection.previous")
        try {
            Files.writeString(pending, "first")
            assertEquals(LocalCommitResult.COMMITTED, LocalLockPosixCommit.commit(pending, record, backup, false))
            assertEquals("first", Files.readString(record))
            assertFalse(Files.exists(pending))
            assertFalse(Files.exists(backup))
            Files.writeString(pending, "second")
            assertEquals(LocalCommitResult.COMMITTED, LocalLockPosixCommit.commit(pending, record, backup, true))
            assertEquals("second", Files.readString(record))
            assertEquals("first", Files.readString(backup))
            assertFalse(Files.exists(pending))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun conflictingNamesPreserveEveryExistingRecord() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val root = Files.createTempDirectory("posix-conflict-")
        val pending = root.resolve("local-protection.pending")
        val record = root.resolve("local-protection.wrap")
        val backup = root.resolve("local-protection.previous")
        try {
            Files.writeString(pending, "candidate")
            Files.writeString(record, "existing")
            assertEquals(LocalCommitResult.INDETERMINATE, LocalLockPosixCommit.commit(pending, record, backup, false))
            assertEquals("existing", Files.readString(record))
            Files.writeString(backup, "retained")
            assertEquals(LocalCommitResult.INDETERMINATE, LocalLockPosixCommit.commit(pending, record, backup, true))
            assertEquals("retained", Files.readString(backup))
            assertEquals("existing", Files.readString(record))
            assertEquals("candidate", Files.readString(pending))
        } finally { root.toFile().deleteRecursively() }
    }
}
