package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatFolder
import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ChatFolderSettingsSessionTest {
    @Test
    fun matchingNativeOwnerRetainsRichDraftNameSelectionQueryAndListState() {
        val session = FolderSettingsSession()
        session.observe(snapshot())
        val editor = session.editor(source, 2)
        editor.draft = source.copy(name = "LocalDraft", includeGroups = true, excludedChatIds = listOf(55))
        editor.name = TextFieldValue("LocalDraft", TextRange(2, 7))
        editor.query = TextFieldValue("filter", TextRange(1, 3))
        session.observe(snapshot().copy(folderSaving = true))
        val restored = session.editor(source, 2)
        assertSame(editor, restored)
        assertEquals(editor.draft, restored.draft)
        assertEquals(TextRange(2, 7), restored.name.selection)
        assertEquals(TextRange(1, 3), restored.query.selection)
        assertSame(editor.listState, restored.listState)
    }

    @Test
    fun anExplicitCancelConsumesOnlyItsOwnOwnerAndANewRevisionStartsFromNativeContent() {
        val session = FolderSettingsSession()
        session.observe(snapshot())
        val previous = session.editor(source, 2)
        previous.draft = source.copy(name = "Previous")
        val current = session.editor(source, 4)
        current.draft = source.copy(name = "Current")
        session.discard(previous.owner)
        assertSame(current, session.editor(source, 4))
        assertEquals("Current", current.draft.name)
        session.discard(current.owner)
        assertEquals(source, session.editor(source, 4).draft)
        assertNotSame(previous, session.editor(source, 8))
    }

    @Test
    fun acceptedSubmissionWhileUnmountedClearsItsOriginalOwnerWithoutConsumingAnotherDraft() {
        val session = FolderSettingsSession()
        session.observe(snapshot())
        val submitted = session.editor(source, 2)
        submitted.name = TextFieldValue("Accepted")
        session.submitted(submitted.owner)
        val otherSource = source.copy(id = 8, name = "Other")
        val other = session.editor(otherSource, 7)
        other.name = TextFieldValue("New local")
        session.observe(snapshot().copy(folderEditorId = null, folderEditor = null, folderRevision = 8))
        assertSame(other, session.editor(otherSource, 7))
        assertEquals("New local", other.name.text)
        assertNotSame(submitted, session.editor(source, 2))
    }

    @Test
    fun nativeFailureRetainsItsDraftForRetryAndGenerationChangeDropsAllAccountContent() {
        val session = FolderSettingsSession()
        session.observe(snapshot())
        val editor = session.editor(source, 2)
        editor.name = TextFieldValue("RetryMe")
        session.submitted(editor.owner)
        session.observe(snapshot().copy(folderError = ChatError.REQUEST_FAILED))
        assertSame(editor, session.editor(source, 2))
        assertEquals("RetryMe", editor.name.text)
        val overview = session.overviewScrollState
        session.observe(snapshot().copy(token = ChatToken(22, 0, null)))
        assertNotSame(editor, session.editor(source, 2))
        assertNotSame(overview, session.overviewScrollState)
        assertEquals(source.name, session.editor(source, 2).name.text)
    }

    @Test
    fun actualEditorSurvivesUnmountWithItsQueryAndScrollThenConsumesItsAcceptedRevision() {
        var current by mutableStateOf(snapshot().copy(knownChats = (1L..30L).map { ChatItem(it, "Alpha $it") }))
        var mounted by mutableStateOf(true)
        val session = FolderSettingsSession()
        val saves = mutableListOf<ChatFolder>()
        val features = ChatFeatureActions(saveFolder = { _, folder ->
            saves += folder
            current = current.copy(folderSaving = true)
        })
        ComposePixelScene(620, 500) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                Box(Modifier.fillMaxSize()) {
                    if (mounted) ChatFolderSettingsContent(current, features, ChatFolderPreferences(), {}, session = session)
                }
            }
        }.use { scene ->
            scene.settledFrame()
            scene.replace(source.name, "LocalDraft")
            val editor = session.editor(source, 2)
            assertEquals("LocalDraft", editor.draft.name)
            repeat(20) {
                if (scene.editableBounds("") != null) return@repeat
                scene.scrollAt(500f, 300f, deltaY = 5f)
                scene.settledFrame(4, 100)
            }
            scene.replace("", "Alpha")
            // Capture the anchor after the focused text-field label has finished
            // reflowing the list item, including the host's font metrics.
            scene.settledFrame()
            assertEquals("Alpha", editor.query.text)
            val index = editor.listState.firstVisibleItemIndex
            val offset = editor.listState.firstVisibleItemScrollOffset
            assertTrue(index > 0, "The actual editor list must scroll before testing its restoration.")
            mounted = false
            scene.settledFrame()
            mounted = true
            scene.settledFrame()
            assertSame(editor, session.editor(source, 2))
            assertEquals(index, editor.listState.firstVisibleItemIndex)
            assertEquals(offset, editor.listState.firstVisibleItemScrollOffset)
            assertNotNull(scene.editableBounds("Alpha"))
            repeat(14) {
                scene.scrollAt(500f, 250f, deltaY = -5f)
                scene.settledFrame(2, 100)
            }
            assertNotNull(scene.editableBounds("LocalDraft"))
            scene.click(scene.text("保存"))
            scene.settledFrame()
            assertEquals(listOf(source.copy(name = "LocalDraft")), saves)
            mounted = false
            scene.settledFrame()
            current = current.copy(folderEditorId = null, folderEditor = null, folderSaving = false, folderRevision = 3)
            mounted = true
            scene.settledFrame()
            assertNotNull(scene.text("创建新文件夹"))
            assertNotSame(editor, session.editor(source, 2), "Only the accepted draft is consumed after returning to settings.")
        }
    }

    @Test
    fun inactivePageAndSharedFoldersCannotSubmitOrMutateTheNativeDraft() {
        for (shared in listOf(false, true)) {
            val folder = source.copy(isShareable = shared)
            val session = FolderSettingsSession()
            val current = snapshot().copy(folderEditor = folder)
            var saves = 0
            ComposePixelScene(620, 500) {
                val theme = remember { ThemeController(ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalSettingsPageActive provides shared) {
                    MiuixTheme(controller = theme) {
                        ChatFolderSettingsContent(current, ChatFeatureActions(saveFolder = { _, _ -> saves++ }),
                            ChatFolderPreferences(), {}, session = session)
                    }
                }
            }.use { scene ->
                scene.settledFrame()
                val editor = session.editor(folder, 2)
                scene.click(scene.text("联系人"))
                scene.click(scene.text("保存"))
                scene.settledFrame()
                assertEquals(folder, editor.draft)
                assertEquals(0, saves, "A hidden page or a native shared folder must never submit.")
            }
        }
    }

    @Test
    fun actualSuffixEditingPreservesCustomIdentityAndIndependentAnimationAcrossAConflict() {
        val rich = source.copy(name = "📁 W", sourceId = 88, editorRevision = 2,
            nameEntities = listOf(MessageEntity(0, 2, MessageEntityKind.CUSTOM_EMOJI, id = 98765)), animateCustomEmoji = true)
        var current by mutableStateOf(snapshot().copy(folderEditor = rich))
        val session = FolderSettingsSession()
        val saves = mutableListOf<ChatFolder>()
        ComposePixelScene(620, 500) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                ChatFolderSettingsContent(current, ChatFeatureActions(saveFolder = { _, value ->
                    saves += value; current = current.copy(folderSaving = true)
                }), ChatFolderPreferences(), {}, session = session)
            }
        }.use { scene ->
            scene.settledFrame()
            scene.click(assertNotNull(scene.editableBounds("📁 W")))
            scene.settledFrame(2, 16)
            scene.keyStroke(KeyEvent.VK_END)
            scene.settledFrame(2, 16)
            scene.keyStroke(KeyEvent.VK_BACK_SPACE)
            scene.settledFrame(2, 16)
            scene.keyStroke(KeyEvent.VK_X, 'X')
            scene.settledFrame()
            val editor = session.editor(rich, 2)
            assertEquals("📁 X", editor.draft.name)
            assertEquals(rich.nameEntities, editor.draft.nameEntities)
            assertTrue(editor.draft.animateCustomEmoji)
            scene.click(scene.text("名称表情动画"))
            scene.settledFrame()
            assertTrue(!editor.draft.animateCustomEmoji)
            scene.click(scene.text("保存"))
            scene.settledFrame()
            assertEquals(editor.draft, saves.single())
            assertEquals(88L, saves.single().sourceId)
            assertEquals(2L, saves.single().editorRevision)
            current = current.copy(folderSaving = false, folderError = ChatError.NOT_READY)
            scene.settledFrame()
            assertSame(editor, session.editor(rich, 2))
            assertNotNull(scene.editableBounds("📁 X"))
            assertEquals(rich.nameEntities, editor.draft.nameEntities)
            assertTrue(!editor.draft.animateCustomEmoji)
            assertNotNull(scene.text("文件夹来源已更改。草稿已保留，请取消后重新打开核对；不会覆盖其他位置的新设置。"))
        }
    }

    @Test
    fun lateAcceptanceConsumesOnlyTheSubmittedSourceEvenAfterTheSameFolderHasANewRevision() {
        val session = FolderSettingsSession()
        session.observe(snapshot())
        val oldSource = source.copy(sourceId = 81, editorRevision = 2)
        val old = session.editor(oldSource, 2)
        session.submitted(old.owner)
        val newSource = source.copy(sourceId = 82, editorRevision = 4)
        val newer = session.editor(newSource, 4)
        newer.draft = newSource.copy(name = "New local")
        session.observe(snapshot().copy(folderEditorId = null, folderEditor = null, folderRevision = 5))
        assertSame(newer, session.editor(newSource, 4))
        assertEquals("New local", newer.draft.name)
        assertNotSame(old, session.editor(oldSource, 2))
    }

    @Test
    fun actualCaretInsertionAndDeletionKeepIdenticalPlainAndCustomGlyphsSeparate() {
        val rich = source.copy(name = "⭐ W", sourceId = 88, editorRevision = 2,
            nameEntities = listOf(MessageEntity(0, 1, MessageEntityKind.CUSTOM_EMOJI, id = 98765)), animateCustomEmoji = true)
        val session = FolderSettingsSession()
        ComposePixelScene(620, 500) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                ChatFolderSettingsContent(snapshot().copy(folderEditor = rich), ChatFeatureActions(),
                    ChatFolderPreferences(), {}, session = session)
            }
        }.use { scene ->
            scene.settledFrame()
            scene.click(assertNotNull(scene.editableBounds("⭐ W")))
            scene.settledFrame(2, 16)
            scene.keyStroke(KeyEvent.VK_HOME)
            scene.settledFrame(2, 16)
            scene.keyStroke(KeyEvent.VK_UNDEFINED, '⭐')
            scene.settledFrame()
            val editor = session.editor(rich, 2)
            assertEquals("⭐⭐ W", editor.draft.name)
            assertEquals(listOf(rich.nameEntities.single().copy(offset = 1)), editor.draft.nameEntities,
                "The new ordinary glyph cannot inherit the original custom identity.")
            scene.keyStroke(KeyEvent.VK_HOME)
            scene.settledFrame(2, 16)
            scene.keyStroke(KeyEvent.VK_DELETE)
            scene.settledFrame()
            assertEquals(rich.name, editor.draft.name)
            assertEquals(rich.nameEntities, editor.draft.nameEntities,
                "Deleting the new plain glyph must retain the untouched original custom glyph.")
        }
    }

    @Test
    fun lowAndTallEditorsKeepTheirRichDraftAndVisibleActionsAfterAConflictingNativeResult() {
        for ((width, height) in listOf(620 to 500, 880 to 900)) for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            val rich = source.copy(name = "📁 W", sourceId = 88, editorRevision = 2,
                nameEntities = listOf(MessageEntity(0, 2, MessageEntityKind.CUSTOM_EMOJI, id = 98765)), animateCustomEmoji = true)
            var current by mutableStateOf(snapshot().copy(folderEditor = rich,
                knownChats = (1L..40L).map { ChatItem(it, "Synthetic peer $it") }))
            var mounted by mutableStateOf(true)
            val session = FolderSettingsSession()
            val saves = mutableListOf<ChatFolder>()
            ComposePixelScene(width, height) {
                val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = theme) {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).padding(16.dp)) {
                            if (mounted) ChatFolderSettingsContent(current, ChatFeatureActions(saveFolder = { _, value ->
                                saves += value; current = current.copy(folderSaving = true)
                            }), ChatFolderPreferences(), {}, session = session)
                        }
                    }
                }
            }.use { scene ->
                val name = "editor-${width}x$height-${if (dark) "dark" else "light"}-$density"
                scene.settledFrame().save("$name-initial")
                scene.click(assertNotNull(scene.editableBounds("📁 W")))
                scene.settledFrame(2, 16)
                scene.keyStroke(KeyEvent.VK_END)
                scene.settledFrame(2, 16)
                scene.keyStroke(KeyEvent.VK_BACK_SPACE)
                scene.settledFrame(2, 16)
                scene.keyStroke(KeyEvent.VK_X, 'X')
                scene.settledFrame()
                val editor = session.editor(rich, 2)
                assertEquals("📁 X", editor.draft.name)
                assertEquals(rich.nameEntities, editor.draft.nameEntities)
                val selection = editor.name.selection
                scene.click(scene.text("保存"))
                scene.settledFrame()
                assertEquals(listOf(editor.draft), saves)
                SwingUtilities.invokeAndWait { current = current.copy(folderSaving = false, folderError = ChatError.NOT_READY) }
                scene.settledFrame()
                SwingUtilities.invokeAndWait { mounted = false }
                scene.settledFrame()
                SwingUtilities.invokeAndWait { mounted = true }
                val restored = scene.settledFrame()
                assertSame(editor, session.editor(rich, 2))
                assertEquals(selection, editor.name.selection)
                assertEquals(rich.nameEntities, editor.draft.nameEntities)
                assertTrue(editor.draft.animateCustomEmoji)
                assertNotNull(scene.editableBounds("📁 X"))
                for (label in listOf("还原", "取消", "保存")) {
                    val bounds = scene.text(label)
                    assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= height,
                        "$name must keep the actual $label action inside the physical viewport: $bounds")
                }
                val error = scene.text("文件夹来源已更改。草稿已保留，请取消后重新打开核对；不会覆盖其他位置的新设置。")
                assertTrue(error.top >= 0 && error.bottom <= height)
                restored.save("$name-conflict")
                assertEquals(1, saves.size, "Remounting the failed editor cannot resubmit its draft.")
            }
        }
    }

    private fun ComposePixelFrame.save(name: String) {
        val output = File("build/settings-folders-editor-validation/frames/$name.png")
        output.parentFile.mkdirs()
        val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) bitmap.setRGB(x, y, this[x, y])
        ImageIO.write(bitmap, "png", output)
    }

    private fun ComposePixelScene.editableBounds(value: String): Rect? = semanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText)?.text == value
    }.firstOrNull { it.width > 5 && it.height > 15 }

    private fun ComposePixelScene.replace(previous: String, replacement: String) {
        click(assertNotNull(editableBounds(previous), "Expected visible editor '$previous'"))
        settledFrame(2, 16)
        selectAll()
        settledFrame(2, 16)
        keyStroke(KeyEvent.VK_BACK_SPACE)
        settledFrame(2, 16)
        for (character in replacement) {
            keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character)
            settledFrame(1, 16)
        }
        assertNotNull(editableBounds(replacement))
    }

    private fun ComposePixelScene.text(label: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.singleOrNull(), label)

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    companion object {
        private val source = ChatFolder(id = 4, name = "Initial", includeContacts = true)
        private fun snapshot() = ChatSnapshot(enabled = true, token = ChatToken(17, 0, null),
            folders = listOf(ChatFolderInfo(4, "Initial")), folderEditorId = 4, folderEditor = source, folderRevision = 2)
    }
}
