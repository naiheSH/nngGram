package com.badnng.moe.security

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ

/** Same-directory POSIX publication with an exclusive hard link retaining the old inode.
 * The store owns the root lease and verifies contents before and after this operation.
 * Interrupted link/rename/unlink steps retain pending/previous for explicit recovery.
 */
internal object LocalLockPosixCommit : LocalLockCommitBackend {
    override fun commit(pending: Path, record: Path, backup: Path, replaceExisting: Boolean): LocalCommitResult {
        if (!pending.isAbsolute || pending.normalize() != pending ||
            pending.parent != record.parent || pending.parent != backup.parent ||
            pending.fileName.toString() != "local-protection.pending" ||
            record.fileName.toString() != "local-protection.wrap" ||
            backup.fileName.toString() != "local-protection.previous") return LocalCommitResult.UNAVAILABLE
        return try {
            FileChannel.open(pending.parent, READ).use { directory ->
                // Fail before issuing any name transition if the filesystem cannot sync directories.
                directory.force(true)
                if (replaceExisting) {
                    Files.createLink(backup, record)
                    directory.force(true)
                    Files.move(pending, record, ATOMIC_MOVE, REPLACE_EXISTING)
                } else {
                    // Unlike ATOMIC_MOVE, createLink never replaces a concurrently created name.
                    Files.createLink(record, pending)
                    directory.force(true)
                    Files.delete(pending)
                }
                directory.force(true)
            }
            LocalCommitResult.COMMITTED
        } catch (_: Exception) {
            LocalCommitResult.INDETERMINATE
        }
    }
}
