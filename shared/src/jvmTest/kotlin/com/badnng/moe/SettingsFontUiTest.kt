package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

class SettingsFontUiTest {
    @Test
    fun actualChooserSelectionAndCancellationNeverWriteThePreference() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.click("字体家族")
            fixture.session.query = "No matching font"
            scene.settledFrame()
            assertTrue(scene.texts("没有匹配的已安装字体。").isNotEmpty())
            fixture.session.query = "Serif"
            scene.click("Synthetic Serif")
            assertEquals(fixture.installed, fixture.session.editor!!.draft)
            assertTrue(fixture.writes.isEmpty())
            assertEquals(SettingsChatFont(), fixture.preferences.chatFont)
            scene.click("取消字体更改")
            assertNull(fixture.session.editor)
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test
    fun narrowLowLightAndDarkPagesCanScrollToTheActualSaveAndCancelActions() {
        for (dark in listOf(false, true)) {
            val fixture = Fixture()
            fixture.scene(320, 240, dark).use { scene ->
                scene.click("字体家族")
                scene.reveal("系统默认", 320f, 240f)
                scene.click("系统默认")
                scene.reveal("保存字体", 320f, 240f)
                scene.click("保存字体")
                assertEquals(SettingsChatFont(SettingsChatFontMode.SYSTEM_DEFAULT), fixture.preferences.chatFont)
                assertNull(fixture.session.editor)
            }
        }
    }

    @Test
    fun failedSaveKeepsTheChosenDraftAndActualRetryOnlyCommitsOnTrue() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.click("字体家族"); scene.click("Synthetic Serif")
            fixture.allowSave = false
            scene.click("保存字体")
            assertEquals(SettingsChatFont(), fixture.preferences.chatFont)
            assertEquals(fixture.installed, fixture.session.editor!!.draft)
            assertTrue(scene.texts("字体设置保存失败，当前字体未更改。").isNotEmpty())
            fixture.allowSave = true
            scene.click("保存字体")
            assertEquals(fixture.installed, fixture.preferences.chatFont)
            assertEquals(listOf(fixture.installed, fixture.installed), fixture.writes)
            assertNull(fixture.session.editor)
        }
    }

    @Test
    fun unmountAndInactivePageKeepSmallDraftAndSearchButCannotSave() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.click("字体家族"); scene.click("Synthetic Serif")
            fixture.session.query = "Serif"
            fixture.mounted = false; scene.settledFrame()
            fixture.mounted = true; fixture.enabled = false; scene.settledFrame()
            assertNotNull(fixture.session.editor)
            assertEquals(fixture.installed, fixture.session.editor!!.draft)
            assertEquals("Serif", fixture.session.query)
            assertTrue(fixture.writes.isEmpty())
            fixture.enabled = true; scene.settledFrame()
            assertEquals(fixture.installed, fixture.session.editor!!.draft)
            scene.click("保存字体")
            assertEquals(fixture.installed, fixture.preferences.chatFont)
        }
    }

    @Test
    fun missingSavedFamilyIsVisibleAndDoesNotPretendToHaveLoadedOrOverwritePreference() {
        val fixture = Fixture()
        val missing = SettingsChatFont(SettingsChatFontMode.INSTALLED, "Removed Family")
        fixture.preferences = fixture.preferences.copy(chatFont = missing)
        fixture.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.texts("字体“Removed Family”不可用；保留原选择，当前使用默认字体。").isNotEmpty())
            scene.click("字体家族")
            assertEquals(missing, fixture.session.editor!!.draft)
            val save = scene.texts("保存字体").single()
            scene.pressAt(save.center.x, save.center.y); scene.releaseAt(save.center.x, save.center.y)
            scene.settledFrame()
            assertTrue(fixture.writes.isEmpty())
            assertEquals(missing, fixture.preferences.chatFont)
            scene.click("取消字体更改")
            assertFalse(fixture.session.saveFailed)
        }
    }

    private class Fixture {
        val installed = SettingsChatFont(SettingsChatFontMode.INSTALLED, "Synthetic Serif")
        var preferences by mutableStateOf(SettingsPreferences())
        var mounted by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        var allowSave = true
        val session = SettingsFontEditorSession()
        val writes = mutableListOf<SettingsChatFont>()
        val service = object : SettingsFontService {
            override suspend fun catalog() = SettingsFontCatalog(listOf("Synthetic Serif", "Synthetic Sans"))
            override suspend fun load(font: SettingsChatFont) = when {
                font.mode == SettingsChatFontMode.DEFAULT_OPEN_SANS -> SettingsFontLoad(font)
                font.familyName == "Removed Family" -> SettingsFontLoad(font, SettingsFontLoadStatus.MISSING)
                else -> SettingsFontLoad(font, SettingsFontLoadStatus.READY, FontFamily.Serif)
            }
        }
        fun scene(width: Int = 600, height: Int = 1000, dark: Boolean = true) = ComposePixelScene(width, height) {
            val controller = rememberSettingsThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light, SettingsAccentColor.GREEN)
            MiuixTheme(controller = controller) {
                CompositionLocalProvider(LocalSettingsFontService provides service) {
                    SettingsFontHost(preferences, service = service) {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState()).padding(10.dp)) {
                            if (mounted) SettingsFontControl(preferences, onSave = { font ->
                                writes += font
                                if (allowSave) preferences = preferences.copy(chatFont = font)
                                allowSave
                            }, enabled = enabled, session = session)
                        }
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.texts(label: String): List<Rect> = semanticsBounds { semantics ->
        semantics.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    }

    private fun ComposePixelScene.click(label: String) {
        settledFrame()
        val bounds = semanticsBounds { semantics ->
            semantics.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
                semantics.getOrNull(SemanticsActions.OnClick) != null
        }.single()
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
        settledFrame()
    }

    private fun ComposePixelScene.reveal(label: String, width: Float, height: Float) {
        repeat(48) {
            settledFrame()
            val bounds = texts(label).firstOrNull()
            if (bounds != null && bounds.top >= 0f && bounds.bottom <= height && bounds.height > 0f) return
            // Bounded steps cannot jump over the whole short viewport.
            scrollAt(width - 4f, height / 2f, deltaY = 8f)
        }
        error("$label bounds=${texts(label)} must be reachable in the narrow settings viewport")
    }
}
