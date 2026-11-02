package com.badnng.moe.media

import com.badnng.moe.chat.MediaFormat
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.skottie.Animation
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

internal sealed class DecodedMedia : AutoCloseable {
    abstract val width: Int
    abstract val height: Int
    open val durationMillis: Long = 0
    open val animationSchedule: MediaAnimationSchedule? = null
    abstract fun draw(canvas: Canvas, destination: Rect, timeMillis: Long)
    /** Called on a worker with the complete image's intended physical display size. */
    open fun prepareForSize(widthPx: Int, heightPx: Int) = Unit
    /** Compatibility entry point: every raster now uses the same smooth scale-aware sampler. */
    open fun drawHighQuality(canvas: Canvas, destination: Rect, timeMillis: Long) = draw(canvas, destination, timeMillis)

    class Still(private val image: Image) : DecodedMedia() {
        private val raster = AreaRasterImage(image)
        override val width = image.width
        override val height = image.height
        override fun prepareForSize(widthPx: Int, heightPx: Int) = raster.prepare(widthPx, heightPx)
        override fun draw(canvas: Canvas, destination: Rect, timeMillis: Long) = raster.draw(canvas, destination)
        override fun close() = raster.close()
    }

    class Vector(private val animation: Animation) : DecodedMedia() {
        override val width = animation.width.toInt()
        override val height = animation.height.toInt()
        override val durationMillis = (animation.duration * 1_000).toLong().coerceAtLeast(1)
        override val animationSchedule = MediaAnimationSchedule(durationMillis, animation.fPS.toDouble())
        override fun draw(canvas: Canvas, destination: Rect, timeMillis: Long) {
            animation.seek((timeMillis % durationMillis).toFloat() / durationMillis)
            animation.render(canvas, destination)
        }
        override fun close() = animation.close()
    }

    class Frames(private val frames: List<Image>) : DecodedMedia() {
        private val rasters = frames.map { AreaRasterImage(it, MediaDerivedPixelBudget / frames.size.coerceAtLeast(1)) }
        override val width = frames.first().width
        override val height = frames.first().height
        override val durationMillis = frames.size * 50L
        override val animationSchedule = MediaAnimationSchedule(durationMillis, 20.0)
        override fun prepareForSize(widthPx: Int, heightPx: Int) = rasters.forEach { it.prepare(widthPx, heightPx) }
        override fun draw(canvas: Canvas, destination: Rect, timeMillis: Long) {
            rasters[((timeMillis % durationMillis) / 50).toInt()].draw(canvas, destination)
        }
        override fun close() = rasters.forEach(AreaRasterImage::close)
    }
}

/** Bounded decoders. Errors are converted to fixed UI text, never logged with paths or content. */
internal object MediaDecoder {
    private const val MAX_FILE_BYTES = 24 * 1024 * 1024
    private const val MAX_PIXELS = 24_000_000L
    private val decoderSlots = java.util.concurrent.Semaphore(2)
    private val processTimeouts = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "sticker-decoder-timeout").apply { isDaemon = true }
    }

    fun decode(path: String, format: MediaFormat, fitzpatrickType: Int = 0): DecodedMedia {
        val file = verifiedFile(path)
        return when (format) {
            MediaFormat.PHOTO, MediaFormat.WEBP -> decodeImage(Files.readAllBytes(file))
            MediaFormat.TGS -> {
                val bytes = Files.newInputStream(file).use { stream ->
                    GZIPInputStream(stream).use { it.readNBytes(2 * 1024 * 1024 + 1) }
                }
                require(bytes.size <= 2 * 1024 * 1024) { "Unsupported animation" }
                val animation = Data.makeFromBytes(animatedEmojiLottieBytes(bytes, fitzpatrickType)).use(Animation::makeFromData)
                try {
                    require(animation.width in 1f..2048f && animation.height in 1f..2048f &&
                        animation.duration.isFinite() && animation.duration in 0.01f..60f) { "Unsupported animation" }
                    DecodedMedia.Vector(animation)
                } catch (error: Exception) {
                    animation.close()
                    throw error
                }
            }
            MediaFormat.WEBM -> decodeVideo(file)
            MediaFormat.VIDEO, MediaFormat.ANIMATION, MediaFormat.FILE -> error("Use the attachment viewer")
        }
    }

    /** Reaction-grid video rasters cover high-DPI cells without retaining full sticker-size animations. */
    fun decodeReaction(path: String, format: MediaFormat, firstFrame: Boolean): DecodedMedia =
        decodeGridPreview(path, format, firstFrame, edge = 96)

    /** Larger composer cells keep their own bounded raster size; full message stickers retain 256 px. */
    fun decodeGridPreview(path: String, format: MediaFormat, firstFrame: Boolean, edge: Int): DecodedMedia =
        if (format == MediaFormat.WEBM) {
            require(edge in 32..256) { "Unsupported preview size" }
            decodeVideo(verifiedFile(path), maxFrames = if (firstFrame) 1 else 60, edge = edge)
        } else decode(path, format)

    private fun verifiedFile(path: String): Path = Path.of(path).also { file ->
        require(Files.isRegularFile(file) && Files.size(file) in 1..MAX_FILE_BYTES.toLong()) { "Unsupported media" }
    }

    private fun decodeImage(bytes: ByteArray): DecodedMedia {
        Data.makeFromBytes(bytes).use { data ->
            Codec.makeFromData(data).use { codec ->
                require(codec.width > 0 && codec.height > 0 && codec.width.toLong() * codec.height <= MAX_PIXELS) { "Image too large" }
            }
        }
        return DecodedMedia.Still(Image.makeFromEncoded(bytes))
    }

    /** Telegram video stickers are VP9 WebM, up to 3 seconds. Decode small transparent frames off the UI thread. */
    private fun decodeVideo(file: Path, maxFrames: Int = 60, edge: Int = 256): DecodedMedia {
        decoderSlots.acquire()
        try {
            val executable = findFfmpeg() ?: error("Video decoder unavailable")
            val command = listOf(executable.toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
                "-protocol_whitelist", "file,pipe", "-threads", "1", "-c:v", "libvpx-vp9", "-i", file.toAbsolutePath().toString(),
                "-t", "3", "-an", "-vf", "fps=20,scale=$edge:$edge:force_original_aspect_ratio=decrease,pad=$edge:$edge:(ow-iw)/2:(oh-ih)/2:color=black@0",
                "-pix_fmt", "rgba", "-threads", "1", "-frames:v", maxFrames.toString(), "-f", "rawvideo", "pipe:1")
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val deadline = processTimeouts.schedule({ process.destroyForcibly() }, 15, TimeUnit.SECONDS)
            val frames = mutableListOf<Image>()
            try {
                process.outputStream.close()
                val frameSize = edge * edge * 4
                process.inputStream.use { stream ->
                    while (frames.size < maxFrames) {
                        val rgba = stream.readNBytes(frameSize)
                        if (rgba.isEmpty()) break
                        require(rgba.size == frameSize) { "Invalid video frame" }
                        premultiplyMediaRgba(rgba)
                        val info = ImageInfo(edge, edge, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
                        frames += Image.makeRaster(info, rgba, edge * 4)
                    }
                }
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                require(frames.isNotEmpty() && process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) { "Invalid video sticker" }
                return DecodedMedia.Frames(frames)
            } catch (error: Exception) {
                frames.forEach(Image::close)
                throw error
            } finally {
                deadline.cancel(false)
                process.destroyForcibly()
            }
        } finally {
            decoderSlots.release()
        }
    }

    internal fun findFfmpeg(): Path? {
        val tool = com.badnng.moe.platform.DesktopPlatform.current.executable("ffmpeg")
        val candidates = mutableListOf<Path>()
        System.getProperty("nnggram.ffmpeg.path")?.takeIf(String::isNotBlank)?.let { candidates.add(Path.of(it)) }
        System.getenv("NNGGRAM_FFMPEG_PATH")?.takeIf(String::isNotBlank)?.let { candidates.add(Path.of(it)) }
        System.getProperty("compose.application.resources.dir")?.let { candidates.add(Path.of(it, "media", tool)) }
        System.getProperty("nnggram.tdlib.dir")?.let { candidates.add(Path.of(it, "media", tool)) }
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") {
            ProcessHandle.current().info().command().orElse(null)?.let { executable ->
                Path.of(executable).toAbsolutePath().parent?.let { directory ->
                    candidates.add(directory.resolve("media/$tool"))
                    candidates.add(directory.resolve("resources/media/$tool"))
                    candidates.add(directory.resolve("app/resources/media/$tool"))
                }
            }
        }
        System.getenv("PATH")?.split(java.io.File.pathSeparator)?.filter(String::isNotBlank)?.forEach {
            runCatching { candidates.add(Path.of(it, tool)) }
        }
        return candidates.firstOrNull(Files::isRegularFile)
    }
}
