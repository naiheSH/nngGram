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
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop hosts other than Windows. AWT talks to the current platform clipboard
 * (macOS NSPasteboard, Linux X11/Wayland via the JDK). Windows keeps its HWND owner.
 */
internal class DesktopSystemClipboard : DesktopClipboardHost {
    private val system get() = Toolkit.getDefaultToolkit().systemClipboard

    override suspend fun getClipEntry(): ClipEntry? = withContext(Dispatchers.IO) {
        runCatching { system.getContents(null)?.let(::ClipEntry) }.getOrNull()
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = withContext(Dispatchers.IO) {
        val transferable = clipEntry?.asAwtTransferable
        if (transferable == null) system.setContents(StringSelection(""), null)
        else system.setContents(transferable, null)
    }

    override fun hasAttachments(): Boolean = runCatching {
        val contents = system.getContents(null) ?: return false
        contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || contents.isDataFlavorSupported(DataFlavor.imageFlavor)
    }.getOrDefault(false)

    override suspend fun readAttachments(): ClipboardAttachmentContent? = withContext(Dispatchers.IO) {
        runCatching {
            val contents = system.getContents(null) ?: return@runCatching null
            if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                val files = (contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                    ?.filterIsInstance<File>()?.map { it.path }.orEmpty()
                if (files.isNotEmpty()) return@runCatching ClipboardAttachmentContent.FileList(files)
            }
            if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                val image = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image ?: return@runCatching null
                val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                buffered.createGraphics().use { graphics -> graphics.drawImage(image, 0, 0, null) }
                val bytes = java.io.ByteArrayOutputStream().use { out ->
                    ImageIO.write(buffered, "png", out)
                    out.toByteArray()
                }
                if (bytes.isNotEmpty()) ClipboardAttachmentContent.PngImage(bytes) else null
            } else null
        }.getOrNull()
    }

    override suspend fun writeFiles(paths: List<String>) {
        withContext(Dispatchers.IO) { system.setContents(fileTransferable(paths.map(::File)), null) }
    }

    override suspend fun writePng(bytes: ByteArray) {
        withContext(Dispatchers.IO) { system.setContents(pngTransferable(bytes), null) }
    }

    override fun prepareProxyQr(size: Int, modules: BooleanArray): SettingsProxyQrPrepared? {
        if (size !in 21..177 || (size - 21) % 4 != 0 || modules.size != size * size) return null
        val scale = 4
        val quiet = 4
        val side = (size + quiet * 2) * scale
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color.WHITE
        graphics.fillRect(0, 0, side, side)
        graphics.color = java.awt.Color.BLACK
        for (y in 0 until size) for (x in 0 until size) {
            if (modules[y * size + x]) graphics.fillRect((x + quiet) * scale, (y + quiet) * scale, scale, scale)
        }
        graphics.dispose()
        return PreparedImage(image)
    }

    override fun prepareProxyText(value: String): SettingsProxyTextPrepared? {
        if (value.isEmpty() || value.length > 4096 || '\u0000' in value) return null
        return PreparedText(value)
    }

    override val manager: ClipboardManager = object : ClipboardManager {
        override fun getText(): AnnotatedString? = runCatching {
            system.getContents(null)?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
                ?.getTransferData(DataFlavor.stringFlavor) as? String
        }.getOrNull()?.let(::AnnotatedString)

        override fun setText(annotatedString: AnnotatedString) {
            runCatching { system.setContents(StringSelection(annotatedString.text), null) }
        }

        override fun hasText(): Boolean = runCatching {
            system.getContents(null)?.isDataFlavorSupported(DataFlavor.stringFlavor) == true
        }.getOrDefault(false)

        override fun getClip(): ClipEntry? = runCatching { system.getContents(null)?.let(::ClipEntry) }.getOrNull()

        override fun setClip(clipEntry: ClipEntry?) {
            runCatching {
                val transferable = clipEntry?.asAwtTransferable
                system.setContents(transferable ?: StringSelection(""), null)
            }
        }
    }

    private class PreparedText(private var value: String?) : SettingsProxyTextPrepared {
        override fun publishIfCurrent(isCurrent: () -> Boolean): Boolean {
            val text = value ?: return false
            if (!isCurrent()) return false
            return runCatching {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                true
            }.getOrDefault(false)
        }

        override fun close() { value = null }
        override fun toString() = "PreparedText(<redacted>)"
    }

    private class PreparedImage(private var image: BufferedImage?) : SettingsProxyQrPrepared {
        override fun publishIfCurrent(isCurrent: () -> Boolean): Boolean {
            val current = image ?: return false
            if (!isCurrent()) return false
            return runCatching {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(imageTransferable(current), null)
                true
            }.getOrDefault(false)
        }

        override fun close() { image = null }
        override fun toString() = "PreparedImage(<redacted>)"
    }
}

private fun fileTransferable(files: List<File>): Transferable = object : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.javaFileListFlavor) files else throw UnsupportedFlavorException(flavor)
}

private fun pngTransferable(bytes: ByteArray): Transferable = object : Transferable {
    private val flavor = DataFlavor("image/png", "PNG image")
    override fun getTransferDataFlavors() = arrayOf(flavor, DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(candidate: DataFlavor) = candidate == flavor || candidate == DataFlavor.imageFlavor
    override fun getTransferData(candidate: DataFlavor): Any = when (candidate) {
        flavor -> ByteArrayInputStream(bytes)
        DataFlavor.imageFlavor -> ImageIO.read(ByteArrayInputStream(bytes))
        else -> throw UnsupportedFlavorException(candidate)
    }
}

private fun imageTransferable(image: BufferedImage): Transferable = object : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
}

private inline fun <T> java.awt.Graphics.use(block: (java.awt.Graphics2D) -> T): T {
    return try { block(this as java.awt.Graphics2D) } finally { dispose() }
}
