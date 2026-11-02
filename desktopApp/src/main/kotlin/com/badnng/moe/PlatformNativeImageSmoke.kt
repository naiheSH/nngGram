@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.badnng.moe

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import dev.nucleusframework.graalvm.GraalVmInitializer
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Explicit, account-free ABI check. Never reads the system clipboard or opens an audio device. */
internal object PlatformNativeImageSmoke {
    fun handle(arguments: Array<String>): Boolean {
        if (arguments.firstOrNull() != "--platform-native-smoke") return false
        try {
            require(arguments.size <= 2)
            val report = arguments.getOrNull(1)?.let(Path::of)
            require(report == null || report.isAbsolute)
            GraalVmInitializer.initialize()
            check(java.nio.charset.Charset.isSupported("GBK"))
            if (com.badnng.moe.platform.DesktopPlatform.current.isWindows) {
                val kernel = NativeLibrary.getInstance("kernel32")
                val processId = kernel.getFunction("GetCurrentProcessId", Function.ALT_CONVENTION).invokeInt(emptyArray())
                check((processId.toLong() and 0xffff_ffffL) == ProcessHandle.current().pid())
                Memory(8).use { timestamp ->
                    timestamp.clear()
                    kernel.getFunction("GetSystemTimeAsFileTime", Function.ALT_CONVENTION).invokeVoid(arrayOf(timestamp))
                    check(timestamp.getLong(0) > 0)
                }
                val user = NativeLibrary.getInstance("user32")
                check(user.getFunction("IsWindow", Function.ALT_CONVENTION).invokeInt(arrayOf(Pointer.NULL)) == 0)
                listOf("OpenClipboard", "GetClipboardData", "SetClipboardData", "CloseClipboard").forEach {
                    check(Pointer.nativeValue(user.getFunction(it, Function.ALT_CONVENTION)) != 0L)
                }
                val audio = NativeLibrary.getInstance("winmm")
                check(Pointer.nativeValue(NativeLibrary.getInstance("shell32").getFunction("ShellExecuteW", Function.ALT_CONVENTION)) != 0L)
                listOf("waveOutOpen", "waveOutPrepareHeader", "waveOutWrite", "waveOutReset", "waveOutUnprepareHeader", "waveOutClose",
                    "waveInOpen", "waveInPrepareHeader", "waveInAddBuffer", "waveInStart", "waveInStop", "waveInReset",
                    "waveInUnprepareHeader", "waveInClose").forEach {
                    check(Pointer.nativeValue(audio.getFunction(it, Function.ALT_CONVENTION)) != 0L)
                }
            } else {
                val libc = NativeLibrary.getInstance(com.sun.jna.Platform.C_LIBRARY_NAME)
                check(libc.getFunction("getpid").invokeInt(emptyArray()).toLong() == ProcessHandle.current().pid())
            }
            // Compose uses this Java data-transfer container even on Tao; no Toolkit is involved.
            val sample = "离线剪贴板容器🙂"
            val entry = ClipEntry(StringSelection(sample))
            check(entry.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) == sample)
            if (report != null) Files.writeString(report,
                "{\"kind\":\"platform_native_smoke\",\"passed\":true,\"nativeImage\":${GraalVmInitializer.isNativeImage},\"systemClipboardAccessed\":false,\"audioDeviceOpened\":false}\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            println("PLATFORM_NATIVE_SMOKE_OK")
            return true
        } catch (_: Throwable) {
            throw IllegalStateException("PLATFORM_NATIVE_SMOKE_FAILED")
        }
    }
}
