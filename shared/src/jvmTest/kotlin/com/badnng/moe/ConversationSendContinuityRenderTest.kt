package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.tdlib.ChatController
import com.badnng.moe.tdlib.TdChatSession
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Checks every displayed frame, including the frames between native insertion and bottom following. */
class ConversationSendContinuityRenderTest {
    @Test
    fun existingMessagesStayPaintedWithoutAOneFrameBounceAcrossTwoSendsAndNativeReplacement() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f)) {
                val fixture = Fixture(mode, density)
                fixture.scene().use { scene ->
                    val initial = scene.settledFrame()
                    val composer = scene.composerBounds()
                    val rowHeight = assertNotNull(fixture.measured.values.maxOfOrNull { it.height })
                    val reservedTop = 100f * density + 2f * rowHeight + 20f * density
                    val newestIds = fixture.snapshot.messages.takeLast(2).map { it.id }.toSet()
                    val probes = fixture.measured.entries
                        .filter { it.key !in newestIds && it.value.top > reservedTop &&
                            it.value.bottom < composer.top - 30f * density }
                        .sortedByDescending { it.value.top }
                        .take(2)
                        .map { (id, bounds) -> Probe(id, bounds,
                            InkSample.capture(initial, scene.textBounds(existingText(id)))) }
                    assertEquals(2, probes.size,
                        "Two real old rows must clear both future sends and the actual composer: " +
                            "reservedTop=$reservedTop, composer=$composer, rows=${fixture.measured}")
                    val beforeSend = probes.associate { it.id to it.initial }

                    fixture.draft = TextFieldValue("First native send")
                    scene.settledFrame(3, stepMillis = 16)
                    scene.clickSend()
                    fixture.capture(scene, probes, composer, "submission", 3,
                        stationary = beforeSend)
                    assertEquals(1, fixture.session.sends.size)

                    val first = outgoing(1_001, "First native send")
                    fixture.acknowledge(0, first)
                    fixture.capture(scene, probes, composer, "first-insert", 18, movingUp = true)
                    val beforeDelivery = probes.associate { it.id to assertNotNull(fixture.measured[it.id]) }
                    val delivered = first.copy(id = 2_001, delivery = MessageDelivery.SENT)
                    fixture.event(ChatEvent.SendSucceeded(delivered, first.id))
                    fixture.capture(scene, probes, composer, "native-replacement", 12,
                        stationary = beforeDelivery)
                    assertEquals(first.id, fixture.snapshot.messages.last().presentationId,
                        "Native id replacement must retain one logical LazyColumn row.")

                    fixture.draft = TextFieldValue("Second native send")
                    scene.settledFrame(3, stepMillis = 16)
                    scene.clickSend()
                    fixture.capture(scene, probes, composer, "second-submission", 3,
                        stationary = beforeDelivery)
                    assertEquals(2, fixture.session.sends.size)
                    fixture.acknowledge(1, outgoing(2_002, "Second native send"))
                    fixture.capture(scene, probes, composer, "second-insert", 18, movingUp = true)
                    assertEquals(42, fixture.snapshot.messages.size)
                }
            }
        }
    }

    @Test
    fun aSmallRealWheelCancelsPendingLatestNavigationBeforeChangingTheVisibleItem() {
        val fixture = Fixture(ColorSchemeMode.Light, 1f)
        fixture.scene().use { scene ->
            scene.settledFrame()
            fixture.draft = TextFieldValue("Pending native send")
            scene.settledFrame(3, stepMillis = 16)
            scene.clickSend()
            scene.settledFrame(1, stepMillis = 16)
            assertTrue(fixture.snapshot.sendPending)
            val latestId = fixture.snapshot.messages.last().id
            val resting = assertNotNull(fixture.measured[latestId])
            assertTrue(fixture.cancelledFollowTokens.isEmpty(), "Submission cannot imitate a user scroll.")

            var displaced = false
            for (delta in listOf(-0.2f, 0.2f)) {
                val beforeSignals = fixture.cancelledFollowTokens.size
                scene.scrollAt(420f, 350f, deltaY = delta)
                scene.settledFrame(1, stepMillis = 16)
                assertTrue(fixture.cancelledFollowTokens.size > beforeSignals,
                    "Actual user input must cancel send navigation in the first frame, before any item-key change.")
                assertEquals(fixture.snapshot.token, fixture.cancelledFollowTokens.last())
                val moved = assertNotNull(fixture.measured[latestId], "The same latest row must remain composed.")
                val displacement = moved.top - resting.top
                if (displacement > 1f) {
                    assertTrue(displacement < resting.height,
                        "The small wheel must stay within the same visible item: $resting -> $moved")
                    displaced = true
                    break
                }
            }
            assertTrue(displaced, "Real wheel input must move the viewport within the original latest row.")
        }
    }

    private inner class Fixture(val mode: ColorSchemeMode, val density: Float) {
        var snapshot by mutableStateOf(ChatSnapshot())
        var draft by mutableStateOf(TextFieldValue())
        val measured = ConcurrentHashMap<Long, Rect>()
        val cancelledFollowTokens = mutableListOf<ChatToken>()
        val session = FakeSession((1L..40L).map { ChatMessage(it, 10, text = existingText(it)) })
        private val queue = ArrayDeque<() -> Unit>()
        private val controller = ChatController(1, session, queue::addLast, { snapshot = it })

        init {
            onActor {
                controller.start()
                controller.event(ChatEvent.NewChat(ChatItem(10, "Synthetic send continuity", order = 100)))
                controller.select(snapshot.token, 10)
                flush()
            }
        }

        private fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun event(event: ChatEvent) = onActor { controller.event(event); flush() }
        fun acknowledge(index: Int, message: ChatMessage) = onActor {
            session.sends[index](ChatResult(message))
            flush()
            draft = TextFieldValue()
        }

        fun scene() = ComposePixelScene((900 * density).roundToInt(), (700 * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides desktopChatLayout(900.dp, 700.dp),
                LocalConversationPreviewMessageBounds provides { id, bounds ->
                    if (bounds == null) measured.remove(id) else measured[id] = bounds
                }) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.width(900.dp).height(700.dp)) {
                        ConversationPane(snapshot, snapshot.selected!!, draft, { draft = it }, null,
                            onLoadOlder = {}, onSend = {
                                controller.sendText(snapshot.token, draft.text)
                                flush()
                            }, onRetry = {}, onMarkRead = {}, features = ChatFeatureActions(
                                cancelSendLatestNavigation = { cancelledFollowTokens += it }), active = true,
                            onSearch = {}, onSearchSender = {}, onAdmins = {}, onInfo = {}, showInfoButton = false)
                    }
                }
            }
        }

        fun capture(scene: ComposePixelScene, probes: List<Probe>, composer: Rect, stage: String,
            count: Int, stationary: Map<Long, Rect>? = null, movingUp: Boolean = false) {
            var previous = probes.associate { it.id to assertNotNull(measured[it.id]) }
            repeat(count) { index ->
                val frame = scene.settledFrame(1, stepMillis = 16)
                val label = "$stage/frame-$index/$mode/${density}x"
                exportIfRequested(frame, stage, index)
                assertEquals(composer, scene.composerBounds(), "The editor must remain fixed: $label")
                probes.forEach { probe ->
                    val bounds = assertNotNull(measured[probe.id], "An existing row disappeared: $label")
                    val prior = assertNotNull(previous[probe.id])
                    assertTrue(bounds.top > 100f * density && bounds.bottom < composer.top - 30f * density,
                        "The stable old row must remain visible in the real viewport: $label, $bounds")
                    assertEquals(probe.initial.size, bounds.size,
                        "Sending must not transiently resize every old row: $label")
                    if (movingUp) assertTrue(bounds.top <= prior.top + 1.5f * density,
                        "Existing content moved down after moving up, exposing a one-frame bounce: $label, $prior -> $bounds")
                    stationary?.get(probe.id)?.let { expected ->
                        assertTrue(abs(bounds.top - expected.top) <= density,
                            "Submission or native id replacement moved existing content: $label, $expected -> $bounds")
                    }
                    probe.ink.assertPainted(frame, scene.textBounds(existingText(probe.id)), label)
                }
                previous = probes.associate { it.id to assertNotNull(measured[it.id]) }
            }
        }

        private fun exportIfRequested(frame: ComposePixelFrame, stage: String, index: Int) {
            val root = System.getProperty("nngGram.sendContinuityFrames") ?: return
            val directory = File(root).apply { mkdirs() }
            val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
            assertTrue(ImageIO.write(image, "png", File(directory, "$mode-${density}x-$stage-$index.png")))
        }
    }

    private data class Probe(val id: Long, val initial: Rect, val ink: InkSample)
    private data class InkPoint(val x: Int, val y: Int, val color: Int)

    /** Stable text cores must still be drawn, even if layout callbacks continue reporting invisible rows. */
    private class InkSample(private val width: Float, private val height: Float, private val points: List<InkPoint>) {
        fun assertPainted(frame: ComposePixelFrame, bounds: Rect, stage: String) {
            assertTrue(abs(width - bounds.width) <= 1f && abs(height - bounds.height) <= 1f,
                "The actual old text geometry changed: $stage")
            val left = bounds.left.roundToInt()
            val top = bounds.top.roundToInt()
            val retained = points.count { point ->
                // Fractional placement can shift antialiasing by one physical pixel.
                (-1..1).any { dy -> (-1..1).any { dx ->
                    val x = left + point.x + dx
                    val y = top + point.y + dy
                    x in 0 until frame.width && y in 0 until frame.height &&
                        colorDistance(frame[x, y], point.color) <= 48
                } }
            }
            assertTrue(retained >= points.size * 0.75f,
                "Existing message text faded or vanished for a displayed frame: $stage, retained=$retained/${points.size}")
        }

        companion object {
            fun capture(frame: ComposePixelFrame, bounds: Rect): InkSample {
                val left = bounds.left.roundToInt()
                val top = bounds.top.roundToInt()
                val pixels = buildList {
                    for (y in 1 until bounds.height.roundToInt() - 1)
                        for (x in 1 until bounds.width.roundToInt() - 1) add(InkPoint(x, y, frame[left + x, top + y]))
                }
                val background = assertNotNull(pixels.groupingBy { it.color }.eachCount().maxByOrNull { it.value }).key
                val contrast = pixels.maxOf { colorDistance(it.color, background) }
                assertTrue(contrast > 100, "The native font must render readable text against its background.")
                // Grayscale and subpixel font rasterizers have different solid-core palettes.
                // Track high-contrast ink, retaining the same per-frame visibility assertion.
                val points = pixels.filter { colorDistance(it.color, background) >= contrast * .5f }
                assertTrue(points.size > 50, "The synthetic old row must contain measurable real text pixels.")
                return InkSample(bounds.width, bounds.height, points)
            }
        }
    }

    private class FakeSession(private val initial: List<ChatMessage>) : TdChatSession {
        val sends = mutableListOf<(ChatResult<ChatMessage>) -> Unit>()
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) =
            result(ChatResult(if (fromMessageId == 0L) initial else emptyList()))
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) { sends += result }
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = Unit
    }

    private fun ComposePixelScene.textBounds(text: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.singleOrNull(), "A previously visible message lost its actual text node: $text")
    private fun ComposePixelScene.composerBounds() = assertNotNull(semanticsBounds {
        "消息输入区域" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull())
    private fun ComposePixelScene.clickSend() {
        val bounds = assertNotNull(semanticsBounds {
            "发送消息" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                it.getOrNull(SemanticsProperties.Role) == Role.Button
        }.singleOrNull())
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    companion object {
        private fun existingText(id: Long) = "Existing message ${id.toString().padStart(2, '0')}: MMMM stable text"
        private fun outgoing(id: Long, text: String) = ChatMessage(id, 10, outgoing = true,
            text = text, delivery = MessageDelivery.PENDING)
        private fun colorDistance(first: Int, second: Int) =
            abs((first shr 16 and 255) - (second shr 16 and 255)) +
                abs((first shr 8 and 255) - (second shr 8 and 255)) +
                abs((first and 255) - (second and 255))
        private fun <T> onActor(action: () -> T): T {
            if (SwingUtilities.isEventDispatchThread()) return action()
            val task = FutureTask(Callable(action))
            SwingUtilities.invokeAndWait(task)
            return task.get()
        }
    }
}
