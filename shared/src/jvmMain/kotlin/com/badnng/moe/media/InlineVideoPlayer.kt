package com.badnng.moe.media

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.min

@Immutable
internal data class VideoPlayerState(
    val ready: Boolean = false,
    val loading: Boolean = true,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val failed: Boolean = false,
    val audioUnavailable: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val volume: Float = 1f,
    val buffering: Boolean = false,
    /** A displayed raster, independent of probed metadata and the next decoder's readiness. */
    val hasFrame: Boolean = false,
)

/** Injectable PCM sink lets smoke tests decode sound without accessing the user's audio device. */
internal interface VideoAudioOutput : AutoCloseable {
    fun start()
    fun write(bytes: ByteArray, length: Int)
    fun drain()
}

/** Streaming playback uses one displayed frame, one decoded frame, and bounded PCM buffers.
 * Each seek owns new decoder pipes; a stale decode cannot replace the current run's frame.
 */
internal class InlineVideoPlayer(
    private val path: String,
    autoPlay: Boolean = true,
    private val audioFactory: () -> VideoAudioOutput = DesktopAudio::output,
    private val progressiveSource: ProgressiveVideoSource? = null,
    initialVolume: Float = 1f,
    /** GIF/animation playback never creates an audio decoder or opens an output device. */
    private val videoOnly: Boolean = false,
    private val loop: Boolean = false,
    /** Native photo cover time, captured before any decoder can publish the first frame. */
    private val initialPositionMillis: Long = 0,
    /** Optional one-way source permission, checked without relying on a Compose frame. */
    private val sourceIsActive: () -> Boolean = { true },
) : AutoCloseable {
    private val guard = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Set gain before launching any decoder so autoplay cannot leak an audible first buffer.
    @Volatile private var volume = initialVolume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    private val mutableState = MutableStateFlow(VideoPlayerState(volume = volume))
    val state = mutableState.asStateFlow()
    private val revision = mutableIntStateOf(0)
    private var image: Image? = null
    private var info: AttachmentFiles.VideoInfo? = null
    private var run: PlaybackRun? = null
    private var closed = false
    private var playWhenReady = autoPlay

    init {
        scope.launch {
            try {
                require(initialPositionMillis >= 0)
                if (!sourceIsActive()) return@launch
                val metadata = progressiveSource?.let(AttachmentFiles::validatePlayback) ?: AttachmentFiles.validatePlayback(path)
                check(MediaDecoder.findFfmpeg() != null)
                // Desktop clamps a photo seek to duration - 1 ms and keeps the last valid
                // raster at EOF. The decoder's retained preroll/fps/last-frame padding below
                // provides that final interval even for sparse video or an audio-only tail.
                val initialPosition = (initialPositionMillis / 1_000.0)
                    .coerceAtMost((metadata.duration - 0.001).coerceAtLeast(0.0))
                synchronized(guard) {
                    if (!closed && sourceIsActive()) {
                        info = metadata
                        mutableState.value = mutableState.value.copy(ready = true, duration = metadata.duration)
                        startRun(initialPosition, playWhenReady)
                    }
                }
            } catch (_: Exception) {
                synchronized(guard) {
                    if (!closed && sourceIsActive()) mutableState.value = mutableState.value.copy(loading = false, buffering = false, failed = true)
                }
            }
        }
        if (progressiveSource != null) scope.launch {
            progressiveSource.buffering.collect { buffering ->
                synchronized(guard) {
                    if (!closed) {
                        if (run == null) mutableState.value = mutableState.value.copy(buffering = buffering && info == null)
                        else updateBuffering()
                    }
                }
            }
        }
    }

    fun play() = synchronized(guard) {
        if (closed || !sourceIsActive()) return@synchronized
        playWhenReady = true
        if (info == null || (mutableState.value.playing && !mutableState.value.failed)) return@synchronized
        startRun(if (mutableState.value.ended) 0.0 else mutableState.value.position, true)
    }

    fun pause() = synchronized(guard) {
        playWhenReady = false
        if (closed || run?.playing != true) return@synchronized
        val position = run?.position()?.coerceAtMost(mutableState.value.duration) ?: mutableState.value.position
        run?.close()
        progressiveSource?.cancelReaders()
        run = null
        mutableState.value = mutableState.value.copy(playing = false, loading = false, buffering = false, position = position)
    }

    fun seek(seconds: Double) = synchronized(guard) {
        if (closed || !sourceIsActive() || info == null || !seconds.isFinite()) return@synchronized
        val position = seconds.coerceIn(0.0, (mutableState.value.duration - 0.04).coerceAtLeast(0.0))
        startRun(position, mutableState.value.playing, loading = true)
    }

    fun setVolume(value: Float) = synchronized(guard) {
        if (!value.isFinite()) return@synchronized
        volume = value.coerceIn(0f, 1f)
        mutableState.value = mutableState.value.copy(volume = volume)
    }

    internal fun playbackPreferences(): VideoPlaybackPreferences = synchronized(guard) {
        VideoPlaybackPreferences(volume, playWhenReady)
    }

    internal fun applyPlaybackPreferences(preferences: VideoPlaybackPreferences, isActive: () -> Boolean = { true }) = synchronized(guard) {
        if (closed) return@synchronized
        // Flush queued PCM before returning to the chat, then resume with its saved gain.
        pause()
        setVolume(preferences.volume)
        if (preferences.playing && isActive()) play()
    }

    /** The presentation clock is sampled only by visible playback controls, never an idle timer. */
    fun currentPosition(): Double = synchronized(guard) {
        val state = mutableState.value
        if (closed || !state.playing || state.failed || state.ended) state.position
        else (run?.position() ?: state.position).coerceIn(0.0, state.duration)
    }

    private fun updateBuffering() = synchronized(guard) {
        val current = run ?: return@synchronized
        if (current.closed || mutableState.value.failed) return@synchronized
        // FFmpeg reads ahead. A waiting HTTP request alone must not freeze frames already decoded.
        val stalled = progressiveSource?.buffering?.value == true && current.isWaitingForData()
        current.setBuffering(stalled)
        mutableState.value = mutableState.value.copy(buffering = stalled)
    }

    /** Read in the draw phase: new frames redraw the canvas without recomposing the conversation. */
    fun draw(canvas: Canvas, width: Float, height: Float) = synchronized(guard) {
        revision.intValue
        if (!sourceIsActive()) return@synchronized
        val frame = image ?: return@synchronized
        if (width <= 0 || height <= 0) return@synchronized
        val scale = min(width / frame.width, height / frame.height)
        val w = frame.width * scale
        val h = frame.height * scale
        // Streaming frames already have a bounded playback raster; filter directly without
        // creating another per-frame mip chain or changing decoder/frame ownership budgets.
        canvas.drawMediaImage(frame, Rect.makeXYWH((width - w) / 2, (height - h) / 2, w, h))
    }

    internal fun hasFrame(): Boolean = synchronized(guard) { image != null }

    private fun startRun(position: Double, playing: Boolean, loading: Boolean = image == null) {
        if (!sourceIsActive()) return
        val metadata = info ?: return
        run?.close()
        progressiveSource?.cancelReaders()
        val current = PlaybackRun(position, playing)
        run = current
        // Pause releases decoder pipes but keeps the displayed raster. Resuming those pipes
        // is not an initial load; only an explicit seek or a real range stall needs progress.
        mutableState.value = mutableState.value.copy(position = position, playing = playing, loading = loading,
            failed = false, ended = false, audioUnavailable = false, buffering = false)
        current.job = scope.launch { decode(current, metadata) }
    }

    private suspend fun decode(current: PlaybackRun, metadata: AttachmentFiles.VideoInfo) = coroutineScope {
        val watchdog = launch {
            while (isActive) {
                delay(200)
                if (!sourceIsActive()) break
                updateBuffering()
                val lastProgress = if (current.videoEnded) current.lastAudioNanos else current.lastFrameNanos
                if (System.nanoTime() - lastProgress > 15_000_000_000L) {
                    fail(current)
                    current.close()
                    break
                }
                synchronized(guard) {
                    if (!closed && run === current && current.playing && !mutableState.value.loading && !mutableState.value.failed) {
                        mutableState.value = mutableState.value.copy(position = current.position().coerceAtMost(metadata.duration))
                    }
                }
            }
        }
        var audioJob: Job? = null
        try {
            if (!sourceIsActive()) return@coroutineScope
            val ratio = metadata.aspectRatio.coerceIn(1.0 / 64, 64.0)
            // Decode at most the source's square-pixel display width. Enlarging a
            // 64x48 source to 720x540 multiplies pipe traffic and per-frame work by
            // over 100 without adding detail; presentation already scales the image.
            val width = min(metadata.displayWidth.toDouble(), min(960.0, 540.0 * ratio)).toInt().coerceAtLeast(2)
            val height = (width / ratio).toInt().coerceAtLeast(2)
            // Keep decoder preroll so fps can supply the frame spanning a sparse timestamp
            // at a seek boundary, including the final interval before EOF.
            // Hold the final picture through an audio-only tail. Limit output to the
            // remaining container duration so padding never extends the playback itself.
            val videoArguments = listOf("-map", "0:v:0", "-an", "-vf",
                "scale=$width:$height,setsar=1,tpad=stop_mode=clone:stop_duration=${metadata.duration},fps=30:start_time=0",
                "-t", (metadata.duration - current.offset).coerceAtLeast(0.001).toString(),
                "-threads", "2", "-f", "rawvideo", "-pix_fmt", "rgba", "pipe:1")
            val audioReady = CompletableDeferred<Unit>()
            val start = CompletableDeferred<Unit>()
            // The run still owns and cancels this job. Keeping it outside this coroutineScope
            // lets a stuck native audio-device open return late without delaying video EOF.
            val audio = if (current.playing && metadata.hasAudio && !videoOnly) scope.launch {
                decodeAudio(current, audioReady, start)
            } else null
            audioJob = audio
            if (audio == null) audioReady.complete(Unit)
            val video = current.process(videoArguments, accurateSeek = false)
            val frameSize = width * height * 4
            val firstFrame = current.readVideoFrame(video.inputStream, frameSize)
            var index = 0
            fun present(bytes: ByteArray): Boolean {
                if (!sourceIsActive()) return false
                premultiplyMediaRgba(bytes)
                val next = Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), bytes, width * 4)
                return synchronized(guard) {
                    if (!closed && run === current && sourceIsActive()) {
                        image?.close()
                        image = next
                        revision.intValue++
                        if (index == 0) mutableState.value = mutableState.value.copy(
                            loading = false, hasFrame = true, position = current.offset)
                        true
                    } else { next.close(); false }
                }
            }
            video.inputStream.use { stream ->
                while (isActive && !current.closed && sourceIsActive()) {
                    val bytes = if (index == 0) firstFrame else current.readVideoFrame(stream, frameSize)
                    if (bytes.isEmpty()) break
                    check(bytes.size == frameSize)
                    if (index == 0) {
                        // Display the available picture immediately. A slow audio decoder/device
                        // must not keep a playable video behind the loading indicator.
                        if (!present(bytes)) return@coroutineScope
                        if (withTimeoutOrNull(750L) { audioReady.await(); true } != true) {
                            current.disableAudio()
                            audio?.cancel()
                            synchronized(guard) {
                                if (!closed && run === current) mutableState.value = mutableState.value.copy(audioUnavailable = true)
                            }
                        }
                        current.startClock()
                        start.complete(Unit)
                    }
                    current.awaitBuffer()
                    val target = current.startNanos + index * 1_000_000_000L / 30
                    val wait = (target - current.clockNanos()) / 1_000_000
                    if (current.playing && wait > 0) delay(wait)
                    ensureActive()
                    if (index > 0 && (!current.playing || current.clockNanos() - target < 100_000_000L)) {
                        if (!present(bytes)) return@coroutineScope
                    }
                    current.lastFrameNanos = System.nanoTime()
                    index++
                    if (!current.playing) return@coroutineScope
                }
            }
            if (!sourceIsActive()) return@coroutineScope
            check(index > 0 && video.waitFor(2, TimeUnit.SECONDS) && video.exitValue() == 0)
            current.videoEnded = true
            if (audio?.isCancelled != true) audio?.join()
            if (loop && current.playing) {
                // The final frame covers a real interval. EOF follows its presentation,
                // so retain it through the native duration before starting the next loop.
                // This also keeps very short GIFs from looping at decoder-process speed.
                while (isActive && !current.closed && sourceIsActive()) {
                    current.awaitBuffer()
                    val remainingMillis = (metadata.duration - current.position()) * 1_000.0
                    if (remainingMillis <= 0.0) break
                    delay(kotlin.math.ceil(remainingMillis).toLong().coerceIn(1, 100))
                }
                ensureActive()
            }
            synchronized(guard) {
                if (!closed && run === current && sourceIsActive()) {
                    if (loop && playWhenReady && current.playing) startRun(0.0, true, loading = false)
                    else mutableState.value = mutableState.value.copy(loading = false,
                        playing = false, ended = true, position = metadata.duration)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (sourceIsActive()) fail(current)
        } finally {
            watchdog.cancel()
            audioJob?.cancel()
            current.closeResources()
        }
    }

    private suspend fun decodeAudio(current: PlaybackRun, ready: CompletableDeferred<Unit>, start: CompletableDeferred<Unit>) {
        var sink: VideoAudioOutput? = null
        var process: Process? = null
        try {
            val audio = current.process(listOf("-map", "0:a:0", "-vn", "-af", "aresample=async=1:first_pts=0",
                "-ac", "2", "-ar", "48000", "-f", "s16le", "pipe:1"), audio = true)
            process = audio
            val output = audioFactory()
            sink = output
            current.attach(output)
            currentCoroutineContext().ensureActive()
            audio.inputStream.use { stream ->
                var bytes = current.readAudio(stream, 1_920) // 10 ms of stereo PCM.
                check(bytes.isNotEmpty())
                ready.complete(Unit)
                start.await()
                output.start()
                while (currentCoroutineContext().isActive && !current.closed && bytes.isNotEmpty()) {
                    current.awaitBuffer()
                    scalePcmVolume(bytes, volume)
                    output.write(bytes, bytes.size)
                    current.lastAudioNanos = System.nanoTime()
                    bytes = current.readAudio(stream, 1_920)
                }
                output.drain()
            }
            check(audio.waitFor(2, TimeUnit.SECONDS) && audio.exitValue() == 0)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            synchronized(guard) {
                if (!closed && run === current && !current.closed) mutableState.value = mutableState.value.copy(audioUnavailable = true)
            }
        } finally {
            ready.complete(Unit)
            sink?.let(current::release)
            process?.destroyForcibly()
        }
    }

    private fun fail(current: PlaybackRun) = synchronized(guard) {
        if (!closed && run === current) mutableState.value = mutableState.value.copy(loading = false, buffering = false, playing = false, failed = true)
    }

    override fun close() = synchronized(guard) {
        closed = true
        run?.close()
        progressiveSource?.cancelReaders()
        run = null
        scope.cancel()
        image?.close()
        image = null
        mutableState.value = mutableState.value.copy(hasFrame = false, playing = false, loading = false, buffering = false)
    }

    private inner class PlaybackRun(val offset: Double, val playing: Boolean) {
        // A retired run retains its old HTTP route even if its process starts late.
        private val streamingUrl = progressiveSource?.url
        @Volatile var closed = false
        @Volatile var startNanos = 0L
        @Volatile var lastFrameNanos = System.nanoTime()
        @Volatile var lastAudioNanos = System.nanoTime()
        @Volatile var videoEnded = false
        var job: Job? = null
        private val processes = mutableListOf<Process>()
        private var audioProcess: Process? = null
        private var audioDisabled = false
        private var sink: VideoAudioOutput? = null
        @Volatile private var buffering = false
        @Volatile private var videoWaitingSince = 0L
        @Volatile private var audioWaitingSince = 0L
        private var bufferedAt = 0L
        private var bufferShift = 0L
        @Synchronized fun startClock() { startNanos = System.nanoTime(); bufferShift = 0 }
        fun isWaitingForData(): Boolean {
            val now = System.nanoTime()
            return (videoWaitingSince > 0 && now - videoWaitingSince > 100_000_000L) ||
                (audioWaitingSince > 0 && now - audioWaitingSince > 100_000_000L)
        }
        fun readVideoFrame(stream: java.io.InputStream, count: Int): ByteArray {
            videoWaitingSince = System.nanoTime()
            return try { stream.readNBytes(count) } finally { videoWaitingSince = 0; updateBuffering() }
        }
        fun readAudio(stream: java.io.InputStream, count: Int): ByteArray {
            audioWaitingSince = System.nanoTime()
            return try { stream.readNBytes(count) } finally { audioWaitingSince = 0; updateBuffering() }
        }
        @Synchronized fun setBuffering(value: Boolean) {
            if (value == buffering) return
            val now = System.nanoTime()
            if (value) bufferedAt = now
            else if (startNanos > 0) bufferShift += now - maxOf(bufferedAt, startNanos)
            buffering = value
        }
        @Synchronized fun clockNanos(): Long = System.nanoTime() - bufferShift -
            if (buffering && startNanos > 0) (System.nanoTime() - maxOf(bufferedAt, startNanos)).coerceAtLeast(0) else 0
        suspend fun awaitBuffer() { while (buffering && !closed) delay(25) }
        fun position() = offset + if (startNanos > 0 && playing) (clockNanos() - startNanos).coerceAtLeast(0) / 1_000_000_000.0 else 0.0
        fun process(arguments: List<String>, accurateSeek: Boolean = true, audio: Boolean = false): Process {
            val executable = checkNotNull(MediaDecoder.findFfmpeg())
            val command = listOf(executable.toString(), "-nostdin", "-hide_banner", "-loglevel", "error", "-protocol_whitelist", if (progressiveSource != null) "http,tcp,pipe" else "file,pipe") +
                (if (progressiveSource != null) AttachmentFiles.streamingInputOptions else emptyList()) +
                (if (accurateSeek) emptyList() else listOf("-noaccurate_seek")) + listOf("-ss", offset.toString(),
                    "-i", streamingUrl ?: Path.of(path).toAbsolutePath().toString()) + arguments
            val process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.close()
            synchronized(this) {
                if (closed || audio && audioDisabled) { process.destroyForcibly(); throw CancellationException() }
                processes.add(process)
                if (audio) audioProcess = process
            }
            return process
        }
        fun attach(output: VideoAudioOutput) = synchronized(this) {
            if (closed || audioDisabled) { output.close(); throw CancellationException() }
            sink = output
        }
        fun disableAudio() {
            synchronized(this) {
                audioDisabled = true
                audioProcess?.destroyForcibly()
                audioProcess = null
                runCatching { sink?.close() }
                sink = null
                audioWaitingSince = 0
            }
            updateBuffering()
        }
        fun release(output: VideoAudioOutput) = synchronized(this) {
            if (sink === output) {
                sink = null
                runCatching { output.close() }
            }
        }
        fun close() { job?.cancel(); closeResources() }
        fun closeResources() = synchronized(this) {
            closed = true
            processes.forEach { it.destroyForcibly() }
            processes.clear()
            audioProcess = null
            runCatching { sink?.close() }
            sink = null
        }
    }
}

/** Signed little-endian samples, including -32768. The gain never amplifies or clips. */
internal fun scalePcmVolume(bytes: ByteArray, volume: Float) {
    val gain = volume.coerceIn(0f, 1f)
    if (gain == 1f) return
    for (index in 0 until bytes.size - 1 step 2) {
        val sample = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
        val scaled = (sample * gain).toInt()
        bytes[index] = scaled.toByte()
        bytes[index + 1] = (scaled shr 8).toByte()
    }
}
