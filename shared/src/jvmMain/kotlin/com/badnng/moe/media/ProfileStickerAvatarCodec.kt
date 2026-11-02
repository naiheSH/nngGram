package com.badnng.moe.media

import androidx.compose.ui.graphics.asComposeImageBitmap
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.tdlib.ProfileStickerAvatarEncoded
import org.jetbrains.skia.*
import org.jetbrains.skia.skottie.Animation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** All prepared and temporary RGBA allocations share this process-wide conservative budget. */
internal class ProfileStickerAvatarPixelBudget(private val limit: Long = 16_000_000L) {
    private val lock = Any()
    private var held = 0L
    fun used(): Long = synchronized(lock) { held }
    fun reserve(pixels: Long): Hold? = synchronized(lock) {
        if (pixels <= 0 || pixels > limit || held > limit - pixels) null
        else { held += pixels; Hold(pixels) }
    }
    inner class Hold(private var pixels: Long) : AutoCloseable {
        fun shrink(to: Long) = synchronized(lock) {
            check(to in 1..pixels); held -= pixels - to; pixels = to
        }
        override fun close() = synchronized(lock) { if (pixels > 0) { held -= pixels; pixels = 0 } }
    }
}

/** Counts reservations, not a periodic directory scan; failed deletion stays charged. */
internal class ProfileStickerAvatarDiskBudget(private val limit: Long = 128L * 1024 * 1024) {
    private val lock = Any()
    private var held = 0L
    fun used(): Long = synchronized(lock) { held }
    fun reserve(bytes: Long): AutoCloseable? = synchronized(lock) {
        if (bytes <= 0 || bytes > limit || held > limit - bytes) null else {
            held += bytes
            val open = AtomicBoolean(true)
            AutoCloseable { if (open.compareAndSet(true, false)) synchronized(lock) { held -= bytes } }
        }
    }
}

private object ProfileStickerAvatarCodecBudgets {
    val pixels = ProfileStickerAvatarPixelBudget()
    val disk = ProfileStickerAvatarDiskBudget()
}

/** Only exact paths made by this instance can be deleted. Child processes hold a reference. */
internal class ProfileStickerAvatarScratch private constructor(
    val directory: Path,
    private val first: AutoCloseable,
    private val encodedCopy: AutoCloseable?,
) {
    val input: Path = directory.resolve("input.webm")
    val frames: Path = directory.resolve("frames.rgba")
    private val lock = Any()
    private var refs = 1
    private var second: AutoCloseable? = null
    private var cleaning = false

    fun addOutputReservation(value: AutoCloseable) = synchronized(lock) {
        check(refs > 0 && second == null); second = value
    }
    fun retain(): Boolean = synchronized(lock) {
        if (refs == 0) false else { refs++; true }
    }
    fun release() {
        val clean = synchronized(lock) {
            check(refs > 0); refs--
            if (refs == 0 && !cleaning) { cleaning = true; true } else false
        }
        if (clean) ProfileStickerAvatarReaper.delete(this)
    }
    fun tryDelete(): Boolean {
        val deleted = runCatching {
            Files.deleteIfExists(input)
            Files.deleteIfExists(frames)
            Files.deleteIfExists(directory)
            true
        }.getOrDefault(false)
        if (deleted) {
            try { first.close() } finally {
                try { synchronized(lock) { second.also { second = null } }?.close() }
                finally { encodedCopy?.close() }
            }
        }
        return deleted
    }
    companion object {
        fun create(inputBytes: Int, budget: ProfileStickerAvatarDiskBudget,
            encodedCopy: AutoCloseable? = null): ProfileStickerAvatarScratch {
            val hold = budget.reserve(inputBytes.toLong()) ?: run { encodedCopy?.close(); throw ProfileStickerAvatarArtworkBusy() }
            try { return ProfileStickerAvatarScratch(Files.createTempDirectory("nnggram-profile-avatar-"), hold, encodedCopy) }
            catch (failure: Throwable) { hold.close(); encodedCopy?.close(); throw failure }
        }
    }
}

/** No destroy request is treated as proof of physical process exit. */
internal object ProfileStickerAvatarReaper {
    private val executor = Executors.newSingleThreadScheduledExecutor { work ->
        Thread(work, "profile-avatar-process-reaper").apply { isDaemon = true }
    }
    fun watch(process: Process, release: () -> Unit) {
        val poll = object : Runnable {
            override fun run() {
                if (runCatching { process.waitFor(0, TimeUnit.MILLISECONDS) }.getOrDefault(false)) release()
                else executor.schedule(this, 50, TimeUnit.MILLISECONDS)
            }
        }
        executor.execute(poll)
    }
    fun delete(scratch: ProfileStickerAvatarScratch) {
        val poll = object : Runnable {
            override fun run() { if (!scratch.tryDelete()) executor.schedule(this, 250, TimeUnit.MILLISECONDS) }
        }
        executor.execute(poll)
    }
}

/** One finite silent extraction per clip; stdout is polled to preserve cancellation. */
internal class ProfileStickerAvatarProcesses(
    private val start: (List<String>) -> Process = { ProcessBuilder(it).redirectError(ProcessBuilder.Redirect.DISCARD).start() },
    private val timeoutMillis: Long = 15_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init { require(timeoutMillis > 0) }
    fun run(command: List<String>, scratch: ProfileStickerAvatarScratch, permit: ArtworkWorkerPermit,
        maximum: Long, active: () -> Boolean, sink: OutputStream, childMemory: AutoCloseable? = null): Long {
        if (maximum !in 0..128L * 1024 * 1024) {
            childMemory?.close()
            throw IllegalArgumentException("Invalid child output limit")
        }
        try { checkCurrent(active) } catch (failure: Throwable) { childMemory?.close(); throw failure }
        if (!scratch.retain()) { childMemory?.close(); throw InterruptedException() }
        if (!permit.retain()) { scratch.release(); childMemory?.close(); throw InterruptedException() }
        val once = AtomicBoolean()
        val releaseChild = {
            if (once.compareAndSet(false, true)) {
                try { childMemory?.close() } finally { try { scratch.release() } finally { permit.release() } }
            }
            Unit
        }
        var process: Process? = null
        try {
            val started = nanoTime()
            val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            fun checkDeadline() {
                checkCurrent(active)
                check(nanoTime() - started < timeoutNanos) { "Codec process timed out" }
            }
            process = start(command)
            checkDeadline()
            process.outputStream.close()
            checkDeadline()
            val buffer = ByteArray(65_536)
            var written = 0L
            while (true) {
                checkDeadline()
                while (true) {
                    checkDeadline()
                    val available = process.inputStream.available()
                    checkDeadline()
                    if (available <= 0) break
                    checkDeadline()
                    val count = process.inputStream.read(buffer, 0, minOf(buffer.size, available))
                    checkDeadline()
                    if (count <= 0) break
                    require(written <= maximum - count)
                    checkDeadline()
                    sink.write(buffer, 0, count)
                    checkDeadline()
                    written += count
                }
                checkDeadline()
                if (process.waitFor(10, TimeUnit.MILLISECONDS)) {
                    checkDeadline()
                    val remaining = process.inputStream.available()
                    checkDeadline()
                    if (remaining > 0) continue
                    check(process.exitValue() == 0)
                    return written
                }
                checkDeadline()
            }
        } finally {
            val child = process
            if (child == null) releaseChild() else {
                val interrupted = Thread.interrupted()
                try {
                    if (runCatching { child.isAlive }.getOrDefault(true)) runCatching { child.destroyForcibly() }
                    runCatching { child.outputStream.close() }
                    runCatching { child.inputStream.close() }
                    runCatching { child.errorStream.close() }
                    if (runCatching { child.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) releaseChild()
                    else ProfileStickerAvatarReaper.watch(child, releaseChild)
                } finally { if (interrupted) Thread.currentThread().interrupt() }
            }
        }
    }
}

private fun checkCurrent(active: () -> Boolean) {
    if (Thread.currentThread().isInterrupted || !runCatching(active).getOrDefault(false)) throw InterruptedException()
}

/** Real Skia static/TGS and silent FFmpeg WebM, with no profile-color authorization or shared asset keys. */
internal class SkiaProfileStickerAvatarCodec(
    private val pixels: ProfileStickerAvatarPixelBudget = ProfileStickerAvatarCodecBudgets.pixels,
    private val disk: ProfileStickerAvatarDiskBudget = ProfileStickerAvatarCodecBudgets.disk,
    private val ffmpeg: () -> Path? = MediaDecoder::findFfmpeg,
    private val processes: ProfileStickerAvatarProcesses = ProfileStickerAvatarProcesses(),
) : ProfileStickerAvatarCodec {
    override fun thumbnail(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean,
        permit: ArtworkWorkerPermit): ArtworkFrame = still(encoded, edge, active)

    override fun open(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean,
        permit: ArtworkWorkerPermit): ArtworkCodecClip = when (encoded.format) {
        MediaFormat.WEBP, MediaFormat.PHOTO -> StaticClip(still(encoded, edge, active))
        MediaFormat.TGS -> vector(encoded, edge, active)
        MediaFormat.WEBM -> video(encoded, edge, active, permit)
        else -> throw IllegalArgumentException("Unsupported native artwork format")
    }

    private fun still(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean): ArtworkFrame {
        require(edge in 1..1024 && encoded.size in 1..24 * 1024 * 1024)
        // Skia Data owns a second encoded copy until its close.
        val copy = encoded.reserveCopy(encoded.size.toLong()) ?: throw ProfileStickerAvatarArtworkBusy()
        try {
            var frame: ArtworkFrame? = null
            check(encoded.borrow { bytes ->
                Data.makeFromBytes(bytes).use { data -> Codec.makeFromData(data).use { codec ->
                    val validFormat = when (encoded.format) {
                        MediaFormat.WEBP -> codec.encodedImageFormat == EncodedImageFormat.WEBP
                        MediaFormat.PHOTO -> codec.encodedImageFormat in setOf(EncodedImageFormat.PNG, EncodedImageFormat.JPEG)
                        else -> false
                    }
                    require(validFormat && codec.frameCount == 1 && codec.width == encoded.width &&
                        codec.height == encoded.height && codec.width in 1..2048 && codec.height in 1..2048)
                    val hold = rasterBudget(codec.width, codec.height, edge)
                    try {
                        checkCurrent(active)
                        val info = ImageInfo(codec.width, codec.height, ColorType.RGBA_8888,
                            ColorAlphaType.PREMUL, codec.colorSpace)
                        Bitmap().use { bitmap ->
                            require(bitmap.allocPixels(info)); codec.readPixels(bitmap); checkCurrent(active)
                            frame = raster(Image.makeFromBitmap(bitmap), edge, encoded.repaint, hold, active)
                        }
                    } catch (failure: Throwable) { hold.close(); throw failure }
                } }
            })
            return checkNotNull(frame)
        } finally { copy.close() }
    }

    private fun vector(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean): ArtworkCodecClip {
        require(edge in 1..1024 && encoded.size in 1..24 * 1024 * 1024)
        // Charges cover readNBytes' transient chunks/result and Skia's retained Data copy.
        val retained = encoded.reserveCopy(MAX_JSON.toLong()) ?: throw ProfileStickerAvatarArtworkBusy()
        val temporary = encoded.reserveCopy(2L * MAX_JSON) ?: run { retained.close(); throw ProfileStickerAvatarArtworkBusy() }
        var data: Data? = null
        var animation: Animation? = null
        try {
            check(encoded.borrow { bytes ->
                val json = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readNBytes(MAX_JSON + 1) }
                require(json.size in 1..MAX_JSON); checkCurrent(active)
                data = Data.makeFromBytes(json)
                animation = Animation.makeFromData(checkNotNull(data))
            })
            val actual = checkNotNull(animation)
            require(actual.width in 1f..2048f && actual.height in 1f..2048f && actual.duration.isFinite() &&
                actual.duration in .01f..60f && actual.fPS.isFinite() && actual.fPS in 1f..120f)
            checkCurrent(active)
            return VectorClip(actual, checkNotNull(data), retained, edge, encoded.repaint)
        } catch (failure: Throwable) {
            animation?.close(); data?.close(); retained.close(); throw failure
        } finally { temporary.close() }
    }

    private inner class VectorClip(private val animation: Animation, private val data: Data,
        private val dataCharge: AutoCloseable, private val edge: Int, private val repaint: Boolean) : ArtworkCodecClip {
        override val animated = true
        override val durationMillis = (animation.duration * 1000).toLong().coerceAtLeast(1)
        override val frameDurationMillis = (1000.0 / animation.fPS).roundToInt().toLong().coerceAtLeast(1)
        override fun frame(timeMillis: Long, active: () -> Boolean, permit: ArtworkWorkerPermit): ArtworkFrame {
            val hold = pixels.reserve(4L * edge * edge) ?: throw ProfileStickerAvatarArtworkBusy()
            try {
                checkCurrent(active)
                val info = ImageInfo(edge, edge, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)
                val bitmap = Bitmap()
                try {
                    require(bitmap.allocPixels(info))
                    Surface.makeRaster(info).use { surface ->
                        surface.canvas.clear(0)
                        val scale = minOf(edge / animation.width, edge / animation.height)
                        val width = animation.width * scale; val height = animation.height * scale
                        val frameTime = (timeMillis % durationMillis) / frameDurationMillis * frameDurationMillis
                        animation.seek(frameTime.toFloat() / durationMillis)
                        animation.render(surface.canvas, Rect.makeXYWH((edge - width) / 2, (edge - height) / 2, width, height))
                        checkCurrent(active)
                        surface.makeImageSnapshot().use { check(it.readPixels(bitmap)) }
                    }
                    checkCurrent(active); hold.shrink(edge.toLong() * edge)
                    return ArtworkFrame(bitmap.asComposeImageBitmap(), repaint) { bitmap.close(); hold.close() }
                } catch (failure: Throwable) { bitmap.close(); throw failure }
            } catch (failure: Throwable) { hold.close(); throw failure }
        }
        override fun close() { try { animation.close() } finally { try { data.close() } finally { dataCharge.close() } } }
    }

    private class StaticClip(private val prepared: ArtworkFrame) : ArtworkCodecClip {
        override val animated = false
        override val durationMillis = 0L
        override val frameDurationMillis = 0L
        override fun frame(timeMillis: Long, active: () -> Boolean, permit: ArtworkWorkerPermit): ArtworkFrame {
            checkCurrent(active)
            return prepared.retain() ?: throw InterruptedException()
        }
        override fun close() = prepared.close()
    }

    private fun video(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean,
        permit: ArtworkWorkerPermit): ArtworkCodecClip {
        require(edge in 1..1024 && encoded.size in 1..24 * 1024 * 1024)
        val executable = ffmpeg() ?: error("Silent WebM codec unavailable")
        val probe = executable.resolveSibling(if (executable.fileName.toString().endsWith(".exe", true)) "ffprobe.exe" else "ffprobe")
        require(Files.isRegularFile(probe))
        val encodedCopy = encoded.reserveCopy(encoded.size.toLong()) ?: throw ProfileStickerAvatarArtworkBusy()
        val scratch = ProfileStickerAvatarScratch.create(encoded.size, disk, encodedCopy)
        try {
            checkCurrent(active)
            check(encoded.borrow { bytes -> Files.write(scratch.input, bytes) })
            val output = ByteArrayOutputStream()
            // ffprobe decodes bounded frames to obtain real timestamps; retain its working
            // allowance even if it survives cancellation and moves to the process reaper.
            val probeMemory = pixels.reserve(4L * encoded.width * encoded.height + 16_384)
                ?: throw ProfileStickerAvatarArtworkBusy()
            processes.run(listOf(probe.toString(), "-v", "error", "-protocol_whitelist", "file,pipe",
                "-select_streams", "v:0", "-threads", "1", "-show_frames", "-show_streams", "-show_format",
                "-show_entries", "frame=best_effort_timestamp_time,duration_time,pkt_duration_time:stream=width,height,avg_frame_rate:format=duration,start_time",
                "-of", "default", scratch.input.toString()), scratch, permit, 65_536, active, output,
                probeMemory)
            val timeline = profileStickerAvatarVideoTimeline(output.toString(Charsets.UTF_8), encoded.width, encoded.height)
            val width = timeline.width
            val height = timeline.height
            val frameBytes = width.toLong() * height * 4
            // A spare frame detects metadata/extraction disagreement instead of accepting truncation.
            val maxFrames = timeline.positionsMicros.size + 1
            val outputHold = disk.reserve(frameBytes * maxFrames) ?: throw ProfileStickerAvatarArtworkBusy()
            scratch.addOutputReservation(outputHold)
            // Charge the pipe and conservative child RGBA working surfaces through confirmed exit.
            val childMemory = pixels.reserve(4L * width * height + 16_384) ?: throw ProfileStickerAvatarArtworkBusy()
            var transferred = false
            val bytes = try {
                Files.newOutputStream(scratch.frames, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { sink ->
                    val command = listOf(executable.toString(), "-nostdin", "-hide_banner", "-loglevel", "error",
                        "-protocol_whitelist", "file,pipe", "-threads", "1", "-c:v", "libvpx-vp9", "-i", scratch.input.toString(),
                        "-an", "-sn", "-dn", "-map", "0:v:0", "-fps_mode", "passthrough", "-pix_fmt", "rgba",
                        "-threads", "1", "-frames:v", maxFrames.toString(), "-f", "rawvideo", "pipe:1")
                    transferred = true
                    processes.run(command, scratch, permit, frameBytes * maxFrames, active, sink, childMemory)
                }
            } catch (failure: Throwable) {
                if (!transferred) childMemory.close()
                throw failure
            }
            require(bytes > 0 && bytes % frameBytes == 0L && bytes / frameBytes == timeline.positionsMicros.size.toLong())
            checkCurrent(active)
            return VideoClip(scratch, width, height, edge, encoded.repaint, timeline)
        } catch (failure: Throwable) { scratch.release(); throw failure }
    }

    private inner class VideoClip(private val scratch: ProfileStickerAvatarScratch, private val width: Int,
        private val height: Int, private val edge: Int, private val repaint: Boolean,
        private val timeline: ProfileStickerAvatarVideoTimeline) : ArtworkCodecClip {
        override val animated = timeline.positionsMicros.size > 1
        override val durationMillis = if (animated) timeline.durationMillis else 0L
        override val frameDurationMillis = if (animated) timeline.frameHintMillis else 0L
        override fun frame(timeMillis: Long, active: () -> Boolean, permit: ArtworkWorkerPermit): ArtworkFrame {
            val hold = rasterBudget(width, height, edge)
            try {
                checkCurrent(active)
                val rgba = ByteArray(width * height * 4)
                val index = if (animated) timeline.indexAt(timeMillis % durationMillis).toLong() else 0L
                Files.newByteChannel(scratch.frames, StandardOpenOption.READ).use { channel ->
                    channel.position(index * rgba.size)
                    val buffer = ByteBuffer.wrap(rgba)
                    while (buffer.hasRemaining()) { checkCurrent(active); check(channel.read(buffer) > 0) }
                }
                premultiplyMediaRgba(rgba)
                val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)
                return raster(Image.makeRaster(info, rgba, width * 4), edge, repaint, hold, active)
            } catch (failure: Throwable) { hold.close(); throw failure }
        }
        override fun close() = scratch.release()
    }

    private fun rasterBudget(width: Int, height: Int, edge: Int): ProfileStickerAvatarPixelBudget.Hold {
        require(width in 1..2048 && height in 1..2048 && edge in 1..1024)
        // Source bitmap/image/readback/array and target area array/image/surface/output are all charged.
        val peak = 4L * width * height + 6L * edge * edge
        return pixels.reserve(peak) ?: throw ProfileStickerAvatarArtworkBusy()
    }

    /** Owns image and performs all readback/area preparation on a worker before publication. */
    private fun raster(image: Image, edge: Int, repaint: Boolean, hold: ProfileStickerAvatarPixelBudget.Hold,
        active: () -> Boolean): ArtworkFrame = AreaRasterImage(image).use { raster ->
        checkCurrent(active)
        val scale = minOf(edge.toFloat() / raster.width, edge.toFloat() / raster.height)
        val width = (raster.width * scale).roundToInt().coerceIn(1, edge)
        val height = (raster.height * scale).roundToInt().coerceIn(1, edge)
        raster.prepare(width, height); checkCurrent(active)
        val info = ImageInfo(edge, edge, ColorType.RGBA_8888, ColorAlphaType.PREMUL, image.colorSpace)
        val bitmap = Bitmap()
        try {
            require(bitmap.allocPixels(info))
            Surface.makeRaster(info).use { surface ->
                surface.canvas.clear(0)
                raster.draw(surface.canvas, Rect.makeXYWH((edge - width) / 2f, (edge - height) / 2f,
                    width.toFloat(), height.toFloat()))
                surface.makeImageSnapshot().use { check(it.readPixels(bitmap)) }
            }
            checkCurrent(active)
            // Retain the conservative peak charge with the frame. Source and area objects are
            // disposed by outer scopes after this return; they must never be uncharged early.
            ArtworkFrame(bitmap.asComposeImageBitmap(), repaint) { bitmap.close(); hold.close() }
        } catch (failure: Throwable) { bitmap.close(); throw failure }
    }

    private companion object { const val MAX_JSON = 2 * 1024 * 1024 }
}

/** Decoded-frame PTS in extraction order. The hint is not used for VFR frame lookup. */
internal class ProfileStickerAvatarVideoTimeline(
    val width: Int,
    val height: Int,
    val positionsMicros: LongArray,
    val durationMillis: Long,
    val frameHintMillis: Long,
) {
    fun indexAt(timeMillis: Long): Int {
        require(timeMillis >= 0)
        val target = if (timeMillis > Long.MAX_VALUE / 1000) Long.MAX_VALUE else timeMillis * 1000
        var low = 0; var high = positionsMicros.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (positionsMicros[middle] <= target) low = middle + 1 else high = middle
        }
        return (low - 1).coerceAtLeast(0)
    }
}

internal fun profileStickerAvatarVideoTimeline(probe: String, expectedWidth: Int,
    expectedHeight: Int): ProfileStickerAvatarVideoTimeline {
    val frames = mutableListOf<Map<String, String>>()
    var section: String? = null
    var values = mutableMapOf<String, String>()
    var stream: Map<String, String>? = null
    var format: Map<String, String>? = null
    for (line in probe.lineSequence()) {
        when (line.trim()) {
            "[FRAME]", "[STREAM]", "[FORMAT]" -> {
                require(section == null)
                section = line.trim().removeSurrounding("[", "]")
                values = mutableMapOf()
            }
            "[/FRAME]", "[/STREAM]", "[/FORMAT]" -> {
                val ending = line.trim().removeSurrounding("[/", "]")
                require(section == ending)
                when (ending) {
                    "FRAME" -> { frames += values.toMap(); require(frames.size <= 360) }
                    "STREAM" -> { require(stream == null); stream = values.toMap() }
                    "FORMAT" -> { require(format == null); format = values.toMap() }
                }
                section = null
            }
            else -> if (section != null) {
                val separator = line.indexOf('=')
                if (separator > 0) {
                    val key = line.substring(0, separator)
                    require(key !in values)
                    values[key] = line.substring(separator + 1).trim()
                }
            }
        }
    }
    require(section == null && frames.isNotEmpty())
    val streamFields = requireNotNull(stream); val formatFields = requireNotNull(format)
    val width = streamFields["width"]?.toIntOrNull() ?: error("Invalid native video width")
    val height = streamFields["height"]?.toIntOrNull() ?: error("Invalid native video height")
    require(width == expectedWidth && height == expectedHeight && width in 1..2048 && height in 1..2048)
    fun micros(value: String?): Long? {
        val seconds = value?.toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds !in -3600.0..3600.0) return null
        return (seconds * 1_000_000.0).roundToLong()
    }
    val first = micros(frames.first()["best_effort_timestamp_time"]) ?: error("Missing frame timestamp")
    val positions = frames.map { frame ->
        val absolute = micros(frame["best_effort_timestamp_time"]) ?: error("Missing frame timestamp")
        absolute - first
    }.toLongArray()
    require(positions.first() == 0L && positions.last() <= 60_000_000L &&
        (1 until positions.size).all { positions[it] > positions[it - 1] })
    fun reportedDuration(value: String?): Long? {
        if (value == null || value.equals("N/A", ignoreCase = true)) return null
        val reported = micros(value) ?: throw IllegalArgumentException("Invalid frame duration")
        require(reported >= 0)
        // Zero is unknown, never evidence for a positive display interval.
        return reported.takeIf { it > 0 }
    }
    val durations = frames.map { frame ->
        val modern = reportedDuration(frame["duration_time"])
        val legacy = reportedDuration(frame["pkt_duration_time"])
        require(modern == null || legacy == null || kotlin.math.abs(modern - legacy) <= 1L) {
            "Conflicting native frame durations"
        }
        modern ?: legacy
    }
    if (positions.size == 1) {
        // This is only a probe candidate. video() still checks the physical extraction
        // has exactly one RGBA frame before creating or publishing a static clip.
        val lastDuration = durations.single()
        if (lastDuration != null) require(lastDuration <= 60_100_000L)
        val rawFormatDuration = formatFields["duration"]
        val formatDuration = if (rawFormatDuration == null || rawFormatDuration.equals("N/A", ignoreCase = true)) null
            else (micros(rawFormatDuration) ?: throw IllegalArgumentException("Invalid container duration")).also {
                require(it in 0..60_000_000L)
            }.takeIf { it > 0 }
        if (formatDuration != null) {
            val rawStart = formatFields["start_time"]
            val formatStart = if (rawStart == null || rawStart.equals("N/A", ignoreCase = true)) first
                else micros(rawStart) ?: throw IllegalArgumentException("Invalid container start")
            val containerEnd = formatStart + formatDuration - first
            require(containerEnd in 1..60_100_000L)
            if (lastDuration != null) require(lastDuration <= containerEnd + 2_000L)
        }
        return ProfileStickerAvatarVideoTimeline(width, height, positions, 0L, 0L)
    }
    val formatDuration = micros(formatFields["duration"]) ?: error("Missing container duration")
    require(formatDuration in 1..60_000_000L)
    val formatStart = micros(formatFields["start_time"]) ?: first
    val containerEnd = formatStart + formatDuration - first
    require(containerEnd > positions.last() && containerEnd <= 60_100_000L)
    val lastDuration = durations.last() ?: throw IllegalArgumentException("Missing last frame duration")
    // A container may outlast video because of an audio stream. It constrains the frame
    // timeline but cannot establish the last video's presentation duration.
    val duration = positions.last() + lastDuration
    require(duration > positions.last() && duration <= containerEnd + 2_000L && duration <= 60_100_000L)
    val shortest = minOf((1 until positions.size).minOf { positions[it] - positions[it - 1] }, duration - positions.last())
    return ProfileStickerAvatarVideoTimeline(width, height, positions,
        ceil(duration / 1000.0).toLong().coerceAtLeast(1), (shortest / 1000).coerceAtLeast(1))
}
