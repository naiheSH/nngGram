package com.badnng.moe.platform

import java.nio.file.Files
import java.nio.file.Path

object DesktopPaths {
    /** Existing Mac data stays discoverable; platform defaults apply only to new installations. */
    fun applicationRoot(
        platform: DesktopPlatform = DesktopPlatform.current,
        home: Path = Path.of(System.getProperty("user.home")),
        environment: Map<String, String> = System.getenv(),
        exists: (Path) -> Boolean = { Files.exists(it) },
    ): Path {
        fun absolute(name: String): Path? = environment[name]?.takeIf(String::isNotBlank)
            ?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { it.isAbsolute }
        return when {
            platform.isWindows -> (absolute("LOCALAPPDATA") ?: home.resolve("AppData/Local")).resolve("nngGram")
            platform.isMacOS -> {
                val legacy = home.resolve(".local/share/nngGram")
                if (exists(legacy.resolve("tdlib.properties")) || exists(legacy.resolve("data/client.lock"))) legacy
                else home.resolve("Library/Application Support/nngGram")
            }
            else -> (absolute("XDG_DATA_HOME") ?: home.resolve(".local/share")).resolve("nngGram")
        }
    }
}
