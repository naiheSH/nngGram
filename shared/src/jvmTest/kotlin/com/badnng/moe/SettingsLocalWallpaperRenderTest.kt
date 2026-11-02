package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.media.SaveTargetBoundary
import com.badnng.moe.media.SaveTargetIssue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real production controls and pointer input; offscreen evidence does not establish native-picker acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsLocalWallpaperRenderTest {
    @Test fun actualControlsPreviewApplyCancelAndRestoreAcrossSizeThemeAndDensity() {
        for ((width, height) in listOf(350 to 300, 390 to 700, 1160 to 840)) for (density in listOf(1f, 1.25f))
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                verifyControls(width, height, density, mode)
            }
    }

    // Each independent scene owns its own scheduler and the unchanged default runTest deadline.
    // CPU raster time from earlier matrix cells must not cancel a later cell's picker scope.
    private fun verifyControls(width: Int, height: Int, density: Float, mode: ColorSchemeMode) = runTest {
        val picked = WallpaperTestFrame()
        val backend = WallpaperTestBackend().apply { nextPick = picked }
        val controller = LocalWallpaperController(backend, this)
        val widthPx = (width * density).roundToInt()
        val heightPx = (height * density).roundToInt()
        try { ComposePixelScene(widthPx, heightPx) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalLocalWallpaperController provides controller) {
                MiuixTheme(controller = theme) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        SettingsLocalWallpaperControls { true }
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            fun click(label: String) = scene.clickLabel(label, widthPx, heightPx)
            click("从本机选择图片"); runCurrent(); scene.settledFrame()
            assertNotNull(controller.preview, "First explicit picker must complete in this scene's active test scope.")
            assertFalse(controller.busy)
            val preview = scene.revealPreview(widthPx, heightPx, density)
            assertTrue(preview.left >= 0 && preview.right <= widthPx && preview.bottom < heightPx,
                "The real preview fits the narrow/wide viewport at $density / $mode.")
            val coverScale = maxOf(preview.width / picked.width, 208 * density / picked.height)
            val coverWidth = (picked.width * coverScale).roundToInt()
            val coverHeight = (picked.height * coverScale).roundToInt()
            assertTrue(picked.preparedTargets.any { (w, h, blurred) ->
                kotlin.math.abs(w - coverWidth) <= 1 &&
                    kotlin.math.abs(h - coverHeight) <= 1 && !blurred
            }, "Preview preparation uses the complete physical cover target, including cropped pixels.")
            val previewPixels = scene.settledFrame()
            assertEquals(Color.Red.toArgb(), previewPixels[preview.center.x.roundToInt(),
                (preview.bottom - 8 * density).roundToInt()])
            exportKeyFrame(previewPixels, width, height, density, mode, "preview")
            click("夜间预览"); scene.settledFrame()
            assertTrue(controller.previewDark)
            assertTrue(scene.semanticsBounds {
                "本地壁纸夜间预览" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.isNotEmpty())
            assertEquals(0, backend.commits, "Preview mode does not save anything.")
            click("模糊壁纸"); scene.settledFrame()
            assertTrue(controller.preview!!.blurred)
            val blurredPreview = scene.revealPreview(widthPx, heightPx, density)
            assertTrue(picked.preparedTargets.any { (w, h, blurred) ->
                kotlin.math.abs(w - coverWidth) <= 1 &&
                    kotlin.math.abs(h - coverHeight) <= 1 && blurred
            }, "Blur changes request the prepared variant at the same complete cover size.")
            assertEquals(Color.Blue.toArgb(), scene.settledFrame()[blurredPreview.center.x.roundToInt(),
                (blurredPreview.bottom - 8 * density).roundToInt()])
            click("取消预览"); scene.settledFrame()
            assertNull(controller.preview)
            assertEquals(0, backend.commits)
            assertTrue(scene.previewNodes().isEmpty())
            backend.nextPick = WallpaperTestFrame()
            click("从本机选择图片"); runCurrent(); scene.settledFrame()
            assertNotNull(controller.preview, "Second explicit picker must complete before looking for Apply.")
            assertFalse(controller.busy)
            scene.revealLabel("应用本地壁纸", widthPx, heightPx)
            if (exportCell(width, density, mode))
                exportKeyFrame(scene.settledFrame(1), width, height, density, mode, "before-apply")
            click("应用本地壁纸"); runCurrent(); scene.settledFrame()
            assertNotNull(controller.committed)
            assertNull(controller.preview)
            assertEquals(1, backend.commits)
            click("恢复内置壁纸"); runCurrent(); scene.settledFrame()
            assertNull(controller.committed)
            assertEquals(1, backend.restores)
        } } finally { controller.close() }
    }

    @Test fun decodedTransparentImageBecomesOpaqueAndRestartPreservesItsPixels() {
        val directory = Files.createTempDirectory("nnggram-wallpaper-pixels-test-")
        val source = LocalWallpaperImages.prepare(transparentPng())
        val retained = source.retain()
        try {
            val store = LocalWallpaperStore(directory, { object : SaveTargetBoundary {
                override fun check(target: Path): SaveTargetIssue? = null
            } })
            assertTrue(store.commit(source, false) { true })
            source.close()
            val restarted = assertNotNull(store.load())
            try {
                val before = sample(retained)
                val after = sample(restarted.frame)
                assertEquals(before, after, "Restart decodes the same owned artwork without the selected file.")
                assertEquals(255, after ushr 24 and 255)
                for ((shift, expected) in listOf(16 to 223, 8 to 231, 0 to 239))
                    assertTrue(kotlin.math.abs((after ushr shift and 255) - expected) <= 2,
                        "Transparent source is flattened over white before display, channel $shift.")
                assertTrue(store.restore { true })
                assertEquals(before, sample(retained), "Restoring cannot destroy an outstanding UI lease.")
            } finally { restarted.frame.close() }
        } finally { retained.close(); source.close(); directory.toFile().deleteRecursively() }
    }

    private fun sample(frame: LocalWallpaperFrame): Int {
        runBlocking { frame.prepare(40, 30, false) }
        return ComposePixelScene(40, 30) {
            Canvas(Modifier.fillMaxSize().background(Color.Magenta)) {
                frame.draw(this, Rect(0f, 0f, size.width, size.height), false)
            }
        }.use { it.settledFrame()[20, 15] }
    }

    private fun transparentPng(): ByteArray {
        val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 3) for (x in 0 until 4) image.setRGB(x, y, 0x4080a0c0)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)); output.toByteArray()
        }
    }

    private fun ComposePixelScene.previewNodes() = semanticsBounds {
        it.getOrNull(SemanticsProperties.TestTag) == "settings-local-wallpaper-preview"
    }
    private fun ComposePixelScene.revealPreview(width: Int, height: Int, density: Float) =
        reveal(width, height, minimumHeight = 208 * density - 1) { previewNodes() }
    private fun ComposePixelScene.reveal(width: Int, height: Int, minimumHeight: Float = 8f, nodes: () -> List<Rect>): Rect {
        var down = false
        var wheelStep = 8f
        var previousDirection = 0
        repeat(240) { index ->
            val bounds = nodes().firstOrNull { it.width > 0 && it.height > 0 }
            if (bounds != null && bounds.height >= minimumHeight && bounds.top >= 4 && bounds.bottom <= height - 4)
                return bounds
            var position = 0f
            semanticsBounds {
                it.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range -> position = range.value() }
                false
            }
            if (position <= .5f) down = true
            val delta = when {
                bounds != null && bounds.top < 4 -> -8f
                bounds != null && bounds.bottom > height - 4 -> 8f
                bounds != null && bounds.height < minimumHeight -> if (bounds.center.y < height / 2f) -8f else 8f
                else -> if ((index < 120) == down) 8f else -8f
            }
            val direction = if (delta < 0) -1 else 1
            if (previousDirection != 0 && direction != previousDirection)
                wheelStep = (wheelStep / 2).coerceAtLeast(.02f)
            previousDirection = direction
            // Avoid skipping the narrow interval in which a 208dp preview fits.
            scrollAt(width - 4f, height / 2f, deltaY = direction * wheelStep)
            settledFrame(5, 32)
        }
        fail("The actual settings scroll container did not reveal the requested control in ${width}x$height.")
    }
    private fun ComposePixelScene.clickLabel(label: String, width: Int, height: Int) {
        val bounds = revealLabel(label, width, height)
        assertTrue(bounds.width > 0 && bounds.height > 0, "Production control is laid out: $label")
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun ComposePixelScene.revealLabel(label: String, width: Int, height: Int) =
        reveal(width, height) { semanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
        } }

    private fun exportCell(width: Int, density: Float, mode: ColorSchemeMode) =
        (width == 350 && density == 1f && mode == ColorSchemeMode.Light) ||
            (width == 390 && density == 1.25f && mode == ColorSchemeMode.Dark) ||
            (width == 1160 && density == 1.25f && mode == ColorSchemeMode.Dark)

    private fun exportKeyFrame(pixels: ComposePixelFrame, width: Int, height: Int, density: Float,
        mode: ColorSchemeMode, stage: String) {
        // Six genuine scene frames: one configuration per viewport, at preview and before Apply.
        if (!exportCell(width, density, mode)) return
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y])
        val directory = Path.of("build", "settings-local-wallpaper-render-recovery-images")
        Files.createDirectories(directory)
        val output = directory.resolve("${width}x${height}-${density}-${mode.name}-$stage.png")
        check(ImageIO.write(image, "png", output.toFile()))
    }
}
