package com.badnng.moe

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class OfficialComposerRenderTest {
    @Test
    fun replyAndMultilineGrowthAnimateWhileTheAttachmentButtonAndPopupStayAtTheBottom() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) for (width in listOf(366, 900)) {
            var mode by mutableStateOf(0)
            var draft by mutableStateOf(TextFieldValue())
            val initial = fixture()
            val measured = java.util.concurrent.ConcurrentHashMap<Long, Rect>()
            val name = "${if (dark) "dark" else "light"}-$density-$width"
            scene(width, density, dark) {
                CompositionLocalProvider(LocalConversationPreviewMessageBounds provides { id, bounds ->
                    if (bounds == null) measured.remove(id) else measured[id] = bounds
                }) {
                val state = when (mode) {
                    1 -> initial.copy(replyTarget = initial.messages.first())
                    2 -> initial.copy(editTarget = initial.messages.last().copy(text = "编辑第一行\n编辑第二行\n编辑第三行"))
                    else -> initial
                }
                ConversationPane(state, state.selected!!, draft, { draft = it }, null,
                    {}, {}, {}, {}, ChatFeatureActions(), false, {}, {}, {}, {}, false)
                }
            }.use { scene ->
                scene.settledFrame(12)
                val initialHeight = scene.composer().height
                val button = scene.action("附件")
                scene.moveTo(button.center.x, button.center.y)
                scene.settledFrame(12).save("$name-menu-normal")
                val menu = scene.menu()
                assertAttachmentGap(button, menu, density)
                mode = 1
                draft = TextFieldValue("第一行\n第二行\n第三行\n第四行")
                val intermediate = mutableListOf<Float>()
                repeat(5) { index ->
                    val frame = scene.settledFrame(1)
                    intermediate += scene.composer().height
                    assertRectClose(button, scene.action("附件"), "Attachment must not move with reply/multiline growth")
                    assertRectClose(menu, scene.menu(), "Popup must stay anchored to the bottom button")
                    assertAttachmentGap(scene.action("附件"), scene.menu(), density)
                    val last = assertNotNull(measured[initial.messages.last().id])
                    val blurTop = scene.composer().top - (8f * density).roundToInt() -
                        (ConversationComposerBlurFeather.value * density).roundToInt()
                    assertTrue(last.bottom <= blurTop - (2f * density).roundToInt() + 1f,
                        "Every animation frame must keep the latest bubble clear of the actual blur: $last vs $blurTop")
                    if (index == 2) frame.save("$name-reply-middle")
                }
                scene.settledFrame(12).save("$name-reply-expanded")
                val expanded = scene.composer().height
                assertTrue(expanded > initialHeight)
                assertTrue(intermediate.any { it > initialHeight + 1 && it < expanded - 1 },
                    "Real measured height must include intermediate frames: $intermediate from $initialHeight to $expanded")
                scene.keyStroke(KeyEvent.VK_ESCAPE)
                scene.settledFrame(12)
                mode = 2
                scene.settledFrame(12).save("$name-edit")
                assertEquals(1, scene.fields().size, "Editing must reuse the only text field")
                for (label in listOf("取消编辑", "保存修改")) {
                    val action = scene.action(label)
                    assertTrue(action.top >= scene.composer().top && action.bottom <= scene.composer().bottom)
                }
                assertRectClose(button, scene.action("附件"), "Edit context must retain bottom button geometry")
                mode = 0
                draft = TextFieldValue()
                val shrinking = mutableListOf<Float>()
                repeat(5) {
                    shrinking += scene.settledFrame(1).let { scene.composer().height }
                    val last = assertNotNull(measured[initial.messages.last().id])
                    val blurTop = scene.composer().top - (8f * density).roundToInt() -
                        (ConversationComposerBlurFeather.value * density).roundToInt()
                    assertTrue(last.bottom <= blurTop - (2f * density).roundToInt() + 1f,
                        "Closing frames must also keep the latest bubble clear of the actual blur")
                }
                scene.settledFrame(12)
                assertTrue(shrinking.any { it > initialHeight + 1 }, "Closing must animate through measured heights")
                assertEquals(initialHeight, scene.composer().height)
            }
        }
    }

    @Test
    fun theSharedFieldPreservesDraftSelectionAndEntitiesAcrossCancelFailureSaveAndChatChange() {
        var state by mutableStateOf(fixture())
        val savedDraft = RichMessageText("normal draft", listOf(MessageEntity(0, 6, MessageEntityKind.BOLD)))
            .editorValue(TextRange(2, 5))
        var draft by mutableStateOf(savedDraft)
        var cancelCount = 0
        val edits = mutableListOf<Pair<ChatToken, RichMessageText>>()
        val features = ChatFeatureActions(cancelEdit = { token ->
            assertEquals(state.token, token)
            cancelCount++
            state = state.copy(editTarget = null)
        }, editMessage = { token, text ->
            edits += token to text
            state = state.copy(editPending = true)
        })
        scene(580, 1f, false) {
            ConversationPane(state, state.selected!!, draft, { draft = it }, null,
                {}, {}, {}, {}, features, false, {}, {}, {}, {}, false)
        }.use { scene ->
            scene.settledFrame(12)
            val source = state.messages.last()
            state = state.copy(editTarget = source)
            scene.settledFrame(12)
            assertEquals(listOf(source.text), scene.fields())
            scene.keyStroke(KeyEvent.VK_ESCAPE)
            scene.settledFrame(12)
            assertEquals(1, cancelCount)
            assertEquals(savedDraft, draft)
            assertEquals(listOf(savedDraft.text), scene.fields())

            state = state.copy(editTarget = source)
            scene.settledFrame(12)
            scene.keyStroke(KeyEvent.VK_BACK_SPACE)
            scene.settledFrame(12)
            val editedText = source.text.dropLast(1)
            assertEquals(listOf(editedText), scene.fields())
            scene.keyStroke(KeyEvent.VK_ESCAPE)
            scene.settledFrame(12)
            assertEquals(1, cancelCount, "Changed edit needs the official discard confirmation")
            scene.clickText("继续编辑")
            scene.settledFrame(12)
            scene.clickAction("保存修改")
            scene.settledFrame(12)
            assertEquals(editedText, edits.single().second.text)
            assertEquals(savedDraft, draft)
            state = state.copy(editPending = false, editError = ChatError.RATE_LIMITED)
            scene.settledFrame(12)
            assertEquals(listOf(editedText), scene.fields(), "A failed save must retain the edit")
            scene.clickAction("保存修改")
            scene.settledFrame(12)
            assertEquals(2, edits.size)
            state = state.copy(editTarget = null, editPending = false, editError = null)
            scene.settledFrame(12)
            assertEquals(listOf(savedDraft.text), scene.fields())
            assertEquals(savedDraft, draft)

            state = state.copy(editTarget = source.copy(kind = MessageKind.FILE))
            scene.settledFrame(12)
            scene.selectAll()
            scene.settledFrame(1)
            scene.keyStroke(KeyEvent.VK_BACK_SPACE)
            scene.settledFrame(12)
            scene.clickAction("保存修改")
            scene.settledFrame(12)
            assertEquals("", edits.last().second.text, "Media captions can be cleared")
            state = fixture().copy(token = ChatToken(1, 2, 20), selected = ChatItem(20, "Another synthetic chat"))
            draft = TextFieldValue("new chat draft")
            scene.settledFrame(12)
            assertEquals(listOf("new chat draft"), scene.fields(), "An old edit cannot cross selection")
        }
    }

    private fun scene(width: Int, density: Float, dark: Boolean, content: @Composable () -> Unit) =
        ComposePixelScene((width * density).roundToInt(), (700 * density).roundToInt()) {
            val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides desktopChatLayout(width.dp, 700.dp)) {
                MiuixTheme(controller = theme) { content() }
            }
        }

    private fun fixture(): ChatSnapshot {
        val chat = ChatItem(10, "Synthetic official composer")
        return ChatSnapshot(enabled = true, token = ChatToken(1, 1, chat.id), selected = chat, historyEnd = true,
            messages = listOf(ChatMessage(1, chat.id, text = "回复预览 — 中文与 emoji 😀"),
                ChatMessage(2, chat.id, outgoing = true, text = "editable source")))
    }

    private fun ComposePixelScene.action(label: String): Rect = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull(), "Missing action $label")

    private fun ComposePixelScene.composer(): Rect = action("消息输入区域")
    private fun ComposePixelScene.menu(): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.PaneTitle) == "附件菜单"
    }.singleOrNull())

    private fun ComposePixelScene.fields(): List<String> = buildList {
        semanticsBounds { it.getOrNull(SemanticsProperties.EditableText)?.text?.let(::add); false }
    }

    private fun ComposePixelScene.clickAction(label: String) {
        val rect = action(label)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }

    private fun ComposePixelScene.clickText(text: String) {
        val rect = assertNotNull(semanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }.singleOrNull())
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }

    private fun assertRectClose(expected: Rect, actual: Rect, description: String) {
        assertTrue(abs(expected.left - actual.left) <= 1f && abs(expected.top - actual.top) <= 1f &&
            abs(expected.right - actual.right) <= 1f && abs(expected.bottom - actual.bottom) <= 1f,
            "$description: $expected vs $actual")
    }

    private fun assertAttachmentGap(button: Rect, menu: Rect, density: Float) {
        assertTrue(abs(button.top - menu.bottom - 14f * density) <= 2f * density,
            "Reply content and editor height must preserve the official 10dp shadow allowance plus the user's 4dp lift: $button -> $menu")
    }

    private fun ComposePixelFrame.save(name: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "official-composer-frames").apply { mkdirs() }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        check(ImageIO.write(image, "png", File(directory, "$name.png")))
    }
}
