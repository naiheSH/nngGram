package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.badnng.moe.chat.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real PNG/TGS resources exercise the same renderer as the official TDLib artwork. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class OfficialReactionArtworkRenderTest {
    @Test
    fun nativeStaticArtworkReplacesTheFontAndHoverUsesTheNativeVectorAnimation() {
        val image = Files.createTempFile("nnggram-reaction-static-", ".png")
        val animation = Files.createTempFile("nnggram-reaction-select-", ".tgs")
        try {
            val bitmap = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 32) for (x in 0 until 32) bitmap.setRGB(x, y, 0xff24bd76.toInt())
            check(ImageIO.write(bitmap, "png", image.toFile()))
            GZIPOutputStream(Files.newOutputStream(animation)).use { it.write(MOVING_CIRCLE.toByteArray()) }
            val key = ReactionKey("👍")
            val focused = mutableStateOf(true)
            val message = ChatMessage(2, 1)
            val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 1), reactionMessageId = 2,
                reactionOptions = MessageReactionOptions(listOf("👍"), true),
                reactionVisuals = mapOf(key to ReactionVisual(key,
                    MessageMedia(MediaFile(70, localPath = image.toString(), downloaded = true), format = MediaFormat.PHOTO),
                    MessageMedia(MediaFile(71, localPath = animation.toString(), downloaded = true), format = MediaFormat.TGS))))
            ComposePixelScene(320, 440) {
                val controller = remember { ThemeController(ColorSchemeMode.Light) }
                MiuixTheme(controller = controller) {
                    val window = LocalWindowInfo.current
                    val focusedWindow = remember(window) { object : WindowInfo by window {
                        override val isWindowFocused get() = focused.value
                    } }
                    CompositionLocalProvider(LocalWindowInfo provides focusedWindow) {
                        Box(Modifier.fillMaxSize()) { MessageReactionPickerPanel(message, snapshot, ChatFeatureActions(), {}, true) }
                    }
                }
            }.use { scene ->
                scene.settledFrame()
                val cell = assertNotNull(scene.semanticsBounds {
                    "👍" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }.singleOrNull())
                scene.awaitPixels(cell, "initial static artwork") { isGreen(it) }
                Files.delete(image)
                scene.moveTo(cell.center.x, cell.center.y)
                scene.awaitPixels(cell, "first focused hover animation") { isRed(it) }
                Files.delete(animation)
                val first = scene.settledFrame(1)
                val deadline = System.nanoTime() + 2_000_000_000L
                var moved = false
                do {
                    Thread.sleep(10)
                    val next = scene.settledFrame(1, 16)
                    moved = changes(first, next, cell) > 8 &&
                        kotlin.math.abs(redCentroid(first, cell) - redCentroid(next, cell)) >= 0.5
                } while (!moved && System.nanoTime() < deadline)
                assertTrue(moved, "The native red vector shape must move, independently of the cell's hover indication.")
                scene.moveTo(315f, 435f)
                scene.awaitPixels(cell, "cached static artwork after source deletion") { isGreen(it) }
                scene.settledFrame()
                val still = scene.settledFrame(1)
                assertTrue(changes(still, scene.settledFrame(5), cell) == 0,
                    "The unhovered reaction must return to its static artwork and stop animating.")
                scene.moveTo(cell.center.x, cell.center.y)
                scene.awaitPixels(cell, "cached second hover after animation source deletion") { isRed(it) }
                focused.value = false
                scene.awaitPixels(cell, "static artwork after losing window focus") { isGreen(it) }
                val unfocused = scene.settledFrame()
                Thread.sleep(140)
                assertTrue(changes(unfocused, scene.settledFrame(2), cell) == 0,
                    "Losing window focus must stop the official vector animation even if the pointer remains over its cell.")
            }
        } finally {
            Files.deleteIfExists(image)
            Files.deleteIfExists(animation)
        }
    }

    private fun ComposePixelScene.awaitPixels(bounds: Rect, stage: String, predicate: (Int) -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var lastCount = 0
        do {
            val frame = settledFrame(2)
            var count = 0
            for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt())
                for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) if (predicate(frame[x, y])) count++
            lastCount = count
            if (count > 20) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("The isolated reaction resource did not reach $stage; matching pixels=$lastCount.")
    }
    private fun isGreen(pixel: Int) = (pixel ushr 8 and 255) > 130 && (pixel ushr 16 and 255) < 80 && (pixel and 255) in 70..170
    private fun isRed(pixel: Int) = (pixel ushr 16 and 255) > 170 && (pixel ushr 8 and 255) < 100 && (pixel and 255) < 100
    private fun redCentroid(frame: ComposePixelFrame, bounds: Rect): Double {
        var totalX = 0L
        var pixels = 0
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt())
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) if (isRed(frame[x, y])) { totalX += x; pixels++ }
        assertTrue(pixels > 20, "The centroid check must observe the actual native vector shape.")
        return totalX.toDouble() / pixels
    }
    private fun changes(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect): Int {
        var count = 0
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt())
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) if (first[x, y] != second[x, y]) count++
        return count
    }
    private companion object {
        const val MOVING_CIRCLE = """{"v":"5.5.2","fr":30,"ip":0,"op":30,"w":32,"h":32,"nm":"synthetic-native-reaction","ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":4,"nm":"circle","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":1,"k":[{"t":0,"s":[7,16,0],"e":[25,16,0],"i":{"x":[0.67],"y":[1]},"o":{"x":[0.33],"y":[0]}},{"t":30,"s":[25,16,0]}]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},"shapes":[{"ty":"el","p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[10,10]},"d":1},{"ty":"fl","c":{"a":0,"k":[0.9,0.1,0.1,1]},"o":{"a":0,"k":100},"r":1}],"ip":0,"op":30,"st":0,"bm":0}]}"""
    }
}
