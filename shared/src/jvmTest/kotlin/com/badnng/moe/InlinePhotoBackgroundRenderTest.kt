package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
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

/** Photo::prepareImageCacheWithLarge / PrepareWithBlurredBackground, with actual synthetic source pixels. */
class InlinePhotoBackgroundRenderTest {
    @Test
    fun aContainedFullPhotoKeepsSharpAspectAboveTheReadyThumbnailDarkenedFill() = sources { full, thumbnail ->
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            val fixture = Fixture(full, thumbnail, density, mode)
            fixture.scene().use { scene ->
                scene.awaitDescription("聊天图片")
                val canvas = scene.awaitDescription("照片模糊底图")
                val frame = scene.settledFrame()
                val fill = frame[(canvas.left + 8 * density).roundToInt(), canvas.center.y.roundToInt()]
                colorClose(darkened(BLUE), fill, "The already-ready native thumbnail, rather than the full green image, owns the fill.")
                val sharp = sharpGreenBounds(frame, canvas)
                assertTrue(abs(sharp.width / sharp.height - 64f / 48f) <= .03f,
                    "Only undarkened foreground pixels count toward the original full-photo ratio: $sharp")
                assertTrue(abs(sharp.height - 100f * density) <= 1.5f)
                assertEquals(0, fixture.downloads, "The background must not request any source bytes.")
                writeFrame(frame, "contained-$mode-$density")
            }
        }
    }

    @Test
    fun aReadyThumbnailWithoutTheFullPhotoIsBlurOnlyEvenIfTheFutureFullImageWillCrop() = sources { _, thumbnail ->
        for (scale in listOf(ContentScale.Fit, ContentScale.Crop)) {
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                for (density in listOf(1f, 1.25f)) {
                    val fixture = Fixture(null, thumbnail, density, mode, scale = scale)
                    fixture.scene().use { scene ->
                        val canvas = scene.awaitDescription("照片模糊底图")
                        val frame = scene.settledFrame()
                        assertTrue(scene.descriptions("聊天图片").isEmpty() && scene.descriptions("缩略图").isEmpty(),
                            "A native thumbnail must not be rendered as the missing clear full image.")
                        colorClose(darkened(BLUE), frame[canvas.center.x.roundToInt(), canvas.center.y.roundToInt()],
                            "Only the blurred darkened source is painted until the full photo is ready.")
                        assertEquals(0, fixture.downloads)
                        writeFrame(frame, "thumbnail-only-$mode-$density-${if (scale == ContentScale.Crop) "crop" else "fit"}")
                    }
                }
            }
        }
    }

    @Test
    fun theExpandingBranchCropsTheClearPhotoWithoutBlurAndPreservesBothAxisScale() = sources { full, thumbnail ->
        writeSource(full, 80, 100) { x, y ->
            when {
                x in 30 until 50 -> BLUE
                y in 40 until 60 -> RED
                else -> GREEN
            }
        }
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            assertTrue(photoResizeExpands(80, 100, 100f, 100f))
            val fixture = Fixture(full, thumbnail, density, mode, scale = ContentScale.Crop,
                sourceWidth = 80, sourceHeight = 100, canvasWidth = 100)
            fixture.scene().use { scene ->
                val canvas = scene.awaitDescription("聊天图片")
                val frame = scene.settledFrame()
                assertTrue(scene.descriptions("照片模糊底图").isEmpty(), "The expanding branch never paints a blurred underlay.")
                val redHeight = (canvas.top.roundToInt() until canvas.bottom.roundToInt()).sumOf { y ->
                    val pixel = frame[(canvas.left + 25 * density).roundToInt(), y]
                    ((189.0 - (pixel ushr 8 and 255)) / (189 - 40)).coerceIn(0.0, 1.0)
                }
                val blueWidth = (canvas.left.roundToInt() until canvas.right.roundToInt()).sumOf { x ->
                    val pixel = frame[x, (canvas.top + 20 * density).roundToInt()]
                    (((pixel and 255) - 118.0) / (220 - 118)).coerceIn(0.0, 1.0)
                }
                // Integrate the known endpoint-color weights, including linear-sampled
                // edge pixels. Counting only pure colors incorrectly discards real coverage.
                assertTrue(abs(redHeight - 25f * density) <= 1f && abs(blueWidth - 25f * density) <= 1f &&
                    abs(redHeight / blueWidth - 1.0) <= .02,
                    "Both original 20px marker axes must retain the same exact crop coverage, not Fit or nonuniform stretch: $redHeight/$blueWidth")
                writeFrame(frame, "expanded-$mode-$density")
            }
        }
    }

    @Test
    fun fullFallbackNoSourceInactiveAndOptOutBranchesDoNotInventOrDownloadBackgrounds() = sources { full, _ ->
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            val fallback = Fixture(full, null, density, mode)
            fallback.scene().use { scene ->
                val canvas = scene.awaitDescription("照片模糊底图")
                scene.awaitDescription("聊天图片")
                val frame = scene.settledFrame()
                colorClose(darkened(GREEN), frame[(canvas.left + 8 * density).roundToInt(), canvas.center.y.roundToInt()],
                    "A ready full photo is the real fallback when its native thumbnail is unavailable.")
                assertEquals(0, fallback.downloads, "An unavailable thumbnail must not be downloaded just to fill margins.")
                writeFrame(frame, "full-fallback-$mode-$density")
            }
            for ((fullPath, enabled, visible) in listOf(Triple<Path?, Boolean, Boolean>(null, true, true),
                Triple(full, false, true), Triple(full, true, false))) {
                val fixture = Fixture(fullPath, null, density, mode, background = enabled, visible = visible)
                fixture.scene().use { scene ->
                    if (fullPath != null && visible) scene.awaitDescription("聊天图片")
                    val frame = scene.settledFrame()
                    assertTrue(scene.descriptions("照片模糊底图").isEmpty())
                    colorClose(fixture.neutral, frame[(18 * density).roundToInt(), (60 * density).roundToInt()],
                        "Only a missing, inactive or explicitly opted-out source keeps the neutral margin.")
                    assertEquals(0, fixture.downloads)
                    writeFrame(frame, "neutral-${if (fullPath == null) "missing" else if (!enabled) "opt-out" else "inactive"}-$mode-$density")
                }
            }
        }
    }

    private class Fixture(
        full: Path?, thumbnail: Path?, val density: Float, val mode: ColorSchemeMode,
        val scale: ContentScale = ContentScale.Fit, val sourceWidth: Int = 64, val sourceHeight: Int = 48,
        val canvasWidth: Int = 298, val background: Boolean = true, val visible: Boolean = true,
    ) {
        var downloads = 0
        var neutral = 0
        val media = MessageMedia(MediaFile(1, localPath = full?.toString(), downloaded = full != null),
            thumbnail = MediaFile(2, localPath = thumbnail?.toString(), downloaded = thumbnail != null, canDownload = thumbnail != null),
            width = sourceWidth, height = sourceHeight, thumbnailFormat = MediaFormat.PHOTO)
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10), selected = ChatItem(10, "Synthetic photo"))
        fun scene() = ComposePixelScene((320f * density).roundToInt(), (140f * density).roundToInt()) {
            val controller = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = controller) {
                    neutral = MiuixTheme.colorScheme.surfaceContainer.toArgb()
                    Box(Modifier.fillMaxSize().padding(10.dp)) {
                        RemoteMedia(media, snapshot, ChatFeatureActions(downloadFile = { _, _ -> downloads++ }), visible, false,
                            modifier = Modifier.size(canvasWidth.dp, 100.dp), contentScale = scale, photoBackground = background)
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.descriptions(description: String): List<Rect> = unmergedSemanticsBounds {
        description in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.awaitDescription(description: String): Rect {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            settledFrame(2)
            descriptions(description).singleOrNull()?.let { return it }
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("The synthetic $description must actually finish worker preparation.")
    }

    private fun sources(test: (Path, Path) -> Unit) {
        val directory = Files.createTempDirectory("inline-photo-background-")
        val full = directory.resolve("full.png")
        val thumbnail = directory.resolve("thumbnail.png")
        try {
            writeSource(full, 64, 48) { _, _ -> GREEN }
            writeSource(thumbnail, 80, 80) { _, _ -> BLUE }
            test(full, thumbnail)
        } finally {
            Files.deleteIfExists(full); Files.deleteIfExists(thumbnail); Files.deleteIfExists(directory)
        }
    }

    private fun writeSource(path: Path, width: Int, height: Int, color: (Int, Int) -> Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, color(x, y))
        check(ImageIO.write(image, "png", path.toFile()))
    }

    private fun sharpGreenBounds(frame: ComposePixelFrame, canvas: Rect): Rect {
        var left = frame.width; var top = frame.height; var right = 0; var bottom = 0
        for (y in canvas.top.roundToInt() until canvas.bottom.roundToInt()) for (x in canvas.left.roundToInt() until canvas.right.roundToInt()) {
            // The blurred layer has 48/255 black. Only exact undarkened source pixels are foreground.
            if (colorDistance(frame[x, y], GREEN) < 4) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
            }
        }
        assertTrue(right > left && bottom > top)
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun colorClose(expected: Int, actual: Int, reason: String) =
        assertTrue(colorDistance(expected, actual) <= 3, "$reason expected=${expected.toUInt().toString(16)}, actual=${actual.toUInt().toString(16)}")
    private fun colorDistance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf { abs((first ushr it and 255) - (second ushr it and 255)) }
    private fun darkened(color: Int): Int = (255 shl 24) or ((color ushr 16 and 255) * 207 / 255 shl 16) or
        ((color ushr 8 and 255) * 207 / 255 shl 8) or ((color and 255) * 207 / 255)
    private fun writeFrame(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "inline-photo-background-final")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }
    private companion object {
        val GREEN = 0xFF24BD76.toInt()
        val BLUE = 0xFF1E64DC.toInt()
        val RED = 0xFFDC2828.toInt()
    }
}
