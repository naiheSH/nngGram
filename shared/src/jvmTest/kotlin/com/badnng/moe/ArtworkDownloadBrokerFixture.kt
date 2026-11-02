package com.badnng.moe

import com.badnng.moe.chat.*
import com.badnng.moe.tdlib.DownloadBrokerNative
import com.badnng.moe.tdlib.TdDownloadBroker
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/** Real UI owned actions and the production arbiter; only the native boundary is synthetic. */
internal class ArtworkDownloadBrokerFixture(private val currentFile: (Int) -> MediaFile) : AutoCloseable {
    data class Window(val fileId: Int, val offset: Long, val limit: Long, val accept: (ChatResult<MediaFile>) -> Unit)
    val windows = CopyOnWriteArrayList<Window>()
    val acquired = CopyOnWriteArrayList<MediaDownloadOwner>()
    val released = CopyOnWriteArrayList<MediaDownloadOwner>()
    val cancellations = CopyOnWriteArrayList<Int>()
    private val activeOwners = ConcurrentHashMap.newKeySet<Pair<MediaDownloadOwner, Int>>()
    private val callbacks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private fun drainCallbacks() {
        while (true) (callbacks.poll() ?: return).invoke()
    }
    private val native = object : DownloadBrokerNative {
        override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) {
            check(limit > 0)
            windows += Window(fileId, offset, limit) { response -> completed(response); drainCallbacks() }
        }
        override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) { completed(ChatResult(0L)) }
        override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) {
            error("Artwork UI never reads sparse video bytes")
        }
        override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) { completed(ChatResult(currentFile(fileId))) }
        override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) { cancellations += fileId; completed(ChatResult(Unit)) }
    }
    // Native progress is advanced explicitly; Compose frames do not manufacture network ticks.
    private val broker = TdDownloadBroker(native, { _, _ -> }, now = { 0L },
        dispatchOwnedCallbacks = callbacks::add)
    fun actions(outline: (ChatToken, Int) -> Unit = { _, _ -> }): ChatFeatureActions = ChatFeatureActions(
        downloadFile = { _, _ -> error("Passive artwork must use its owned action, never a USER download") },
        acquireOwnedMedia = { owner, fileId ->
            if (activeOwners.add(owner to fileId)) {
                acquired += owner
                val file = currentFile(fileId)
                check(file.id == fileId)
                broker.full(owner, file) { activeOwners.remove(owner to fileId) }
                drainCallbacks()
            }
        },
        releaseOwnedMedia = { owner, fileId ->
            if (activeOwners.remove(owner to fileId)) { released += owner; broker.release(owner, fileId); drainCallbacks() }
        },
        loadStickerOutline = outline,
    )
    fun seedPendingUser(token: ChatToken, fileId: Int) {
        broker.full(MediaDownloadOwner(token, fileId.toLong(), MediaDownloadPurpose.USER, Long.MAX_VALUE), currentFile(fileId))
        check(windows.size == 1)
        windows.single().accept(ChatResult(currentFile(fileId)))
    }
    fun observe(file: MediaFile) { broker.observe(file); drainCallbacks() }
    override fun close() = broker.close()
}
