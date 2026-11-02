package com.badnng.moe.media

import com.badnng.moe.platform.DesktopPlatform
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** Device access stays lazy and occurs only in an explicit playback/recording worker. */
internal object DesktopAudio {
    const val VOICE_BLOCK_BYTES = 1_920
    fun output(): VideoAudioOutput = if (DesktopPlatform.current.isWindows) WinMmAudioOutput() else JavaSoundAudioOutput()
    fun input(): VoicePcmInput = if (DesktopPlatform.current.isWindows) WinMmPcmInput() else JavaSoundPcmInput()
}

/** Poll bounded PCM writes so closing or seeking can interrupt a full device queue. */
internal class JavaSoundAudioOutput(
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(AudioFormat(48_000f, 16, 2, true, false)),
) : VideoAudioOutput {
    private val guard = Any()
    @Volatile private var closed = false
    init {
        try { line.open(AudioFormat(48_000f, 16, 2, true, false), 7_680) }
        catch (failure: Throwable) { line.close(); throw failure }
    }
    override fun start() = synchronized(guard) { if (!closed) line.start() }
    override fun write(bytes: ByteArray, length: Int) {
        require(length in 0..bytes.size && length % 4 == 0)
        var offset = 0
        while (offset < length && !closed) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = synchronized(guard) {
                if (closed) return
                val available = minOf(line.available(), length - offset, 1_920) / 4 * 4
                if (available == 0) 0 else line.write(bytes, offset, available)
            }
            if (count == 0) Thread.sleep(2) else offset += count
        }
    }
    override fun drain() {
        while (!closed && line.available() < line.bufferSize) Thread.sleep(2)
    }
    override fun close() = synchronized(guard) {
        if (closed) return@synchronized
        closed = true
        try { line.stop(); line.flush() } finally { line.close() }
    }
}

/** Nonblocking capture keeps coroutine cancellation and the recording stop button responsive. */
internal class JavaSoundPcmInput(
    private val line: TargetDataLine = AudioSystem.getTargetDataLine(AudioFormat(48_000f, 16, 1, true, false)),
) : VoicePcmInput {
    private val guard = Any()
    private var stopped = false
    private var closed = false
    init {
        try { line.open(AudioFormat(48_000f, 16, 1, true, false), 7_680); line.start() }
        catch (failure: Throwable) { line.close(); throw failure }
    }
    override fun read(destination: ByteArray): Int = synchronized(guard) {
        if (closed || stopped) return@synchronized 0
        val count = minOf(line.available(), destination.size) / 2 * 2
        if (count == 0) 0 else line.read(destination, 0, count)
    }
    override fun stop() = synchronized(guard) {
        if (!closed && !stopped) { stopped = true; line.stop(); line.flush() }
    }
    override fun close() = synchronized(guard) {
        if (closed) return@synchronized
        closed = true
        try { line.stop(); line.flush() } finally { line.close() }
    }
}
