package com.badnng.moe.tdlib

import com.badnng.moe.status.FailureCategory
import com.badnng.moe.readSettingsPreferences
import com.badnng.moe.settingsEffectiveDeviceName
import java.io.IOException
import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.Properties
import java.util.Locale

class SetupFailure(
    val summary: String,
    val recovery: String,
    val category: FailureCategory = FailureCategory.CONFIGURATION,
) : Exception(summary)

enum class ProxyType { SOCKS5, HTTP }

class ProxyConfiguration(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
) {
    override fun toString() = "ProxyConfiguration(<redacted>)"
}

class TdConfiguration(
    val apiId: Int,
    val apiHash: String,
    val dataDirectory: Path,
    val useTestDc: Boolean = false,
    val proxy: ProxyConfiguration? = null,
    val deviceModel: String = "Desktop",
) {
    override fun toString() = "TdConfiguration(credentials=<redacted>)"
}

class ConfigurationStore(
    val path: Path,
    private val defaultDataDirectory: Path,
    private val pathError: SetupFailure? = null,
    private val bundledApiId: String = "",
    private val bundledApiHash: String = "",
) {

    fun read(): TdConfiguration? {
        try {
            ensureTemplate()
            val properties = Properties()
            Files.newBufferedReader(path).use(properties::load)
            return parse(properties)
        } catch (_: IllegalArgumentException) {
            throw SetupFailure("本地配置格式无效", "请检查配置文件格式；Windows 路径请使用正斜杠。")
        } catch (_: IOException) {
            throw SetupFailure("无法读取本地配置", "请检查配置文件是否可读、所在磁盘是否可用，以及路径是否指向文件。")
        } catch (_: SecurityException) {
            throw SetupFailure("没有本地配置访问权限", "请使用当前用户可读写的配置文件。")
        }
    }

    fun ensureTemplate(): Boolean {
        pathError?.let { throw it }
        if (!path.isAbsolute) throw SetupFailure("配置文件必须使用绝对路径", "请检查 NNGGRAM_CONFIG 或 nnggram.config 的路径设置。")
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path.parent)
                Files.writeString(path, "# Telegram application credentials; keep this file private.\napi_id=\napi_hash=\n# Optional absolute path, use forward slashes on Windows.\n# data_directory=D:/nngGram-data\nuse_test_dc=false\n# none (direct), socks5, or http (HTTP CONNECT).\nproxy_type=none\n# proxy_host=127.0.0.1\n# proxy_port=1080\n# proxy_username=\n# proxy_password=\n", StandardOpenOption.CREATE_NEW)
                return true
            } catch (_: FileAlreadyExistsException) { /* Another caller already created it. */
            } catch (_: IOException) {
                throw SetupFailure("无法创建本地配置", "请检查配置目录是否可写、磁盘空间是否充足。")
            } catch (_: SecurityException) {
                throw SetupFailure("没有本地配置写入权限", "请使用当前用户可读写的配置目录。")
            }
        }
        return false
    }

    private fun parse(properties: Properties): TdConfiguration? {
        val localId = properties.getProperty("api_id", "").trim()
        val localHash = properties.getProperty("api_hash", "").trim()
        // Never combine one local field with one bundled field from a different application.
        val useLocal = localId.isNotEmpty() || localHash.isNotEmpty()
        val id = if (useLocal) localId else bundledApiId
        val hash = if (useLocal) localHash else bundledApiHash
        if (id.isEmpty() || hash.isEmpty()) return null
        val apiId = id.toIntOrNull()?.takeIf { it > 0 }
            ?: throw SetupFailure("api_id 格式无效", "请在本地配置中填写 Telegram 应用的正整数 api_id。")
        if (!hash.matches(Regex("[a-fA-F0-9]{32}"))) {
            throw SetupFailure("api_hash 格式无效", "请填写 Telegram 应用的 32 位十六进制 api_hash。")
        }
        val directory = properties.getProperty("data_directory")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { value ->
                try { Path.of(value) } catch (_: IllegalArgumentException) {
                    throw SetupFailure("数据目录路径无效", "请填写有效的绝对路径。")
                }
            } ?: defaultDataDirectory
        if (!directory.isAbsolute) throw SetupFailure("数据目录必须是绝对路径", "请使用例如 D:/nngGram-data 的完整路径。")
        val testDc = when (properties.getProperty("use_test_dc", "false").trim()) {
            "true" -> true
            "false" -> false
            else -> throw SetupFailure("use_test_dc 格式无效", "该选项只能是 true 或 false。日常账号应使用 false。")
        }
        // Snapshot the same local preferences file that the settings editor saves. A running
        // session does not change; a later configuration read sees an accepted local edit.
        val deviceModel = settingsEffectiveDeviceName(
            readSettingsPreferences(path.toAbsolutePath().resolveSibling("ui-settings.properties")).customDeviceModel)
        return TdConfiguration(apiId, hash, directory.normalize(), testDc, parseProxy(properties), deviceModel)
    }

    private fun parseProxy(properties: Properties): ProxyConfiguration? {
        val type = when (properties.getProperty("proxy_type", "none").trim().lowercase(Locale.ROOT)) {
            "none" -> return null
            "socks5" -> ProxyType.SOCKS5
            "http" -> ProxyType.HTTP
            else -> throw SetupFailure("代理类型无效", "proxy_type 只能填写 none、socks5 或 http。")
        }
        val host = proxyHost(properties.getProperty("proxy_host", "").trim())
        val port = properties.getProperty("proxy_port", "").trim().toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: throw SetupFailure("代理端口无效", "proxy_port 请填写 1 到 65535 之间的端口。")
        return ProxyConfiguration(type, host, port,
            properties.getProperty("proxy_username", ""), properties.getProperty("proxy_password", ""))
    }

    private fun proxyHost(value: String): String {
        val failure = SetupFailure("代理地址无效", "proxy_host 请仅填写主机名或 IP 地址；端口单独填写 proxy_port，不要包含协议、路径或账号。")
        val host = value.removeSurrounding("[", "]")
        if (host.isEmpty() || host.any { it.isWhitespace() || it in "/\\@?#[]" }) throw failure
        try {
            // URI validation is local: never resolve a hostname while parsing a
            // configuration. Bracketed and unbracketed IPv6 literals are accepted.
            val normalized = if (':' in host) host else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
            val authority = if (':' in normalized) "[$normalized]" else normalized
            val uri = URI("http://$authority").parseServerAuthority()
            if (uri.host == null || uri.port != -1 || normalized.length > 255) throw failure
            return normalized
        } catch (_: IllegalArgumentException) {
            throw failure
        } catch (_: URISyntaxException) {
            throw failure
        }
    }

    companion object {
        fun default(): ConfigurationStore {
            val root = com.badnng.moe.platform.DesktopPaths.applicationRoot()
            val config = System.getProperty("nnggram.config") ?: System.getenv("NNGGRAM_CONFIG")
            val defaultPath = root.resolve("tdlib.properties")
            val data = root.resolve("data")
            return try {
                ConfigurationStore(config?.let(Path::of) ?: defaultPath, data,
                    bundledApiId = BundledTelegramApi.ID, bundledApiHash = BundledTelegramApi.HASH)
            } catch (_: IllegalArgumentException) {
                ConfigurationStore(defaultPath, data, SetupFailure("配置文件路径无效", "请检查 NNGGRAM_CONFIG 或 nnggram.config 的路径设置。"))
            }
        }
    }
}

/** Root lock only: acquiring it neither creates nor probes database/files or reads selections. */
internal class DataRootLease private constructor(
    private val root: Path,
    private val canonicalRoot: Path,
    private val rootId: String?,
    private val lockId: String?,
    private val createdRoot: Boolean,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    @Volatile private var closed = false

    /** Opaque root binding; unavailable identity never becomes an empty identity. */
    fun identity(): ByteArray? {
        val id = rootId ?: return null
        if (lockId == null || !isCurrent()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val canonical = canonicalRoot.toString().toByteArray(Charsets.UTF_8)
        val objectId = id.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(canonical.size).array())
        digest.update(canonical)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(objectId.size).array())
        digest.update(objectId)
        return digest.digest()
    }

    fun isCurrent(): Boolean = try {
        !closed && lock.isValid && rootId != null && lockId != null &&
            root.toRealPath() == canonicalRoot &&
            com.badnng.moe.media.stableMediaFileIdentity(canonicalRoot) == rootId &&
            Files.isRegularFile(root.resolve("client.lock"), NOFOLLOW_LINKS) &&
            com.badnng.moe.media.stableMediaFileIdentity(root.resolve("client.lock")) == lockId
    } catch (_: Exception) { false }

    /** Only an atomically new root whose sole child is this lock is eligible. */
    fun freshEligible(): Boolean = try {
        createdRoot && isCurrent() && Files.newDirectoryStream(root).use { children ->
            children.all { it.fileName.toString() == "client.lock" }
        }
    } catch (_: Exception) { false }

    /** Standalone DataDirectoryLease retains its portability; local protection separately requires a provable identity. */
    private fun ownsLockAndPath(): Boolean = try {
        !closed && lock.isValid && root.toRealPath() == canonicalRoot &&
            (rootId == null || com.badnng.moe.media.stableMediaFileIdentity(canonicalRoot) == rootId) &&
            (lockId == null || com.badnng.moe.media.stableMediaFileIdentity(root.resolve("client.lock")) == lockId)
    } catch (_: Exception) { false }

    fun prepareDirectories(allowed: () -> Boolean = { true }): DataDirectoryLease {
        val failure = SetupFailure("无法准备数据目录", "请检查数据库和文件目录是否可写、磁盘空间是否充足，以及路径是否被文件占用。", FailureCategory.DIRECTORY)
        try {
            if (!allowed() || !ownsLockAndPath()) throw failure
            val database = root.resolve("database")
            val files = root.resolve("files")
            for (directory in listOf(database, files)) {
                if (!allowed()) throw failure
                Files.createDirectories(directory)
                if (!allowed()) throw failure
                val probe = Files.createTempFile(directory, ".write-check-", ".tmp")
                try {
                    if (!allowed()) throw failure
                    Files.writeString(probe, "ok")
                } finally { Files.deleteIfExists(probe) }
                if (!allowed() || !ownsLockAndPath()) throw failure
            }
            return DataDirectoryLease(database, files) { }
        } catch (error: Exception) { throw if (error is SetupFailure) error else failure }
    }

    @Synchronized override fun close() {
        if (closed) return
        val failure = SetupFailure("数据目录锁释放失败", "请关闭客户端后检查数据目录权限，再重新启动。", FailureCategory.DIRECTORY)
        try {
            try { if (lock.isValid) lock.release() } finally { channel.close() }
            if (lock.isValid || channel.isOpen) throw failure
            closed = true
        } catch (_: IOException) { throw failure }
    }

    companion object {
        fun acquire(root: Path, allowed: () -> Boolean = { true }): DataRootLease {
            var channel: FileChannel? = null
            var lock: FileLock? = null
            try {
                fun requireAllowed() {
                    if (!allowed()) throw SetupFailure("无法准备数据目录", "本地保护状态已变化，请解锁后重试。", FailureCategory.DIRECTORY)
                }
                require(root.isAbsolute && root.normalize() == root)
                root.parent?.let { requireAllowed(); Files.createDirectories(it) }
                requireAllowed()
                val created = try { Files.createDirectory(root); true } catch (_: FileAlreadyExistsException) { false }
                requireAllowed()
                val canonical = root.toRealPath()
                requireAllowed()
                channel = FileChannel.open(root.resolve("client.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                requireAllowed()
                lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) throw SetupFailure("数据目录正在使用", "请先关闭使用同一数据目录的 nngGram 窗口。", FailureCategory.DIRECTORY)
                requireAllowed()
                val rootId = runCatching { com.badnng.moe.media.stableMediaFileIdentity(canonical) }.getOrNull()
                requireAllowed()
                val lockId = runCatching { com.badnng.moe.media.stableMediaFileIdentity(root.resolve("client.lock")) }.getOrNull()
                requireAllowed()
                if (root.toRealPath() != canonical) throw IOException("Root changed while acquiring the lock")
                return DataRootLease(root, canonical, rootId, lockId, created, channel, lock)
            } catch (error: Exception) {
                try { lock?.release() } catch (_: Exception) { }
                try { channel?.close() } catch (_: Exception) { }
                throw if (error is SetupFailure) error else SetupFailure(
                    "无法准备数据目录", "请检查数据目录权限、磁盘空间和目录锁。", FailureCategory.DIRECTORY,
                )
            }
        }
    }
}

class DataDirectoryLease internal constructor(
    val database: Path,
    val files: Path,
    private val release: () -> Unit,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        release()
        closed = true
    }

    companion object {
        fun acquire(root: Path): DataDirectoryLease = acquire(root) { true }

        internal fun acquire(root: Path, allowed: () -> Boolean): DataDirectoryLease {
            if (!allowed()) throw SetupFailure("无法准备数据目录", "本地保护状态已变化，请解锁后重试。", FailureCategory.DIRECTORY)
            val owner = DataRootLease.acquire(root.toAbsolutePath().normalize(), allowed)
            try {
                val borrowed = owner.prepareDirectories(allowed)
                return DataDirectoryLease(borrowed.database, borrowed.files) { owner.close() }
            } catch (error: Exception) {
                try { owner.close() } catch (_: Exception) { }
                throw error
            }
        }
    }
}
