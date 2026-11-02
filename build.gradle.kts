plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.nucleus) apply false
}

// Resolve once for both modules. An unsupported host must never silently select Windows.
val desktopNativeProfile = run {
    val os = System.getProperty("os.name")
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported desktop architecture")
    }
    val family = when {
        os.startsWith("Windows") && arch == "x64" -> "windows"
        os.startsWith("Mac") -> "macos"
        os.startsWith("Linux") -> "linux"
        else -> error("Unsupported desktop host")
    }
    "$family-$arch"
}
extra["desktopNativeProfile"] = desktopNativeProfile
