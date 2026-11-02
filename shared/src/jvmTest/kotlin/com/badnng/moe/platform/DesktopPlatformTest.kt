package com.badnng.moe.platform

import java.nio.file.Path
import kotlin.test.*

class DesktopPlatformTest {
    @Test fun platformSelectionNeverFallsBackToWindows() {
        val cases = listOf(
            Triple("Windows 11", "amd64", DesktopPlatform.WINDOWS_X64),
            Triple("Mac OS X", "aarch64", DesktopPlatform.MACOS_ARM64),
            Triple("Mac OS X", "x86_64", DesktopPlatform.MACOS_X64),
            Triple("Linux", "amd64", DesktopPlatform.LINUX_X64),
            Triple("Linux", "arm64", DesktopPlatform.LINUX_ARM64),
        )
        for ((os, arch, expected) in cases) assertEquals(expected, DesktopPlatform.detect(os, arch))
        assertFailsWith<IllegalStateException> { DesktopPlatform.detect("Other OS", "amd64") }
        assertFailsWith<IllegalStateException> { DesktopPlatform.detect("Windows 11", "arm64") }
        assertEquals("ffmpeg.exe", DesktopPlatform.WINDOWS_X64.executable("ffmpeg"))
        assertEquals("ffmpeg", DesktopPlatform.MACOS_ARM64.executable("ffmpeg"))
        assertEquals("ffprobe", DesktopPlatform.LINUX_ARM64.executable("ffprobe"))
    }

    @Test fun dataRootsRespectHostConventionsAndRetainExistingMacData() {
        val home = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("synthetic-home")
        fun root(platform: DesktopPlatform, env: Map<String, String> = emptyMap(), legacy: Boolean = false) =
            DesktopPaths.applicationRoot(platform, home, env) { legacy && it == home.resolve(".local/share/nngGram/tdlib.properties") }
        val local = home.resolve("relocated-local")
        assertEquals(local.resolve("nngGram"), root(DesktopPlatform.WINDOWS_X64, mapOf("LOCALAPPDATA" to local.toString())))
        assertEquals(home.resolve("Library/Application Support/nngGram"), root(DesktopPlatform.MACOS_ARM64))
        assertEquals(home.resolve(".local/share/nngGram"), root(DesktopPlatform.MACOS_ARM64, legacy = true))
        assertEquals(local.resolve("nngGram"), root(DesktopPlatform.LINUX_X64, mapOf("XDG_DATA_HOME" to local.toString())))
        assertEquals(home.resolve(".local/share/nngGram"), root(DesktopPlatform.LINUX_ARM64, mapOf("XDG_DATA_HOME" to "relative")))
    }
}
