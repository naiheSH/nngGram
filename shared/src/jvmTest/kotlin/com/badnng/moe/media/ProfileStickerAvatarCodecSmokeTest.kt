package com.badnng.moe.media

import androidx.compose.ui.graphics.ImageBitmap
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.tdlib.*
import org.jetbrains.skia.*
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.test.*

/** Only generated local pixels. Run explicitly with -PmediaSmoke and isolated test directories. */
class ProfileStickerAvatarCodecSmokeTest {
    @Test fun webpPngAndJpegDecodeActualColorAndNativeRepaintFlag() {
        smokeOnly()
        val pixelBudget = ProfileStickerAvatarPixelBudget()
        val codec = SkiaProfileStickerAvatarCodec(pixels = pixelBudget, ffmpeg = { error("Static artwork must not use FFmpeg") })
        val permit = ArtworkWorkerPermit {}
        try {
            for (format in listOf(EncodedImageFormat.WEBP, EncodedImageFormat.PNG, EncodedImageFormat.JPEG)) {
                val bytes = syntheticImage(format)
                val encodedBudget = ProfileStickerAvatarEncodedBudget()
                val native = encoded(bytes, if (format == EncodedImageFormat.WEBP) MediaFormat.WEBP else MediaFormat.PHOTO,
                    8, 4, ProfileStickerAvatarMediaRole.THUMBNAIL, repaint = format == EncodedImageFormat.PNG, budget = encodedBudget)
                try {
                    codec.thumbnail(native, 16, { true }, permit).use { frame ->
                        assertEquals(format == EncodedImageFormat.PNG, frame.needsRepainting)
                        assertEquals(0, frame.image.pixel(8, 0))
                        assertColorNear(0xffff4010.toInt(), frame.image.pixel(8, 8), 15)
                        assertTrue(pixelBudget.used() > 0)
                    }
                    assertEquals(0L, pixelBudget.used())
                } finally { native.close() }
                assertEquals(0L, encodedBudget.used())
            }
        } finally { permit.release() }
    }

    @Test fun tgsKeepsOneRealClipAndRendersWithoutIdleWork() {
        smokeOnly()
        val pixelBudget = ProfileStickerAvatarPixelBudget()
        val encodedBudget = ProfileStickerAvatarEncodedBudget()
        val source = encoded(gzip(lottie()), MediaFormat.TGS, 16, 16,
            ProfileStickerAvatarMediaRole.FULL, true, encodedBudget)
        val permit = ArtworkWorkerPermit {}
        try {
            val codec = SkiaProfileStickerAvatarCodec(pixels = pixelBudget, ffmpeg = { error("TGS must not use FFmpeg") })
            val clip = codec.open(source, 16, { true }, permit)
            source.close()
            try {
                assertTrue(clip.animated); assertEquals(1000L, clip.durationMillis)
                assertEquals(33L, clip.frameDurationMillis); assertEquals(0L, pixelBudget.used())
                clip.frame(0, { true }, permit).use { frame ->
                    assertTrue(frame.needsRepainting)
                    assertColorNear(0xff00ff00.toInt(), frame.image.pixel(8, 8), 2)
                }
                assertEquals(0L, pixelBudget.used())
                clip.frame(500, { true }, permit).close()
            } finally { clip.close() }
            assertEquals(0L, encodedBudget.used())
        } finally { source.close(); permit.release() }
    }

    @Test fun webmDecodesOnceToOwnedSilentClipAndReadsMultipleFrames() {
        smokeOnly()
        val executable = MediaDecoder.findFfmpeg()
        assumeTrue("Bundled synthetic FFmpeg/FFprobe are required", executable != null)
        val folder = Files.createTempDirectory("synthetic-avatar-codec-")
        val raw = folder.resolve("source.rgba"); val video = folder.resolve("source.webm")
        val commands = CopyOnWriteArrayList<List<String>>()
        val disk = ProfileStickerAvatarDiskBudget(); val pixels = ProfileStickerAvatarPixelBudget()
        val encodedBudget = ProfileStickerAvatarEncodedBudget()
        val permit = ArtworkWorkerPermit {}
        try {
            val rgba = ByteArray(8 * 4 * 4 * 5) { index ->
                val left = index / 4 % 8 < 4
                when (index % 4) { 0 -> if (left) 255 else 0; 2 -> if (left) 0 else 255
                    3 -> if (left) 128 else 0; else -> 0 }.toByte()
            }
            Files.write(raw, rgba)
            MediaTools.capture(checkNotNull(executable), listOf("-nostdin", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", "8x4", "-framerate", "10", "-i", raw.toString(),
                "-an", "-sn", "-dn", "-c:v", "libvpx-vp9", "-threads", "1", "-lossless", "1", "-auto-alt-ref", "0",
                "-pix_fmt", "yuva420p", "-y", video.toString()))
            val native = encoded(Files.readAllBytes(video), MediaFormat.WEBM, 8, 4,
                ProfileStickerAvatarMediaRole.FULL, false, encodedBudget)
            val codec = SkiaProfileStickerAvatarCodec(pixels, disk, { executable },
                ProfileStickerAvatarProcesses(start = { command ->
                    commands += command.toList()
                    ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                }))
            val clip = try { codec.open(native, 4, { true }, permit) } finally { native.close() }
            try {
                assertTrue(clip.animated); assertTrue(clip.durationMillis in 450..550)
                assertEquals(100L, clip.frameDurationMillis)
                clip.frame(0, { true }, permit).use { frame ->
                    assertEquals(0, frame.image.pixel(0, 0))
                    assertColorNear(0x80ff0000.toInt(), frame.image.pixel(0, 1), 12)
                    assertEquals(0, frame.image.pixel(3, 1))
                }
                clip.frame(200, { true }, permit).close()
                clip.frame(400, { true }, permit).close()
                assertEquals(2, commands.size, "One probe and one extraction for all frames")
                val extraction = commands.single { "rawvideo" in it }
                assertTrue(extraction.containsAll(listOf("-an", "-sn", "-dn")))
                assertTrue(extraction.none { "scale=" in it || "pad=" in it })
                assertTrue(disk.used() > 0)
            } finally { clip.close() }
            await { disk.used() == 0L && encodedBudget.used() == 0L }
            assertEquals(0L, pixels.used()); assertEquals(0L, encodedBudget.used())
            assertTrue(Files.isRegularFile(video), "The source fixture is not scratch ownership")
        } finally {
            permit.release()
            Files.deleteIfExists(raw); Files.deleteIfExists(video); Files.deleteIfExists(folder)
        }
    }

    @Test fun vfrWebmUsesRealFrameTimestampsAndNotLongerAudioContainerTail() {
        smokeOnly()
        val executable = MediaDecoder.findFfmpeg()
        assumeTrue("Bundled synthetic FFmpeg/FFprobe are required", executable != null)
        val binary = checkNotNull(executable)
        val folder = Files.createTempDirectory("synthetic-vfr-avatar-")
        val raw = folder.resolve("vfr.rgba"); val video = folder.resolve("vfr.webm")
        val disk = ProfileStickerAvatarDiskBudget(); val pixels = ProfileStickerAvatarPixelBudget()
        val encodedBudget = ProfileStickerAvatarEncodedBudget(); val permit = ArtworkWorkerPermit {}
        try {
            val bytes = ByteArray(8 * 4 * 4 * 3) { index ->
                val frame = index / (8 * 4 * 4)
                when (index % 4) { 0 -> if (frame == 0) 255 else 0
                    1 -> if (frame == 1) 255 else 0; 2 -> if (frame == 2) 255 else 0; else -> 255 }.toByte()
            }
            Files.write(raw, bytes)
            MediaTools.capture(binary, listOf("-nostdin", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", "8x4", "-framerate", "10", "-i", raw.toString(),
                "-f", "lavfi", "-i", "anullsrc=channel_layout=mono:sample_rate=48000:duration=2",
                "-map", "0:v:0", "-map", "1:a:0",
                "-vf", "setpts=if(eq(N\\,0)\\,0\\,if(eq(N\\,1)\\,0.1\\,0.4))/TB", "-fps_mode", "passthrough",
                "-sn", "-dn", "-c:v", "libvpx-vp9", "-threads", "1", "-lossless", "1", "-lag-in-frames", "0",
                "-auto-alt-ref", "0", "-pix_fmt", "yuva420p", "-c:a", "libopus", "-t", "2", "-y", video.toString()))
            val probe = binary.resolveSibling(if (binary.fileName.toString().endsWith(".exe", true)) "ffprobe.exe" else "ffprobe")
            val containerDuration = MediaTools.capture(probe, listOf("-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", video.toString())).trim().toDouble()
            assertTrue(containerDuration > 1.5, "Synthetic audio makes the container materially longer than video")
            val native = encoded(Files.readAllBytes(video), MediaFormat.WEBM, 8, 4,
                ProfileStickerAvatarMediaRole.FULL, false, encodedBudget)
            val codec = SkiaProfileStickerAvatarCodec(pixels, disk, { binary })
            val clip = try { codec.open(native, 8, { true }, permit) } finally { native.close() }
            try {
                assertTrue(clip.animated)
                assertTrue(clip.durationMillis in 490..510, "Audio tail must not become the last frame duration")
                clip.frame(50, { true }, permit).use { assertColorNear(0xffff0000.toInt(), it.image.pixel(4, 4), 20) }
                clip.frame(150, { true }, permit).use { assertColorNear(0xff00ff00.toInt(), it.image.pixel(4, 4), 20) }
                clip.frame(350, { true }, permit).use { assertColorNear(0xff00ff00.toInt(), it.image.pixel(4, 4), 20) }
                clip.frame(450, { true }, permit).use { assertColorNear(0xff0000ff.toInt(), it.image.pixel(4, 4), 20) }
            } finally { clip.close() }
            await { disk.used() == 0L && encodedBudget.used() == 0L }
            assertEquals(0L, pixels.used())
        } finally {
            permit.release(); Files.deleteIfExists(raw); Files.deleteIfExists(video); Files.deleteIfExists(folder)
        }
    }

    @Test fun singleFrameWebmIsAStaticClipWithRealDecodedPixels() {
        smokeOnly()
        val executable = MediaDecoder.findFfmpeg()
        assumeTrue("Bundled synthetic FFmpeg/FFprobe are required", executable != null)
        val binary = checkNotNull(executable)
        val folder = Files.createTempDirectory("synthetic-single-avatar-")
        val raw = folder.resolve("single.rgba"); val video = folder.resolve("single.webm")
        val disk = ProfileStickerAvatarDiskBudget(); val pixels = ProfileStickerAvatarPixelBudget()
        val encodedBudget = ProfileStickerAvatarEncodedBudget(); val permit = ArtworkWorkerPermit {}
        try {
            val rgba = ByteArray(8 * 4 * 4) { index ->
                when (index % 4) { 0, 3 -> 255; else -> 0 }.toByte()
            }
            Files.write(raw, rgba)
            MediaTools.capture(binary, listOf("-nostdin", "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", "8x4", "-framerate", "10", "-i", raw.toString(),
                "-frames:v", "1", "-an", "-sn", "-dn", "-c:v", "libvpx-vp9", "-threads", "1", "-lossless", "1",
                "-auto-alt-ref", "0", "-pix_fmt", "yuva420p", "-y", video.toString()))
            val native = encoded(Files.readAllBytes(video), MediaFormat.WEBM, 8, 4,
                ProfileStickerAvatarMediaRole.FULL, false, encodedBudget)
            val codec = SkiaProfileStickerAvatarCodec(pixels, disk, { binary })
            val clip = try { codec.open(native, 8, { true }, permit) } finally { native.close() }
            try {
                assertFalse(clip.animated); assertEquals(0L, clip.durationMillis); assertEquals(0L, clip.frameDurationMillis)
                clip.frame(0, { true }, permit).use { assertColorNear(0xffff0000.toInt(), it.image.pixel(4, 4), 20) }
                clip.frame(999, { true }, permit).use { assertColorNear(0xffff0000.toInt(), it.image.pixel(4, 4), 20) }
            } finally { clip.close() }
            await { disk.used() == 0L && encodedBudget.used() == 0L }
            assertEquals(0L, pixels.used())
        } finally {
            permit.release(); Files.deleteIfExists(raw); Files.deleteIfExists(video); Files.deleteIfExists(folder)
        }
    }

    private fun syntheticImage(format: EncodedImageFormat): ByteArray {
        val rgba = ByteArray(8 * 4 * 4) { index -> when (index % 4) {
            0 -> 255; 1 -> 64; 2 -> 16; else -> 255
        }.toByte() }
        val info = ImageInfo(8, 4, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB)
        return Image.makeRaster(info, rgba, 32).use { image -> checkNotNull(image.encodeToData(format)).use { it.bytes } }
    }
    private fun encoded(bytes: ByteArray, format: MediaFormat, width: Int, height: Int,
        role: ProfileStickerAvatarMediaRole, repaint: Boolean, budget: ProfileStickerAvatarEncodedBudget): ProfileStickerAvatarEncoded {
        val file = MediaFile(1, bytes.size.toLong(), uniqueId = "synthetic")
        val asset = ProfileStickerAvatarAsset(file, format, width, height)
        return ProfileStickerAvatarEncoded.take(ProfileStickerAvatarOwnedMedia(role, asset, repaint), bytes,
            checkNotNull(budget.reserve(bytes.size.toLong())))
    }
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(text.encodeToByteArray()) }
    }.toByteArray()
    private fun lottie() = """{"v":"5.5.2","fr":30,"ip":0,"op":30,"w":16,"h":16,"ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":4,"nm":"synthetic","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":0,"k":[0,0,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},"ao":0,"shapes":[{"ty":"rc","d":1,"s":{"a":0,"k":[16,16]},"p":{"a":0,"k":[8,8]},"r":{"a":0,"k":0}},{"ty":"fl","c":{"a":0,"k":[0,1,0,1]},"o":{"a":0,"k":100}}],"ip":0,"op":30,"st":0,"bm":0}]}"""
    private fun ImageBitmap.pixel(x: Int, y: Int): Int = IntArray(width * height).also { readPixels(it) }[y * width + x]
    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int) {
        for (shift in listOf(24, 16, 8, 0)) assertTrue(
            abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= tolerance,
            "Expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}")
    }
    private fun smokeOnly() = assumeTrue("Run synthetic codec tests with -PmediaSmoke", !System.getProperty("nnggram.ffmpeg.path").isNullOrBlank())
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) { check(System.nanoTime() < end); Thread.sleep(5) }
    }
}
