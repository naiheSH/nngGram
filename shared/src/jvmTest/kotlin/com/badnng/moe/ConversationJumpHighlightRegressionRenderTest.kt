package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.tdlib.ChatController
import com.badnng.moe.tdlib.TdChatSession
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Native placement intent and actual full-width row pixels are independent acceptance points. */
class ConversationJumpHighlightRegressionRenderTest {
    @Test fun nativePlacementDoesNotInventAHighlightButExplicitNavigationRetainsOne() {
        val fixture = NativeFixture()
        assertEquals(null, fixture.snapshot.jumpMessageId)
        assertFalse(fixture.snapshot.jumpHighlight, "Ordinary opening is a placement, not an explicit message search.")
        fixture.actor { rememberHistoryAnchor(fixture.snapshot.token, 15) }
        fixture.actor { loadHistoryAnchor(fixture.snapshot.token, 15) }
        fixture.assertJump(15, false, "saved reading anchor")
        fixture.actor { openHistoryMessage(fixture.snapshot.token, 15) }
        fixture.assertJump(15, true, "explicit loaded-message navigation")
        fixture.actor { search(fixture.snapshot.token, ChatSearchQuery("Synthetic", chatId = 10)) }
        fixture.actor { openSearchMessage(fixture.snapshot.token, 10, 15) }
        fixture.assertJump(15, true, "native search result")
        fixture.session.pinnedRows = listOf(fixture.rows[14])
        fixture.actor { loadPinned(fixture.snapshot.token) }
        fixture.actor { openPinnedMessage(fixture.snapshot.token, 15) }
        fixture.assertJump(15, true, "native pinned message")
        fixture.actor { rememberHistoryAnchor(fixture.snapshot.token, 20) }
        fixture.actor { openReplyMessage(fixture.snapshot.token, 20) }
        fixture.assertJump(15, true, "native reply target")
        fixture.actor { openReplyReturn(fixture.snapshot.token, 10, 20) }
        fixture.assertJump(20, true, "explicit reply return")
        fixture.actor { loadHistoryAnchor(fixture.snapshot.token, 15) }
        fixture.assertJump(15, false, "reading restoration after an explicit jump")

        val unread = NativeFixture(unread = true, newest = 1_000)
        unread.assertJump(11, false, "first native unread window")
        unread.actor { sendText(unread.snapshot.token, "Synthetic own send") }
        unread.assertJump(1_001, false, "send acceptance's atomic latest placement")
    }

    @Test fun ordinaryOpenUnreadAndRestoredReadingNeverFlashWhileTheirRowsArePlaced() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            for (reason in listOf("open", "unread", "restore")) {
                val fixture = NativeFixture(unread = reason == "unread")
                val target = if (reason == "unread") 11L else 15L
                if (reason == "restore") {
                    fixture.actor { rememberHistoryAnchor(fixture.snapshot.token, target) }
                    fixture.memory.rememberPosition(ConversationReadPosition(10, target, 7))
                    fixture.memory.prepareEntry(10)
                }
                fixture.scene(mode, density).use { scene ->
                    val samples = mutableListOf<RowSample>()
                    val geometry = mutableListOf<RowGeometry>()
                    repeat(44) {
                        val frame = scene.settledFrame(1, 16)
                        val observed = scene.targetGeometry(fixture.measured[target], density)
                        geometry += observed
                        observed.point?.let { point ->
                            samples += RowSample(assertNotNull(observed.target), point, frame.at(point))
                        }
                    }
                    val normal = scene.settledFrame(64, 64)
                    val resting = assertNotNull(fixture.measured[target], "$reason must really place the native target.")
                    val stableSamples = samples.filter { it.bounds == resting }
                    assertTrue(stableSamples.size >= 20,
                        "The test must inspect real stable row pixels during the potential flash: $reason/$mode/$density; " +
                            "stable=${stableSamples.size}, resting=$resting, geometry=${geometry.distinct()}")
                    stableSamples.forEach { sample ->
                        assertTrue(distance(sample.color, normal.at(sample.point)) <= 1,
                            "Ordinary $reason must never paint a transient jump stripe: $mode/$density, " +
                                "${sample.color.toUInt().toString(16)} vs ${normal.at(sample.point).toUInt().toString(16)}")
                    }
                    assertFalse(fixture.snapshot.jumpHighlight)
                }
            }
        }
    }

    @Test fun incomingNavigationPaintsBeforeItsPageAcceptsInputWithoutReplayingRestorationOrTheOutgoingWindow() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            for (reason in listOf("explicit", "restore", "outgoing")) {
                val fixture = NativeFixture()
                val outgoing = reason == "outgoing"
                if (reason == "restore") {
                    fixture.actor { rememberHistoryAnchor(fixture.snapshot.token, 15) }
                    fixture.memory.rememberPosition(ConversationReadPosition(10, 15, 7))
                    fixture.memory.prepareEntry(10)
                    fixture.actor { loadHistoryAnchor(fixture.snapshot.token, 15) }
                } else fixture.actor { openHistoryMessage(fixture.snapshot.token, 15) }
                fixture.assertJump(15, reason != "restore", "$reason before the mounted page accepts input")
                var interactive by mutableStateOf(false)
                fixture.scene(mode, density, pageInteractive = { interactive }, outgoingWindow = outgoing).use { scene ->
                    val geometry = mutableListOf<RowGeometry>()
                    fun capture(): RowSample? {
                        val frame = scene.settledFrame(1, 16)
                        val observed = scene.targetGeometry(fixture.measured[15], density, outgoing)
                        geometry += observed
                        return observed.point?.let { RowSample(assertNotNull(observed.target), it, frame.at(it)) }
                    }
                    // A real incoming page is mounted throughout the 240ms workspace slide,
                    // while its input remains gated. Native placement must retain its visual intent.
                    val entering = List(16) { capture() }.filterNotNull()
                    val beforeInput = assertNotNull(entering.lastOrNull(), "The entering target must actually be visible: $geometry")
                    interactive = true
                    val afterInput = assertNotNull(capture())
                    assertEquals(beforeInput.bounds, afterInput.bounds, "Enabling page input cannot reposition the native target.")
                    assertEquals(beforeInput.point, afterInput.point)
                    assertTrue(distance(beforeInput.color, afterInput.color) <= 4,
                        "Enabling page input cannot restart or remove the existing row stripe: $reason/$mode/$density")
                    val interactiveSamples = listOf(afterInput) + List(27) { capture() }.filterNotNull()
                    val normal = scene.settledFrame(64, 64)
                    val resting = assertNotNull(fixture.measured[15])
                    val stable = (entering + interactiveSamples).filter { it.bounds == resting }
                    assertTrue(stable.size >= 20,
                        "The gated page must expose stable target pixels: $reason/$mode/$density; " +
                            "stable=${stable.size}, resting=$resting, geometry=${geometry.distinct()}")
                    if (reason == "explicit") {
                        assertTrue(entering.filter { it.bounds == resting }.any { distance(it.color, normal.at(it.point)) > 3 },
                            "An explicit incoming jump must already be visible before page input is enabled: $mode/$density")
                    } else stable.forEach {
                        assertTrue(distance(it.color, normal.at(it.point)) <= 1,
                            "Restored reading and the retained outgoing window cannot acquire a jump stripe: $reason/$mode/$density")
                    }
                    interactive = false
                    capture()
                    interactive = true
                    fixture.actor { event(ChatEvent.Outbox(10, 1)) }
                    repeat(34) {
                        val refreshed = assertNotNull(capture())
                        assertEquals(resting, refreshed.bounds)
                        assertTrue(distance(refreshed.color, normal.at(refreshed.point)) <= 1,
                            "Input gating or unrelated native refresh cannot replay a consumed row stripe: $reason/$mode/$density")
                    }
                }
            }
        }
    }

    @Test fun anExplicitNativeJumpPaintsTheRealPaneAndUnrelatedUpdatesCannotReplayIt() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val fixture = NativeFixture()
            fixture.scene(mode, density).use { scene ->
                scene.settledFrame()
                fixture.actor { openHistoryMessage(fixture.snapshot.token, 15) }
                val samples = mutableListOf<RowSample>()
                repeat(44) {
                    val frame = scene.settledFrame(1, 16)
                    fixture.measured[15]?.let { bounds ->
                        val point = Offset(644f * density, bounds.center.y)
                        samples += RowSample(bounds, point, frame.at(point))
                    }
                }
                val active = assertNotNull(samples.lastOrNull())
                fixture.actor { openHistoryMessage(fixture.snapshot.token, 15) }
                val repeated = scene.settledFrame(1, 16)
                assertTrue(distance(active.color, repeated.at(active.point)) <= 4,
                    "A repeated navigation to the already highlighted target cannot restart its fade-in or flash it off.")
                val normal = scene.settledFrame(64, 64)
                val resting = assertNotNull(fixture.measured[15])
                assertTrue(samples.filter { it.bounds == resting }.any { distance(it.color, normal.at(it.point)) > 3 },
                    "The explicit native request must really paint the Pane's temporary row stripe: $mode/$density")
                fixture.actor { event(ChatEvent.Outbox(10, 1)) }
                val refreshed = scene.settledFrame(34, 16)
                val point = Offset(644f * density, resting.center.y)
                assertEquals(normal.at(point), refreshed.at(point), "An unrelated native refresh cannot replay a consumed jump.")
            }
        }
    }

    @Test fun fullRowHighlightCoversBothMarginsAndTheAvatarSlotWithoutChangingTextOrMediaGeometry() {
        val path = Files.createTempFile("full-row-highlight-", ".png")
        try {
            val image = BufferedImage(240, 140, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, GREEN)
            check(ImageIO.write(image, "png", path.toFile()))
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                for (photo in listOf(false, true)) {
                    var width by mutableStateOf(900)
                    var highlighted by mutableStateOf(false)
                    var refresh by mutableIntStateOf(0)
                    var bubble = Rect.Zero
                    var row = Rect.Zero
                    val message = ChatMessage(1, 10, senderId = 20, date = 1_700_000_000,
                        text = if (photo) "" else "Stable text and metadata",
                        kind = if (photo) MessageKind.PHOTO else MessageKind.TEXT,
                        media = if (photo) MessageMedia(MediaFile(31, downloaded = true, localPath = path.toString()),
                            width = 240, height = 140) else null)
                    ComposePixelScene((900 * density).roundToInt(), (400 * density).roundToInt()) {
                        val theme = remember { ThemeController(mode) }
                        val snapshot = remember(refresh) { ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
                            selected = ChatItem(10, "Synthetic full row", basicGroupId = 30), messages = listOf(message),
                            senderNames = mapOf(20L to "Native sender"),
                            senderAvatars = mapOf(20L to MediaFile(32, downloaded = true, localPath = path.toString()))) }
                        CompositionLocalProvider(LocalDensity provides Density(density)) {
                            MiuixTheme(controller = theme) {
                                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                                    Box(Modifier.offset(y = 70.dp).width(width.dp).onGloballyPositioned {
                                        row = it.boundsInWindow(clipBounds = false)
                                    }) {
                                        MessageBubble(message, "Native sender", showSender = false, endsGroup = true,
                                            onBounds = { if (it != null) bubble = it }, retrying = false,
                                            highlighted = highlighted, read = false, onRetry = {}, snapshot = snapshot,
                                            features = ChatFeatureActions(), visible = true, animate = false, onEnlarge = {},
                                            onSave = { _, _ -> }, onPlay = {}, playing = false, onStopPlaying = {},
                                            onVideoBounds = {}, playbackSession = null, onExpandVideo = {}, menuOpen = false,
                                            onMenuOpen = {}, onMenuDismiss = {}, onReply = {}, onQuote = {}, onJumpToReply = {},
                                            onReact = {}, onReadDetails = {}, onEdit = {}, onForward = {}, onDelete = {},
                                            onSearchSender = {}, showSenderName = false, showAvatar = true,
                                            reserveAvatarSpace = true, horizontalContentPadding = 24.dp)
                                    }
                                }
                            }
                        }
                    }.use { scene ->
                        val deadline = System.nanoTime() + 5_000_000_000L
                        while (scene.imageBounds().size < if (photo) 2 else 1) {
                            assertTrue(System.nanoTime() < deadline, "The real avatar/photo must finish decoding.")
                            Thread.sleep(5)
                            scene.settledFrame(2)
                        }
                        val normal = scene.settledFrame()
                        val originalBubble = bubble
                        val originalArtwork = scene.imageBounds()
                        val avatar = assertNotNull(originalArtwork.singleOrNull { abs(it.width - 33f * density) <= 1f })
                        assertEquals(64f * density, bubble.left, density,
                            "Moving row padding must retain the original 24dp margin and 40dp avatar reservation.")
                        highlighted = true
                        val peak = scene.settledFrame(34, 16)
                        save(peak, "$mode-${density}x-${message.kind}-full-width")
                        val points = stripePoints(width, row.center.y, density)
                        assertUniformStripe(peak, points, "$mode/$density/$photo")
                        assertEquals(originalBubble, bubble)
                        assertEquals(originalArtwork, scene.imageBounds())
                        assertEquals(normal.at(avatar.center), peak.at(avatar.center), "The opaque avatar stays above the row stripe.")
                        val bubblePoint = if (photo) bubble.center else Offset(bubble.left + 8f * density, bubble.center.y)
                        assertEquals(normal.at(bubblePoint), peak.at(bubblePoint), "The ordinary text/photo surface remains unchanged.")
                        width = 680
                        val resized = scene.settledFrame(2, 16)
                        save(resized, "$mode-${density}x-${message.kind}-resized")
                        assertEquals(680f * density, row.width, density)
                        assertEquals(originalBubble, bubble, "A narrower row cannot alter a fitting message's native dimensions.")
                        assertEquals(originalArtwork, scene.imageBounds())
                        assertUniformStripe(resized, stripePoints(width, row.center.y, density), "resized/$mode/$density/$photo")
                        val ended = scene.settledFrame(64, 64)
                        stripePoints(width, row.center.y, density).forEach { assertEquals(MAGENTA, ended.at(it)) }
                        refresh++
                        val refreshed = scene.settledFrame(34, 16)
                        stripePoints(width, row.center.y, density).forEach {
                            assertEquals(MAGENTA, refreshed.at(it), "Unrelated snapshots cannot replay the same row's completed highlight.")
                        }
                    }
                }
            }
        } finally { Files.deleteIfExists(path) }
    }

    private inner class NativeFixture(unread: Boolean = false, newest: Long = 20) {
        val rows = (1L..20L).map { id -> ChatMessage(id, 10, senderId = 20, date = 1_700_000_000,
            text = "Synthetic row $id", replyTo = if (id == 20L) MessageReply(10, 15, "Synthetic row 15") else null) }
        var snapshot by mutableStateOf(ChatSnapshot())
        val session = Session(rows)
        private val queue = ArrayDeque<() -> Unit>()
        private val controller = ChatController(1, session, queue::addLast, { snapshot = it })
        val measured = ConcurrentHashMap<Long, Rect>()
        val memory = ConversationSessionMemory()
        init {
            actor {
                start()
                event(ChatEvent.NewChat(ChatItem(10, "Synthetic highlight intent", order = 100,
                    privateUserId = 20,
                    unreadCount = if (unread) 10 else 0, lastReadInboxId = if (unread) 10 else 0,
                    lastMessage = rows.last().copy(id = newest))))
                select(this@NativeFixture.snapshot.token, 10)
            }
        }

        fun actor(block: ChatController.() -> Unit) = onUi {
            controller.block()
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        }

        fun assertJump(id: Long, highlight: Boolean, reason: String) {
            assertEquals(id, snapshot.jumpMessageId, "The native request must still place its actual target: $reason")
            assertEquals(highlight, snapshot.jumpHighlight, "Placement and visual highlight must remain separate: $reason")
        }

        fun scene(mode: ColorSchemeMode, density: Float, pageInteractive: () -> Boolean = { true },
            outgoingWindow: Boolean = false) = ComposePixelScene((680 * density).roundToInt(), (620 * density).roundToInt(), virtualTime = true) {
            val theme = remember { ThemeController(mode) }
            val motion = remember(outgoingWindow) { if (outgoingWindow) HistoryWindowMotion(true) { 0f } else null }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides desktopChatLayout(680.dp, 620.dp),
                LocalHistoryWindowMotion provides motion,
                LocalConversationPreviewMessageBounds provides { id, bounds ->
                    if (bounds == null) measured.remove(id) else measured[id] = bounds
                }) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.width(680.dp).height(620.dp)) {
                        ConversationPane(snapshot, snapshot.selected!!, TextFieldValue(), {}, null, {}, {}, {}, {},
                            ChatFeatureActions(loadHistoryAnchor = { token, id -> actor { loadHistoryAnchor(token, id) } },
                                rememberHistoryAnchor = { token, id -> actor { rememberHistoryAnchor(token, id) } }),
                            pageInteractive() && !outgoingWindow, {}, {}, {}, {}, false,
                            pageInteractive = pageInteractive(), sessionMemory = memory)
                    }
                }
            }
        }
    }

    private class Session(private val initial: List<ChatMessage>) : TdChatSession {
        var historyRows = initial
        var pinnedRows = emptyList<ChatMessage>()
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) =
            result(ChatResult(historyRows.filter { fromMessageId == 0L || it.id < fromMessageId }.takeLast(limit)))
        override fun historyAround(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = result(ChatResult(initial))
        override fun unreadHistory(chatId: Long, afterMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = result(ChatResult(initial))
        override fun search(query: ChatSearchQuery, offset: String, result: (ChatResult<ChatSearchPage>) -> Unit) =
            result(ChatResult(ChatSearchPage(messages = listOf(initial[14]), totalCount = 1)))
        override fun pinnedMessages(chatId: Long, fromMessageId: Long, result: (ChatResult<ChatSearchPage>) -> Unit) =
            result(ChatResult(ChatSearchPage(messages = if (fromMessageId == 0L) pinnedRows else emptyList(), totalCount = pinnedRows.size)))
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) {
            val sent = ChatMessage(1_001, chatId, text = text, outgoing = true, delivery = MessageDelivery.PENDING)
            historyRows = (998L..1_000L).map { ChatMessage(it, chatId, text = "Synthetic latest $it") } + sent
            result(ChatResult(sent))
        }
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = Unit
    }

    private data class RowSample(val bounds: Rect, val point: Offset, val color: Int)
    private data class RowGeometry(val target: Rect?, val composer: Rect?, val point: Offset?)
    private fun ComposePixelScene.targetGeometry(bounds: Rect?, density: Float, outgoing: Boolean = false): RowGeometry {
        val composer = semanticsBounds {
            "消息输入区域" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.singleOrNull()
        // This semantics node is inside the composer's real 8dp top padding. Its whole
        // overlay plus the actual feather occludes the timeline, even beside the field.
        val clearBottom = if (outgoing) 620f * density else composer?.top?.minus(
            (8f * density).roundToInt() + (ConversationComposerBlurFeather.value * density).roundToInt()) ?: 0f
        val top = maxOf(bounds?.top?.plus(density) ?: 0f, 100f * density)
        val bottom = minOf(bounds?.bottom?.minus(density) ?: 0f, clearBottom - 2f * density)
        val point = if (bounds != null && bottom - top >= 4f * density)
            Offset(644f * density, (top + bottom) / 2f) else null
        return RowGeometry(bounds, composer, point)
    }
    private fun ComposePixelScene.imageBounds() = unmergedSemanticsBounds {
        "聊天图片" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.sortedBy { it.width }
    private fun stripePoints(width: Int, y: Float, density: Float) =
        listOf(1f, 12f, 60f, width - 12f, width - 1f).map { Offset(it * density, y) }
    private fun assertUniformStripe(frame: ComposePixelFrame, points: List<Offset>, label: String) {
        val color = frame.at(points.first())
        assertTrue(distance(color, MAGENTA) > 3, "The explicit highlight must reach the outermost conversation margin: $label")
        points.forEach { assertEquals(color, frame.at(it), "The stripe must span both viewport edges and the avatar gap: $label/$it") }
    }
    private fun ComposePixelFrame.at(point: Offset) = this[point.x.roundToInt(), point.y.roundToInt()]
    private fun save(frame: ComposePixelFrame, label: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "conversation-jump-highlight-regression")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$label.png").toFile()))
    }
    private companion object {
        val GREEN = 0xff24bd76.toInt()
        val MAGENTA = 0xffff00ff.toInt()
        fun distance(first: Int, second: Int) = maxOf(abs((first ushr 16 and 255) - (second ushr 16 and 255)),
            abs((first ushr 8 and 255) - (second ushr 8 and 255)), abs((first and 255) - (second and 255)))
        fun <T> onUi(action: () -> T): T {
            if (SwingUtilities.isEventDispatchThread()) return action()
            val task = FutureTask(Callable(action))
            SwingUtilities.invokeAndWait(task)
            return task.get()
        }
    }
}
