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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SettingsProfileExtrasUiTest {
    @Test fun actualBirthdayFieldsRejectInvalidDayAndKeepTypedContentAfterNativeFailure() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.text("生日")); scene.settledFrame()
            scene.replace(scene.editors()[0], "31"); scene.replace(scene.editors()[1], "4")
            scene.reveal("保存生日", f)
            scene.click(scene.text("保存生日")); scene.settledFrame()
            assertTrue(f.writes.isEmpty(), "An invalid date cannot become an implicit native removal.")
            scene.replace(scene.editors()[0], "30")
            scene.reveal("保存生日", f); scene.click(scene.text("保存生日")); scene.settledFrame()
            assertEquals(SettingsBirthdate(29, 2), f.writes.single().first)
            assertEquals(SettingsBirthdate(30, 4), f.writes.single().second)
            f.extras = f.extras.copy(startedRevision = 1, failedRevision = 1, error = SettingsError.RATE_LIMITED)
            scene.settledFrame()
            assertEquals(listOf("30", "4", ""), scene.editorValues())
            scene.reveal("保存生日", f); scene.click(scene.text("保存生日")); scene.settledFrame()
            assertEquals(2, f.writes.size, "Failure retains the actual typed date for an explicit retry.")
        }
    }
    @Test fun saveableBirthdayDraftSurvivesRemovingAndRemountingTheProfilePage() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.click(scene.text("生日")); scene.settledFrame()
            scene.replace(scene.editors()[0], "15")
            f.mounted = false; scene.settledFrame()
            assertEquals(1, f.closed)
            f.mounted = true; scene.settledFrame()
            assertEquals(listOf("15", "2", ""), scene.editorValues())
            assertTrue(f.writes.isEmpty())
            assertEquals(2, f.opened)
        }
    }
    @Test fun removeBirthdayRequiresActualConfirmationAndPreservesTheFrozenNativeDate() {
        val f = Fixture(height = 480)
        f.scene().use { scene ->
            scene.settledFrame(); scene.reveal("移除生日", f); scene.click(scene.text("移除生日")); scene.settledFrame()
            assertTrue(f.writes.isEmpty())
            scene.reveal("取消", f); scene.click(scene.text("取消")); scene.settledFrame()
            assertTrue(f.writes.isEmpty())
            scene.reveal("移除生日", f); scene.click(scene.text("移除生日")); scene.settledFrame()
            scene.reveal("确认", f); scene.click(scene.text("确认")); scene.settledFrame()
            assertEquals(SettingsBirthdate(29, 2), f.writes.single().first)
            assertNull(f.writes.single().second)
        }
    }
    private class Fixture(val width: Int = 390, val height: Int = 844) {
        var mounted by mutableStateOf(true)
        var extras by mutableStateOf(SettingsProfileExtrasSnapshot(SettingsProfileExtrasToken(7, 3, 43),
            SettingsProfileExtrasData(43, null, SettingsBirthdate(29, 2), SettingsCalendarDate(25, 10, 2026))))
        val writes = mutableListOf<Pair<SettingsBirthdate?, SettingsBirthdate?>>()
        var opened = 0
        var closed = 0
        val actions = SettingsProfileExtrasActions(open = { _, _, _ -> opened++ }, close = { closed++ },
            saveBirthdate = { _, expected, desired -> writes += expected to desired })
        fun scene() = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            val holder = rememberSaveableStateHolder()
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsPageActive provides true,
                LocalSettingsSecurityConfirmationHost provides OfflineHost(height.dp)) {
                MiuixTheme(controller = theme) {
                    if (mounted) holder.SaveableStateProvider("profile") {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState()).padding(16.dp)) {
                            SettingsProfileExtrasContent(SettingsToken(7, 1), 43, extras, actions, true)
                        }
                    }
                }
            }
        }
    }
    private class OfflineHost(val height: Dp) : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit,
        ) {
            if (show) Column(Modifier.fillMaxWidth().heightIn(max = height - 32.dp)
                .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp)) {
                title?.let { Text(it) }; content()
            }
        }
    }
    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.text(label: String, first: Boolean = false): Rect {
        val found = texts(label)
        return assertNotNull(if (first) found.firstOrNull() else found.lastOrNull(), label)
    }
    private fun ComposePixelScene.editors() = semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.sortedBy { it.top }
    private fun ComposePixelScene.editorValues(): List<String> = buildList {
        semanticsBounds { config -> config.getOrNull(SemanticsProperties.EditableText)?.text?.let(::add); false }
    }
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun ComposePixelScene.replace(bounds: Rect, text: String) {
        click(bounds); settledFrame(2, 16)
        selectAll(); settledFrame(2, 16)
        keyStroke(KeyEvent.VK_BACK_SPACE); settledFrame(2, 16)
        text.forEach { keyStroke(KeyEvent.getExtendedKeyCodeForChar(it.code), it); settledFrame(1, 16) }
        settledFrame(3, 16)
    }
    private fun ComposePixelScene.reveal(label: String, f: Fixture) {
        repeat(40) {
            val candidates = texts(label)
            if (candidates.any { it.width > 0 && it.height >= 12 && it.top >= 8 && it.bottom <= f.height - 8 }) return
            val scroller = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.height > 20 && it.bottom > 0 && it.top < f.height }
            val direction = if (candidates.any { it.width > 0 && it.height > 0 && it.bottom < 8 }) -5f else 5f
            scrollAt(scroller?.center?.x ?: f.width / 2f, scroller?.center?.y?.coerceIn(8f, f.height - 8f) ?: f.height * .6f,
                deltaY = direction); settledFrame(5, 32)
        }
        assertTrue(texts(label).any { it.width > 0 && it.height > 0 && it.top >= 8 && it.bottom <= f.height - 8 }, label)
    }
}
