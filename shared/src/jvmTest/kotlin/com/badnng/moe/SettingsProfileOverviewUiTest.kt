package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.media.LocalNativeDialogOwner
import com.badnng.moe.media.LocalNativeFilePicker
import com.badnng.moe.settings.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real account-page pointer/keyboard input with a synthetic account, clipboard and file-dialog boundary. */
@Suppress("DEPRECATION")
class SettingsProfileOverviewUiTest {
    @Test fun copiesOnlyTheNativeNumericIdAndReportsClipboardFailure() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.description("复制账号 ID")); scene.settledFrame(2, 16)
            assertEquals(listOf("1145141919"), f.clipboard.writes)
            assertNotNull(scene.text("已复制 ID"))
            f.clipboard.fail = true
            scene.click(scene.description("复制账号 ID")); scene.settledFrame(2, 16)
            assertNotNull(scene.text("复制失败，请重试"))
            assertEquals(1, f.clipboard.writes.size)
            f.active = false; scene.settledFrame()
            scene.click(scene.description("复制账号 ID")); scene.settledFrame(2, 16)
            assertEquals(1, f.clipboard.writes.size)
        }
    }

    @Test fun theCardCornerOwnsNameUsernameAndBioEditingButNeverThePhone() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.editors().isEmpty())
            scene.click(scene.text("简介")); scene.settledFrame()
            assertTrue(scene.editors().isEmpty(), "The summary is not another edit entrance.")
            val readCard = scene.profileCard()
            scene.click(scene.description("更改个人资料")); scene.settledFrame()
            scene.assertInlineEditor(readCard)
            assertEquals(4, scene.editors().size)
            scene.replace(scene.editor("简介编辑框"), "ChangedBio")
            assertTrue(f.saves.isEmpty())
            assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.EditableText)?.text == "+8613800000000" }.isEmpty())
            scene.click(scene.action("保存")); scene.settledFrame()
            assertEquals(listOf(SettingsProfileEdit(ProfileField.BIO, "ChangedBio")), f.saves)
            f.accept()
            scene.settledFrame()
            assertTrue(scene.editors().isEmpty())
            scene.click(scene.description("更改个人资料")); scene.settledFrame()
            scene.click(scene.action("取消")); scene.settledFrame()
            assertTrue(scene.editors().isEmpty())
            assertEquals(1, f.saves.size)
        }
    }

    @Test fun oneSaveSerializesChangedNativeFieldsAndPartialFailureRetainsTheRemainingDraft() {
        val f = Fixture(height = 1500)
        f.scene().use { scene ->
            scene.settledFrame(); scene.click(scene.description("更改个人资料")); scene.settledFrame()
            assertEquals(4, scene.editors().size)
            scene.replace(scene.editor("名字编辑框"), "ChangedName")
            scene.replace(scene.editor("姓氏编辑框"), "ChangedSurname")
            scene.replace(scene.editor("简介编辑框"), "ChangedBio")
            scene.replace(scene.editor("用户名编辑框"), "changed_username")
            scene.click(scene.action("保存")); scene.settledFrame()
            assertEquals(listOf(ProfileField.NAME), f.saves.map { it.field }, "Only the first native request starts.")
            assertEquals("ChangedSurname", f.saves.single().secondary)
            f.accept(); scene.settledFrame()
            assertEquals(listOf(ProfileField.NAME, ProfileField.BIO), f.saves.map { it.field })
            f.reject(); scene.settledFrame()
            assertEquals(4, scene.editors().size)
            for (value in listOf("ChangedName", "ChangedSurname", "ChangedBio", "changed_username"))
                assertTrue(scene.hasDraft(value), "A partial failure must preserve $value")
            assertEquals(2, f.saves.size, "Failure stops the batch before username.")
            scene.click(scene.action("保存")); scene.settledFrame()
            assertEquals(listOf(ProfileField.NAME, ProfileField.BIO, ProfileField.BIO), f.saves.map { it.field },
                "Retry must not repeat the already accepted name.")
            f.accept(); scene.settledFrame()
            assertEquals(ProfileField.USERNAME, f.saves.last().field)
            assertEquals(4, f.saves.size)
            f.accept(); scene.settledFrame()
            assertTrue(scene.editors().isEmpty())
        }
    }

    @Test fun anUnrelatedNewerNativeCompletionCannotAcceptTheBoundProfileSubmission() {
        val f = Fixture(height = 1500)
        f.scene().use { scene ->
            scene.settledFrame(); scene.click(scene.description("更改个人资料")); scene.settledFrame()
            scene.replace(scene.editor("名字编辑框"), "BoundName")
            scene.replace(scene.editor("简介编辑框"), "UnsentBio")
            scene.click(scene.action("保存")); scene.settledFrame()
            assertEquals(1L, f.snapshot.startedSaveRevision[SettingsSection.PROFILE])
            f.snapshot = f.snapshot.copy(saving = emptySet(),
                completedSaveRevision = mapOf(SettingsSection.PROFILE to 9L))
            scene.settledFrame()
            assertEquals(1, f.saves.size, "A newer unrelated receipt cannot advance the queued bio.")
            assertEquals(4, scene.editors().size)
            assertTrue(scene.hasDraft("BoundName")); assertTrue(scene.hasDraft("UnsentBio"))
        }
    }

    @Test fun cancelWithFourChangedFieldsDoesNotWriteAndReopenStartsFromNativeValues() {
        val f = Fixture(height = 1500)
        f.scene().use { scene ->
            scene.settledFrame(); scene.click(scene.description("更改个人资料")); scene.settledFrame()
            for ((field, value) in listOf("名字编辑框" to "Other", "姓氏编辑框" to "Surname",
                "简介编辑框" to "OtherBio", "用户名编辑框" to "other_username")) scene.replace(scene.editor(field), value)
            scene.click(scene.action("取消")); scene.settledFrame()
            assertTrue(scene.editors().isEmpty()); assertTrue(f.saves.isEmpty())
            scene.click(scene.description("更改个人资料")); scene.settledFrame()
            assertEquals(4, scene.editors().size)
            assertTrue(scene.hasDraft("nngGram")); assertTrue(scene.hasDraft("username"))
            assertTrue(f.saves.isEmpty())
        }
    }

    @Test fun cameraDirectlyOpensOneCombinedNativePickerAndCancelNeverUploads() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.description("选择图片或视频头像"))
            scene.await { f.pickers.size == 1 }
            val (extensions, owner) = f.pickers.single()
            assertEquals(123L, owner)
            assertTrue(extensions!!.containsAll(setOf("jpg", "png", "mp4", "mov")))
            assertTrue(f.saves.isEmpty())
            assertEquals(0, f.uploads)
            scene.assertNoAvatarMenu()
            assertNotNull(scene.description("查看当前头像"))
            assertNotNull(scene.action("创建表情头像"))
            assertNotNull(scene.action("头像历史"))
            scene.click(scene.description("选择图片或视频头像"))
            scene.await { f.pickers.size == 2 }
            f.active = false; scene.settledFrame()
            scene.click(scene.description("选择图片或视频头像")); scene.settledFrame()
            assertEquals(2, f.pickers.size)
            assertEquals(0, f.uploads)
        }
    }

    @Test fun referenceCardsReflowAndRenderInBothThemesAndDensities() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            for (width in listOf(840, 350)) {
                val f = Fixture(width, 1500, density, mode)
                val longBio = "这是一段完整显示的合成简介，用于核对窄窗中的多行内容、左右对齐以及整行垂直居中。"
                f.snapshot = f.snapshot.copy(profile = f.snapshot.profile!!.copy(bio = longBio))
                f.scene().use { scene ->
                    scene.settledFrame()
                    val details = scene.text("个人资料")
                    val avatar = scene.text("头像")
                    if (width == 840) {
                        assertTrue(kotlin.math.abs(details.top - avatar.top) < density)
                        assertTrue(avatar.left > details.right)
                    } else assertTrue(avatar.top > details.bottom)
                    val camera = scene.description("选择图片或视频头像")
                    assertTrue(kotlin.math.abs(camera.width - 44f * density) <= 2f)
                    assertTrue(camera.left >= 0 && camera.right <= width * density)
                    val pencil = scene.description("更改个人资料")
                    assertTrue(pencil.left > details.left)
                    for ((value, label) in listOf("+8613800000000" to "手机号码", longBio to "简介", "@username" to "用户名")) {
                        val valueBounds = scene.text(value)
                        val labelBounds = scene.text(label)
                        assertTrue(valueBounds.left > labelBounds.right, "$label value stays right of its label")
                        assertTrue(kotlin.math.abs(valueBounds.center.y - labelBounds.center.y) <= density, "$label vertically centered")
                    }
                    assertTrue(scene.text("手机号码").top < scene.text("简介").top)
                    assertTrue(scene.text("简介").top < scene.text("用户名").top)
                    scene.assertNoAvatarMenu()
                    val oldBioBounds = scene.text(longBio)
                    val readFrame = scene.settledFrame()
                    assertTrue(readFrame.bluePixels(oldBioBounds) > 5, "The regression region must contain actual blue summary glyphs.")
                    scene.writeProfileFrame("read", mode, width, density, readFrame)
                    val readCard = scene.profileCard()
                    scene.clickWithoutAdvancing(pencil)
                    scene.settledFrame(1, 1)
                    val opening = scene.settledFrame(5, 16)
                    val openingCard = scene.profileCard()
                    assertEquals(0, opening.bluePixels(oldBioBounds),
                        "At 80ms the old blue bio must finish fading before new input text draws.")
                    scene.writeProfileFrame("opening-middle", mode, width, density, opening)
                    scene.settledFrame()
                    scene.assertInlineEditor(readCard)
                    assertEquals(4, scene.editors().size)
                    val editCard = scene.profileCard()
                    assertTrue(openingCard.height > readCard.height + density, "Opening must measure a real intermediate expansion")
                    assertTrue(openingCard.height < editCard.height - density, "Opening cannot jump to full height")
                    scene.writeProfileFrame("edit", mode, width, density)
                    scene.clickWithoutAdvancing(scene.action("取消"))
                    scene.settledFrame(1, 1)
                    val closing = scene.settledFrame(5, 16)
                    val closingCard = scene.profileCard()
                    scene.writeProfileFrame("closing-middle", mode, width, density, closing)
                    assertTrue(closingCard.height > readCard.height + density && closingCard.height < editCard.height - density,
                        "Closing retains a real intermediate card height")
                    assertTrue(scene.editors().size <= 4, "A transition never mounts a second editable form")
                    scene.settledFrame()
                    assertEquals(readCard.height, scene.profileCard().height)
                    assertTrue(scene.editors().isEmpty())
                }
            }
        }
    }

    private class Fixture(val width: Int = 840, val height: Int = 1050, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light,
    ) {
        var active by mutableStateOf(true)
        val clipboard = MemoryClipboard()
        val saves = mutableListOf<SettingsProfileEdit>()
        val pickers = mutableListOf<Pair<Set<String>?, Long?>>()
        var uploads = 0
        var snapshot by mutableStateOf(SettingsSnapshot(token = SettingsToken(7, 1), enabled = true,
            loaded = setOf(SettingsSection.PROFILE), profile = SettingsProfile(1145141919L, "nngGram", "用户", "username",
                "", "8613800000000", bioLengthMax = 70),
            profileExtras = SettingsProfileExtrasSnapshot(SettingsProfileExtrasToken(7, 3, 1145141919L),
                SettingsProfileExtrasData(1145141919L, null, null, SettingsCalendarDate(30, 10, 2026)))))
        fun accept() {
            val edit = saves.last()
            val profile = snapshot.profile!!
            snapshot = snapshot.copy(saving = emptySet(), errors = emptyMap(),
                completedSaveRevision = mapOf(SettingsSection.PROFILE to snapshot.startedSaveRevision.getValue(SettingsSection.PROFILE)),
                profile = when (edit.field) {
                    ProfileField.NAME -> profile.copy(firstName = edit.value, lastName = edit.secondary)
                    ProfileField.BIO -> profile.copy(bio = edit.value)
                    ProfileField.USERNAME -> profile.copy(username = edit.value)
                })
        }
        fun reject() {
            snapshot = snapshot.copy(saving = emptySet(), errors = mapOf(SettingsSection.PROFILE to SettingsError.REQUEST_FAILED),
                failedSaveRevision = mapOf(SettingsSection.PROFILE to snapshot.startedSaveRevision.getValue(SettingsSection.PROFILE)))
        }
        private val actions = SettingsActions(saveProfile = { _, edit ->
            saves += edit
            snapshot = snapshot.copy(saving = setOf(SettingsSection.PROFILE),
                startedSaveRevision = mapOf(SettingsSection.PROFILE to ((snapshot.startedSaveRevision[SettingsSection.PROFILE] ?: 0L) + 1L)))
        },
            profileExtras = SettingsProfileExtrasActions(uploadPhoto = { _, _, _ -> uploads++ }))
        fun scene() = ComposePixelScene((width * density).roundToInt(), (height * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            val holder = rememberSaveableStateHolder()
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active,
                LocalClipboardManager provides clipboard, LocalNativeDialogOwner provides 123L,
                LocalNativeFilePicker provides { _, extensions, owner -> pickers += extensions to owner; null }) {
                MiuixTheme(controller = theme) {
                    holder.SaveableStateProvider("profile") {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            SettingsProfilePage(snapshot, actions, ChatSnapshot(), ChatFeatureActions())
                        }
                    }
                }
            }
        }
    }

    private class MemoryClipboard : ClipboardManager {
        val writes = mutableListOf<String>()
        var fail = false
        override fun setText(annotatedString: AnnotatedString) {
            if (fail) error("synthetic clipboard failure")
            writes += annotatedString.text
        }
        override fun getText(): AnnotatedString? = writes.lastOrNull()?.let(::AnnotatedString)
    }

    private fun ComposePixelScene.description(label: String) = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() && it.getOrNull(SemanticsActions.OnClick) != null
    }.firstOrNull { it.width > 0 && it.height > 0 }, label)
    private fun ComposePixelScene.text(label: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.firstOrNull { it.width > 0 && it.height > 0 }, label)
    private fun ComposePixelScene.action(label: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } &&
            it.getOrNull(SemanticsActions.OnClick) != null && it.getOrNull(SemanticsProperties.EditableText) == null
    }.firstOrNull { it.width > 0 && it.height > 0 }, label)
    private fun ComposePixelScene.hasDraft(value: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText)?.text == value
    }.isNotEmpty()
    private fun ComposePixelScene.editors() = semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }
    private fun ComposePixelScene.editor(description: String) = assertNotNull(unmergedSemanticsBounds {
        description in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.firstOrNull { it.width > 0 && it.height > 0 }, description)
    private fun ComposePixelScene.profileCard() = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.TestTag) == "SettingsProfileDetailsCard"
    }.singleOrNull(), "Exactly one profile details card")
    private fun ComposePixelScene.assertInlineEditor(readCard: Rect) {
        val card = profileCard()
        assertEquals(readCard.left, card.left)
        assertEquals(readCard.top, card.top)
        assertEquals(readCard.right, card.right)
        assertTrue(editor("简介编辑框").top < editor("用户名编辑框").top, "Bio stays before username while editing")
        assertTrue(semanticsBounds { it.getOrNull(SemanticsProperties.Text).orEmpty().any { text ->
            text.text == "更改个人资料"
        } }.isEmpty(), "Editing must not add a separate titled group")
        for (bounds in editors() + action("取消") + action("保存")) {
            assertTrue(bounds.left >= card.left && bounds.right <= card.right && bounds.top >= card.top && bounds.bottom <= card.bottom,
                "Editor and actions must belong to the original profile card: $bounds outside $card")
        }
        assertTrue(semanticsBounds { it.getOrNull(SemanticsProperties.EditableText)?.text == "+8613800000000" }.isEmpty())
    }
    private fun ComposePixelScene.assertNoAvatarMenu() {
        assertTrue(semanticsBounds {
            it.getOrNull(SemanticsProperties.PaneTitle) == "头像操作" ||
                it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label ->
                    label in setOf("头像操作", "更多头像操作", "头像菜单")
                }
        }.isEmpty())
        for (label in listOf("设置新头像", "选择图片头像", "选择视频头像", "删除当前头像")) {
            assertTrue(semanticsBounds { it.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }.isEmpty(), label)
        }
    }
    private fun ComposePixelFrame.bluePixels(bounds: Rect): Int {
        var count = 0
        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(height))
            for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(width)) {
                val pixel = this[x, y]
                val red = (pixel ushr 16) and 255
                val green = (pixel ushr 8) and 255
                val blue = pixel and 255
                if (blue > red + 20 && blue > green + 10) count++
            }
        return count
    }
    private fun ComposePixelScene.writeProfileFrame(state: String, mode: ColorSchemeMode, width: Int, density: Float,
        frame: ComposePixelFrame = settledFrame()) {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val target = File(repository, "docs/screenshots/official-settings/profile-full-form/" +
            "profile-$state-${mode.name.lowercase()}-$width-$density.png")
        target.parentFile.mkdirs(); ImageIO.write(image, "png", target)
    }
    private fun ComposePixelScene.clickWithoutAdvancing(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y)
    }
    private fun ComposePixelScene.click(rect: Rect) {
        clickWithoutAdvancing(rect)
        settledFrame(4, 16)
    }
    private fun ComposePixelScene.replace(rect: Rect, value: String) {
        click(rect); selectAll(); settledFrame(2, 16)
        keyStroke(KeyEvent.VK_BACK_SPACE); settledFrame(2, 16)
        for (char in value) { keyStroke(KeyEvent.getExtendedKeyCodeForChar(char.code), char); settledFrame(1, 16) }
        settledFrame()
    }
    private fun ComposePixelScene.await(predicate: () -> Boolean) {
        repeat(100) { if (predicate()) return; settledFrame(2, 16); Thread.sleep(5) }
        assertTrue(predicate())
    }
}
