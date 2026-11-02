@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package com.badnng.moe

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import com.badnng.moe.media.AttachmentClipboard
import com.badnng.moe.media.ClipboardAttachmentContent
import com.badnng.moe.settings.SettingsProxyQrClipboard
import com.badnng.moe.settings.SettingsProxyQrPrepared
import com.badnng.moe.settings.SettingsProxyTextClipboard
import com.badnng.moe.settings.SettingsProxyTextPrepared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** Transferable is a toolkit-free container; the OS is accessed only through Win32. */
internal interface DesktopClipboardHost : Clipboard, AttachmentClipboard, SettingsProxyQrClipboard, SettingsProxyTextClipboard {
    val manager: ClipboardManager
}

internal class WindowsClipboard(owner: Long, access: WindowsClipboardAccess? = null) : DesktopClipboardHost {
    init { require(owner != 0L) { "A native clipboard requires a live window owner." } }
    private val owner = owner
    private val access = access ?: Win32ClipboardAccess()

    override suspend fun getClipEntry(): ClipEntry? = withContext(Dispatchers.IO) {
        readSnapshot(includeText = true).toTransferable()?.let(::ClipEntry)
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = withContext(Dispatchers.IO) {
        writeTransferable(clipEntry?.asAwtTransferable, clear = clipEntry == null)
    }

    override fun hasAttachments(): Boolean = listOf(WindowsClipboardFormats.FILE_DROP, access.pngFormat,
        WindowsClipboardFormats.DIB_V5, WindowsClipboardFormats.DIB).any(access::available)

    override suspend fun readAttachments(): ClipboardAttachmentContent? = retryAttachmentAccess {
        withContext(Dispatchers.IO) {
            val snapshot = readSnapshot(includeText = false)
            snapshot.files?.let { ClipboardAttachmentContent.FileList(it) }
                ?: snapshot.png?.let { ClipboardAttachmentContent.PngImage(it) }
        }
    }

    override suspend fun writeFiles(paths: List<String>) {
        val bytes = WindowsClipboardFormats.encodeFiles(paths)
        retryAttachmentAccess {
            withContext(Dispatchers.IO) { publish(mapOf(WindowsClipboardFormats.FILE_DROP to bytes)) }
        }
    }

    override suspend fun writePng(bytes: ByteArray) {
        val payload = withContext(Dispatchers.IO) { imageFormats(bytes) }
        retryAttachmentAccess { withContext(Dispatchers.IO) { publish(payload) } }
    }

    /** Preparation is bounded and does not open the system clipboard. */
    override fun prepareProxyQr(size: Int, modules: BooleanArray): SettingsProxyQrPrepared? {
        if (size !in 21..177 || (size - 21) % 4 != 0 || modules.size != size * size) return null
        val scale = 4
        val quiet = 4
        val side = (size + quiet * 2) * scale
        val pixels = IntArray(side * side) { 0xffffffff.toInt() }
        var png: ByteArray? = null
        var dib: ByteArray? = null
        return try {
            for (y in 0 until size) for (x in 0 until size) {
                if (modules[y * size + x]) {
                    val left = (x + quiet) * scale
                    val top = (y + quiet) * scale
                    repeat(scale) { offset ->
                        val start = (top + offset) * side + left
                        pixels.fill(0xff000000.toInt(), start, start + scale)
                    }
                }
            }
            val raster = ClipboardRaster(side, side, pixels)
            val pngBytes = WindowsClipboardFormats.rasterToPng(raster).also { png = it }
            val dibBytes = WindowsClipboardFormats.encodeDibV5(raster).also { dib = it }
            PreparedProxyClipboard(mapOf(access.pngFormat to pngBytes, WindowsClipboardFormats.DIB_V5 to dibBytes))
        } catch (_: Exception) {
            png?.fill(0)
            dib?.fill(0)
            null
        } finally {
            pixels.fill(0)
        }
    }

    override fun prepareProxyText(value: String): SettingsProxyTextPrepared? {
        if (value.isEmpty() || value.length > WindowsClipboardFormats.MAX_TEXT_BYTES / 2 - 1 || '\u0000' in value) return null
        return try {
            PreparedProxyClipboard(mapOf(WindowsClipboardFormats.UNICODE_TEXT to WindowsClipboardFormats.encodeText(value)))
        } catch (_: Exception) { null }
    }

    /** No asynchronous work or retry: the caller holds the share owner's commit boundary. */
    private inner class PreparedProxyClipboard(private var formats: Map<Int, ByteArray>?) :
        SettingsProxyQrPrepared, SettingsProxyTextPrepared {
        private val gate = Any()

        override fun publishIfCurrent(isCurrent: () -> Boolean): Boolean = synchronized(gate) {
            val currentFormats = formats ?: return@synchronized false
            try {
                withClipboard {
                    // Opening the clipboard can race a stop or a revoked source.
                    if (!isCurrent()) false else { access.publish(currentFormats); true }
                }
            } catch (_: Exception) { false }
        }

        override fun close() = synchronized(gate) {
            formats?.values?.forEach { it.fill(0) }
            formats = null
        }

        override fun toString() = "PreparedProxyClipboard(<redacted>)"
    }

    override val manager: ClipboardManager = object : ClipboardManager {
        override fun getText(): AnnotatedString? = try {
            withClipboard { this@WindowsClipboard.access.read(WindowsClipboardFormats.UNICODE_TEXT, WindowsClipboardFormats.MAX_TEXT_BYTES) }
                ?.let(WindowsClipboardFormats::decodeText)?.let(::AnnotatedString)
        } catch (_: ClipboardBusy) { null }

        override fun setText(annotatedString: AnnotatedString) {
            try { publish(mapOf(WindowsClipboardFormats.UNICODE_TEXT to WindowsClipboardFormats.encodeText(annotatedString.text))) }
            catch (_: ClipboardBusy) { /* Keep editor content intact while another application owns the clipboard. */ }
        }

        override fun hasText(): Boolean = this@WindowsClipboard.access.available(WindowsClipboardFormats.UNICODE_TEXT)

        override fun getClip(): ClipEntry? = try { readSnapshot(includeText = true).toTransferable()?.let(::ClipEntry) }
            catch (_: ClipboardBusy) { null }

        override fun setClip(clipEntry: ClipEntry?) {
            try { writeTransferable(clipEntry?.asAwtTransferable, clear = clipEntry == null) }
            catch (_: ClipboardBusy) { /* Keep the previous OS content if opening failed. */ }
        }
    }

    private fun readSnapshot(includeText: Boolean): ClipboardSnapshot {
        val raw = withClipboard {
            val text = if (includeText) access.read(WindowsClipboardFormats.UNICODE_TEXT, WindowsClipboardFormats.MAX_TEXT_BYTES)
                ?.let(WindowsClipboardFormats::decodeText) else null
            val files = access.read(WindowsClipboardFormats.FILE_DROP, WindowsClipboardFormats.MAX_FILE_LIST_BYTES)
                ?.let { WindowsClipboardFormats.decodeFiles(it, access.ansiCharset) }
            val png = if (files == null) access.read(access.pngFormat, WindowsClipboardFormats.MAX_PNG_BYTES) else null
            val dib = if (files == null) access.read(WindowsClipboardFormats.DIB_V5, WindowsClipboardFormats.MAX_IMAGE_BYTES)
                ?: access.read(WindowsClipboardFormats.DIB, WindowsClipboardFormats.MAX_IMAGE_BYTES) else null
            RawClipboardSnapshot(text, files, png, dib)
        }
        // Native memory is copied and unlocked before decoding, so a large screenshot
        // never leaves the system clipboard locked while Skia compresses its pixels.
        val png = raw.png?.takeIf { runCatching { WindowsClipboardFormats.validatePng(it) }.isSuccess }
            ?: raw.dib?.let { runCatching { WindowsClipboardFormats.rasterToPng(WindowsClipboardFormats.decodeDib(it)) }.getOrNull() }
        return ClipboardSnapshot(raw.text, raw.files, png)
    }

    private fun writeTransferable(transferable: Transferable?, clear: Boolean) {
        if (clear) { publish(emptyMap()); return }
        if (transferable == null) return
        val files = if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                ?.takeIf { it.isNotEmpty() && it.all { value -> value is File } }?.map { (it as File).path }
        } else null
        if (files != null) { publish(mapOf(WindowsClipboardFormats.FILE_DROP to WindowsClipboardFormats.encodeFiles(files))); return }
        val png = if (transferable.isDataFlavorSupported(PNG_CLIPBOARD_FLAVOR)) {
            when (val image = transferable.getTransferData(PNG_CLIPBOARD_FLAVOR)) {
                is InputStream -> image.use { stream -> stream.readNBytes(WindowsClipboardFormats.MAX_PNG_BYTES + 1) }
                is ByteArray -> image
                else -> null
            }
        } else null
        if (png != null) { publish(imageFormats(png)); return }
        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            (transferable.getTransferData(DataFlavor.stringFlavor) as? String)?.let {
                publish(mapOf(WindowsClipboardFormats.UNICODE_TEXT to WindowsClipboardFormats.encodeText(it)))
            }
        }
    }

    private fun imageFormats(png: ByteArray): Map<Int, ByteArray> {
        val raster = WindowsClipboardFormats.pngToRaster(png)
        return mapOf(access.pngFormat to png.copyOf(), WindowsClipboardFormats.DIB_V5 to WindowsClipboardFormats.encodeDibV5(raster))
    }

    private fun publish(formats: Map<Int, ByteArray>) = withClipboard { access.publish(formats) }

    private inline fun <T> withClipboard(block: () -> T): T {
        if (!access.open(owner)) throw ClipboardBusy()
        return try { block() } finally { access.close() }
    }

    private suspend fun <T> retryAttachmentAccess(operation: suspend () -> T): T {
        repeat(6) { attempt ->
            try { return operation() }
            catch (busy: ClipboardBusy) {
                if (attempt == 5) throw busy
                delay(20L shl attempt)
            }
        }
        error("Unreachable clipboard retry")
    }
}

// Non-serialized MIME data defaults to InputStream without reflective class loading.
internal val PNG_CLIPBOARD_FLAVOR = DataFlavor("image/png", "PNG image")

private class RawClipboardSnapshot(val text: String?, val files: List<String>?, val png: ByteArray?, val dib: ByteArray?) {
    override fun toString() = "RawClipboardSnapshot(<redacted>)"
}

private class ClipboardSnapshot(val text: String?, val files: List<String>?, val png: ByteArray?) {
    fun toTransferable(): Transferable? = if (text == null && files == null && png == null) null else object : Transferable {
        private val flavors = buildList {
            if (files != null) add(DataFlavor.javaFileListFlavor)
            if (png != null) add(PNG_CLIPBOARD_FLAVOR)
            if (text != null) add(DataFlavor.stringFlavor)
        }
        override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in flavors
        override fun getTransferData(flavor: DataFlavor): Any = when {
            flavor == DataFlavor.javaFileListFlavor && files != null -> files.map(::File)
            flavor == PNG_CLIPBOARD_FLAVOR && png != null -> ByteArrayInputStream(png)
            flavor == DataFlavor.stringFlavor && text != null -> text
            else -> throw UnsupportedFlavorException(flavor)
        }
        override fun toString() = "ClipboardTransferable(<redacted>)"
    }
    override fun toString() = "ClipboardSnapshot(<redacted>)"
}

internal class ClipboardBusy : IllegalStateException("cannot open system clipboard")
