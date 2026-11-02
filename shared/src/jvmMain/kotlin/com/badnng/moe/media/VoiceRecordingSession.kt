package com.badnng.moe.media

import com.badnng.moe.chat.VoiceNoteDraft
import com.badnng.moe.chat.ownedAttachmentLease
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class VoiceRecordingStage { RECORDING, ENCODING, READY, FAILED }
internal data class VoiceRecordingState(
    val stage: VoiceRecordingStage = VoiceRecordingStage.RECORDING,
    val elapsedMillis: Long = 0,
    val draft: VoiceNoteDraft? = null,
) {
    override fun toString() = "VoiceRecordingState(stage=$stage)"
}

/** Open only after the user enters recording. Tests inject a PCM source and encoder. */
internal class VoiceRecordingSession(
    private val inputFactory: () -> VoicePcmInput = DesktopAudio::input,
    private val encode: (Path, Path) -> Unit = VoiceRecordingFiles::encode,
    private val directory: Path = Files.createTempDirectory("nnggram-voice-"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowNanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stopRequested = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(VoiceRecordingState())
    val state: StateFlow<VoiceRecordingState> = mutableState
    private val lease = ownedAttachmentLease {
        runCatching { Files.deleteIfExists(directory.resolve("voice.ogg")) }
        runCatching { Files.deleteIfExists(directory.resolve("voice.pcm")) }
        runCatching { Files.deleteIfExists(directory) }
    }
    private val workerLease = checkNotNull(lease.retain())
    private val task = scope.launch {
        try {
            runInterruptible(dispatcher) { record() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!closed.get()) mutableState.value = VoiceRecordingState(VoiceRecordingStage.FAILED)
        }
    }.also { job ->
        // Also runs when cancelled before the coroutine body starts. Active workers finish
        // closing device/process/file handles before their completion callback releases this.
        job.invokeOnCompletion { workerLease.release() }
    }

    fun stop() { stopRequested.set(true) }

    private fun record() {
        val pcm = directory.resolve("voice.pcm")
        val ogg = directory.resolve("voice.ogg")
        val peaks = mutableListOf<Int>()
        var sampleCount = 0L
        inputFactory().use { input ->
            val startedAt = nowNanos()
            Files.newOutputStream(pcm).use { output ->
                val block = ByteArray(DesktopAudio.VOICE_BLOCK_BYTES)
                var stopped = false
                while (sampleCount < MAX_SAMPLES && nowNanos() - startedAt < 300_000_000_000L) {
                    if (Thread.currentThread().isInterrupted || closed.get()) throw InterruptedException()
                    if (stopRequested.get() && !stopped) { input.stop(); stopped = true }
                    val read = input.read(block)
                    if (read == 0) {
                        if (stopped) break
                        Thread.sleep(4)
                        continue
                    }
                    require(read in 2..block.size && read % 2 == 0)
                    val count = minOf(read.toLong(), (MAX_SAMPLES - sampleCount) * 2).toInt()
                    output.write(block, 0, count)
                    sampleCount += count / 2
                    peaks += pcmPeak(block, count)
                    mutableState.value = VoiceRecordingState(elapsedMillis = sampleCount * 1000 / SAMPLE_RATE)
                }
            }
        }
        check(sampleCount >= SAMPLE_RATE / 10)
        if (closed.get()) throw InterruptedException()
        val elapsed = sampleCount * 1000 / SAMPLE_RATE
        mutableState.value = VoiceRecordingState(VoiceRecordingStage.ENCODING, elapsed)
        encode(pcm, ogg)
        val size = Files.size(ogg)
        check(size in 1..MAX_ENCODED_BYTES)
        Files.deleteIfExists(pcm)
        if (closed.get()) throw InterruptedException()
        val draft = VoiceNoteDraft(ogg.toString(), size, ceil(sampleCount.toDouble() / SAMPLE_RATE).toInt(),
            packVoiceWaveform(peaks), lease)
        mutableState.value = VoiceRecordingState(VoiceRecordingStage.READY, elapsed, draft)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopRequested.set(true)
        scope.cancel()
        // A retained engine handle keeps the OGG alive after this UI handle is released.
        lease.release()
    }

    companion object {
        const val SAMPLE_RATE = 48_000L
        const val MAX_SAMPLES = SAMPLE_RATE * 300
        const val MAX_ENCODED_BYTES = 8_000_000L
    }
}

internal fun pcmPeak(bytes: ByteArray, count: Int): Int {
    var peak = 0
    for (offset in 0 until count step 2) {
        val sample = ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
        peak = maxOf(peak, abs(sample))
    }
    return peak
}

/** 100 real peak buckets, packed consecutively in TDLib's least-significant-bit 5-bit format. */
internal fun packVoiceWaveform(peaks: List<Int>): ByteArray {
    if (peaks.isEmpty()) return byteArrayOf()
    val maximum = peaks.maxOrNull()?.coerceAtLeast(1) ?: 1
    val bytes = ByteArray(63)
    for (index in 0 until 100) {
        val first = index * peaks.size / 100
        val end = maxOf(first + 1, (index + 1) * peaks.size / 100).coerceAtMost(peaks.size)
        val amplitude = ((peaks.subList(first, end).maxOrNull() ?: 0).toDouble() / maximum * 31).roundToInt().coerceIn(0, 31)
        val bit = index * 5
        val word = amplitude shl (bit % 8)
        bytes[bit / 8] = (bytes[bit / 8].toInt() or word).toByte()
        if (bit % 8 > 3) bytes[bit / 8 + 1] = (bytes[bit / 8 + 1].toInt() or (word ushr 8)).toByte()
    }
    return bytes
}

internal object VoiceRecordingFiles {
    fun encode(pcm: Path, destination: Path) {
        val ffmpeg = checkNotNull(MediaDecoder.findFfmpeg())
        val process = ProcessBuilder(listOf(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
            "-protocol_whitelist", "file,pipe", "-f", "s16le", "-ar", "48000", "-ac", "1", "-i", pcm.toString(),
            "-c:a", "libopus", "-b:a", "32k", "-vbr", "on", "-application", "voip", "-f", "ogg", destination.toString()))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            process.outputStream.close()
            check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0)
        } finally {
            process.destroyForcibly()
            var interrupted = false
            while (process.isAlive) {
                try { process.waitFor() }
                catch (_: InterruptedException) { interrupted = true; process.destroyForcibly() }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
