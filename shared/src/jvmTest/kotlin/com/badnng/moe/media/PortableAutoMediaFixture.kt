package com.badnng.moe.media

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*

/** Test-only handle model for self-owned temporary fixtures. POSIX cannot provide
 * Windows' no-share-delete contract, so this is never installed as a production backend.
 * Native Windows handle/race checks remain separate Windows-only tests.
 */
internal val portableAutoMediaOwnedFiles: AutoMediaOwnedFiles =
    if (com.sun.jna.Platform.isWindows()) WindowsAutoMediaOwnedFiles else SyntheticNioOwnedFiles

internal object PortableAutoMediaCopyIo : AutoMediaCopyIo by NioAutoMediaCopyIo {
    override val ownedFiles get() = portableAutoMediaOwnedFiles
    override fun cleanupOwnedStage(stage: Path, identity: String?) = deleteExactAutoMediaStage(stage, identity, ownedFiles)
    fun existingDestinationScope(path: Path) = NioAutoMediaCopyIo.existingDestinationScope(path)
}

private object SyntheticNioOwnedFiles : AutoMediaOwnedFiles {
    override fun createNew(path: Path) = open(path, true, true)
    override fun openRead(path: Path) = open(path, false, false)
    override fun openReadWrite(path: Path) = open(path, false, true)
    override fun openReadDelete(path: Path) = open(path, false, false)

    private fun open(path: Path, create: Boolean, write: Boolean): AutoMediaOwnedFile {
        val options = mutableSetOf<java.nio.file.OpenOption>(READ, NOFOLLOW_LINKS)
        if (write) options += WRITE
        if (create) options += CREATE_NEW
        val channel = FileChannel.open(path, options)
        try {
            val identity = checkNotNull(stableMediaFileIdentity(path))
            return object : AutoMediaOwnedFile {
                override val identity = identity
                override fun write(bytes: ByteArray, length: Int) {
                    val buffer = ByteBuffer.wrap(bytes, 0, length)
                    while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
                }
                override fun read(bytes: ByteArray): Int = channel.read(ByteBuffer.wrap(bytes))
                override fun replace(bytes: ByteArray) {
                    channel.position(0); write(bytes, bytes.size); channel.truncate(bytes.size.toLong()); force()
                }
                override fun force() = channel.force(true)
                override fun markDelete() {
                    if (stableMediaFileIdentity(path) != identity) throw IOException("Synthetic fixture identity changed")
                    Files.delete(path)
                }
                override fun close() = channel.close()
            }
        } catch (failure: Throwable) { channel.close(); throw failure }
    }
}
