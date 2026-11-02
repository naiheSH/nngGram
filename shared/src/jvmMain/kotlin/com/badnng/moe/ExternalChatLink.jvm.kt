package com.badnng.moe

import com.badnng.moe.chat.safeExternalLink
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.badnng.moe.platform.DesktopPlatform
import java.awt.Desktop
import java.net.URI

internal actual fun openExternalChatLink(url: String): Boolean = openDesktopExternalLink(url)

/** Validation precedes every backend. URI arguments never pass through a command shell. */
internal fun openDesktopExternalLink(url: String, launch: (URI) -> Boolean = ::launchSystemUri): Boolean {
    val safe = safeExternalLink(url) ?: return false
    return runCatching { launch(URI(safe)) }.getOrDefault(false)
}

private fun launchSystemUri(uri: URI): Boolean {
    if (!DesktopPlatform.current.isWindows) {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            val action = if (uri.scheme.equals("mailto", true)) Desktop.Action.MAIL else Desktop.Action.BROWSE
            if (desktop.isSupported(action)) {
                if (action == Desktop.Action.MAIL) desktop.mail(uri) else desktop.browse(uri)
                return true
            }
        }
        val command = if (DesktopPlatform.current.isMacOS) "open" else "xdg-open"
        ProcessBuilder(command, uri.toASCIIString()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return true
    }
    return runCatching {
        val function = NativeLibrary.getInstance("shell32").getFunction("ShellExecuteW")
        val result = function.invoke(Pointer::class.java, arrayOf(null, WString("open"), WString(uri.toASCIIString()), null, null, 1)) as? Pointer
        result != null && Pointer.nativeValue(result) > 32
    }.getOrDefault(false)
}
