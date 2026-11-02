package com.badnng.moe.tdlib

import com.badnng.moe.status.FailureCategory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class NativeBundleTest {
    @Test
    fun pinnedJavaCommitCanBeReadBeforeLoadingJni() {
        val pin = java.util.Properties().apply {
            NativeBundleTest::class.java.getResourceAsStream("/tdlib-source.properties")!!.use(::load)
        }
        assertEquals(pin.getProperty("commit"), NativeBundle.bindingCommit())
    }

    @Test
    fun missingDependencyIdentifiesTheFileBeforeLoadingNativeCode() {
        val directory = fixture()
        Files.delete(directory.resolve(profile().dependencyName))
        val failure = assertFailsWith<SetupFailure> { validate(directory) }
        assertEquals(FailureCategory.NATIVE, failure.category)
        assertTrue(failure.summary.contains(profile().dependencyName))
    }

    @Test
    fun x86DllIsRejectedWithAnArchitectureExplanation() {
        val directory = fixture(machine = wrongMachine())
        val failure = assertFailsWith<SetupFailure> { validate(directory) }
        assertTrue(failure.summary.contains("不是"))
    }

    @Test
    fun mismatchedJavaJarIsRejectedBeforeJniCanAbortTheProcess() {
        val directory = fixture()
        Files.writeString(directory.resolve("tdlib.jar"), "different binding")
        val failure = assertFailsWith<SetupFailure> { validate(directory) }
        assertTrue(failure.summary.contains("校验失败：tdlib.jar"))
    }

    @Test
    fun validBundlePreservesDependencyLoadOrder() {
        val directory = fixture()
        assertEquals(listOf(profile().dependencyName, profile().jniLibrary), validate(directory).map { it.fileName.toString() })
    }

    @Test
    fun nativeImageRejectsAnExternalBindingDifferentFromItsCompiledBinding() {
        val directory = fixture()
        val failure = assertFailsWith<SetupFailure> {
            NativeBundleValidator("fixture-commit", "0".repeat(64)).validate(directory, directory.resolve("tdlib.jar"))
        }
        assertTrue(failure.summary.contains("绑定校验信息不匹配"))
        val hash = java.util.Properties().apply {
            Files.newBufferedReader(directory.resolve("manifest.properties")).use(::load)
        }.getProperty("sha256.tdlib.jar")
        assertEquals(2, NativeBundleValidator("fixture-commit", hash).validate(directory, directory.resolve("tdlib.jar")).size)
    }

    @Test
    fun nativeImageLocatesASiblingBundleWithoutAJarCodeSourceOrWorkingDirectory() {
        val directory = fixture()
        assertEquals(directory, NativeBundlePaths.locate(null, null, null, directory.resolve("nngGram.exe")))
        assertEquals(directory, NativeBundlePaths.locate(null, directory.toString(), null, null))
        assertEquals(directory, NativeBundlePaths.locate(directory.toString(), null, null, null))
        assertFailsWith<SetupFailure> { NativeBundlePaths.locate("relative/native", null, null, null) }
        assertFailsWith<SetupFailure> { NativeBundlePaths.locate(null, null, null, null) }
    }

    @Test
    fun malformedManifestHasANativeCategoryWithoutEchoingItsContents() {
        val directory = fixture()
        Files.writeString(directory.resolve("manifest.properties"), "private-value=\\uZZZZ")
        val failure = assertFailsWith<SetupFailure> { validate(directory) }
        assertEquals(FailureCategory.NATIVE, failure.category)
        assertFalse(failure.summary.contains("private-value"))
        assertTrue(failure.recovery.isNotBlank())
    }

    private fun validate(path: Path) = NativeBundleValidator("fixture-commit").validate(path, path.resolve("tdlib.jar"))


    private fun profile() = HostPlatform.current.nativeProfile

    private fun wrongMachine() = when (profile()) {
        NativeLibraryProfile.WINDOWS_X64 -> 0x14c
        NativeLibraryProfile.MACOS_X64 -> 0x0100000c
        NativeLibraryProfile.MACOS_ARM64 -> 0x01000007
        NativeLibraryProfile.LINUX_X64 -> 183
        NativeLibraryProfile.LINUX_ARM64 -> 62
    }

    private fun fixture(machine: Int? = null): Path {
        val directory = Files.createTempDirectory("native-fixture-")
        val current = profile()
        val image = nativeImage(current, machine ?: expectedMachine(current))
        Files.writeString(directory.resolve("tdlib.jar"), "fixture binding")
        Files.write(directory.resolve(current.dependencyName), image)
        Files.write(directory.resolve(current.jniLibrary), image)
        val properties = StringBuilder(
            "source.commit=fixture-commit\narchitecture=${current.architecture}\n" +
                "binding.loader.patch=absolute-path-v1\nload.order=${current.dependencyName},${current.jniLibrary}\n",
        )
        for (file in listOf("tdlib.jar", current.dependencyName, current.jniLibrary)) {
            val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve(file))).joinToString("") { "%02x".format(it) }
            properties.append("sha256.$file=$hash\n")
        }
        Files.writeString(directory.resolve("manifest.properties"), properties.toString())
        return directory
    }
}

class HostPlatformTest {
    @Test fun detectsDesktopHostsWithoutTreatingAndroidAsADesktopJvm() {
        assertEquals(HostPlatform.WINDOWS_X64, HostPlatform.detect("Windows 11", "amd64"))
        assertEquals(HostPlatform.MACOS_ARM64, HostPlatform.detect("Mac OS X", "aarch64"))
        assertEquals(HostPlatform.MACOS_X64, HostPlatform.detect("Mac OS X", "x86_64"))
        assertEquals(HostPlatform.LINUX_X64, HostPlatform.detect("Linux", "amd64"))
        assertEquals(HostPlatform.LINUX_ARM64, HostPlatform.detect("Linux", "aarch64"))
        assertFailsWith<IllegalStateException> { HostPlatform.detect("Linux", "x86") }
        assertFailsWith<IllegalStateException> { HostPlatform.detect("Android", "aarch64") }
    }
}

private fun expectedMachine(profile: NativeLibraryProfile) = when (profile) {
    NativeLibraryProfile.WINDOWS_X64 -> 0x8664
    NativeLibraryProfile.MACOS_X64 -> 0x01000007
    NativeLibraryProfile.MACOS_ARM64 -> 0x0100000c
    NativeLibraryProfile.LINUX_X64 -> 62
    NativeLibraryProfile.LINUX_ARM64 -> 183
}

private fun nativeImage(profile: NativeLibraryProfile, machine: Int): ByteArray = when (profile) {
    NativeLibraryProfile.WINDOWS_X64 -> ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0, 'M'.code.toByte()); put(1, 'Z'.code.toByte())
        putInt(0x3c, 64); putInt(64, 0x4550); putShort(68, machine.toShort())
    }.array()
    NativeLibraryProfile.MACOS_X64, NativeLibraryProfile.MACOS_ARM64 -> ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0, 0xcffaedfe.toInt()); putInt(4, Integer.reverseBytes(machine))
    }.array()
    NativeLibraryProfile.LINUX_X64, NativeLibraryProfile.LINUX_ARM64 -> ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0, 0x7f); put(1, 'E'.code.toByte()); put(2, 'L'.code.toByte()); put(3, 'F'.code.toByte())
        put(4, 2); put(5, 1); putShort(18, machine.toShort())
    }.array()
}
