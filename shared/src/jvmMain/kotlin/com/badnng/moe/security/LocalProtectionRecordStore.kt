package com.badnng.moe.security

import com.badnng.moe.settings.LocalLockIssue
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Engine-owned, root-only exclusive lease; it must not create or open a TDLib database. */
internal class LocalProtectionRoot(
    val path: Path,
    val identity: () -> ByteArray?,
    val isCurrent: () -> Boolean,
    val freshEligible: () -> Boolean,
    val revision: () -> Long = { 0L },
    /** A host may explicitly reject an unsupported filesystem; operations still check each result. */
    val durableCommitAvailable: () -> Boolean = { true },
) {
    override fun toString(): String = "LocalProtectionRoot[redacted]"
}

internal class LocalStoredEnvelope(
    val bytes: ByteArray,
    val generation: Long,
    val revision: ByteArray,
    val kind: LocalStoredKind = LocalStoredKind.PASSWORD,
) {
    override fun toString(): String = "LocalStoredEnvelope[redacted]"
}

internal enum class LocalStoredKind { PASSWORD, OPEN }

internal sealed interface LocalRecordRead {
    data object Absent : LocalRecordRead
    class Present(val envelope: LocalStoredEnvelope, private var openKey: LocalDatabaseKey? = null) : LocalRecordRead, AutoCloseable {
        fun takeOpenKey(): LocalDatabaseKey? = openKey.also { openKey = null }
        override fun close() {
            openKey?.close()
            openKey = null
        }
        override fun toString(): String = "LocalRecordRead.Present[redacted]"
    }
    class Failed(val issue: LocalLockIssue) : LocalRecordRead
}

internal enum class LocalCommitResult { COMMITTED, CONFLICT, INDETERMINATE, UNAVAILABLE }

/** Injectable so synthetic tests never need an account directory. */
internal interface LocalProtectionPersistence {
    fun read(): LocalRecordRead
    fun commit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult
    fun finalizeCommit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult
}

/**
 * On disk writes require a create-new pending file, file force, bounded readback,
 * platform atomic move/replace with backup and final readback. Any unsupported or
 * uncertain step is failure, never permission to use an empty key. Pending/backup
 * are retained for explicit recovery after interruption. Hardware/power-loss and
 * hostile rollback guarantees are outside this normal process-crash contract.
 */
internal class LocalProtectionRecordStore(
    private val root: LocalProtectionRoot,
    private val backend: LocalLockCommitBackend = if (System.getProperty("os.name").startsWith("Windows"))
        LocalLockWindowsCommit else LocalLockPosixCommit,
) : LocalProtectionPersistence {
    private val record = root.path.resolve("local-protection.wrap")
    private val pending = root.path.resolve("local-protection.pending")
    private val backup = root.path.resolve("local-protection.previous")

    override fun read(): LocalRecordRead {
        return try {
            if (!root.isCurrent()) return LocalRecordRead.Failed(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE)
            if (attributesOrNull(pending) != null || attributesOrNull(backup) != null) return LocalRecordRead.Failed(LocalLockIssue.IO_FAILURE)
            val attributes = attributesOrNull(record) ?: return LocalRecordRead.Absent
            if (!attributes.isRegularFile || attributes.isSymbolicLink) return LocalRecordRead.Failed(LocalLockIssue.CORRUPT_RECORD)
            val bytes = readBounded(record) ?: return LocalRecordRead.Failed(LocalLockIssue.CORRUPT_RECORD)
            try {
                if (!root.isCurrent()) return LocalRecordRead.Failed(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE)
                inspect(bytes)
            } finally {
                bytes.fill(0)
            }
        } catch (_: IOException) {
            LocalRecordRead.Failed(LocalLockIssue.IO_FAILURE)
        } catch (_: SecurityException) {
            LocalRecordRead.Failed(LocalLockIssue.IO_FAILURE)
        } catch (_: RuntimeException) {
            LocalRecordRead.Failed(LocalLockIssue.IO_FAILURE)
        }
    }

    override fun commit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult {
        var pendingCreated = false
        var oldBytes: ByteArray? = null
        var candidateReadback: ByteArray? = null
        var committedReadback: ByteArray? = null
        var backupReadback: ByteArray? = null
        return try {
            if (!root.isCurrent() || !root.durableCommitAvailable()) return LocalCommitResult.UNAVAILABLE
            val checked = inspect(candidate)
            if (checked !is LocalRecordRead.Present) return LocalCommitResult.UNAVAILABLE
            val newEnvelope = checked.envelope
            checked.close()
            val old = read()
            when {
                expected == null && (old !== LocalRecordRead.Absent || !root.freshEligible() || newEnvelope.generation != 1L) -> {
                    if (old is LocalRecordRead.Present) old.close()
                    return LocalCommitResult.CONFLICT
                }
                expected != null && (old !is LocalRecordRead.Present ||
                    !MessageDigest.isEqual(old.envelope.revision, expected.revision) ||
                    expected.generation == Long.MAX_VALUE || newEnvelope.generation != expected.generation + 1) -> {
                    if (old is LocalRecordRead.Present) old.close()
                    return LocalCommitResult.CONFLICT
                }
            }
            if (old is LocalRecordRead.Present) old.close()
            if (expected != null) {
                oldBytes = readBounded(record) ?: return LocalCommitResult.UNAVAILABLE
                if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(oldBytes), expected.revision)) {
                    return LocalCommitResult.CONFLICT
                }
            }
            if (!root.isCurrent()) return LocalCommitResult.UNAVAILABLE
            pendingCreated = true
            FileChannel.open(pending, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val input = ByteBuffer.wrap(candidate)
                while (input.hasRemaining()) {
                    if (channel.write(input) <= 0) throw IOException("write unavailable")
                }
                channel.force(true)
            }
            candidateReadback = readBounded(pending) ?: throw IOException("readback unavailable")
            if (!candidateReadback.contentEquals(candidate) || !root.isCurrent()) throw IOException("readback unavailable")
            if (backend.commit(pending, record, backup, expected != null) != LocalCommitResult.COMMITTED) {
                return LocalCommitResult.INDETERMINATE
            }
            committedReadback = readBounded(record) ?: throw IOException("commit uncertain")
            if (!root.isCurrent() || !committedReadback.contentEquals(candidate)) throw IOException("commit uncertain")
            if (attributesOrNull(pending) != null) throw IOException("pending still exists")
            if (expected == null && attributesOrNull(backup) != null) throw IOException("unexpected backup")
            if (expected != null) {
                backupReadback = readBounded(backup) ?: throw IOException("backup unavailable")
                if (!backupReadback.contentEquals(oldBytes)) throw IOException("backup mismatch")
            }
            LocalCommitResult.COMMITTED
        } catch (_: IOException) {
            if (pendingCreated) LocalCommitResult.INDETERMINATE else LocalCommitResult.UNAVAILABLE
        } catch (_: SecurityException) {
            if (pendingCreated) LocalCommitResult.INDETERMINATE else LocalCommitResult.UNAVAILABLE
        } catch (_: RuntimeException) {
            if (pendingCreated) LocalCommitResult.INDETERMINATE else LocalCommitResult.UNAVAILABLE
        } finally {
            oldBytes?.fill(0)
            candidateReadback?.fill(0)
            committedReadback?.fill(0)
            backupReadback?.fill(0)
        }
    }

    /** Caller has verified candidate opens the same D; retain backup until this separate step. */
    override fun finalizeCommit(candidate: ByteArray, expected: LocalStoredEnvelope?): LocalCommitResult {
        var currentBytes: ByteArray? = null
        var priorBytes: ByteArray? = null
        return try {
            if (!root.isCurrent() || attributesOrNull(pending) != null) return LocalCommitResult.INDETERMINATE
            currentBytes = readBounded(record) ?: return LocalCommitResult.INDETERMINATE
            if (!currentBytes.contentEquals(candidate)) return LocalCommitResult.INDETERMINATE
            val checked = inspect(currentBytes)
            if (checked !is LocalRecordRead.Present) return LocalCommitResult.INDETERMINATE
            checked.close()
            if (expected == null) {
                if (attributesOrNull(backup) != null) return LocalCommitResult.INDETERMINATE
            } else {
                priorBytes = readBounded(backup) ?: return LocalCommitResult.INDETERMINATE
                if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(priorBytes), expected.revision)) {
                    return LocalCommitResult.INDETERMINATE
                }
                if (!root.isCurrent()) return LocalCommitResult.INDETERMINATE
                // Once this succeeds, the verified candidate is the committed record.
                Files.delete(backup)
                if (attributesOrNull(backup) != null) return LocalCommitResult.INDETERMINATE
            }
            LocalCommitResult.COMMITTED
        } catch (_: IOException) {
            LocalCommitResult.INDETERMINATE
        } catch (_: SecurityException) {
            LocalCommitResult.INDETERMINATE
        } catch (_: RuntimeException) {
            LocalCommitResult.INDETERMINATE
        } finally {
            currentBytes?.fill(0)
            priorBytes?.fill(0)
        }
    }

    private fun inspect(bytes: ByteArray): LocalRecordRead {
        val identity = root.identity() ?: return LocalRecordRead.Failed(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE)
        if (identity.size != LocalEnvelopeFormat.ROOT_BYTES || !root.isCurrent()) {
            return LocalRecordRead.Failed(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE)
        }
        if (bytes.size == LocalOpenWrap.RECORD_BYTES) return LocalOpenWrap.inspect(bytes, identity)
        if (bytes.size != LocalEnvelopeFormat.RECORD_BYTES) return LocalRecordRead.Failed(LocalLockIssue.CORRUPT_RECORD)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(8).also { buffer.get(it) }
        if (!magic.contentEquals(LocalEnvelopeFormat.magic)) return LocalRecordRead.Failed(LocalLockIssue.CORRUPT_RECORD)
        if (buffer.get().toInt() != 1 || buffer.get().toInt() != 1 || buffer.get().toInt() != 1 || buffer.get().toInt() != 0) {
            return LocalRecordRead.Failed(LocalLockIssue.UNSUPPORTED_RECORD)
        }
        if (buffer.int != LocalEnvelopeFormat.MEMORY_KIB || buffer.int != LocalEnvelopeFormat.ITERATIONS ||
            buffer.int != LocalEnvelopeFormat.LANES) return LocalRecordRead.Failed(LocalLockIssue.UNSUPPORTED_RECORD)
        val generation = buffer.long
        if (generation <= 0) return LocalRecordRead.Failed(LocalLockIssue.CORRUPT_RECORD)
        val recordedRoot = ByteArray(LocalEnvelopeFormat.ROOT_BYTES).also { buffer.get(it) }
        if (!MessageDigest.isEqual(recordedRoot, identity)) return LocalRecordRead.Failed(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE)
        buffer.position(108)
        if ((buffer.short.toInt() and 0xffff) != 48) return LocalRecordRead.Failed(LocalLockIssue.CORRUPT_RECORD)
        return LocalRecordRead.Present(LocalStoredEnvelope(bytes.copyOf(), generation, MessageDigest.getInstance("SHA-256").digest(bytes)))
    }

    private fun attributesOrNull(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun readBounded(path: Path): ByteArray? {
        val buffer = ByteBuffer.allocate(LocalEnvelopeFormat.RECORD_BYTES + 1)
        try {
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                while (buffer.hasRemaining()) {
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) throw IOException("read unavailable")
                }
            }
            val size = buffer.position()
            if (size != LocalEnvelopeFormat.RECORD_BYTES && size != LocalOpenWrap.RECORD_BYTES) return null
            return buffer.array().copyOf(size)
        } finally {
            buffer.array().fill(0)
        }
    }
}

/** No password is involved: the checksum only detects accidents, not a malicious rewrite. */
internal object LocalOpenWrap {
    const val RECORD_BYTES = 116
    private const val KEY_OFFSET = 52
    private const val DIGEST_OFFSET = 84
    private val magic get() = byteArrayOf(0x4e, 0x4e, 0x47, 0x4f, 0x50, 0x45, 0x4e, 0x31)
    private val purpose get() = "nngGram/local-open-wrap/v1\u0000".toByteArray(Charsets.US_ASCII)

    fun encode(key: LocalDatabaseKey, identity: ByteArray, generation: Long): LocalCryptoResult<ByteArray> {
        if (identity.size != 32 || generation <= 0) return LocalCryptoResult.InvalidInput
        return key.borrow { bytes ->
            val record = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.BIG_ENDIAN)
            var returned = false
            try {
                record.put(magic).put(1).put(0).put(0).put(0).putLong(generation).put(identity).put(bytes)
                val checksum = checksum(record.array())
                try {
                    record.put(checksum)
                    val result = LocalCryptoResult.Success(record.array())
                    returned = true
                    result
                } finally {
                    checksum.fill(0)
                }
            } finally {
                if (!returned) record.array().fill(0)
            }
        }
    }

    fun inspect(bytes: ByteArray, identity: ByteArray): LocalRecordRead {
        if (bytes.size != RECORD_BYTES || identity.size != 32) return LocalRecordRead.Failed(LocalLockIssue.CORRUPT_RECORD)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val prefix = ByteArray(8).also { buffer.get(it) }
        if (!prefix.contentEquals(magic)) return LocalRecordRead.Failed(LocalLockIssue.CORRUPT_RECORD)
        if (buffer.get().toInt() != 1 || buffer.get().toInt() != 0 || buffer.get().toInt() != 0 || buffer.get().toInt() != 0) {
            return LocalRecordRead.Failed(LocalLockIssue.UNSUPPORTED_RECORD)
        }
        val generation = buffer.long
        if (generation <= 0) return LocalRecordRead.Failed(LocalLockIssue.CORRUPT_RECORD)
        val root = ByteArray(32).also { buffer.get(it) }
        if (!MessageDigest.isEqual(root, identity)) return LocalRecordRead.Failed(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE)
        val digest = checksum(bytes)
        try {
            if (!MessageDigest.isEqual(digest, bytes.copyOfRange(DIGEST_OFFSET, RECORD_BYTES))) {
                return LocalRecordRead.Failed(LocalLockIssue.CORRUPT_RECORD)
            }
        } finally {
            digest.fill(0)
        }
        val key = bytes.copyOfRange(KEY_OFFSET, DIGEST_OFFSET)
        return try {
            LocalRecordRead.Present(
                LocalStoredEnvelope(ByteArray(0), generation, MessageDigest.getInstance("SHA-256").digest(bytes), LocalStoredKind.OPEN),
                LocalDatabaseKey.copyOf(key),
            )
        } finally {
            key.fill(0)
        }
    }

    private fun checksum(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update(purpose)
        update(bytes, 0, DIGEST_OFFSET)
        digest()
    }
}
