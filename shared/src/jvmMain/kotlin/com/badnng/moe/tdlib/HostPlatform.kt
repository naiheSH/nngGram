package com.badnng.moe.tdlib

import java.io.RandomAccessFile
import java.nio.file.Path

/** Desktop JVM hosts that can load an official TDLib JNI library beside the app. Android is a separate target. */
internal enum class HostPlatform(
    val displayName: String,
    val nativeProfile: NativeLibraryProfile,
) {
    WINDOWS_X64("Windows", NativeLibraryProfile.WINDOWS_X64),
    MACOS_X64("macOS", NativeLibraryProfile.MACOS_X64),
    MACOS_ARM64("macOS", NativeLibraryProfile.MACOS_ARM64),
    LINUX_X64("Linux", NativeLibraryProfile.LINUX_X64),
    LINUX_ARM64("Linux", NativeLibraryProfile.LINUX_ARM64),
    ;

    companion object {
        val current: HostPlatform by lazy { detect(System.getProperty("os.name"), System.getProperty("os.arch")) }

        internal fun detect(osName: String, osArch: String): HostPlatform {
            return valueOf(com.badnng.moe.platform.DesktopPlatform.detect(osName, osArch).name)
        }
    }
}

internal enum class NativeImageKind { PE, MACHO, ELF }

internal enum class NativeLibraryProfile(
    val bundleDirectoryName: String,
    val architecture: String,
    val jniLibrary: String,
    val libraryPattern: String,
    private val machine: Int,
    private val kind: NativeImageKind,
) {
    WINDOWS_X64("windows-x64", "x64", "tdjni.dll", "[a-zA-Z0-9._+-]+\\.dll", 0x8664, NativeImageKind.PE),
    MACOS_X64("macos-x64", "x64", "libtdjni.dylib", "[a-zA-Z0-9._+-]+\\.dylib", 0x01000007, NativeImageKind.MACHO),
    MACOS_ARM64("macos-arm64", "arm64", "libtdjni.dylib", "[a-zA-Z0-9._+-]+\\.dylib", 0x0100000c, NativeImageKind.MACHO),
    LINUX_X64("linux-x64", "x64", "libtdjni.so", "[a-zA-Z0-9._+-]+\\.so", 62, NativeImageKind.ELF),
    LINUX_ARM64("linux-arm64", "arm64", "libtdjni.so", "[a-zA-Z0-9._+-]+\\.so", 183, NativeImageKind.ELF),
    ;

    val dependencyName: String get() = when (kind) {
        NativeImageKind.PE -> "dependency.dll"
        NativeImageKind.MACHO -> "libdependency.dylib"
        NativeImageKind.ELF -> "libdependency.so"
    }

    fun verify(path: Path) = when (kind) {
        NativeImageKind.PE -> verifyPe(path)
        NativeImageKind.MACHO -> verifyMachO(path)
        NativeImageKind.ELF -> verifyElf(path)
    }

    private fun verifyPe(path: Path) {
        RandomAccessFile(path.toFile(), "r").use { file ->
            if (file.length() < 64 || file.readUnsignedShort() != 0x4d5a) invalid(path)
            file.seek(0x3c)
            val offset = Integer.reverseBytes(file.readInt()).toLong()
            if (offset < 64 || offset > file.length() - 6) invalid(path)
            file.seek(offset)
            if (file.readInt() != 0x50450000) invalid(path)
            val actual = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
            if (actual != machine) fail("动态库不是 Windows x64：${path.fileName}", "请替换为 x64 原生库，不能混用 x86、ARM64 或其他系统的动态库。")
        }
    }

    private fun verifyMachO(path: Path) {
        RandomAccessFile(path.toFile(), "r").use { file ->
            if (file.length() < 8) invalid(path)
            val magic = file.readInt()
            val cpu = file.readInt()
            val swapped = magic == 0xcffaedfe.toInt()
            val native = magic == 0xfeedfacf.toInt()
            if (!swapped && !native) invalid(path)
            val actual = if (swapped) Integer.reverseBytes(cpu) else cpu
            if (actual != machine) fail("动态库不是 $displayArchitecture：${path.fileName}", "请替换为与当前 Mac 架构一致的 dylib。")
        }
    }

    private fun verifyElf(path: Path) {
        RandomAccessFile(path.toFile(), "r").use { file ->
            if (file.length() < 20) invalid(path)
            val magic = ByteArray(4)
            file.readFully(magic)
            if (!magic.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) invalid(path)
            if (file.read() != 2 || file.read() != 1) invalid(path)
            file.seek(18)
            val actual = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
            if (actual != machine) fail("动态库不是 $displayArchitecture：${path.fileName}", "请替换为与当前 Linux 架构一致的 .so。")
        }
    }

    private val displayArchitecture get() = "$displayName $architecture"
    private val displayName get() = when (kind) {
        NativeImageKind.PE -> "Windows"
        NativeImageKind.MACHO -> "macOS"
        NativeImageKind.ELF -> "Linux"
    }
    private fun invalid(path: Path): Nothing = fail("动态库文件损坏：${path.fileName}", "请重新构建或恢复原生文件。")
    private fun fail(summary: String, recovery: String): Nothing =
        throw SetupFailure(summary, recovery, com.badnng.moe.status.FailureCategory.NATIVE)
}
