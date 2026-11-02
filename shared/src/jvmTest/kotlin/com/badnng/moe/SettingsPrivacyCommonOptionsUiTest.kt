package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SettingsPrivacyCommonOptionsUiTest {
    @Test fun batchConfirmationFreezesDevicesAndCannotSubmitAfterRemoteIdentityChanges() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.reveal("终止所有其他会话", f)
            scene.click(scene.text("终止所有其他会话"))
            scene.settledFrame()
            assertTrue(f.batch.isEmpty())
            scene.reveal("终止其他会话", f)
            assertTrue(scene.texts("其他设备").any { it.height > 0 })
            f.onUi { f.security = f.security.copy(sessions = f.security.sessions + device(-88, "新设备")) }
            scene.settledFrame()
            scene.reveal("终止其他会话", f)
            scene.click(scene.text("终止其他会话"))
            scene.settledFrame()
            assertTrue(f.batch.isEmpty(), "An old frozen confirmation cannot include a newly connected device.")
            f.onUi { f.active = false }
            scene.settledFrame()
            assertTrue(f.batch.isEmpty())
        }
    }

    @Test fun actualBatchActionRetainsCurrentDeviceAndDisablesDuplicateSubmission() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.reveal("终止所有其他会话", f)
            scene.click(scene.text("终止所有其他会话"))
            scene.settledFrame()
            scene.reveal("终止其他会话", f)
            scene.click(scene.text("终止其他会话"))
            scene.settledFrame()
            assertEquals(listOf(1L, -77L), f.batch.single().map { it.id })
            assertEquals(1, f.batch.single().count { it.isCurrent })
            scene.reveal("终止所有其他会话", f)
            scene.click(scene.text("终止所有其他会话"))
            scene.settledFrame()
            assertEquals(1, f.batch.size)
        }
    }

    @Test fun actualSessionTtlPreserves366AndOnlyExplicitSaveWritesTheChosenPreset() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.reveal("若未活动时间超过", f)
            scene.click(scene.text("若未活动时间超过"))
            scene.settledFrame()
            scene.reveal("366 天", f)
            assertTrue(f.ttlWrites.isEmpty())
            scene.reveal("7 天", f)
            scene.click(scene.text("7 天"))
            scene.settledFrame()
            assertTrue(f.ttlWrites.isEmpty())
            scene.reveal("保存", f)
            scene.click(scene.text("保存"))
            scene.settledFrame()
            assertEquals(listOf(366 to 7), f.ttlWrites)
            scene.click(scene.text("保存"))
            scene.settledFrame()
            assertEquals(1, f.ttlWrites.size)
        }
    }

    @Test fun lowHeightScaledDefaultTtlCustomInputIsReachableAndNeedsTheActualEnableConfirmation() {
        val f = Fixture(width = 390, height = 360, density = 1.25f, section = SettingsSecuritySection.AUTO_DELETE)
        f.scene().use { scene ->
            scene.settledFrame()
            scene.reveal("自定义时间", f)
            scene.click(scene.text("自定义时间"))
            scene.settledFrame()
            scene.revealEditor(f)
            scene.replace(scene.editors().first { it.width > 0 && it.height > 0 }, "366")
            assertTrue(f.autoWrites.isEmpty())
            scene.reveal("保存", f)
            scene.click(scene.text("保存"))
            scene.settledFrame()
            assertTrue(f.autoWrites.isEmpty(), "Out-of-range days cannot trigger a native write.")
            scene.revealEditor(f)
            scene.replace(scene.editors().first { it.width > 0 && it.height > 0 }, "17")
            scene.reveal("保存", f)
            scene.click(scene.text("保存"))
            scene.settledFrame()
            assertTrue(f.autoWrites.isEmpty())
            scene.reveal("开启", f)
            val button = scene.text("开启")
            assertTrue(button.top >= 0 && button.bottom <= f.height && button.height > 0)
            scene.settledFrame().save("auto-delete-390x360-1.25")
            scene.click(button)
            scene.settledFrame()
            assertEquals(listOf(0 to 17 * 86400), f.autoWrites)
        }
    }

    @Test fun nativeCustomDefaultTimeIsNotReplacedByTheNearestPresetAndInactivePageCannotWrite() {
        val f = Fixture(section = SettingsSecuritySection.AUTO_DELETE)
        f.onUi { f.security = f.security.copy(defaultMessageAutoDeleteSeconds = 17 * 86400) }
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.texts("17 天").isNotEmpty())
            scene.reveal("关闭", f)
            scene.click(scene.text("关闭"))
            scene.settledFrame()
            f.onUi { f.active = false }
            scene.settledFrame()
            scene.reveal("保存", f)
            scene.click(scene.text("保存"))
            scene.settledFrame()
            assertTrue(f.autoWrites.isEmpty())
        }
    }

    private class Fixture(val width: Int = 640, val height: Int = 800, val density: Float = 1f,
        val section: SettingsSecuritySection = SettingsSecuritySection.SESSIONS,
    ) {
        var active by mutableStateOf(true)
        var security by mutableStateOf(SettingsSecuritySnapshot(SettingsSecurityToken(19, 4), section,
            sessions = listOf(device(1, "当前设备", true), device(-77, "其他设备")), inactiveSessionTtlDays = 366,
            defaultMessageAutoDeleteSeconds = 0))
        val batch = mutableListOf<List<SettingsSession>>()
        val ttlWrites = mutableListOf<Pair<Int, Int>>()
        val autoWrites = mutableListOf<Pair<Int, Int>>()
        private val actions = SettingsActions(terminateOtherSecuritySessions = { _, expected, _ -> batch += expected },
            saveInactiveSessionTtl = { _, expected, desired, _ -> ttlWrites += expected to desired },
            saveDefaultMessageAutoDeleteTime = { _, expected, desired, _ -> autoWrites += expected to desired })
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
        fun scene() = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active,
                LocalSettingsSecurityConfirmationHost provides OfflineConfirmation((height / density).dp)) {
                MiuixTheme(controller = theme) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        val writesAllowed = security.error != SettingsError.RESULT_UNKNOWN
                        if (section == SettingsSecuritySection.SESSIONS)
                            SettingsSessionCommonOptions(security, actions, active, false, writesAllowed = writesAllowed)
                        else SettingsDefaultAutoDeletePage(security, actions, active, writesAllowed = writesAllowed)
                    }
                }
            }
        }
    }

    private class OfflineConfirmation(private val viewport: Dp) : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().heightIn(max = (viewport - 24.dp).coerceAtLeast(96.dp))
                .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(10.dp)) {
                title?.let { Text(it) }
                content()
            }
        }
    }

    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }
    private fun ComposePixelScene.text(label: String) = assertNotNull(texts(label).firstOrNull { it.width > 0 && it.height > 0 }, label)
    private fun ComposePixelScene.editors() = semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y)
    }
    private fun ComposePixelScene.replace(rect: Rect, text: String) {
        click(rect); settledFrame(2, 16); selectAll(); settledFrame(2, 16)
        keyStroke(KeyEvent.VK_BACK_SPACE); settledFrame(2, 16)
        text.forEach { keyStroke(KeyEvent.getExtendedKeyCodeForChar(it.code), it); settledFrame(1, 16) }
        settledFrame(3, 16)
    }
    private fun ComposePixelScene.reveal(label: String, f: Fixture) = revealBounds(f) { texts(label) }
    private fun ComposePixelScene.revealEditor(f: Fixture) = revealBounds(f) { editors() }
    private fun ComposePixelScene.revealBounds(f: Fixture, target: () -> List<Rect>) {
        repeat(60) {
            val rects = target()
            if (rects.any { it.width > 0 && it.height >= 12 && it.top >= 4 && it.bottom <= f.height - 4 }) return
            val scrollers = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            val scroll = scrollers.lastOrNull { it.width > 0 && it.height > 20 && it.bottom > 0 && it.top < f.height }
            val direction = if (rects.any { it.width > 0 && it.height > 0 && it.bottom < 4 }) -4f else 4f
            scrollAt(scroll?.center?.x ?: f.width / 2f, scroll?.center?.y?.coerceIn(4f, f.height - 4f) ?: f.height * .7f,
                deltaY = direction)
            settledFrame(5, 32)
        }
        assertTrue(target().any { it.width > 0 && it.height >= 12 && it.top >= 4 && it.bottom <= f.height - 4 },
            "Actual scroll must reveal the complete target: ${target()}")
    }
    private fun ComposePixelFrame.save(name: String) {
        val output = File("build/settings-privacy-common-validation/frames/$name.png")
        output.parentFile.mkdirs()
        val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) bitmap.setRGB(x, y, this[x, y])
        ImageIO.write(bitmap, "png", output)
    }
    companion object {
        private fun device(id: Long, name: String, current: Boolean = false) = SettingsSession(id, current, name,
            "Synthetic client", "1.0", "Windows", "11", 1, "192.0.2.1", "Synthetic location")
    }
}
