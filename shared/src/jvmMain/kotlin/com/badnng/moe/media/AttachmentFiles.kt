package com.badnng.moe.media

import com.badnng.moe.chat.AttachmentKind
import com.badnng.moe.chat.LocalAttachment
import com.badnng.moe.chat.LocalAttachmentSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Fixed messages are used at UI boundaries; native output and local paths are never diagnostics. */
internal class AttachmentFailure(val reason: Reason) : Exception(reason.name) {
    enum class Reason { INVALID_FILE, TOO_LARGE, VIDEO_TOOLS_MISSING, INVALID_VIDEO, VIDEO_FORMAT, SAVE_FAILED }
}

/** A progressing native byte stream has no total probe deadline. */
internal class MediaProgressDeadline(private val started: Long, private val timeout: Long) {
    fun expired(now: Long, lastProgress: Long, active: Boolean): Boolean =
        !active || now - maxOf(started, lastProgress) > timeout
}

internal object MediaTools {
    private val timeouts = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "media-command-timeout").apply { isDaemon = true }
    }

    fun find(name: String): Path? {
        require(name == "ffprobe.exe")
        val key = name.removeSuffix(".exe")
        val candidates = mutableListOf<Path>()
        System.getProperty("nnggram.$key.path")?.takeIf(String::isNotBlank)?.let { candidates.add(Path.of(it)) }
        System.getenv("NNGGRAM_${key.uppercase()}_PATH")?.takeIf(String::isNotBlank)?.let { candidates.add(Path.of(it)) }
        val tool = com.badnng.moe.platform.DesktopPlatform.current.executable(key)
        MediaDecoder.findFfmpeg()?.parent?.let { candidates.add(it.resolve(tool)) }
        return candidates.firstOrNull(Files::isRegularFile)
    }

    fun capture(executable: Path, arguments: List<String>, timeoutSeconds: Long = 20,
        progressNanos: (() -> Long)? = null, active: () -> Boolean = { true }): String {
        val process = ProcessBuilder(listOf(executable.toString()) + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val started = System.nanoTime()
        val progressDeadline = MediaProgressDeadline(started, TimeUnit.SECONDS.toNanos(timeoutSeconds))
        val deadline = if (progressNanos == null) timeouts.schedule({ process.destroyForcibly() }, timeoutSeconds, TimeUnit.SECONDS)
            else timeouts.scheduleWithFixedDelay({
                if (progressDeadline.expired(System.nanoTime(), progressNanos(), active()))
                    process.destroyForcibly()
            }, 100, 100, TimeUnit.MILLISECONDS)
        try {
            process.outputStream.close()
            val bytes = process.inputStream.use { it.readNBytes(65_537) }
            check(bytes.size <= 65_536 && process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0)
            return bytes.toString(Charsets.UTF_8)
        } finally {
            deadline.cancel(false)
            process.destroyForcibly()
        }
    }
}

internal object AttachmentFiles {
    const val MAX_BYTES = 4_000_000_000L

    // MP4 carries its stream metadata in the container. Avoid FFmpeg's default multi-second,
    // multi-megabyte analysis before presenting an already decodable first frame.
    val streamingInputOptions = listOf("-probesize", "262144", "-analyzeduration", "250000", "-rw_timeout", "15000000")

    fun inspect(path: String, kind: AttachmentKind): LocalAttachment {
        val file = try { Path.of(path).toAbsolutePath() } catch (_: Exception) { fail(AttachmentFailure.Reason.INVALID_FILE) }
        val size = try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) fail(AttachmentFailure.Reason.INVALID_FILE)
            Files.size(file)
        } catch (error: AttachmentFailure) { throw error }
        catch (_: Exception) { fail(AttachmentFailure.Reason.INVALID_FILE) }
        if (size <= 0) fail(AttachmentFailure.Reason.INVALID_FILE)
        if (size > MAX_BYTES) fail(AttachmentFailure.Reason.TOO_LARGE)
        val source = identity(file)
        if (kind == AttachmentKind.FILE) return LocalAttachment(kind, file.toString(), file.fileName.toString(), size, source = source)
        val video = probe(file)
        val layout = inspectMp4Layout(file)
        if (video.codec != "h264" || !layout.isMp4) fail(AttachmentFailure.Reason.VIDEO_FORMAT)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val prepared = prepareVideoThumbnail(file, video)
        try {
            val attachment = LocalAttachment(kind, file.toString(), file.fileName.toString(), size, ceil(video.duration).toInt(),
                video.displayWidth, video.displayHeight, layout.supportsStreaming, prepared.first, source, prepared.second)
            if (!matchesSelection(attachment)) fail(AttachmentFailure.Reason.INVALID_FILE)
            return attachment
        } catch (error: Throwable) {
            prepared.second.release()
            throw error
        }
    }

    internal fun identity(path: Path): LocalAttachmentSource {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        return LocalAttachmentSource(attributes.lastModifiedTime().toMillis(), attributes.fileKey()?.toString())
    }

    /** The user's confirmation is for this file version, not a replacement at the same pathname. */
    fun matchesSelection(attachment: LocalAttachment): Boolean = runCatching {
        fun matches(path: String, size: Long, source: LocalAttachmentSource?): Boolean {
            if (source == null) return true
            val file = Path.of(path)
            return Files.isRegularFile(file) && Files.isReadable(file) && Files.size(file) == size && identity(file) == source
        }
        matches(attachment.path, attachment.size, attachment.source) && attachment.thumbnail?.let {
            matches(it.path, it.size, it.source)
        } != false
    }.getOrDefault(false)

    fun validatePlayback(path: String): VideoInfo {
        val file = Path.of(path).toAbsolutePath()
        if (!Files.isRegularFile(file) || Files.size(file) !in 1..MAX_BYTES) fail(AttachmentFailure.Reason.INVALID_FILE)
        return probe(file.toString(), includeAudio = true)
    }

    fun validatePlayback(source: ProgressiveVideoSource): VideoInfo = try {
        probe(source.probeUrl, includeAudio = true, streaming = true, source = source)
    } finally { source.releaseProbe() }

    private fun probe(file: Path): VideoInfo = probe(file.toString())

    private fun probe(input: String, includeAudio: Boolean = false, streaming: Boolean = false, source: ProgressiveVideoSource? = null): VideoInfo {
        val executable = MediaTools.find("ffprobe.exe") ?: fail(AttachmentFailure.Reason.VIDEO_TOOLS_MISSING)
        val protocols = if (streaming) "http,tcp" else "file,pipe"
        val timeout = if (streaming) 15L else 20L
        val output = try {
            MediaTools.capture(executable, listOf("-v", "error", "-protocol_whitelist", protocols) +
                (if (streaming) streamingInputOptions else emptyList()) +
                listOf("-show_entries", "stream=index,codec_type,codec_name,width,height,duration,sample_aspect_ratio:stream_disposition=attached_pic:stream_side_data=rotation:format=format_name,duration",
                    "-of", "default", "-i", input), timeout,
                progressNanos = source?.let { { it.lastProgressNanos } }, active = { source?.isOpen != false })
        } catch (_: Exception) { fail(AttachmentFailure.Reason.INVALID_VIDEO) }
        return parseVideoInfo(output, includeAudio)
    }

    /** One probe reports both video and audio, even when the audio stream appears first. */
    internal fun parseVideoInfo(output: String, includeAudio: Boolean): VideoInfo {
        val streams = mutableListOf<MutableMap<String, String>>()
        val format = mutableMapOf<String, String>()
        var section: MutableMap<String, String>? = null
        output.lineSequence().forEach { line ->
            when (line) {
                "[STREAM]" -> section = mutableMapOf<String, String>().also(streams::add)
                "[FORMAT]" -> section = format
                "[/STREAM]", "[/FORMAT]" -> section = null
                else -> {
                    val index = line.indexOf('=')
                    if (index > 0) section?.set(line.take(index).removePrefix("DISPOSITION:"), line.substring(index + 1).trim())
                }
            }
        }
        val fields = streams.firstOrNull { it["codec_type"] == "video" && it["attached_pic"] != "1" }
            ?: fail(AttachmentFailure.Reason.INVALID_VIDEO)
        val width = fields["width"]?.toIntOrNull() ?: 0
        val height = fields["height"]?.toIntOrNull() ?: 0
        // The container timeline includes audio extending beyond the final picture.
        val duration = (format["duration"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            ?: fields["duration"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }) ?: 0.0
        if (width !in 1..16_384 || height !in 1..16_384 || duration <= 0 || duration > Int.MAX_VALUE) fail(AttachmentFailure.Reason.INVALID_VIDEO)
        val rotation = fields["rotation"]?.toIntOrNull() ?: 0
        val swappedAxes = ((rotation % 360) + 360) % 360 in setOf(90, 270)
        val sar = fields["sample_aspect_ratio"]?.split(':')
        val aspect = if (sar?.size == 2) (sar[0].toDoubleOrNull() ?: 1.0) / (sar[1].toDoubleOrNull() ?: 1.0) else 1.0
        val pixelAspect = aspect.takeIf { it.isFinite() && it > 0 } ?: 1.0
        val rawRatio = width * pixelAspect / height
        val displayRatio = if (swappedAxes) 1.0 / rawRatio else rawRatio
        val hasAudio = includeAudio && streams.any { it["codec_type"] == "audio" }
        val displayWidth = (width * pixelAspect).takeIf { it.isFinite() && it in 1.0..16_384.0 }?.roundToInt()
            ?: fail(AttachmentFailure.Reason.INVALID_VIDEO)
        return VideoInfo(fields["codec_name"].orEmpty(), format["format_name"].orEmpty(),
            if (swappedAxes) height else width, if (swappedAxes) width else height,
            duration, displayRatio, hasAudio, fields["index"]?.toIntOrNull()?.takeIf { it in 0..1024 } ?: streams.indexOf(fields),
            if (swappedAxes) height else displayWidth, if (swappedAxes) displayWidth else height)
    }

    /** Suggested server filenames cannot escape the directory selected by the user. */
    fun safeName(suggested: String): String {
        val basename = suggested.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f<>:\"/\\\\|?*]"), "_").trim().trimEnd('.', ' ')
        val stem = basename.substringBefore('.').uppercase()
        val reserved = stem in setOf("CON", "PRN", "AUX", "NUL") || stem.matches(Regex("(?:COM|LPT)[1-9]"))
        return (if (reserved) "_$basename" else basename).take(180).ifBlank { "下载文件" }
    }

    fun save(source: Path, destination: Path, replaceExisting: Boolean) {
        if (!Files.isRegularFile(source)) fail(AttachmentFailure.Reason.SAVE_FAILED)
        if (source.toAbsolutePath().normalize() == destination.toAbsolutePath().normalize() ||
            (Files.exists(destination) && Files.isSameFile(source, destination))) return
        val target = destination.toAbsolutePath()
        val temporary = Files.createTempFile(target.parent, ".nnggram-save-", ".part")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            if (replaceExisting) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            else Files.move(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun fail(reason: AttachmentFailure.Reason): Nothing = throw AttachmentFailure(reason)
    internal data class VideoInfo(val codec: String, val container: String, val width: Int, val height: Int,
        val duration: Double, val aspectRatio: Double, val hasAudio: Boolean, val streamIndex: Int,
        val displayWidth: Int, val displayHeight: Int)
}
