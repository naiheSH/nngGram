package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.MessageReaction
import com.badnng.moe.chat.ReactionKey
import com.badnng.moe.chat.ReactionVisual
import com.badnng.moe.media.LocalMediaDrawObserver
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Transparent native center artwork exposes duplicate stationary bodies that opaque fixtures conceal. */
class ReactionArtworkOwnershipRenderTest {
    @Test
    fun nativeCenterMidFramesRemainVisibleOutsideTheMiuixPillsSubtreeMask() = withAssets { assets ->
        for (density in listOf(1f, 1.25f, 2f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val message = mutableStateOf(reactionMessage(chosen = false))
            val visual = assets.visual(centerPath = assets.fullCenter).copy(aroundAnimation = null)
            rowScene(message, visual, density, mode).use { scene ->
                scene.awaitFrame("static before expanding center") { count(it, ::isRed) > 30 }
                message.value = reactionMessage(chosen = true)
                val expanded = scene.awaitFrame("native full-canvas middle frames") {
                    count(it, ::isBlue) > 20 && coloredBounds(it, ::isBlue).width / density >= 30f
                }
                val ink = coloredBounds(expanded, ::isBlue)
                val pill = scene.semanticsBounds { node -> node.getOrNull(SemanticsProperties.ContentDescription)
                    ?.any { it.contains("条回应") } == true }.single()
                assertTrue(ink.top < pill.top - 2f * density && ink.bottom > pill.bottom + 2f * density,
                    "The native center's middle frames must draw above and below the 22dp Miuix pill: $mode/$density, ink=$ink, pill=$pill")
                assertEquals(0, count(expanded, ::isRed), "The external center still owns the stationary glyph.")
                export(expanded, "native-center-unclipped-middle-$mode-$density")
            }
        }
    }

    @Test
    fun nativeCenterOwnsTheWholeEmojiBodyIncludingItsTransparentPixelsAndRestoresStaticAfterwards() = withAssets { assets ->
        for (density in listOf(1f, 1.25f, 2f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val message = mutableStateOf(reactionMessage(chosen = false))
            rowScene(message, assets.visual(), density, mode).use { scene ->
                val idle = scene.awaitFrame("initial static body") { count(it, ::isRed) > 30 }
                assertEquals(0, count(idle, ::isBlue), "Initial history must not launch an addition.")
                message.value = reactionMessage(chosen = true)
                val center = scene.awaitFrame("native transparent center and around") {
                    count(it, ::isBlue) > 10 && count(it, ::isGreen) > 10
                }
                export(center, "native-center-$mode-$density")
                assertEquals(0, count(center, ::isRed),
                    "The red stationary emoji must be absent even through transparent center-animation pixels: $mode/$density")
                val restored = scene.awaitFrame("static body after one native effect") {
                    count(it, ::isRed) > 30 && count(it, ::isBlue) == 0 && count(it, ::isGreen) == 0
                }
                export(restored, "native-restored-$mode-$density")
                message.value = message.value.copy(reactions = listOf(MessageReaction("🔥", 2, true)))
                assertEquals(0, count(scene.settledFrame(4), ::isBlue), "A count-only native update must not replay the center.")
                message.value = reactionMessage(chosen = false)
                scene.settledFrame(2)
                message.value = reactionMessage(chosen = true)
                val readded = scene.awaitFrame("a later independent addition") { count(it, ::isBlue) > 10 }
                assertEquals(0, count(readded, ::isRed), "A later removal/addition must acquire body ownership again.")
            }
        }
    }

    @Test
    fun arrivalHidesTheExistingChipsStaticBodyAndLateNativeAcknowledgementDoesNotReplay() = withAssets { assets ->
        for (density in listOf(1f, 1.25f, 2f)) {
            val message = mutableStateOf(reactionMessage(chosen = false))
            val intents = ReactionAdditionIntents()
            rowScene(message, assets.visual(), density, intents = intents).use { scene ->
                scene.awaitFrame("existing stationary emoji") { count(it, ::isRed) > 30 }
                intents.request(1, KEY, Rect(260f * density, 30f * density, 292f * density, 62f * density))
                scene.awaitFrame("source artwork travelling above the stationary footer", stepMillis = 16) { flying ->
                    var pixels = 0
                    for (y in 0 until (165 * density).roundToInt()) for (x in 0 until flying.width)
                        if (isRed(flying[x, y])) pixels++
                    pixels > 20
                }
                val landed = scene.awaitFrame("flight arrival with transparent native center") {
                    count(it, ::isBlue) > 10 && count(it, ::isGreen) > 10
                }
                export(landed, "flight-center-$density")
                assertEquals(0, count(landed, ::isRed),
                    "Landing must suppress both the flight's static fallback and the pre-existing chip body: $density")
                val bodyCenter = centroid(landed, ::isBlue)
                val aroundCenter = centroid(landed, ::isGreen)
                assertTrue(kotlin.math.abs(bodyCenter.x - aroundCenter.x) <= 1f &&
                    kotlin.math.abs(bodyCenter.y - aroundCenter.y) <= 1f,
                    "The native 64dp around canvas must share the actual artwork center, within one raster pixel: " +
                        "$density, body=$bodyCenter, around=$aroundCenter")
                val centerBounds = coloredBounds(landed, ::isBlue)
                val aroundBounds = coloredBounds(landed, ::isGreen)
                // The fixture's blue ink occupies 21/32 of its native canvas.
                // Fitting that entire canvas into the 18dp layout slot made it
                // about 12dp wide; the official 32dp canvas restores 21dp ink.
                assertTrue(centerBounds.width / density in 19f..23f,
                    "Native center transparent margins must not shrink its ink into the layout slot: $density/$centerBounds")
                assertTrue(aroundBounds.width / density in 60f..66f,
                    "The around stream must occupy the official 64dp canvas outside the chip: $density/$aroundBounds")
                assertTrue(message.value.reactions.none { it.isChosen }, "An effect must not manufacture native chosen state.")
                scene.awaitFrame("arrival completion") {
                    intents.current == null && count(it, ::isRed) > 30 && count(it, ::isBlue) == 0
                }
                assertNull(intents.current)
                message.value = reactionMessage(chosen = true)
                val confirmed = scene.settledFrame(8)
                assertEquals(0, count(confirmed, ::isBlue), "The matching native acknowledgement must not launch another center.")
                assertEquals(0, count(confirmed, ::isGreen), "The matching native acknowledgement must not launch another around effect.")
                assertTrue(count(confirmed, ::isRed) > 30)
            }
        }
    }

    @Test
    fun missingNativeCenterUsesHalfSizeSelectCanvasAndKeepsItsOwnAroundCanvas() = withAssets { assets ->
        for (density in listOf(1f, 1.25f, 2f)) {
            val message = mutableStateOf(reactionMessage(chosen = false))
            val intents = ReactionAdditionIntents()
            val visual = assets.visual(centerPath = null)
                .copy(selectAnimation = media(4, assets.center, MediaFormat.TGS))
            rowScene(message, visual, density, intents = intents).use { scene ->
                scene.awaitFrame("static before select fallback") { count(it, ::isRed) > 30 }
                intents.request(1, KEY, null)
                val landed = scene.awaitFrame("native select fallback") {
                    count(it, ::isBlue) > 10 && count(it, ::isGreen) > 10
                }
                val centerBounds = coloredBounds(landed, ::isBlue)
                val aroundBounds = coloredBounds(landed, ::isGreen)
                assertTrue(centerBounds.width / density in 9f..13f,
                    "ReactionFlyAnimation halves only the select fallback's 32dp canvas: $density/$centerBounds")
                assertTrue(aroundBounds.width / density in 60f..66f,
                    "Using the select fallback must not halve its independent around canvas: $density/$aroundBounds")
                assertEquals(0, count(landed, ::isRed), "A ready fallback center still owns the stationary body.")
                export(landed, "select-fallback-official-canvas-$density")
            }
        }
    }

    @Test
    fun aroundOnlyAndRejectedCentersNeverTakeOwnershipAwayFromTheStaticBody() = withAssets { assets ->
        for (center in listOf<Path?>(null, assets.directory.resolve("corrupt.tgs"), assets.directory.resolve("missing.tgs"))) {
            val message = mutableStateOf(reactionMessage(chosen = false))
            rowScene(message, assets.visual(center)).use { scene ->
                scene.awaitFrame("loaded fallback") { count(it, ::isRed) > 30 }
                message.value = reactionMessage(chosen = true)
                val around = scene.awaitFrame("around effect with unavailable center") { count(it, ::isGreen) > 10 }
                assertTrue(count(around, ::isRed) > 30,
                    "An around-only/failed-center effect must retain the emoji instead of creating a hollow reaction.")
                assertEquals(0, count(around, ::isBlue))
                export(around, "fallback-${center?.fileName ?: "absent"}")
            }
        }
    }

    @Test
    fun chosenHistoryMountsAndRemountsWithoutReplayingTheAddition() = withAssets { assets ->
        repeat(2) {
            val message = mutableStateOf(reactionMessage(chosen = true))
            rowScene(message, assets.visual()).use { scene ->
                val mounted = scene.awaitFrame("chosen-history static body") { count(it, ::isRed) > 30 }
                assertEquals(0, count(mounted, ::isBlue))
                assertEquals(0, count(mounted, ::isGreen))
                repeat(4) {
                    val idle = scene.settledFrame(2)
                    assertEquals(0, count(idle, ::isBlue), "An already chosen lazy item must stay static after mounting.")
                    assertEquals(0, count(idle, ::isGreen))
                }
            }
        }
    }

    @Test
    fun staticArtworkFiltersSmallRasterEdgesAtEachPhysicalDisplayDensity() = withAssets { assets ->
        for (density in listOf(1f, 1.25f, 2f)) {
            val snapshot = ChatSnapshot(token = TOKEN,
                reactionVisuals = mapOf(KEY to ReactionVisual(KEY, staticIcon = media(7, assets.diagonal, MediaFormat.PHOTO))))
            val nativeDraws = AtomicInteger()
            val artworkBounds = AtomicReference<Rect?>()
            ComposePixelScene((80 * density).roundToInt(), (80 * density).roundToInt()) {
                val controller = remember { ThemeController(ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(density),
                    LocalMediaDrawObserver provides { nativeDraws.incrementAndGet(); Unit }) {
                    MiuixTheme(controller = controller) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            ReactionArtwork(KEY, snapshot, ChatFeatureActions(), false,
                                Modifier.offset(20.dp, 20.dp).size(18.dp)
                                    .onGloballyPositioned { artworkBounds.set(it.boundsInWindow(clipBounds = false)) }, fontSize = 16)
                        }
                    }
                }
            }.use { scene ->
                // The fire fallback itself has enough red pixels at 2x to satisfy a color
                // readiness heuristic. Wait for the real prepared media canvas instead.
                val frame = scene.awaitFrame("small native raster at density $density") { nativeDraws.get() > 0 }
                export(frame, "sampled-small-raster-$density")
                val measured = assertNotNull(artworkBounds.get(), "The actual reaction cell must be measured.")
                val left = measured.left.roundToInt()
                val top = measured.top.roundToInt()
                val right = measured.right.roundToInt()
                val bottom = measured.bottom.roundToInt()
                assertEquals((18 * density).roundToInt(), right - left)
                assertEquals((18 * density).roundToInt(), bottom - top)
                fun expectedCoverage(x: Int, y: Int): Double {
                    // Pixel centers map to texel centers. The binary diagonal's transparent
                    // texels contribute zero premultiplied color; full-image sampling clamps
                    // each neighbour to the real 8x8 source instead of inventing an exterior.
                    val sourceX = (x - left + .5) * 8 / (right - left) - .5
                    val sourceY = (y - top + .5) * 8 / (bottom - top) - .5
                    val baseX = floor(sourceX).toInt()
                    val baseY = floor(sourceY).toInt()
                    val fractionX = sourceX - baseX
                    val fractionY = sourceY - baseY
                    var coverage = 0.0
                    for (dy in 0..1) for (dx in 0..1) {
                        val texelX = (baseX + dx).coerceIn(0, 7)
                        val texelY = (baseY + dy).coerceIn(0, 7)
                        if (texelX <= texelY) {
                            coverage += (if (dx == 0) 1 - fractionX else fractionX) *
                                (if (dy == 0) 1 - fractionY else fractionY)
                        }
                    }
                    return coverage
                }
                var expectedIntermediate = 0
                var observedIntermediate = 0
                for (y in top until bottom) for (x in left until right) {
                    val coverage = expectedCoverage(x, y)
                    val pixel = frame[x, y]
                    assertEquals(255, pixel ushr 24 and 255, "The white-backed artwork must remain opaque.")
                    for ((shift, sourceChannel) in listOf(16 to 225, 8 to 25, 0 to 25)) {
                        val expected = (sourceChannel * coverage + 255 * (1 - coverage)).roundToInt()
                        val actual = pixel ushr shift and 255
                        assertTrue(abs(actual - expected) <= 3,
                            "Small raster interpolation must match independent premultiplied bilinear coverage: " +
                                "$density @ $x,$y channel=$shift expected=$expected actual=$actual")
                        assertTrue(actual in (sourceChannel - 1)..255,
                            "A non-negative linear filter must not introduce a cubic dark halo.")
                    }
                    if (coverage in 0.1..0.9) expectedIntermediate++
                    if ((pixel ushr 8 and 255) in 34..246) observedIntermediate++
                }
                assertTrue(expectedIntermediate >= (right - left) / 2,
                    "The diagonal fixture must actually exercise partially covered output pixels: $density")
                assertTrue(observedIntermediate >= expectedIntermediate,
                    "The renderer must retain the expected intermediate edge coverage instead of nearest-neighbour steps.")
                for (y in 0 until frame.height) for (x in 0 until frame.width) {
                    if (x < left || x >= right || y < top || y >= bottom)
                        assertEquals(0xFFFFFFFF.toInt(), frame[x, y], "Filtering must not paint outside the measured emoji cell.")
                }
            }
        }
    }

    private fun rowScene(message: MutableState<ChatMessage>, visual: ReactionVisual, density: Float = 1f,
        mode: ColorSchemeMode = ColorSchemeMode.Light, intents: ReactionAdditionIntents = ReactionAdditionIntents()) =
        ComposePixelScene((400 * density).roundToInt(), (280 * density).roundToInt()) {
            val controller = remember { ThemeController(mode) }
            val snapshot = ChatSnapshot(enabled = true, token = TOKEN, messages = listOf(message.value),
                reactionVisuals = mapOf(KEY to visual))
            CompositionLocalProvider(LocalDensity provides Density(density), LocalReactionAdditionIntents provides intents) {
                MiuixTheme(controller = controller) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .padding(start = 50.dp, top = 180.dp, end = 40.dp)) {
                        MessageReactionRow(message.value, snapshot, ChatFeatureActions(), active = true)
                    }
                }
            }
        }

    private fun ComposePixelScene.awaitFrame(stage: String, stepMillis: Long = 64, predicate: (ComposePixelFrame) -> Boolean): ComposePixelFrame {
        val deadline = System.nanoTime() + 4_000_000_000L
        var lastFrame: ComposePixelFrame? = null
        do {
            val frame = settledFrame(1, stepMillis)
            lastFrame = frame
            if (predicate(frame)) return frame
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        val last = checkNotNull(lastFrame)
        export(last, "timeout-${stage.replace(Regex("[^A-Za-z0-9]+"), "-")}")
        throw AssertionError("Synthetic reaction did not reach $stage; last pixels red=${count(last, ::isRed)}, " +
            "blue=${count(last, ::isBlue)}, green=${count(last, ::isGreen)}.")
    }

    private fun count(frame: ComposePixelFrame, predicate: (Int) -> Boolean): Int {
        var count = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) if (predicate(frame[x, y])) count++
        return count
    }

    private fun centroid(frame: ComposePixelFrame, predicate: (Int) -> Boolean): Offset {
        var count = 0
        var totalX = 0L
        var totalY = 0L
        for (y in 0 until frame.height) for (x in 0 until frame.width) if (predicate(frame[x, y])) {
            count++
            totalX += x
            totalY += y
        }
        assertTrue(count > 10, "The alignment check must observe the supplied artwork's actual colored ring.")
        return Offset(totalX.toFloat() / count, totalY.toFloat() / count)
    }

    private fun coloredBounds(frame: ComposePixelFrame, predicate: (Int) -> Boolean): Rect {
        var left = frame.width
        var top = frame.height
        var right = 0
        var bottom = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) if (predicate(frame[x, y])) {
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }
        assertTrue(right > left && bottom > top, "The size check must observe actual native-colored artwork.")
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun export(frame: ComposePixelFrame, name: String) {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("gradlew.bat")) }
        val directory = root.resolve("build/reaction-artwork-ownership")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private fun withAssets(test: (Assets) -> Unit) {
        val directory = Files.createTempDirectory("synthetic-reaction-body-ownership-")
        try {
            val still = directory.resolve("red-static.png")
            val bitmap = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 32) for (x in 0 until 32) bitmap.setRGB(x, y, 0xFFE11919.toInt())
            check(ImageIO.write(bitmap, "png", still.toFile()))
            val diagonal = directory.resolve("binary-diagonal.png")
            val pixels = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 8) for (x in 0 until 8) pixels.setRGB(x, y, if (x <= y) 0xFFE11919.toInt() else 0)
            check(ImageIO.write(pixels, "png", diagonal.toFile()))
            val center = directory.resolve("hollow-blue-center.tgs")
            val fullCenter = directory.resolve("expanding-blue-center.tgs")
            val around = directory.resolve("hollow-green-around.tgs")
            GZIPOutputStream(Files.newOutputStream(center)).use { it.write(ring("[0.08,0.16,0.92,1]", 18, 3).toByteArray()) }
            val expandingCenter = ring("[0.08,0.16,0.92,1]", 30, 2).replace(
                "\"s\":{\"a\":0,\"k\":[100,100,100]}",
                """"s":{"a":1,"k":[{"t":0,"s":[60,60,100],"e":[100,100,100],"o":{"x":0,"y":0},"i":{"x":1,"y":1}},{"t":6,"s":[100,100,100],"e":[100,100,100],"o":{"x":0,"y":0},"i":{"x":1,"y":1}},{"t":18,"s":[100,100,100],"e":[60,60,100],"o":{"x":0,"y":0},"i":{"x":1,"y":1}},{"t":24,"s":[60,60,100]}]}""",
            )
            GZIPOutputStream(Files.newOutputStream(fullCenter)).use { it.write(expandingCenter.toByteArray()) }
            GZIPOutputStream(Files.newOutputStream(around)).use { it.write(ring("[0.05,0.78,0.2,1]", 30, 2).toByteArray()) }
            Files.writeString(directory.resolve("corrupt.tgs"), "synthetic invalid animation")
            test(Assets(directory, still, diagonal, center, fullCenter, around))
        } finally {
            Files.list(directory).use { paths -> paths.forEach(Files::delete) }
            Files.delete(directory)
        }
    }

    private class Assets(val directory: Path, val still: Path, val diagonal: Path, val center: Path, val fullCenter: Path, val around: Path) {
        fun visual(centerPath: Path? = center) = ReactionVisual(KEY, staticIcon = media(1, still, MediaFormat.PHOTO),
            centerAnimation = centerPath?.let { media(2, it, MediaFormat.TGS) }, aroundAnimation = media(3, around, MediaFormat.TGS))
    }

    private companion object {
        val KEY = ReactionKey("🔥")
        val TOKEN = ChatToken(1, 1, 10)
        fun reactionMessage(chosen: Boolean) = ChatMessage(1, 10, reactions = listOf(MessageReaction("🔥", 1, chosen)))
        fun media(id: Int, path: Path, format: MediaFormat) = MessageMedia(
            MediaFile(id, localPath = path.toString(), downloaded = true), format = format)
        fun isRed(pixel: Int) = (pixel ushr 16 and 255) > 170 && (pixel ushr 8 and 255) < 90 && (pixel and 255) < 90
        fun isBlue(pixel: Int) = (pixel and 255) > 160 && (pixel ushr 16 and 255) < 80 && (pixel ushr 8 and 255) < 75
        fun isGreen(pixel: Int) = (pixel ushr 8 and 255) > 135 && (pixel ushr 16 and 255) < 100 && (pixel and 255) < 120
        fun ring(color: String, diameter: Int, stroke: Int) = """{"v":"5.5.2","fr":30,"ip":0,"op":24,"w":32,"h":32,"nm":"synthetic-hollow-center","ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":4,"nm":"ring","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":0,"k":[16,16,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},"shapes":[{"ty":"el","p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[$diameter,$diameter]},"d":1},{"ty":"st","c":{"a":0,"k":$color},"o":{"a":0,"k":100},"w":{"a":0,"k":$stroke},"lc":2,"lj":2}],"ip":0,"op":24,"st":0,"bm":0}]}"""
    }
}
