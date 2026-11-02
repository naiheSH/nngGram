package com.badnng.moe.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.badnng.moe.ComposePixelFrame
import com.badnng.moe.ComposePixelScene
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatResult
import com.badnng.moe.chat.RichMessageText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Surface
import java.io.ByteArrayInputStream
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Synthetic media only; -PmediaSmoke supplies the bundled decoder. No real audio device is opened. */
class InlineVideoPlayerSmokeTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeTest
    fun installSceneLifecycleDispatcher() {
        Dispatchers.setMain(object : MainCoroutineDispatcher() {
            override val immediate get() = this
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterTest
    fun restoreLifecycleDispatcherAfterScenesClose() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadedVideoPauseAndResumeKeepTheRealRasterInTheProductionPlayerBody() = runBlocking {
        withTimingVideo(videoDuration = 8.0, frameRate = 30) { file ->
            withResumeThumbnail { thumbnail ->
                for ((density, dark) in listOf(1f to false, 1.25f to true)) {
                    InlineVideoPlayer(file.toString(), autoPlay = false,
                        audioFactory = { error("Silent fixture must never open audio") }).use { player ->
                        awaitState(player) { it.ready && !it.loading && it.hasFrame }
                        playerScene(player, thumbnail, density, dark).use { scene ->
                            val paused = scene.settledFrame(3)
                            assertBlue(paused.videoSample(density))
                            assertNoLoadingCover(scene)
                            scene.clickDescription("播放")
                            assertTrue(player.state.value.playing)
                            assertFalse(player.state.value.loading,
                                "Restarting the decoder for an already displayed local frame is not an initial load.")
                            repeat(10) {
                                val resumed = scene.settledFrame(1, 16)
                                assertNoLoadingCover(scene)
                                assertTrue(scene.loadingDescriptions().isEmpty(),
                                    "A fully local resume must not flash a loading arc between decoder runs.")
                                assertVideoColor(resumed.videoSample(density))
                                assertTrue(player.state.value.hasFrame)
                                delay(10)
                            }
                            exportResumeFrame(scene.settledFrame(1), "local-resumed-$dark-$density")
                            scene.clickDescription("暂停")
                            assertFalse(player.state.value.playing)
                            assertTrue(player.state.value.hasFrame)
                            val stopped = player.currentPosition()
                            delay(100)
                            assertEquals(stopped, player.currentPosition(), 0.001)
                            assertNoLoadingCover(scene)
                            scene.clickDescription("播放")
                            assertFalse(player.state.value.loading)
                            assertNoLoadingCover(scene)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun resumedRangeWaitShowsAnArcOverTheRetainedVideoRatherThanItsBlurredThumbnail() = runBlocking {
        withVideo(hasAudio = false) { file ->
            // Avoid a whole-file read while keeping a short deterministic generated video.
            java.io.RandomAccessFile(file.toFile(), "rw").use { output ->
                val original = output.length()
                output.seek(original)
                output.writeInt(32 * 1024 * 1024)
                output.writeBytes("free")
                output.setLength(original + 32 * 1024 * 1024)
            }
            withResumeThumbnail { thumbnail ->
                val blocked = java.util.concurrent.atomic.AtomicBoolean(false)
                java.nio.channels.FileChannel.open(file).use { channel ->
                    ProgressiveVideoSource(Files.size(file)) { offset, count, result ->
                        if (blocked.get()) result(ChatResult(error = ChatError.NOT_READY))
                        else {
                            val buffer = java.nio.ByteBuffer.allocate(count)
                            val read = channel.read(buffer, offset)
                            result(ChatResult(buffer.array().copyOf(read)))
                        }
                    }.use { source ->
                        InlineVideoPlayer("", autoPlay = false, progressiveSource = source,
                            audioFactory = { error("Silent fixture must never open audio") }).use { player ->
                            awaitState(player) { it.ready && !it.loading && it.hasFrame }
                            playerScene(player, thumbnail, 1f, false).use { scene ->
                                scene.settledFrame(3)
                                blocked.set(true)
                                scene.clickDescription("播放")
                                assertFalse(player.state.value.loading)
                                awaitState(player) { it.buffering }
                                repeat(8) {
                                    val retained = scene.settledFrame(1, 16)
                                    assertNoLoadingCover(scene)
                                    assertBlue(retained.videoSample(1f))
                                    assertTrue(scene.loadingDescriptions().isNotEmpty(),
                                        "A real blocked native range keeps the Miuix indicator over the clear frame.")
                                }
                                exportResumeFrame(scene.settledFrame(2), "range-wait-retained")
                                player.pause()
                                val stopped = player.currentPosition()
                                scene.settledFrame(3)
                                assertTrue(scene.loadingDescriptions().isEmpty())
                                assertNoLoadingCover(scene)
                                assertTrue(player.state.value.hasFrame && player.hasFrame())
                                delay(100)
                                assertEquals(stopped, player.currentPosition(), 0.001)
                                blocked.set(false)
                                scene.clickDescription("播放")
                                awaitState(player) { !it.buffering && player.currentPosition() > stopped + 0.1 }
                                assertNoLoadingCover(scene)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun nativeAnimationLoopsAndNeverCreatesAudioEvenWhenItsMp4HasAnAudioStream() = runBlocking {
        withVideo(hasAudio = true) { file ->
            val attempts = AtomicInteger()
            InlineVideoPlayer(file.toString(), initialVolume = 0f, videoOnly = true, loop = true,
                audioFactory = { attempts.incrementAndGet(); error("Animation must never open audio") }).use { player ->
                awaitState(player) { !it.loading && it.position >= 1.4 }
                assertRed(drawCenter(player))
                awaitCondition { player.currentPosition() < 0.4 }
                awaitCondition { (drawCenter(player) and 255) > 180 }
                assertFalse(player.state.value.ended)
                assertFalse(player.state.value.failed)
                assertEquals(0, attempts.get())
                player.pause()
                val stopped = player.currentPosition()
                delay(180)
                assertEquals(stopped, player.currentPosition(), 0.001)
                assertFalse(player.state.value.playing)
                assertEquals(0, attempts.get())
            }
        }
    }

    @Test
    fun profileAnimationStartsAtItsCoverBeforeTheFirstRasterThenLoopsFromZero() = runBlocking {
        withVideo(hasAudio = true) { file ->
            val attempts = AtomicInteger()
            InlineVideoPlayer(file.toString(), autoPlay = false, videoOnly = true, loop = true,
                initialPositionMillis = 1_500,
                audioFactory = { attempts.incrementAndGet(); error("Profile preview must never open audio") }).use { player ->
                awaitState(player) { it.ready && !it.loading && player.hasFrame() }
                assertEquals(1.5, player.state.value.position, 0.001)
                assertRed(drawCenter(player))
                delay(120)
                assertEquals(1.5, player.currentPosition(), 0.001)
                player.play()
                awaitCondition { player.currentPosition() > 1.65 }
                player.pause()
                val stopped = player.currentPosition()
                assertRed(drawCenter(player))
                delay(120)
                assertEquals(stopped, player.currentPosition(), 0.001)
                player.play()
                awaitCondition { player.currentPosition() < 0.4 }
                awaitCondition { (drawCenter(player) and 255) > 180 }
                assertBlue(drawCenter(player))
                assertFalse(player.state.value.failed)
                assertFalse(player.state.value.ended)
                assertEquals(0, attempts.get())
            }
        }
    }

    @Test
    fun profileAnimationComposablePassesCoverTimeToItsFirstDecoder() = runBlocking {
        withTimingVideo(videoDuration = 8.0, frameRate = 30) { file ->
            ComposePixelScene(320, 240) {
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                        currentState = Lifecycle.State.RESUMED
                    }
                } }
                val window = LocalWindowInfo.current
                val focusedWindow = remember(window) { object : WindowInfo by window {
                    override val isWindowFocused = true
                } }
                val theme = remember { ThemeController(ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalWindowInfo provides focusedWindow) {
                    MiuixTheme(controller = theme) {
                        AnimationPlayback(file.toString(), true, Modifier.fillMaxSize(), startPositionMillis = 1_500)
                    }
                }
            }.use { scene ->
                var firstRaster: Int? = null
                awaitCondition {
                    val color = scene.settledFrame(1, 16)[80, 180]
                    val red = color ushr 16 and 255
                    val blue = color and 255
                    if (red > 180 && blue < 60 || blue > 180 && red < 60) firstRaster = color
                    firstRaster != null
                }
                assertRed(assertNotNull(firstRaster))
            }
        }
    }

    @Test
    fun profileAnimationPastDurationUsesTheLastSparseFrameWithoutOpeningAudio() = runBlocking {
        withTimingVideo(videoDuration = 2.0, frameRate = 1, audioDuration = 3.0) { file ->
            val attempts = AtomicInteger()
            InlineVideoPlayer(file.toString(), autoPlay = false, videoOnly = true,
                initialPositionMillis = Long.MAX_VALUE,
                audioFactory = { attempts.incrementAndGet(); error("Profile preview must never open audio") }).use { player ->
                awaitState(player) { it.ready && !it.loading && player.hasFrame() }
                assertEquals(3.0, player.state.value.duration, 0.04)
                assertEquals(player.state.value.duration - 0.001, player.state.value.position, 0.0001)
                assertRed(drawCenter(player))
                assertFalse(player.state.value.playing)
                assertFalse(player.state.value.failed)
                assertEquals(0, attempts.get())
            }
        }
    }

    @Test
    fun negativeInitialPositionCannotPublishAFrameOrOpenAudio() = runBlocking {
        withVideo(hasAudio = true) { file ->
            val attempts = AtomicInteger()
            InlineVideoPlayer(file.toString(), initialPositionMillis = -1, videoOnly = true,
                audioFactory = { attempts.incrementAndGet(); error("Invalid position must never open audio") }).use { player ->
                awaitState(player) { it.failed }
                assertFalse(player.state.value.loading)
                assertFalse(player.state.value.ready)
                assertFalse(player.state.value.playing)
                assertFalse(player.hasFrame())
                player.play()
                assertFalse(player.hasFrame())
                assertEquals(0, attempts.get())
            }
        }
    }

    @Test
    fun nativeGifUsesItsActualFramesAndDurationRatherThanAStaticPoster() = runBlocking {
        val file = Files.createTempFile("synthetic-inline-animation-", ".gif")
        try {
            MediaTools.capture(assertNotNull(MediaDecoder.findFfmpeg()), listOf(
                "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=blue:s=64x48:r=10:d=0.6", "-filter_complex",
                // Preserve both known colors in the actual GIF palette. The default GIF
                // quantizer dithers blue, making a single downsampled raster pixel ambiguous.
                "[0:v]drawbox=color=red:t=fill:enable='gte(t,0.3)',split[frames][palette];" +
                    "[palette]palettegen[colors];[frames][colors]paletteuse=dither=none",
                "-threads", "1", "-loop", "0", "-y", file.toString()), timeoutSeconds = 15)
            InlineVideoPlayer(file.toString(), autoPlay = false, videoOnly = true,
                audioFactory = { error("GIF must never open audio") }).use { player ->
                awaitState(player) { it.ready && !it.loading && player.hasFrame() }
                assertEquals(0.6, player.state.value.duration, 0.12)
                assertBlue(drawCenter(player))
                player.play()
                awaitState(player) { it.ended }
                assertRed(drawCenter(player))
                assertFalse(player.state.value.failed)
                assertFalse(player.state.value.audioUnavailable)
            }
        } finally { deleteTemporary(file) }
    }

    @Test
    fun returningFromTheViewerFlushesAudibleBuffersAndRestoresMutedPlayback() = runBlocking {
        withVideo(hasAudio = true) { file ->
            val sinks = CopyOnWriteArrayList<RecordingAudioOutput>()
            InlineVideoPlayer(file.toString(), initialVolume = 0f, audioFactory = {
                RecordingAudioOutput().also(sinks::add)
            }).use { player ->
                val presentation = VideoPresentationState()
                awaitCondition { sinks.sumOf { it.bytesWritten } > 12_000 }
                player.applyPlaybackPreferences(presentation.enterViewer(player.playbackPreferences()))
                awaitCondition { sinks.sumOf { it.nonZeroBytes } > 0 }
                val viewerSinks = sinks.toList()
                player.applyPlaybackPreferences(presentation.leaveViewer(player.playbackPreferences()))
                assertEquals(0f, player.state.value.volume)
                assertTrue(viewerSinks.all { it.closed })
                awaitCondition { sinks.size > viewerSinks.size && sinks.last().bytesWritten > 12_000 }
                assertEquals(0L, sinks.last().nonZeroBytes)
                assertTrue(player.playbackPreferences().playing)
            }
        }
    }

    @Test
    fun initiallyMutedPlaybackIsSilentUntilTheUserRestoresVolume() = runBlocking {
        withVideo(hasAudio = true) { file ->
            val sinks = CopyOnWriteArrayList<RecordingAudioOutput>()
            InlineVideoPlayer(file.toString(), initialVolume = 0f, audioFactory = {
                RecordingAudioOutput().also(sinks::add)
            }).use { player ->
                assertEquals(0f, player.state.value.volume)
                awaitCondition { sinks.sumOf { it.bytesWritten } > 24_000 }
                assertEquals(0L, sinks.sumOf { it.nonZeroBytes })
                player.setVolume(0.6f)
                awaitCondition { sinks.sumOf { it.nonZeroBytes } > 0 }
                assertEquals(0.6f, player.state.value.volume)
                assertFalse(player.state.value.audioUnavailable)
            }
        }
    }

    @Test
    fun mutedPlaybackKeepsItsVolumeAcrossPausingAndSeeking() = runBlocking {
        withVideo(hasAudio = true) { file ->
            val sinks = CopyOnWriteArrayList<RecordingAudioOutput>()
            InlineVideoPlayer(file.toString(), initialVolume = 0f, audioFactory = {
                RecordingAudioOutput().also(sinks::add)
            }).use { player ->
                awaitState(player) { it.position >= 0.2 && !it.loading }
                player.pause()
                player.seek(1.6)
                awaitState(player) { !it.loading && !it.playing && abs(it.position - 1.6) < 0.04 }
                player.play()
                awaitState(player) { it.ended }
                assertEquals(0f, player.state.value.volume)
                assertTrue(sinks.sumOf { it.bytesWritten } > 0)
                assertEquals(0L, sinks.sumOf { it.nonZeroBytes })
                assertFalse(player.state.value.audioUnavailable)
            }
        }
    }

    @Test
    fun firstFrameAndVideoTimelineDoNotWaitForASlowAudioDevice() = runBlocking {
        withVideo(hasAudio = true) { file ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val sink = RecordingAudioOutput()
            try {
                InlineVideoPlayer(file.toString(), audioFactory = {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    sink
                }).use { player ->
                    awaitCondition { entered.count == 0L }
                    awaitState(player) { !it.loading && player.hasFrame() }
                    assertBlue(drawCenter(player))
                    // The initial image and advancing video are independent of audio readiness.
                    awaitState(player) { it.audioUnavailable && player.currentPosition() > 0.1 }
                    assertFalse(player.state.value.failed)
                    assertFalse(sink.started)
                    awaitState(player) { it.ended }
                    release.countDown()
                    awaitCondition { sink.closed }
                    assertFalse(sink.started, "A late audio startup must not play out of sync")
                }
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun nativeSmokeContractExercisesSyntheticLoopbackAndRasterPlayback() {
        val directory = Files.createTempDirectory("media-smoke-contract-")
        val report = directory.resolve("report.json")
        try {
            MediaNativeImageSmoke.run(report)
            assertTrue(Files.readString(report).contains("\"passed\":true"))
            assertTrue(Files.readString(report).contains("\"audioDeviceOpened\":false"))
        } finally { Files.deleteIfExists(report); Files.deleteIfExists(directory) }
    }
    @Test
    fun progressivePlaybackDrawsBeforeFullFileReadAndCanSeekUsingRanges() = runBlocking {
        withVideo(hasAudio = false) { file ->
            // A legal MP4 free box makes whole-file download observable without adding a long test video.
            java.io.RandomAccessFile(file.toFile(), "rw").use { output ->
                val original = output.length()
                output.seek(original)
                output.writeInt(32 * 1024 * 1024)
                output.writeBytes("free")
                output.setLength(original + 32 * 1024 * 1024)
            }
            val bytesRead = java.util.concurrent.atomic.AtomicLong()
            val offsets = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
            val blocked = java.util.concurrent.atomic.AtomicBoolean(false)
            java.nio.channels.FileChannel.open(file).use { channel ->
                ProgressiveVideoSource(Files.size(file)) { offset, count, result ->
                    if (blocked.get()) result(com.badnng.moe.chat.ChatResult(error = com.badnng.moe.chat.ChatError.NOT_READY))
                    else {
                        val buffer = java.nio.ByteBuffer.allocate(count)
                        val read = channel.read(buffer, offset)
                        bytesRead.addAndGet(read.toLong())
                        offsets.add(offset)
                        result(com.badnng.moe.chat.ChatResult(buffer.array().copyOf(read)))
                    }
                }.use { source ->
                    InlineVideoPlayer("", autoPlay = false, progressiveSource = source,
                        audioFactory = { error("Silent fixture") }).use { player ->
                        awaitState(player) { it.ready && !it.loading && player.hasFrame() }
                        assertBlue(drawCenter(player))
                        assertTrue(bytesRead.get() < Files.size(file), "A frame must appear before all file bytes are requested")
                        blocked.set(true)
                        player.seek(1.6)
                        awaitState(player) { it.buffering }
                        blocked.set(false)
                        awaitState(player) { !it.loading && !it.buffering && abs(it.position - 1.6) < 0.04 }
                        assertRed(drawCenter(player))
                        player.play()
                        awaitState(player) { it.ended }
                        assertFalse(player.state.value.failed)
                        assertTrue(offsets.any { it > 0 })
                    }
                }
            }
        }
    }

    @Test
    fun pausedFirstFrameDrawsAndPlaybackDecodesNonSilentPcmUntilEnd() = runBlocking {
        withVideo(hasAudio = true) { file ->
            val sinks = CopyOnWriteArrayList<RecordingAudioOutput>()
            InlineVideoPlayer(file.toString(), autoPlay = false, audioFactory = {
                RecordingAudioOutput().also(sinks::add)
            }).use { player ->
                awaitState(player) { it.ready && !it.loading && player.hasFrame() }
                assertFalse(player.state.value.playing)
                assertTrue(sinks.isEmpty())
                assertBlue(drawCenter(player))
                player.play()
                awaitState(player) { it.ended }
                assertFalse(player.state.value.playing)
                assertFalse(player.state.value.failed)
                assertFalse(player.state.value.audioUnavailable)
                assertEquals(player.state.value.duration, player.state.value.position, 0.04)
                assertTrue(sinks.sumOf { it.bytesWritten } > 48_000)
                assertTrue(sinks.sumOf { it.nonZeroBytes } > 0)
                assertTrue(sinks.all { it.started && it.drained })
                awaitCondition { sinks.all { it.closed } }
            }
        }
    }

    @Test
    fun pauseKeepsPositionAndSeekRefreshesPausedFrameBeforeResuming() = runBlocking {
        withVideo(hasAudio = true) { file ->
            val sinks = CopyOnWriteArrayList<RecordingAudioOutput>()
            InlineVideoPlayer(file.toString(), audioFactory = {
                RecordingAudioOutput().also(sinks::add)
            }).use { player ->
                awaitState(player) { it.position >= 0.2 && !it.loading }
                player.pause()
                val paused = player.state.value.position
                assertEquals(paused, player.currentPosition(), 0.001)
                assertFalse(player.state.value.playing)
                delay(200)
                assertEquals(paused, player.state.value.position, 0.001)
                assertEquals(paused, player.currentPosition(), 0.001)
                awaitCondition { sinks.isNotEmpty() && sinks.all { it.closed } }
                player.seek(1.6)
                awaitState(player) { !it.loading && !it.playing && abs(it.position - 1.6) < 0.04 }
                assertRed(drawCenter(player))
                val outputsBeforeResume = sinks.size
                player.play()
                awaitState(player) { it.ended }
                assertTrue(sinks.size > outputsBeforeResume)
                assertTrue(sinks.last().nonZeroBytes > 0)
                assertFalse(player.state.value.failed)
            }
        }
    }

    @Test fun presentationClockAdvancesBetweenCoarseStateNotificationsAndStopsOnPause() = runBlocking {
        withVideo(hasAudio = false) { file ->
            InlineVideoPlayer(file.toString()).use { player ->
                awaitState(player) { it.ready && !it.loading && it.position > 0.1 }
                val first = player.currentPosition()
                delay(45)
                assertTrue(player.currentPosition() > first + 0.02)
                player.pause()
                val stopped = player.currentPosition()
                delay(80)
                assertEquals(stopped, player.currentPosition(), 0.001)
            }
        }
    }

    @Test
    fun silentVideoFinishesWithoutCreatingAnAudioOutput() = runBlocking {
        withVideo(hasAudio = false) { file ->
            val attempts = AtomicInteger()
            InlineVideoPlayer(file.toString(), audioFactory = {
                attempts.incrementAndGet()
                error("A silent synthetic clip must not request audio")
            }).use { player ->
                awaitState(player) { it.ended }
                assertTrue(player.hasFrame())
                assertFalse(player.state.value.audioUnavailable)
                assertFalse(player.state.value.failed)
                assertEquals(0, attempts.get())
                assertRed(drawCenter(player))
            }
        }
    }

    @Test
    fun closeReleasesAudioAndPreventsLateFramesOrStateUpdates() = runBlocking {
        withVideo(hasAudio = true) { file ->
            val sinks = CopyOnWriteArrayList<RecordingAudioOutput>()
            val player = InlineVideoPlayer(file.toString(), audioFactory = {
                RecordingAudioOutput().also(sinks::add)
            })
            try {
                awaitState(player) { it.position >= 0.2 && player.hasFrame() }
                assertTrue(sinks.isNotEmpty())
                player.close()
                val closedState = player.state.value
                val closedBytes = sinks.sumOf { it.bytesWritten }
                assertTrue(sinks.all { it.closed })
                assertFalse(player.hasFrame())
                delay(250)
                assertFalse(player.hasFrame())
                assertEquals(closedState, player.state.value)
                assertEquals(closedBytes, sinks.sumOf { it.bytesWritten })
                assertEquals(BACKGROUND, drawCenter(player))
            } finally {
                player.close()
            }
        }
    }

    @Test
    fun malformedVideoFailsWithoutOpeningAudio() = runBlocking {
        val file = Files.createTempFile("synthetic-inline-invalid-", ".mp4")
        try {
            Files.writeString(file, "synthetic invalid video")
            val attempts = AtomicInteger()
            InlineVideoPlayer(file.toString(), audioFactory = {
                attempts.incrementAndGet()
                error("Invalid synthetic video must not request audio")
            }).use { player ->
                awaitCondition { player.state.value.failed }
                assertFalse(player.state.value.loading)
                assertFalse(player.state.value.playing)
                assertFalse(player.hasFrame())
                assertEquals(0, attempts.get())
            }
        } finally {
            deleteTemporary(file)
        }
    }

    @Test
    fun audioDeviceFailureFallsBackToSilentVideoPlayback() = runBlocking {
        withVideo(hasAudio = true) { file ->
            val attempts = AtomicInteger()
            InlineVideoPlayer(file.toString(), audioFactory = {
                attempts.incrementAndGet()
                throw IllegalStateException("Synthetic audio output unavailable")
            }).use { player ->
                awaitState(player) { it.audioUnavailable && player.hasFrame() }
                awaitState(player) { it.ended }
                assertFalse(player.state.value.failed)
                assertTrue(player.state.value.audioUnavailable)
                assertEquals(1, attempts.get())
                assertRed(drawCenter(player))
            }
        }
    }

    @Test
    fun lastSparseFrameCanBeSelectedWhilePausedAndThenPlayedToEnd() = runBlocking {
        withTimingVideo(videoDuration = 2.0, frameRate = 1) { file ->
            InlineVideoPlayer(file.toString(), autoPlay = false, audioFactory = {
                error("A silent synthetic clip must not request audio")
            }).use { player ->
                awaitState(player) { it.ready && !it.loading && player.hasFrame() }
                assertEquals(2.0, player.state.value.duration, 0.04)
                assertBlue(drawCenter(player))
                player.seek(1.96)
                awaitState(player) { !it.loading && !it.playing && abs(it.position - 1.96) < 0.04 }
                assertRed(drawCenter(player))
                player.play()
                awaitState(player) { it.ended }
                assertFalse(player.state.value.failed)
                assertEquals(2.0, player.state.value.position, 0.04)
                assertRed(drawCenter(player))
            }
        }
    }

    @Test
    fun delayedAudioKeepsItsLeadingSilenceAndSeekRemovesOnlyElapsedSilence() = runBlocking {
        withTimingVideo(videoDuration = 3.0, frameRate = 30, audioDuration = 2.0, audioOffset = 1.0) { file ->
            val sinks = CopyOnWriteArrayList<TimestampAudioOutput>()
            InlineVideoPlayer(file.toString(), audioFactory = {
                TimestampAudioOutput().also(sinks::add)
            }).use { player ->
                awaitState(player) { it.ended }
                assertFalse(player.state.value.audioUnavailable)
                val first = sinks.single()
                // AAC priming may move the first nonzero sample slightly before the 1 s boundary.
                assertTrue(first.firstNonZeroSeconds in 0.90..1.05)
                assertTrue(first.durationSeconds in 2.95..3.08)
                player.seek(1.5)
                awaitState(player) { !it.loading && !it.playing && abs(it.position - 1.5) < 0.04 }
                player.play()
                awaitState(player) { it.ended }
                assertEquals(2, sinks.size)
                assertTrue(sinks.last().firstNonZeroSeconds in 0.0..0.05)
                assertTrue(sinks.last().durationSeconds in 1.45..1.58)
                assertFalse(player.state.value.failed)
            }
        }
    }

    @Test
    fun containerDurationIncludesAudioThatOutlastsTheVideoStream() = runBlocking {
        withTimingVideo(videoDuration = 2.0, frameRate = 30, audioDuration = 3.0) { file ->
            assertEquals(3.0, AttachmentFiles.validatePlayback(file.toString()).duration, 0.04)
            val sinks = CopyOnWriteArrayList<TimestampAudioOutput>()
            InlineVideoPlayer(file.toString(), audioFactory = {
                TimestampAudioOutput().also(sinks::add)
            }).use { player ->
                awaitState(player) { it.ended }
                assertEquals(3.0, player.state.value.duration, 0.04)
                assertEquals(3.0, player.state.value.position, 0.04)
                assertTrue(sinks.single().durationSeconds in 2.95..3.08)
                assertFalse(player.state.value.failed)
                assertFalse(player.state.value.audioUnavailable)
                assertRed(drawCenter(player))
                player.seek(2.96)
                awaitState(player) { !it.loading && !it.playing && abs(it.position - 2.96) < 0.04 }
                assertRed(drawCenter(player))
                player.play()
                awaitState(player) { it.ended }
                assertFalse(player.state.value.failed)
                assertEquals(3.0, player.state.value.position, 0.04)
                assertRed(drawCenter(player))
            }
        }
    }

    private fun playerScene(player: InlineVideoPlayer, thumbnail: Path, density: Float, dark: Boolean) =
        ComposePixelScene((320 * density).roundToInt(), (240 * density).roundToInt()) {
            val owner = remember { object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                    currentState = Lifecycle.State.RESUMED
                }
            } }
            val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
            val window = LocalWindowInfo.current
            val focusedWindow = remember(window) { object : WindowInfo by window {
                override val isWindowFocused = true
            } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalLifecycleOwner provides owner,
                LocalWindowInfo provides focusedWindow) {
                MiuixTheme(controller = theme) {
                    PlayerBody(player, active = true, expanded = false, modifier = Modifier.fillMaxSize(),
                        onRetry = { error("Generated fixture should not require retry") }, onDismiss = {},
                        onSave = null, onExpand = null, caption = RichMessageText(""), title = "Synthetic video",
                        downloadedFraction = 1f, allowCaptionCopy = false, canCopyCaptionNow = { false },
                        loadingThumbnailPath = thumbnail.toString(), loadingMinithumbnail = null)
                }
            }
        }

    private fun ComposePixelScene.clickDescription(description: String) {
        settledFrame(2, 16)
        val bounds = assertNotNull(semanticsBounds {
            description in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.singleOrNull(), "The production player must expose its actual $description control.")
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
        settledFrame(2, 16)
    }

    private fun assertNoLoadingCover(scene: ComposePixelScene) {
        assertTrue(scene.unmergedSemanticsBounds {
            "真实模糊缩略图" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.isEmpty(), "The actual decoded frame must stay visible through pause, resume and later range waits.")
    }

    private fun ComposePixelScene.loadingDescriptions() = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { description ->
            description == "视频加载中" || description == "视频缓冲中"
        }
    }

    private fun ComposePixelFrame.videoSample(density: Float) = this[(80 * density).roundToInt(), (180 * density).roundToInt()]

    private suspend fun withResumeThumbnail(block: suspend (Path) -> Unit) {
        val path = Files.createTempFile("synthetic-video-resume-thumbnail-", ".png")
        try {
            val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, 0xffffcc00.toInt())
            check(ImageIO.write(image, "png", path.toFile()))
            block(path)
        } finally { deleteTemporary(path) }
    }

    private fun exportResumeFrame(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "inline-video-resume")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
        println("Synthetic loaded-video resume frame: ${directory.resolve("$name.png")}")
    }

    private suspend fun withTimingVideo(
        videoDuration: Double,
        frameRate: Int,
        audioDuration: Double? = null,
        audioOffset: Double = 0.0,
        block: suspend (Path) -> Unit,
    ) {
        val file = Files.createTempFile("synthetic-inline-timing-", ".mp4")
        try {
            val executable = assertNotNull(MediaDecoder.findFfmpeg())
            val arguments = mutableListOf("-nostdin", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=blue:s=64x48:r=$frameRate:d=$videoDuration")
            if (audioDuration != null) arguments += listOf("-itsoffset", audioOffset.toString(), "-f", "lavfi", "-i",
                "sine=frequency=660:sample_rate=48000:duration=$audioDuration")
            arguments += listOf("-map", "0:v:0", "-vf", "drawbox=color=red:t=fill:enable='gte(t,1)'")
            if (audioDuration != null) arguments += listOf("-map", "1:a:0", "-c:a", "aac")
            arguments += listOf("-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-y", file.toString())
            MediaTools.capture(executable, arguments, timeoutSeconds = 15)
            block(file)
        } finally {
            deleteTemporary(file)
        }
    }

    private suspend fun withVideo(hasAudio: Boolean, block: suspend (Path) -> Unit) {
        val file = Files.createTempFile("synthetic-inline-video-", ".mp4")
        try {
            val executable = assertNotNull(MediaDecoder.findFfmpeg())
            val arguments = mutableListOf("-nostdin", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=blue:s=64x48:r=30:d=1.2",
                "-f", "lavfi", "-i", "color=c=red:s=64x48:r=30:d=1.2")
            if (hasAudio) arguments += listOf("-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=2.4")
            arguments += listOf("-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]", "-map", "[v]")
            if (hasAudio) arguments += listOf("-map", "2:a:0", "-c:a", "aac", "-shortest")
            arguments += listOf("-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-y", file.toString())
            MediaTools.capture(executable, arguments, timeoutSeconds = 15)
            block(file)
        } finally {
            deleteTemporary(file)
        }
    }

    private suspend fun awaitState(player: InlineVideoPlayer, predicate: (VideoPlayerState) -> Boolean) {
        val reached = kotlinx.coroutines.withTimeoutOrNull(15_000) {
            while (!predicate(player.state.value)) {
                assertFalse(player.state.value.failed, "Synthetic video player failed before the expected state: ${player.state.value}")
                delay(20)
            }
            true
        } == true
        assertTrue(reached, "Synthetic video did not reach the expected state within 15s: ${player.state.value}")
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(15_000) {
            while (!predicate()) delay(20)
        }
    }

    private fun drawCenter(player: InlineVideoPlayer): Int = Surface.makeRasterN32Premul(64, 48).use { surface ->
        surface.canvas.clear(BACKGROUND)
        player.draw(surface.canvas, 64f, 48f)
        surface.makeImageSnapshot().use { image ->
            assertNotNull(image.encodeToData()).use { data ->
                val bitmap = assertNotNull(ImageIO.read(ByteArrayInputStream(data.bytes)))
                bitmap.getRGB(32, 24)
            }
        }
    }

    private fun assertBlue(color: Int) {
        assertTrue((color and 255) > 180 && ((color ushr 16) and 255) < 60, "Expected the initial synthetic blue frame")
    }

    private fun assertVideoColor(color: Int) {
        val red = color ushr 16 and 255
        val green = color ushr 8 and 255
        val blue = color and 255
        assertTrue((blue > 180 && red < 60 || red > 180 && blue < 60) && green < 60,
            "Resume must show an actual blue/red video raster, never the distinct yellow loading thumbnail.")
    }

    private fun assertRed(color: Int) {
        assertTrue(((color ushr 16) and 255) > 180 && (color and 255) < 60, "Expected the later synthetic red frame")
    }

    private suspend fun deleteTemporary(file: Path) {
        // Decoder shutdown is asynchronous; bounded retries accommodate Windows file handles.
        repeat(50) {
            if (runCatching { Files.deleteIfExists(file) }.isSuccess) return
            delay(20)
        }
        Files.deleteIfExists(file)
    }

    private class RecordingAudioOutput : VideoAudioOutput {
        private val guard = Any()
        @Volatile var started = false
            private set
        @Volatile var drained = false
            private set
        @Volatile var closed = false
            private set
        @Volatile var bytesWritten = 0L
            private set
        @Volatile var nonZeroBytes = 0L
            private set

        override fun start() = synchronized(guard) { if (!closed) started = true }

        override fun write(bytes: ByteArray, length: Int) {
            synchronized(guard) {
                if (closed) return
                check(started)
                check(length in 0..bytes.size)
                bytesWritten += length
                nonZeroBytes += bytes.take(length).count { it != 0.toByte() }
            }
            // The player's output format is 48 kHz, stereo, signed 16-bit PCM.
            Thread.sleep((length * 1_000L / (48_000L * 2 * 2)).coerceIn(1, 20))
        }

        override fun drain() = synchronized(guard) { if (!closed) drained = true }

        override fun close() = synchronized(guard) { closed = true }
    }

    /** Adds timeline observations around the existing fake sink; never accesses JavaSound. */
    private class TimestampAudioOutput : VideoAudioOutput {
        private val output = RecordingAudioOutput()
        @Volatile private var firstNonZeroByte = -1L
        val firstNonZeroSeconds: Double get() = firstNonZeroByte / PCM_BYTES_PER_SECOND
        val durationSeconds: Double get() = output.bytesWritten / PCM_BYTES_PER_SECOND

        override fun start() = output.start()
        override fun write(bytes: ByteArray, length: Int) {
            if (firstNonZeroByte < 0) {
                val index = (0 until length).firstOrNull { bytes[it] != 0.toByte() }
                if (index != null) firstNonZeroByte = output.bytesWritten + index
            }
            output.write(bytes, length)
        }
        override fun drain() = output.drain()
        override fun close() = output.close()
    }

    private companion object {
        const val BACKGROUND = -65281 // Opaque magenta, absent from the generated video.
        const val PCM_BYTES_PER_SECOND = 48_000.0 * 2 * 2 // 48 kHz, stereo, signed 16-bit PCM.
    }
}
