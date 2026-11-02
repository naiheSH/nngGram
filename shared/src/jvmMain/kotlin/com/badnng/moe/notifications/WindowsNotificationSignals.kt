package com.badnng.moe.notifications

import com.badnng.moe.media.VideoAudioOutput
import com.badnng.moe.media.DesktopAudio
import com.badnng.moe.platform.DesktopPlatform
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicBoolean

/** Device signals accept only the default recording or already validated, immutable PCM. */
internal interface DeviceNotificationSignals : AutoCloseable {
    fun play(volumePercent: Int)
    /** True means enqueued, not played. Rejection does not invoke [completed].
     * [active] must read only a thread-safe lease, never actor-owned state. */
    fun playPrepared(
        pcm: NotificationSoundPcm,
        volumePercent: Int,
        active: () -> Boolean,
        completed: (Boolean) -> Unit = {},
    ): Boolean = false
    fun flash()
    fun stop()
    /** May run on a caller or audio thread; listeners must only enqueue a failure without details. */
    fun setFailureListener(listener: (() -> Unit)?) {}
}

/**
 * Shared notification audio ownership; platform factories choose the device and attention backend.
 * One lazy daemon worker owns default and prepared playback, with one coalesced pending sound.
 * Preview owners use their own instance, so stopping a preview cannot stop notifications.
 */
internal class QueuedNotificationSignals(
    private val hwnd: () -> Long,
    private val audioFactory: () -> VideoAudioOutput = DesktopAudio::output,
    private val flashDriver: NotificationWindowFlashDriver = NativeNotificationWindowFlashDriver(),
    private val pcmFactory: (Int) -> ByteArray = ::deviceDefaultNotificationPcm,
    private val windowsAttention: Boolean = true,
) : DeviceNotificationSignals {
    private val guard = java.lang.Object()
    private var closed = false
    private var generation = 0L
    private var pending: SoundRequest? = null
    private var worker: Thread? = null
    private var activeRequest: SoundRequest? = null
    private var activeOutput: OwnedOutput? = null
    private var flashedHwnd = 0L
    @Volatile private var failureListener: (() -> Unit)? = null

    override fun setFailureListener(listener: (() -> Unit)?) = synchronized(guard) {
        if (!closed) failureListener = listener
    }

    override fun play(volumePercent: Int) {
        enqueue(null, volumePercent, { true }, {})
    }

    override fun playPrepared(
        pcm: NotificationSoundPcm,
        volumePercent: Int,
        active: () -> Boolean,
        completed: (Boolean) -> Unit,
    ): Boolean = enqueue(pcm, volumePercent, active, completed)

    private fun enqueue(
        pcm: NotificationSoundPcm?,
        volumePercent: Int,
        active: () -> Boolean,
        completed: (Boolean) -> Unit,
    ): Boolean {
        val volume = volumePercent.coerceIn(0, 100)
        if (volume == 0 || !runCatching(active).getOrDefault(false)) return false
        val replaced = synchronized(guard) {
            if (closed) return false
            val previous = pending
            pending = SoundRequest(pcm, volume, generation, active, completed)
            if (worker == null) {
                worker = Thread({ runWorker() }, "nngGram-notification-audio").apply {
                    isDaemon = true
                    start()
                }
            }
            guard.notifyAll()
            previous
        }
        replaced?.finish(false)
        return true
    }

    override fun flash() {
        if (!windowsAttention) {
            runCatching {
                if (java.awt.Taskbar.isTaskbarSupported()) {
                    val taskbar = java.awt.Taskbar.getTaskbar()
                    if (taskbar.isSupported(java.awt.Taskbar.Feature.USER_ATTENTION)) taskbar.requestUserAttention(false, false)
                }
            }
            return
        }
        var failures = 0
        synchronized(guard) {
            if (closed) return
            // Window replacement must not leave an earlier Tao window flashing.
            val target = runCatching(hwnd).onFailure { failures++ }.getOrDefault(0L)
            if (target != 0L) {
                if (flashedHwnd != 0L && flashedHwnd != target && !flashWindow(flashedHwnd, FLASHW_STOP)) failures++
                flashedHwnd = target
                if (!flashWindow(target, FLASHW_ALL or FLASHW_TIMERNOFG)) failures++
            }
        }
        repeat(failures) { reportFailure() }
    }

    override fun stop() = cancel(closing = false)

    override fun close() = cancel(closing = true)

    private fun cancel(closing: Boolean) {
        var flashFailed = false
        val cancelled = synchronized(guard) {
            if (closed) return
            if (closing) {
                closed = true
                failureListener = null
            }
            generation++
            val requests = listOfNotNull(activeRequest, pending)
            pending = null
            if (flashedHwnd != 0L) {
                flashFailed = !flashWindow(flashedHwnd, FLASHW_STOP)
                flashedHwnd = 0L
            }
            guard.notifyAll()
            (activeOutput to requests).also { activeOutput = null }
        }
        // WinMmAudioOutput synchronizes close/write and resets queued native buffers before freeing.
        // Do not wait for drain, or hold the command lock while the driver closes.
        if (cancelled.first?.close() == false || flashFailed) reportFailure()
        cancelled.second.forEach { it.finish(false) }
    }

    private fun runWorker() {
        while (true) {
            val request = synchronized(guard) {
                while (!closed && pending == null) guard.wait()
                if (closed) return
                checkNotNull(pending).also { pending = null; activeRequest = it }
            }
            var output: OwnedOutput? = null
            var failureReported = false
            var success = false
            try {
                if (!isActive(request)) continue
                val samples = request.pcm?.samples(request.volumePercent) ?: pcmFactory(request.volumePercent)
                if (!isActive(request)) continue
                // A cancellation during a slow device open still prevents its first write.
                val opened = OwnedOutput(audioFactory())
                output = opened
                val accepted = synchronized(guard) {
                    (!closed && generation == request.generation).also { valid ->
                        if (valid) activeOutput = opened
                    }
                }
                if (accepted && isActive(request)) {
                    opened.output.start()
                    if (request.pcm == null) {
                        if (!isActive(request)) continue
                        // Preserve the original recording's complete single-write contract.
                        opened.output.write(samples, samples.size)
                    } else {
                        var offset = 0
                        while (offset < samples.size) {
                            if (!isActive(request)) break
                            val length = minOf(PREPARED_CHUNK_BYTES, samples.size - offset)
                            opened.output.write(samples.copyOfRange(offset, offset + length), length)
                            offset += length
                        }
                        if (offset != samples.size) continue
                    }
                    if (!isActive(request)) continue
                    opened.output.drain()
                    success = isActive(request)
                }
            } catch (_: Throwable) {
                // A missing/disconnected output device must not terminate the notification owner.
                // WinMmAudioOutput retains native memory if reset/unprepare fails for driver safety.
                failureReported = true
                reportFailure(request.generation)
            } finally {
                synchronized(guard) {
                    if (activeOutput === output) activeOutput = null
                }
                if (output?.close() == false) {
                    success = false
                    if (!failureReported) reportFailure(request.generation)
                }
                val leaseActive = success && runCatching(request.active).getOrDefault(false)
                val completion = synchronized(guard) {
                    // Claim completion before removing the active request. A concurrent stop must
                    // either see it here or invalidate this generation before success is claimed.
                    request.claim(leaseActive && !closed && generation == request.generation).also {
                        if (activeRequest === request) activeRequest = null
                    }
                }
                completion?.invoke()
            }
        }
    }

    private fun isActive(request: SoundRequest): Boolean =
        !request.finished.get() && runCatching(request.active).getOrDefault(false) && synchronized(guard) {
            !closed && generation == request.generation
        }

    private fun reportFailure(expectedGeneration: Long? = null) {
        val listener = synchronized(guard) {
            if (closed || (expectedGeneration != null && generation != expectedGeneration)) return
            failureListener
        }
        // Only signal the category. Native exception text can contain device-specific information,
        // and a consumer callback must not be allowed to kill the shared worker.
        runCatching { listener?.invoke() }
    }

    private fun flashWindow(target: Long, flags: Int): Boolean =
        runCatching {
            require(Native.POINTER_SIZE == 8) { "The desktop notification host requires Windows x64." }
            // Win64 FLASHWINFO: UINT at 0, aligned HWND at 8, DWORD/UINT/DWORD at 16/20/24.
            // https://learn.microsoft.com/windows/win32/api/winuser/ns-winuser-flashwinfo
            // FlashWindowEx's BOOL reports the *previous active state*, not success/failure.
            Memory(32).use { info ->
                info.clear()
                info.setInt(0, 32)
                info.setPointer(8, Pointer(target))
                info.setInt(16, flags)
                flashDriver.flash(info)
            }
        }.isSuccess

    private class SoundRequest(
        val pcm: NotificationSoundPcm?,
        val volumePercent: Int,
        val generation: Long,
        val active: () -> Boolean,
        val completed: (Boolean) -> Unit,
    ) {
        val finished = AtomicBoolean()
        fun finish(success: Boolean) {
            claim(success)?.invoke()
        }
        fun claim(success: Boolean): (() -> Unit)? =
            if (finished.compareAndSet(false, true)) ({ runCatching { completed(success) }; Unit }) else null
    }

    private class OwnedOutput(val output: VideoAudioOutput) {
        private val closed = AtomicBoolean()
        fun close(): Boolean = !closed.compareAndSet(false, true) || runCatching { output.close() }.isSuccess
    }

    private companion object {
        const val FLASHW_STOP = 0
        const val FLASHW_ALL = 3
        const val FLASHW_TIMERNOFG = 12
        const val PREPARED_CHUNK_BYTES = NotificationSoundPcm.BYTES_PER_MILLISECOND * 10
    }
}

/** Kept for Windows backend contract tests and downstream source compatibility. */
internal typealias WindowsNotificationSignals = QueuedNotificationSignals

internal fun desktopNotificationSignals(window: () -> Long): DeviceNotificationSignals =
    QueuedNotificationSignals(window, windowsAttention = DesktopPlatform.current.isWindows)

/** Injecting this boundary keeps tests independent of User32 and the actual Tao window. */
internal fun interface NotificationWindowFlashDriver {
    fun flash(info: Pointer): Int
}

private class NativeNotificationWindowFlashDriver : NotificationWindowFlashDriver {
    private val api by lazy { NativeLibrary.getInstance("user32") }
    override fun flash(info: Pointer): Int =
        api.getFunction("FlashWindowEx", Function.ALT_CONVENTION).invokeInt(arrayOf(info))
}

