package com.badnng.moe.tdlib

import com.badnng.moe.status.NativeInfo
import com.badnng.moe.status.FailureCategory
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

internal class NativeBundleValidator(
    private val expectedCommit: String,
    private val embeddedBindingHash: String? = null,
) {
    fun validate(directory: Path, bindingJar: Path): List<Path> = nativeFileOperation {
        validateFiles(directory, bindingJar)
    }

    private fun validateFiles(directory: Path, bindingJar: Path): List<Path> {
        val manifest = directory.resolve("manifest.properties")
        if (!Files.isRegularFile(manifest)) fail("缺少 TDLib 原生文件清单", "请重新构建或完整复制当前平台的 native 目录。")
        val properties = Properties().apply { Files.newBufferedReader(manifest).use(::load) }
        val profile = HostPlatform.current.nativeProfile
        if (properties.getProperty("source.commit") != expectedCommit ||
            properties.getProperty("architecture") != profile.architecture ||
            properties.getProperty("binding.loader.patch") != "absolute-path-v1") {
            fail("TDLib 构建来源、架构或绑定不匹配", "请使用本项目脚本为当前平台完整构建原生包。")
        }
        if (embeddedBindingHash != null && (!embeddedBindingHash.matches(Regex("[a-f0-9]{64}")) ||
                properties.getProperty("sha256.tdlib.jar") != embeddedBindingHash)) {
            fail("原生程序与 TDLib 绑定校验信息不匹配", "请重新生成 Native Image 元数据，并将同一原生包随应用一起分发。")
        }
        val names = properties.getProperty("load.order", "").split(',')
        val library = Regex(profile.libraryPattern)
        if (names.lastOrNull() != profile.jniLibrary || names.any { !it.matches(library) } || names.distinct().size != names.size) {
            fail("TDLib 文件清单无效", "请重新构建原生文件，勿混合不同构建的动态库。")
        }
        fun verifyHash(path: Path, name: String) {
            if (!Files.isRegularFile(path)) fail("缺少原生依赖：$name", "请恢复完整的 ${profile.bundleDirectoryName} 目录。")
            val expected = properties.getProperty("sha256.$name")
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) fail("TDLib 文件校验失败：$name", "请重新构建，确保 Java 绑定与动态库来自同一次构建。")
        }
        verifyHash(bindingJar, "tdlib.jar")
        return names.map { name ->
            directory.resolve(name).also { file ->
                if (!Files.isRegularFile(file)) fail("缺少原生依赖：$name", "请恢复完整的 ${profile.bundleDirectoryName} 目录。")
                profile.verify(file)
                verifyHash(file, name)
            }
        }
    }

    private fun fail(summary: String, recovery: String): Nothing = throw nativeFailure(summary, recovery)
}

object NativeBundle {
    private var loaded: NativeInfo? = null
    private var initializationFailed = false

    @Synchronized
    fun load(): NativeInfo = nativeFileOperation { loadBundle() }

    /** Fixed progress identifiers for the explicitly requested, synthetic CLI acceptance path. */
    @Synchronized
    internal fun loadForSmoke(onStage: (NativeBundleStage) -> Unit, onLinkageFailure: (Throwable) -> Unit): NativeInfo =
        nativeFileOperation {
            try {
                loadBundle(onStage, onLinkageFailure)
            } catch (failure: Throwable) {
                // Capture only a whitelisted failure kind before the public file-error wrapper.
                if (failure !is SetupFailure) onLinkageFailure(failure)
                throw failure
            }
        }

    private fun loadBundle(
        onStage: (NativeBundleStage) -> Unit = {},
        onLinkageFailure: (Throwable) -> Unit = {},
    ): NativeInfo {
        loaded?.let { return it }
        onStage(NativeBundleStage.PLATFORM)
        if (initializationFailed) throw nativeFailure("原生库加载曾失败", "修复原生依赖后请重启应用，重新加载绑定。")
        val profile = try {
            HostPlatform.current.nativeProfile
        } catch (_: IllegalStateException) {
            throw nativeFailure("当前平台不受支持", "请使用 Windows、macOS 或 Linux 的 x64/arm64 桌面程序，或受支持的 64 位 JVM。")
        }
        onStage(NativeBundleStage.SOURCE_METADATA)
        val expected = Properties().apply {
            NativeBundle::class.java.getResourceAsStream("/tdlib-source.properties")?.use(::load)
                ?: throw nativeFailure("缺少 TDLib 版本约束", "请重新构建应用。")
        }.getProperty("commit") ?: throw nativeFailure("TDLib 版本约束无效", "请重新构建应用。")
        // Class initialization also loads JNI. Read build-derived JAR metadata
        // first so no native code runs before the complete bundle is checked.
        onStage(NativeBundleStage.BINDING_METADATA)
        if (bindingCommit() != expected) {
            throw nativeFailure("Java 绑定版本不匹配", "请使用与固定 TDLib 提交匹配的 tdlib.jar 后重新构建应用。")
        }
        val nativeRuntime = isNativeImageRuntime()
        val jvmJar = if (nativeRuntime) null else bindingJar()
        onStage(NativeBundleStage.LOCATE_BUNDLE)
        val directory = NativeBundlePaths.locate(
            override = System.getProperty("nnggram.tdlib.dir") ?: System.getenv("NNGGRAM_TDLIB_DIR"),
            resources = System.getProperty("compose.application.resources.dir"),
            bindingJar = jvmJar,
            executable = if (nativeRuntime) runCatching {
                ProcessHandle.current().info().command().orElse(null)?.let(Path::of)
            }.getOrNull() else null,
        )
        // Native Image has compiled the binding into the executable; codeSource can be null.
        // The embedded build-time digest ties that binding to the complete external bundle.
        onStage(NativeBundleStage.BINDING_DIGEST)
        val jar = jvmJar ?: directory.resolve("tdlib.jar")
        val embeddedHash = if (nativeRuntime) embeddedBindingMetadata().getProperty("binding.sha256")
            ?: throw nativeFailure("缺少原生程序绑定校验信息", "请重新生成 Native Image 元数据并构建应用。") else null
        onStage(NativeBundleStage.VERIFY_BUNDLE)
        val libraries = NativeBundleValidator(expected, embeddedHash).validate(directory, jar)
        try {
            onStage(NativeBundleStage.LOAD_DEPENDENCIES)
            libraries.dropLast(1).forEach { System.load(it.toAbsolutePath().toString()) }
            System.setProperty("nnggram.tdlib.library", libraries.last().toAbsolutePath().toString())
            onStage(NativeBundleStage.INITIALIZE_JNI)
            Client.execute(TdApi.SetLogStream(TdApi.LogStreamEmpty()))
            onStage(NativeBundleStage.DISABLE_LOGGING)
            Client.execute(TdApi.SetLogVerbosityLevel(0))
            onStage(NativeBundleStage.READ_VERSION)
            val version = (Client.execute(TdApi.GetOption("version")) as TdApi.OptionValueString).value
            onStage(NativeBundleStage.READ_COMMIT)
            val commit = (Client.execute(TdApi.GetOption("commit_hash")) as TdApi.OptionValueString).value
            onStage(NativeBundleStage.VERIFY_COMMIT)
            if (commit != expected) throw nativeFailure("原生库报告了不同的提交", "请重新构建完整原生包并重启应用。")
            return NativeInfo(version, commit, directory.toString()).also { loaded = it }
        } catch (error: LinkageError) {
            initializationFailed = true
            onLinkageFailure(error)
            throw nativeFailure("${HostPlatform.current.displayName} 无法加载 TDLib 依赖", "请检查动态库依赖与 64 位 JVM 是否完整，修复后重启应用（${error.javaClass.simpleName}）。")
        }
    }

    internal fun bindingCommit(): String {
        val metadata = if (isNativeImageRuntime()) embeddedBindingMetadata() else java.util.zip.ZipFile(bindingJar().toFile()).use { zip ->
            val entry = zip.getEntry("META-INF/nnggram-tdlib.properties")
                ?: throw nativeFailure("Java 绑定缺少版本信息", "请使用本项目脚本重新构建官方 Java 绑定。")
            Properties().apply { zip.getInputStream(entry).use(::load) }
        }
        if (metadata.getProperty("loader.patch") != "absolute-path-v1") {
            throw nativeFailure("Java 绑定加载器不匹配", "请使用本项目脚本重新构建原生包。")
        }
        return metadata.getProperty("java.api.commit")
            ?: throw nativeFailure("Java 绑定版本信息无效", "请重新构建原生包。")
    }

    internal fun embeddedBindingMetadata(): Properties = Properties().apply {
        NativeBundle::class.java.getResourceAsStream("/tdlib-native-image.properties")?.use(::load)
            ?: throw nativeFailure("缺少 Native Image 绑定信息", "请运行固定绑定的元数据生成脚本后重新构建应用。")
    }

    private fun bindingJar(): Path {
        val location = TdApi::class.java.protectionDomain?.codeSource?.location
            ?: throw nativeFailure("无法确定 Java 绑定位置", "请使用完整的应用分发包。")
        return Path.of(location.toURI())
    }
}

internal enum class NativeBundleStage {
    PLATFORM, SOURCE_METADATA, BINDING_METADATA, LOCATE_BUNDLE, BINDING_DIGEST, VERIFY_BUNDLE,
    LOAD_DEPENDENCIES, INITIALIZE_JNI, DISABLE_LOGGING, READ_VERSION, READ_COMMIT, VERIFY_COMMIT,
}

internal fun isNativeImageRuntime() = System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime"

/** Explicit paths first; native executables never rely on a build-machine codeSource or working directory. */
internal object NativeBundlePaths {
    fun locate(override: String?, resources: String?, bindingJar: Path?, executable: Path?): Path {
        if (override != null) {
            val path = Path.of(override)
            if (!path.isAbsolute) throw nativeFailure("原生库路径必须是绝对路径", "请检查 NNGGRAM_TDLIB_DIR 设置。")
            return path.normalize()
        }
        val candidates = buildList {
            resources?.let { add(Path.of(it)) }
            executable?.parent?.let { add(it); add(it.resolve("resources")); add(it.resolve("app/resources")) }
        }
        candidates.firstOrNull { it.isAbsolute && Files.isRegularFile(it.resolve("manifest.properties")) }?.let { return it.normalize() }
        return bindingJar?.parent
            ?: throw nativeFailure("找不到 TDLib 原生包", "请将完整原生包放在程序旁，或设置 NNGGRAM_TDLIB_DIR 绝对路径。")
    }
}

private fun nativeFailure(summary: String, recovery: String) =
    SetupFailure(summary, recovery, FailureCategory.NATIVE)

private inline fun <T> nativeFileOperation(block: () -> T): T = try {
    block()
} catch (failure: SetupFailure) {
    throw failure
} catch (_: IOException) {
    throw nativeFailure("无法读取 TDLib 原生包", "请检查原生目录是否存在、可读且包含完整文件，再重试。")
} catch (_: SecurityException) {
    throw nativeFailure("没有读取 TDLib 原生包的权限", "请将完整原生包放入当前用户可读取的目录。")
} catch (_: IllegalArgumentException) {
    throw nativeFailure("TDLib 路径或清单格式无效", "请检查原生目录设置，必要时重新构建原生包。")
}
