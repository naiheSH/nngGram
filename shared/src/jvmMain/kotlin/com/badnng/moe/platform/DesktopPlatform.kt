package com.badnng.moe.platform

/** One runtime host description shared by native loading, media and desktop services. */
enum class DesktopPlatform(val bundleDirectory: String, val isWindows: Boolean = false) {
    WINDOWS_X64("windows-x64", true),
    MACOS_X64("macos-x64"), MACOS_ARM64("macos-arm64"),
    LINUX_X64("linux-x64"), LINUX_ARM64("linux-arm64");

    val isMacOS: Boolean get() = this == MACOS_X64 || this == MACOS_ARM64
    fun executable(name: String): String = if (isWindows) "$name.exe" else name

    companion object {
        val current: DesktopPlatform by lazy { detect(System.getProperty("os.name"), System.getProperty("os.arch")) }

        fun detect(os: String, architecture: String): DesktopPlatform {
            val arm = architecture.lowercase() in setOf("aarch64", "arm64")
            val x64 = architecture.lowercase() in setOf("amd64", "x86_64")
            return when {
                os.startsWith("Windows", true) && x64 -> WINDOWS_X64
                os.startsWith("Mac", true) && x64 -> MACOS_X64
                os.startsWith("Mac", true) && arm -> MACOS_ARM64
                os.startsWith("Linux", true) && x64 -> LINUX_X64
                os.startsWith("Linux", true) && arm -> LINUX_ARM64
                else -> error("Unsupported desktop host: $os / $architecture")
            }
        }
    }
}
