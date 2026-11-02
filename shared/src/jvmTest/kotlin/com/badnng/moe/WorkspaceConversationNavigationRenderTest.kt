package com.badnng.moe

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSearchState
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.status.ConnectionPhase
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual workspace input/snapshot races from the supplied recording, with no account or native I/O. */
class WorkspaceConversationNavigationRenderTest {
    @Test
    fun delayedSelectionNeverShowsThePreviousConversationAndBothDirectionsPaintIntermediateFrames() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            val fixture = Fixture()
            val sample = "${if (dark) "dark" else "light"}-$density"
            fixture.scene(dark, density).use { scene ->
                val openedA = scene.settledFrame()
                openedA.save("$sample-original-a")
                assertEquals(listOf(DRAFT_A), scene.fields())
                val oldComposer = scene.description("消息输入区域")
                val oldBack = scene.description("返回聊天列表")
                fixture.visibility.clear()
                scene.click(oldBack)
                // Recomposition and the first animation frame can occupy two renders.
                // The third 64ms frame records movement inside the 240ms transition.
                val exitingA = scene.settledFrame(3)
                exitingA.save("$sample-exit-a")
                assertTrue(scene.fields().isEmpty(), "The outgoing composer must leave the input/accessibility tree immediately.")
                assertFalse(scene.hasText(MESSAGE_A), "The retained outgoing timeline must have no accessible content.")
                scene.click(oldComposer)
                scene.keyStroke(KeyEvent.VK_X, 'x')
                assertEquals(DRAFT_A, fixture.drafts[CHAT_A.id]?.text,
                    "The painted closing editor must reject pointer and keyboard input.")
                val list = scene.settledFrame()
                list.save("$sample-list")
                assertIntermediate(openedA, exitingA, list, "Back must slide the real outgoing conversation away")
                assertFalse(scene.hasText(MESSAGE_A))
                assertTrue(scene.hasText(CHAT_B.title))

                scene.click(scene.text(CHAT_B.title))
                scene.settledFrame().save("$sample-pending-b")
                assertEquals(listOf(CHAT_B.id), fixture.selections.toList())
                assertFalse(scene.hasText(CHAT_A.title), "An unacknowledged B request must not display the old A header.")
                assertFalse(scene.hasText(MESSAGE_A), "A delayed native snapshot must not flash A's messages as B opens.")
                scene.assertNoConversationComposer("No old editor may open while native selection is pending.")
                assertTrue(fixture.visibility.none { it.active && it.ids.isNotEmpty() },
                    "Returning to the list or waiting for B must keep the hidden timeline inactive.")

                fixture.acknowledge(CHAT_B)
                val enteringB = scene.settledFrame(3)
                enteringB.save("$sample-entry-b")
                assertTrue(scene.fields().isEmpty(), "The incoming editor must remain inert until the movement settles.")
                Thread.sleep(340)
                assertTrue(fixture.visibility.none { it.active && it.ids.isNotEmpty() },
                    "Animation frames must not dispatch the newly entering timeline's visibility debounce.")
                val openedB = scene.settledFrame()
                openedB.save("$sample-confirmed-b")
                assertIntermediate(list, enteringB, openedB, "Acknowledging B must slide the actual B conversation in")
                assertTrue(scene.hasText(CHAT_B.title))
                assertTrue(scene.hasText(MESSAGE_B))
                assertFalse(scene.hasText(MESSAGE_A))
                assertEquals(listOf(DRAFT_B), scene.fields())
                scene.click(scene.field())
                scene.settledFrame(2)
                scene.selectAll()
                scene.settledFrame(2)
                scene.keyStroke(KeyEvent.VK_X, 'x')
                scene.settledFrame(2)
                assertEquals("x", fixture.drafts[CHAT_B.id]?.text,
                    "The current composer must accept focused selection/replacement input after the transition.")
                assertTrue(fixture.sent.isEmpty(), "Navigation must never submit either retained draft.")
            }
        }
    }

    @Test
    fun selectingTheAlreadySelectedChatReopensWithoutWaitingForAnUnchangedNativeToken() {
        val fixture = Fixture(includeAInList = true)
        fixture.scene().use { scene ->
            scene.settledFrame()
            val unchangedToken = fixture.snapshot.token
            scene.click(scene.description("返回聊天列表"))
            val list = scene.settledFrame()
            scene.click(scene.text(CHAT_A.title))
            val entering = scene.settledFrame(3)
            val opened = scene.settledFrame()
            assertEquals(listOf(CHAT_A.id), fixture.selections.toList())
            assertEquals(unchangedToken, fixture.snapshot.token)
            assertIntermediate(list, entering, opened, "Reopening the same selected chat still needs real motion")
            assertTrue(scene.hasText(MESSAGE_A))
            assertEquals(listOf(DRAFT_A), scene.fields())
        }
    }

    @Test
    fun delayedSearchSelectionAlsoWaitsForTheRequestedChatInsteadOfReopeningTheOldOne() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.description("返回聊天列表"))
            scene.settledFrame()
            scene.click(scene.description("搜索当前聊天列表"))
            scene.settledFrame()
            assertTrue(scene.hasText("最近"))
            scene.click(scene.text(CHAT_B.title))
            scene.settledFrame()
            assertEquals(listOf(CHAT_B.id), fixture.searchSelections.toList())
            assertFalse(scene.hasText(MESSAGE_A))
            assertFalse(scene.hasText(CHAT_A.title))
            assertTrue(scene.fields().none { it == DRAFT_A || it == DRAFT_B },
                "The search field may remain visible; neither conversation composer may open before acknowledgement.")
            fixture.acknowledge(CHAT_B)
            scene.settledFrame()
            assertTrue(scene.hasText(MESSAGE_B))
            assertFalse(scene.hasText(MESSAGE_A))
            assertEquals(listOf(DRAFT_B), scene.fields())
        }
    }

    @Test
    fun cancellingAPendingSelectionPreventsItsLateSnapshotFromReopeningTheConversation() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.description("返回聊天列表"))
            scene.settledFrame()
            scene.click(scene.text(CHAT_B.title))
            scene.settledFrame(1)
            assertEquals(listOf(CHAT_B.id), fixture.selections.toList())
            scene.keyStroke(KeyEvent.VK_ESCAPE)
            scene.settledFrame(1)
            fixture.acknowledge(CHAT_B)
            scene.settledFrame()
            assertTrue(scene.hasText(CHAT_B.title), "The list remains visible after cancelling a delayed navigation request.")
            assertFalse(scene.hasText(MESSAGE_B), "A late selection acknowledgement must not undo the user's return to the list.")
            scene.assertNoConversationComposer("Cancelling pending navigation must leave the list search field as the only editor.")
            assertTrue(scene.descriptions("搜索当前聊天列表").isNotEmpty())
            // The native selection is now B. A new explicit click may reopen it without
            // waiting for yet another token update.
            scene.click(scene.text(CHAT_B.title))
            scene.settledFrame()
            assertTrue(scene.hasText(MESSAGE_B))
            assertEquals(listOf(DRAFT_B), scene.fields())
        }
    }

    @Test
    fun replacingAPendingRequestAndReversingTheEntryNeverOpensTheLatePreviousTarget() {
        val fixture = Fixture(includeAInList = true)
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.description("返回聊天列表"))
            scene.settledFrame()
            scene.click(scene.text(CHAT_B.title))
            scene.settledFrame(2)
            scene.click(scene.text(CHAT_A.title))
            scene.settledFrame(3)
            assertEquals(listOf(CHAT_B.id, CHAT_A.id), fixture.selections.toList(),
                "The list must permit replacing a pending B request with a fresh A request.")
            // A is the already selected chat, so its entry has started. Let the late
            // acknowledgement for the abandoned B request arrive during that movement.
            fixture.acknowledge(CHAT_B)
            scene.settledFrame()
            assertFalse(scene.hasText(MESSAGE_B), "An abandoned target may not take over an entry in progress.")
            scene.assertNoConversationComposer("The mismatched late native selection cannot own an editor.")
            fixture.acknowledge(CHAT_B)
            scene.settledFrame()
            assertFalse(scene.hasText(MESSAGE_B), "Further updates for B must not automatically reopen the abandoned target.")
            scene.assertNoConversationComposer("Only a new explicit request can open B after this reversal.")
            assertTrue(scene.descriptions("搜索当前聊天列表").isNotEmpty())
        }
    }

    @Test
    fun wideWorkspaceKeepsItsTwoPaneGeometryAndCanChangeSelection() {
        val fixture = Fixture(includeAInList = true)
        fixture.scene(width = 1160).use { scene ->
            scene.settledFrame()
            assertTrue(scene.descriptions("返回聊天列表").isEmpty())
            assertEquals(1, scene.descriptions("调整聊天列表宽度").size)
            val oldComposer = scene.description("消息输入区域")
            fixture.visibility.clear()
            scene.click(scene.text(CHAT_B.title))
            scene.settledFrame()
            assertEquals(listOf(CHAT_B.id), fixture.selections.toList())
            scene.assertNoConversationComposer("A wide pending B request must freeze the old A conversation's input and semantics.")
            assertEquals(listOf(""), scene.fields(), "The independent list search remains editable while B is pending.")
            scene.click(oldComposer)
            scene.keyStroke(KeyEvent.VK_X, 'x')
            assertEquals(DRAFT_A, fixture.drafts[CHAT_A.id]?.text,
                "The retained wide A pane must reject keyboard input while B is pending.")
            assertTrue(fixture.visibility.none { it.active && it.ids.isNotEmpty() },
                "The retained wide pane must not advertise visible incoming messages while its selection is pending.")
            fixture.acknowledge(CHAT_B)
            scene.settledFrame()
            assertTrue(scene.hasText(MESSAGE_B))
            assertFalse(scene.hasText(MESSAGE_A))
            assertTrue(DRAFT_B in scene.fields())
            assertFalse(DRAFT_A in scene.fields())
            assertEquals(1, scene.descriptions("消息输入区域").size)
            assertEquals(1, scene.descriptions("调整聊天列表宽度").size)
            assertTrue(scene.descriptions("返回聊天列表").isEmpty())
        }
    }

    @Test
    fun nativeSelectionClosureReturnsToTheActualListAndReleasesTheRetainedPage() {
        for (width in listOf(380, 1160)) {
            val fixture = Fixture()
            fixture.scene(width = width).use { scene ->
                scene.settledFrame()
                assertTrue(scene.hasText(MESSAGE_A))
                val oldComposer = scene.description("消息输入区域")
                fixture.visibility.clear()
                SwingUtilities.invokeAndWait {
                    fixture.snapshot = fixture.snapshot.copy(
                        token = fixture.snapshot.token.copy(selection = fixture.snapshot.token.selection + 1, chatId = null),
                        selected = null, messages = emptyList(), knownChats = listOf(CHAT_B))
                }
                scene.settledFrame(2, 16)
                scene.assertNoConversationComposer("A native closure must immediately revoke the retained page's editor: $width")
                assertFalse(scene.hasText(MESSAGE_A), "The retained closing timeline cannot remain accessible: $width")
                scene.click(oldComposer)
                scene.keyStroke(KeyEvent.VK_X, 'x')
                scene.settledFrame()
                assertEquals(DRAFT_A, fixture.drafts[CHAT_A.id]?.text,
                    "A native-closed page cannot receive input through the old composer's coordinates: $width")
                assertTrue(fixture.visibility.none { it.active && it.ids.isNotEmpty() },
                    "Native closure must also revoke the retained timeline's active visibility: $width")
                assertTrue(scene.hasText(CHAT_B.title), "The current chat list must be reachable after native closure: $width")
                scene.click(scene.text(CHAT_B.title))
                scene.settledFrame(2)
                assertEquals(listOf(CHAT_B.id), fixture.selections.toList())
                scene.assertNoConversationComposer("A new list request still waits for B's native identity: $width")
                fixture.acknowledge(CHAT_B)
                scene.settledFrame()
                assertTrue(scene.hasText(MESSAGE_B))
                assertFalse(scene.hasText(MESSAGE_A))
                assertTrue(DRAFT_B in scene.fields())
                assertTrue(fixture.sent.isEmpty())
            }
        }
    }

    private class Fixture(includeAInList: Boolean = false) {
        var snapshot by mutableStateOf(ChatSnapshot(
            enabled = true, token = ChatToken(1, 1, CHAT_A.id), selected = CHAT_A,
            items = if (includeAInList) listOf(CHAT_A, CHAT_B) else listOf(CHAT_B),
            knownChats = listOf(CHAT_A, CHAT_B), listEnd = true, historyEnd = true,
            messages = listOf(ChatMessage(101, CHAT_A.id, text = MESSAGE_A)),
            search = ChatSearchState(recent = listOf(CHAT_B)),
        ))
        val drafts = mutableStateMapOf(CHAT_A.id to TextFieldValue(DRAFT_A), CHAT_B.id to TextFieldValue(DRAFT_B))
        val selections = CopyOnWriteArrayList<Long>()
        val searchSelections = CopyOnWriteArrayList<Long>()
        val sent = CopyOnWriteArrayList<ChatToken>()
        val visibility = CopyOnWriteArrayList<Visibility>()
        private val features = ChatFeatureActions(
            selectSearchChat = { _, id -> searchSelections += id },
            visibleMessages = { token, ids, active -> visibility += Visibility(token, ids.toList(), active) },
        )

        fun acknowledge(chat: ChatItem) = SwingUtilities.invokeAndWait {
            snapshot = snapshot.copy(token = snapshot.token.copy(selection = snapshot.token.selection + 1, chatId = chat.id),
                selected = chat, messages = listOf(ChatMessage(201, chat.id, text = MESSAGE_B)))
        }

        fun scene(dark: Boolean = false, density: Float = 1f, width: Int = 380) =
            ComposePixelScene((width * density).roundToInt(), (700 * density).roundToInt()) {
                val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = theme) {
                        ChatWorkspace(snapshot, ConnectionPhase.READY, drafts, onSettings = {},
                            onSelectChat = { _, id -> selections += id }, onLoadChats = {}, onLoadOlder = {},
                            onSendText = { token, _, _ -> sent += token }, onRetryMessage = { _, _ -> }, onMarkRead = {},
                            windowFocused = true, features = features, darkTheme = dark, onDarkTheme = {},
                            persistUiPreferences = false)
                    }
                }
            }
    }

    private data class Visibility(val token: ChatToken, val ids: List<Long>, val active: Boolean)

    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.description(label: String): Rect =
        assertNotNull(descriptions(label).firstOrNull(), "Missing workspace action: $label")
    private fun ComposePixelScene.text(label: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.firstOrNull(), "Missing workspace text: $label")
    private fun ComposePixelScene.hasText(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.isNotEmpty()
    private fun ComposePixelScene.field(): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText) != null
    }.singleOrNull(), "The selected conversation must expose one current editor")
    private fun ComposePixelScene.fields(): List<String> = buildList {
        semanticsBounds { it.getOrNull(SemanticsProperties.EditableText)?.text?.let(::add); false }
    }
    private fun ComposePixelScene.assertNoConversationComposer(message: String) {
        assertTrue(descriptions("消息输入区域").isEmpty(), message)
        assertTrue(fields().none { it == DRAFT_A || it == DRAFT_B }, message)
    }
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun assertIntermediate(start: ComposePixelFrame, middle: ComposePixelFrame, end: ComposePixelFrame, message: String) {
        val startToMiddle = changedBody(start, middle)
        val middleToEnd = changedBody(middle, end)
        val minimumBodyMovement = start.width * (start.height / 2) / 10
        assertTrue(startToMiddle > minimumBodyMovement && middleToEnd > 300,
            "$message; the central body must change on both sides of the intermediate frame " +
                "($startToMiddle, $middleToEnd pixels), independently of header/composer disabled colors.")
    }
    private fun changedBody(first: ComposePixelFrame, second: ComposePixelFrame): Int {
        var changed = 0
        for (y in first.height / 4 until first.height * 3 / 4) for (x in 0 until first.width) {
            if (first[x, y] != second[x, y]) changed++
        }
        return changed
    }
    private fun ComposePixelFrame.save(name: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "workspace-conversation-navigation")
        check(directory.isDirectory || directory.mkdirs())
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, IntArray(width * height) { index ->
            this[index % width, index / width]
        }, 0, width)
        check(ImageIO.write(image, "png", File(directory, "$name.png")))
    }

    companion object {
        private val CHAT_A = ChatItem(10, "合成原会话 A", order = 100)
        private val CHAT_B = ChatItem(20, "合成目标会话 B", order = 90)
        private const val MESSAGE_A = "A 的旧消息不可闪现在 B 的进入帧"
        private const val MESSAGE_B = "B 的已确认消息"
        private const val DRAFT_A = "原会话草稿 A"
        private const val DRAFT_B = "目标会话草稿 B"
    }
}
