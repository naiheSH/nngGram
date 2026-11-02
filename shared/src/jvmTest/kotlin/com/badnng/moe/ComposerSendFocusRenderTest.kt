package com.badnng.moe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatToken
import java.awt.event.KeyEvent
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ComposerSendFocusRenderTest {
    @Test fun mouseAndEnterRestoreTheActualEditorAfterSubmissionClearsItsTextAndFocus() {
        for (mouse in listOf(true, false)) {
            var value by mutableStateOf(TextFieldValue())
            var pending by mutableStateOf(false)
            val submitted = mutableListOf<String>()
            scene({ value }, { pending }, { value = it }, {
                submitted += value.text
                value = TextFieldValue()
                pending = true
            }, clearFocusDuringSubmit = true).use { scene ->
                scene.settledFrame()
                scene.click(scene.editor())
                scene.typeText("first")
                assertEquals("first", value.text, "The real editable node must receive the draft before submission.")
                if (mouse) scene.click(scene.action("发送消息")) else scene.keyStroke(KeyEvent.VK_ENTER)
                scene.settledFrame(1, 16)
                assertEquals(listOf("first"), submitted)
                assertEquals("", value.text)
                assertTrue(scene.editorFocused(),
                    "Focus must return after the submission's field reset, not only before it.")
                scene.typeText("next")
                assertEquals("next", value.text, "The next draft must accept real typing during the native pending request.")
                assertTrue(scene.editorFocused())
                pending = false
                scene.settledFrame(4, 16)
                assertEquals("next", value.text)
                assertTrue(scene.editorFocused())
            }
        }
    }

    @Test fun lateResultsDoNotStealNewFocusOrConsumeAnIdenticalRetypedDraftOnAnInactivePage() {
        for (accepted in listOf(false, true)) for (leavePage in listOf(false, true)) {
            var value by mutableStateOf(TextFieldValue("first", TextRange(5)))
            var other by mutableStateOf(TextFieldValue())
            var pending by mutableStateOf(false)
            var interactive by mutableStateOf(true)
            val token = ChatToken(1, 1, 10)
            val revisions = ComposerDraftRevisions()
            val submitted = value.richText()
            scene({ value }, { pending }, { next ->
                revisions.changed(10, value, next)
                value = next
            }, {
                revisions.submitted(token, value.richText(), 0)
                pending = true
            }, interactive = { interactive }, otherValue = { other }, onOtherValue = { other = it }).use { scene ->
                scene.settledFrame()
                scene.click(scene.action("发送消息"))
                scene.settledFrame(1, 16)
                assertTrue(scene.editorFocused())
                scene.selectAll()
                scene.settledFrame(1, 16)
                assertEquals(TextRange(0, value.text.length), value.selection,
                    "The synthetic select-all event must reach the actual editor before Backspace.")
                scene.keyStroke(KeyEvent.VK_BACK_SPACE)
                scene.settledFrame(1, 16)
                assertEquals("", value.text, "Backspace must finish deleting the selected draft before retyping.")
                scene.typeText("first")
                assertEquals("first", value.text)
                assertTrue(revisions.revision(10) >= 2, "Deleting and retyping must acquire a new content revision.")
                scene.click(scene.otherEditor())
                scene.typeText("search")
                assertFalse(scene.editorFocused())
                assertTrue(scene.otherEditorFocused())
                if (leavePage) interactive = false
                scene.settledFrame(4, 16)
                if (accepted && revisions.consumeAccepted(10, 1, submitted, value)) value = TextFieldValue()
                pending = false
                scene.settledFrame(8, 16)
                assertEquals("first", value.text, "A late acceptance owns the original revision only.")
                assertFalse(scene.editorFocused(), "A late native result cannot undo the user's later focus transfer.")
                assertTrue(scene.otherEditorFocused())
                scene.typeText("x")
                assertEquals("searchx", other.text)
                interactive = true
                scene.settledFrame(4, 16)
                assertTrue(scene.otherEditorFocused(), "Returning to the page cannot replay an old submit focus request.")
            }
        }
    }

    @Test fun mouseAndKeyboardSendKeepTheRealEditorFocusedAndTheNextDraftWritableDuringPending() {
        for (mouse in listOf(true, false)) {
            var value by mutableStateOf(TextFieldValue("first", TextRange(5)))
            var pending by mutableStateOf(false)
            var sends = 0
            var edits = 0
            scene(value = { value }, pending = { pending }, onValue = { value = it }, onSend = { sends++; pending = true },
                onEditLast = { edits++ }).use { scene ->
                scene.settledFrame()
                if (mouse) scene.click(scene.action("发送消息")) else {
                    scene.click(scene.editor())
                    scene.keyStroke(KeyEvent.VK_ENTER)
                }
                scene.settledFrame()
                assertEquals(1, sends)
                assertTrue(scene.editorFocused(), "Sending must leave the actual editable node focused.")
                scene.keyStroke(KeyEvent.VK_ENTER)
                scene.settledFrame()
                assertEquals(1, sends, "Enter during pending must never queue another send.")
                scene.selectAll()
                scene.settledFrame(1)
                scene.keyStroke(KeyEvent.VK_N, character = 'n')
                scene.settledFrame()
                assertEquals("n", value.text, "The pending request must allow writing the next draft.")
                scene.selectAll()
                scene.settledFrame(1)
                scene.keyStroke(KeyEvent.VK_BACK_SPACE)
                scene.settledFrame(1)
                scene.keyStroke(KeyEvent.VK_UP)
                scene.settledFrame()
                assertEquals(0, edits, "Pending input must not open the previous-message editor.")
                value = TextFieldValue("next draft", TextRange(10))
                pending = false
                scene.settledFrame()
                assertTrue(scene.editorFocused())
                scene.keyStroke(KeyEvent.VK_X, character = 'x')
                scene.settledFrame()
                assertEquals("next draftx", value.text)
                assertEquals(1, sends)
            }
        }
    }

    @Test fun pendingTextRemainsDraggableAndFailureDoesNotResetItsSelectionOrContent() {
        var value by mutableStateOf(TextFieldValue("select this pending draft"))
        var pending by mutableStateOf(false)
        scene({ value }, { pending }, { value = it }, { pending = true }).use { scene ->
            scene.settledFrame()
            scene.click(scene.action("发送消息"))
            scene.settledFrame()
            val field = scene.editor()
            scene.pressAt(field.left + 8f, field.center.y)
            scene.dragTo(field.left + 135f, field.center.y)
            scene.releaseAt(field.left + 135f, field.center.y)
            scene.settledFrame()
            assertFalse(value.selection.collapsed, "Pending sends must retain real mouse selection.")
            val selected = value.selection
            pending = false // Native rejection: no accepted draft was consumed.
            scene.settledFrame()
            assertEquals("select this pending draft", value.text)
            assertEquals(selected, value.selection)
            assertTrue(scene.editorFocused())
        }
    }

    @Test fun anImeCompositionIsNotSubmittedByTheSendButton() {
        var value by mutableStateOf(TextFieldValue("正在输入", composition = TextRange(0, 4)))
        var sends = 0
        scene({ value }, { false }, { value = it }, { sends++ }).use { scene ->
            scene.settledFrame()
            scene.click(scene.action("发送消息"))
            scene.settledFrame()
            assertEquals(0, sends)
            assertEquals("正在输入", value.text)
        }
    }

    private fun scene(value: () -> TextFieldValue, pending: () -> Boolean, onValue: (TextFieldValue) -> Unit,
        onSend: () -> Unit, onEditLast: () -> Unit = {}, clearFocusDuringSubmit: Boolean = false,
        interactive: () -> Boolean = { true }, otherValue: (() -> TextFieldValue)? = null,
        onOtherValue: (TextFieldValue) -> Unit = {}) = ComposePixelScene(640, 220) {
        val theme = remember { ThemeController(ColorSchemeMode.Light) }
        val focus = remember { FocusRequester() }
        MiuixTheme(controller = theme) {
            val window = LocalWindowInfo.current
            val focusManager = LocalFocusManager.current
            val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused = true } }
            CompositionLocalProvider(LocalWindowInfo provides focused) {
                Column {
                    ConversationComposer(value(), onValue, true, pending(), true, true, true, true,
                        focus, {
                            onSend()
                            // Native clearFieldText and host input reconciliation happen before
                            // HistoryWidget::setInnerFocus, within the same explicit send action.
                            if (clearFocusDuringSubmit) focusManager.clearFocus(force = true)
                        }, null, onEditLast, {}, {}, {}, {}, interactive = interactive())
                    otherValue?.let { readOther ->
                        BasicTextField(readOther(), onOtherValue, Modifier.padding(12.dp).width(360.dp).height(40.dp)
                            .semantics { contentDescription = OtherEditorDescription })
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.action(label: String): Rect = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull())
    private fun ComposePixelScene.editor(): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText) != null &&
            OtherEditorDescription !in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull())
    private fun ComposePixelScene.editorFocused(): Boolean = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText) != null && it.getOrNull(SemanticsProperties.Focused) == true &&
            OtherEditorDescription !in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.isNotEmpty()
    private fun ComposePixelScene.otherEditor(): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText) != null &&
            OtherEditorDescription in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull())
    private fun ComposePixelScene.otherEditorFocused(): Boolean = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText) != null && it.getOrNull(SemanticsProperties.Focused) == true &&
            OtherEditorDescription in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.isNotEmpty()
    private fun ComposePixelScene.typeText(text: String) {
        text.forEach { character ->
            keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character = character)
            settledFrame(1, 16)
        }
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }

    private companion object { const val OtherEditorDescription = "Synthetic other input" }
}
