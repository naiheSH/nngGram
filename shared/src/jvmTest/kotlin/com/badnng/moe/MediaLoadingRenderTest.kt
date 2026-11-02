package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.media.InlineVideoLoadingBackdrop
import com.badnng.moe.media.InlineVideoProgressOverlay
import com.badnng.moe.media.VideoPlayerState
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real thumbnail pixels and the production loading composables; no account, video decoder or audio. */
class MediaLoadingRenderTest {
    @Test
    fun photoDownloadAndPreparedRasterKeepTheRealBlurUntilTheClearImageCanDraw() = sources { full, thumbnail ->
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            val pending = MediaFile(1, size = 1000, canDownload = true, downloading = true, downloadedSize = 200)
            val media = MessageMedia(pending, MediaFile(2, localPath = thumbnail.toString(), downloaded = true),
                width = 320, height = 160, thumbnailFormat = MediaFormat.PHOTO)
            val snapshot = mutableStateOf(ChatSnapshot(token = ChatToken(1, 1, 10)))
            val visible = mutableStateOf(true)
            val active = mutableStateOf(true)
            var downloads = 0
            val automaticVisibility = mutableListOf<Pair<Long, Boolean>>()
            mediaScene(density, dark) {
                RemoteMedia(media, snapshot.value, ChatFeatureActions(downloadFile = { _, _ -> downloads++ },
                    automaticMedia = { _, messageId, visible -> automaticVisibility += messageId to visible }),
                    visible.value, active.value, Modifier.size(320.dp, 160.dp), photoBackground = true,
                    contentScale = ContentScale.Crop, messageId = 99L)
            }.use { scene ->
                scene.awaitDescription("照片模糊底图")
                val ring = scene.awaitDescription("图片加载中")
                assertTrue(abs(ring.width - 30f * density) <= 1f && abs(ring.height - 30f * density) <= 1f)
                val loading = scene.settledFrame(2)
                assertWarmAndCool(loading, density)
                export(loading, "photo-loading-${theme(dark)}-$density")
                val later = scene.settledFrame(3)
                assertTrue(differs(loading, later, ring), "The Miuix loading arc must animate above the real source.")

                snapshot.value = snapshot.value.copy(mediaFiles = mapOf(1 to pending.copy(
                    localPath = full.toString(), downloaded = true, downloading = false)))
                repeat(8) {
                    val frame = scene.settledFrame(1, 16)
                    val pixel = frame[(48 * density).roundToInt(), (92 * density).roundToInt()]
                    val warmBlur = (pixel ushr 16 and 255) > (pixel and 255) + 50
                    val clearGreen = (pixel ushr 8 and 255) > (pixel ushr 16 and 255) + 70
                    assertTrue(warmBlur || clearGreen,
                        "Download acceptance and worker preparation must not expose a neutral/empty frame.")
                }
                scene.awaitDescription("聊天图片")
                val ready = scene.settledFrame(4)
                assertTrue(scene.descriptions("图片加载中").isEmpty())
                assertTrue(scene.descriptions("照片模糊底图").isEmpty(), "The ready Crop branch retains its independent clear geometry.")
                assertEquals(GREEN, ready[(48 * density).roundToInt(), (92 * density).roundToInt()])
                assertEquals(0, downloads, "Ready native thumbnails and an in-flight original need no additional download.")
                assertEquals(listOf(99L to true), automaticVisibility,
                    "Like PhotoMessageContent, an inline photo routes its message identity through AUTO visibility rather than a generic ARTWORK download.")
                export(ready, "photo-ready-${theme(dark)}-$density")

                snapshot.value = snapshot.value.copy(mediaFiles = emptyMap(), fileErrors = mapOf(1 to ChatError.REQUEST_FAILED))
                scene.settledFrame(3)
                assertTrue(scene.descriptions("图片加载中").isEmpty(), "A native failure must stop the spinner.")
                assertTrue(scene.unmergedSemanticsBounds {
                    it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "媒体加载失败" }
                }.isNotEmpty())
                active.value = false
                visible.value = false
                scene.settledFrame(3)
                assertTrue(scene.descriptions("图片加载中").isEmpty())
                assertEquals(99L to false, automaticVisibility.last(), "Leaving the photo must revoke its AUTO visibility.")
            }
        }
    }

    @Test
    fun videoInitialBufferingUsesNativeBlurButLaterBufferingRetainsTheActualFrame() = sources { _, thumbnail ->
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            val state = mutableStateOf(VideoPlayerState())
            val active = mutableStateOf(true)
            val focused = mutableStateOf(true)
            val path = mutableStateOf<String?>(thumbnail.toString())
            mediaScene(density, dark, focused = { focused.value }) {
                Box(Modifier.size(320.dp, 160.dp).background(Color(GREEN))) {
                    InlineVideoLoadingBackdrop(state.value, active.value, path.value, Modifier.matchParentSize())
                    InlineVideoProgressOverlay(state.value, active.value, { .25f }, Modifier.matchParentSize())
                }
            }.use { scene ->
                scene.awaitDescription("真实模糊缩略图")
                val ring = scene.awaitDescription("视频加载中")
                val first = scene.settledFrame(2)
                assertWarmAndCool(first, density)
                export(first, "video-first-frame-${theme(dark)}-$density")
                assertTrue(scene.unmergedSemanticsBounds { it.getOrNull(SemanticsActions.SetProgress) != null }.isEmpty())

                state.value = state.value.copy(ready = true, loading = false, playing = true, buffering = true)
                scene.awaitDescription("视频缓冲中")
                val buffering = scene.settledFrame(3)
                assertWarmAndCool(buffering, density)
                export(buffering, "video-buffering-${theme(dark)}-$density")
                focused.value = false
                val unfocused = scene.settledFrame(3)
                assertTrue(scene.loadingBounds().isEmpty())
                val later = scene.settledFrame(5)
                assertFalse(differs(unfocused, later, ring), "An unfocused cover must keep its raster without animating.")
                focused.value = true
                active.value = false
                scene.settledFrame(2)
                assertTrue(scene.loadingBounds().isEmpty())
                active.value = true
                state.value = state.value.copy(hasFrame = true, buffering = false)
                val ready = scene.settledFrame(3)
                assertTrue(scene.loadingBounds().isEmpty() && scene.descriptions("真实模糊缩略图").isEmpty())
                assertEquals(GREEN, ready[(48 * density).roundToInt(), (92 * density).roundToInt()])
                export(ready, "video-ready-${theme(dark)}-$density")
                state.value = state.value.copy(buffering = true)
                scene.awaitDescription("视频缓冲中")
                repeat(8) {
                    val retained = scene.settledFrame(1, 16)
                    assertTrue(scene.descriptions("真实模糊缩略图").isEmpty(),
                        "A real retained frame must not be replaced by its loading thumbnail during later buffering.")
                    assertEquals(GREEN, retained[(48 * density).roundToInt(), (92 * density).roundToInt()])
                }
                export(scene.settledFrame(2), "video-retained-buffering-${theme(dark)}-$density")
                state.value = state.value.copy(buffering = false, playing = false)
                scene.settledFrame(2)
                assertTrue(scene.loadingBounds().isEmpty() && scene.descriptions("真实模糊缩略图").isEmpty())
                state.value = state.value.copy(loading = true, playing = true)
                scene.awaitDescription("视频加载中")
                val seeking = scene.settledFrame(2)
                assertTrue(scene.descriptions("真实模糊缩略图").isEmpty(),
                    "An explicit seek may indicate its wait while keeping the previous clear frame.")
                assertEquals(GREEN, seeking[(48 * density).roundToInt(), (92 * density).roundToInt()])
                state.value = state.value.copy(buffering = true, failed = true)
                scene.settledFrame(3)
                assertTrue(scene.loadingBounds().isEmpty() && scene.descriptions("真实模糊缩略图").isEmpty())
                state.value = VideoPlayerState()
                path.value = null
                val noSource = scene.settledFrame(3)
                assertTrue(scene.descriptions("真实模糊缩略图").isEmpty(), "A missing thumbnail must not fabricate a cover.")
                assertEquals(GREEN, noSource[(48 * density).roundToInt(), (92 * density).roundToInt()])
                export(noSource, "video-no-thumbnail-${theme(dark)}-$density")
            }
        }
    }

    @Test
    fun nativeThumbnailRequestsAreVisibleIsolatedAndNeverFetchTheOriginal() {
        val source = MessageMedia(MediaFile(1, canDownload = true), MediaFile(2, canDownload = true))
        val allowed = mutableStateOf(false)
        val snapshot = mutableStateOf(ChatSnapshot(token = ChatToken(1, 1, 10)))
        val requests = mutableListOf<Pair<ChatToken, Int>>()
        mediaScene(1f, false) {
            rememberLoadingThumbnailPath(source, snapshot.value,
                ChatFeatureActions(downloadFile = { token, file -> requests += token to file }), allowed.value)
        }.use { scene ->
            scene.settledFrame(2)
            assertTrue(requests.isEmpty())
            allowed.value = true
            scene.settledFrame(3)
            assertEquals(listOf(2), requests.map { it.second })
            repeat(3) {
                snapshot.value = snapshot.value.copy(mediaFiles = mapOf(2 to source.thumbnail!!.copy(downloading = it % 2 == 0)))
                scene.settledFrame(2)
            }
            assertEquals(1, requests.size, "Unrelated/native progress updates must not restart thumbnail requests.")
            snapshot.value = snapshot.value.copy(token = ChatToken(1, 2, 11), mediaFiles = emptyMap(),
                fileErrors = mapOf(2 to ChatError.REQUEST_FAILED))
            scene.settledFrame(2)
            assertEquals(1, requests.size, "A failed native thumbnail must not idle-retry in another owner.")
            snapshot.value = snapshot.value.copy(fileErrors = emptyMap())
            scene.settledFrame(3)
            assertEquals(listOf(2, 2), requests.map { it.second })
            assertNotEquals(requests[0].first, requests[1].first)
        }
    }

    private fun mediaScene(density: Float, dark: Boolean, focused: () -> Boolean = { true },
        content: @Composable () -> Unit): ComposePixelScene =
        ComposePixelScene((352 * density).roundToInt(), (192 * density).roundToInt()) {
            val controller = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
            val window = LocalWindowInfo.current
            val currentFocus by rememberUpdatedState(focused())
            val testWindow = remember(window) { object : WindowInfo by window {
                override val isWindowFocused get() = currentFocus
            } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides testWindow) {
                MiuixTheme(controller = controller) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(16.dp)) { content() }
                }
            }
        }

    private fun ComposePixelScene.descriptions(value: String): List<Rect> = unmergedSemanticsBounds {
        value in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.loadingBounds(): List<Rect> = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { text -> text == "视频加载中" || text == "视频缓冲中" }
    }
    private fun ComposePixelScene.awaitDescription(value: String): Rect {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            settledFrame(2)
            descriptions(value).singleOrNull()?.let { return it }
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("The real synthetic $value must finish preparation and become visible.")
    }
    private fun assertWarmAndCool(frame: ComposePixelFrame, density: Float) {
        val warm = frame[(48 * density).roundToInt(), (92 * density).roundToInt()]
        val cool = frame[(288 * density).roundToInt(), (92 * density).roundToInt()]
        assertTrue((warm ushr 16 and 255) > (warm and 255) + 70, "The actual warm half of the native thumbnail must remain visible.")
        assertTrue((cool and 255) > (cool ushr 16 and 255) + 70, "The actual cool half of the native thumbnail must remain visible.")
    }
    private fun differs(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect): Boolean =
        (bounds.top.roundToInt() until bounds.bottom.roundToInt()).any { y ->
            (bounds.left.roundToInt() until bounds.right.roundToInt()).any { x -> first[x, y] != second[x, y] }
        }
    private fun sources(test: (Path, Path) -> Unit) {
        val directory = Files.createTempDirectory("media-loading-sources-")
        val full = directory.resolve("full.png")
        val thumbnail = directory.resolve("thumbnail.png")
        try {
            for ((path, solid) in listOf(full to true, thumbnail to false)) {
                val image = BufferedImage(320, 160, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y,
                    if (solid) GREEN else if (x < 160) 0xFFCC4020.toInt() else 0xFF2084CC.toInt())
                check(ImageIO.write(image, "png", path.toFile()))
            }
            test(full, thumbnail)
        } finally { Files.deleteIfExists(full); Files.deleteIfExists(thumbnail); Files.deleteIfExists(directory) }
    }
    private fun export(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "media-loading")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
        println("Synthetic media loading frame: ${directory.resolve("$name.png")}")
    }
    private fun theme(dark: Boolean) = if (dark) "dark" else "light"
    private companion object { val GREEN = 0xFF24BD76.toInt() }
}
