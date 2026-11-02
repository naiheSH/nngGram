package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.settings.SettingsProfile
import com.badnng.moe.settings.SettingsSection
import com.badnng.moe.settings.SettingsSnapshot
import com.badnng.moe.settings.SettingsToken
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.ClientStatus
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.RuntimeSnapshot
import java.awt.event.KeyEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SettingsInterfaceScaleRenderTest {
    @Test
    fun actualFrameGeometryMultipliesHostDensityOnceAndKeepsIndependentFontScale() {
        val guard = Color(0xFFFF00FF)
        val marker = Color(0xFF009688)
        for (host in listOf(1f, 1.25f, 1.5f, 2f)) for (percent in listOf(0, 50, 100, 125, 200, 300)) {
            val system = Density(host, fontScale = 1.35f)
            var observedFontScale = 0f
            ComposePixelScene(400, 400) {
                CompositionLocalProvider(LocalSettingsSystemDensity provides system,
                    LocalDensity provides settingsInterfaceDensity(system, percent)) {
                    val actual = LocalDensity.current
                    SideEffect { observedFontScale = actual.fontScale }
                    Box(Modifier.fillMaxSize().background(guard)) {
                        Box(Modifier.size(40.dp).background(marker).semantics { contentDescription = "真实缩放标尺" })
                    }
                }
            }.use { scene ->
                val frame = scene.settledFrame(3, 16)
                val bounds = scene.description("真实缩放标尺")
                val factor = if (percent == 0) 1f else percent / 100f
                val expected = (40 * host * factor).roundToInt()
                assertEquals(expected.toFloat(), bounds.width)
                assertEquals(expected.toFloat(), bounds.height)
                assertEquals(system.fontScale, observedFontScale)
                assertEquals(marker.toArgb(), frame[expected / 2, expected / 2])
                assertEquals(guard.toArgb(), frame[expected + 2, expected / 2],
                    "A real draw must end at the once-scaled physical size for host=$host percent=$percent.")
            }
        }
    }

    @Test
    fun nativeScaleControlSeparatesAutomaticFromManualAndRejectsFailedOrInactiveChanges() {
        val fixture = ControlFixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.hasText("系统缩放：125%"))
            scene.click(scene.text("默认界面缩放比例"))
            scene.settledFrame()
            assertEquals(100, fixture.preferences.interfaceScalePercent,
                "Manual 100% must remain distinct from automatic even though their density is equal.")

            fixture.acceptChanges = false
            scene.dragScaleToMiddle()
            assertEquals(175, fixture.requests.last().interfaceScalePercent)
            assertEquals(100, fixture.preferences.interfaceScalePercent,
                "The control cannot claim a new persisted setting when its owner rejects the update.")
            assertTrue(scene.hasText("100%"))
            fixture.acceptChanges = true
            scene.dragScaleToMiddle()
            assertEquals(175, fixture.preferences.interfaceScalePercent)
            assertTrue(scene.hasText("系统缩放：125%"), "The baseline must remain independent of application scale.")

            var keyboardFocused = false
            repeat(8) {
                if (!keyboardFocused) {
                    scene.keyStroke(KeyEvent.VK_TAB)
                    scene.settledFrame(2, 16)
                    keyboardFocused = scene.semanticsBounds {
                        "界面缩放比例" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                            it.getOrNull(SemanticsProperties.Focused) == true
                    }.isNotEmpty()
                }
            }
            assertTrue(keyboardFocused, "The real native slider must be reachable through Tab navigation.")
            scene.keyStroke(KeyEvent.VK_RIGHT)
            scene.settledFrame()
            assertEquals(180, fixture.preferences.interfaceScalePercent)
            assertTrue(scene.setScaleSemantically(125f))
            scene.settledFrame()
            assertEquals(125, fixture.preferences.interfaceScalePercent,
                "SetProgress must commit without relying on a pointer-only drag-finished event.")

            scene.click(scene.text("默认界面缩放比例"))
            scene.settledFrame()
            assertEquals(0, fixture.preferences.interfaceScalePercent)
            fixture.enabled = false
            scene.settledFrame()
            val acceptedRequests = fixture.requests.size
            scene.click(scene.text("默认界面缩放比例"))
            scene.dragScaleToMiddle()
            assertFalse(scene.setScaleSemantically(200f))
            scene.settledFrame()
            assertEquals(acceptedRequests, fixture.requests.size)
        }
    }

    @Test
    fun appScaleReflowsTheRealSettingsBreakpointAndRetainsTheUnscaledHostReference() {
        val system = Density(1.25f, fontScale = 1.15f)
        var width by mutableStateOf(1_125)
        val runtime = RuntimeSnapshot(configPath = "synthetic/settings.properties", generation = 19, clientActive = true,
            status = ClientStatus(EnginePhase.INITIALIZED, AuthorizationPhase.READY, ConnectionPhase.READY),
            chats = ChatSnapshot(enabled = true, token = ChatToken(19, 0, null), listEnd = true,
                account = AccountProfile(800001, "Synthetic Scale")),
            settings = SettingsSnapshot(token = SettingsToken(19), enabled = true, loaded = setOf(SettingsSection.PROFILE),
                profile = SettingsProfile(800001, "Synthetic", "Scale", "synthetic_scale", "Synthetic biography", "15550002222")))
        ComposePixelScene(1_800, 1_250) {
            CompositionLocalProvider(LocalDensity provides system) {
                Box(Modifier.requiredSize((width / system.density).dp, (1_250 / system.density).dp)) {
                    App(runtime = runtime, initialTheme = ColorSchemeMode.Light, persistUiPreferences = false)
                }
            }
        }.use { scene ->
            scene.settledFrame()
            scene.click(scene.description("打开账号菜单"))
            scene.settledFrame()
            scene.click(scene.text("设置"))
            scene.settledFrame()
            assertTrue(scene.hasText("简介"), "900dp must initially show actual profile content beside the categories.")
            assertEquals(50f, scene.description("返回聊天").width, .01f)
            scene.revealScaleControl(150f, 1_160f)
            scene.dragScaleToMiddle()
            assertEquals(88f, scene.description("返回聊天").width, .01f,
                "The real 40dp App control must become 40 * 1.25 * 1.75 pixels, with no second multiplication.")
            assertFalse(scene.hasText("简介"), "Scaling must move the same window naturally below the 760dp breakpoint.")
            // The larger density shrinks the real logical viewport. Scroll the
            // now lower sidebar control into view before inspecting clipped text.
            scene.revealScaleControl(150f, 1_160f)
            assertTrue(scene.hasText("系统缩放：125%"), "The reflowed control must retain its independent host density: ${scene.systemScaleLabels()}")

            SwingUtilities.invokeAndWait { width = 1_660 }
            scene.settledFrame()
            assertFalse(scene.hasText("简介"), "1660 / 2.1875 remains below 760dp.")
            SwingUtilities.invokeAndWait { width = 1_665 }
            scene.settledFrame()
            assertTrue(scene.hasText("简介"), "1665 / 2.1875 must reveal the right-hand content again.")

            scene.revealScaleControl(150f, 1_160f)
            scene.click(scene.text("默认界面缩放比例"))
            scene.settledFrame()
            assertEquals(50f, scene.description("返回聊天").width, .01f)
            scene.revealScaleControl(150f, 1_160f)
            assertTrue(scene.hasText("系统缩放：125%"), "Restoring automatic must keep the real host reference: ${scene.systemScaleLabels()}")
            assertTrue(scene.hasText("简介"))
        }
    }

    private class ControlFixture {
        var preferences by mutableStateOf(SettingsPreferences())
        var enabled by mutableStateOf(true)
        var acceptChanges = true
        val requests = CopyOnWriteArrayList<SettingsPreferences>()
        fun scene() = ComposePixelScene(620, 900) {
            val system = Density(1.25f, fontScale = 1.15f)
            CompositionLocalProvider(LocalSettingsSystemDensity provides system,
                LocalDensity provides settingsInterfaceDensity(system, preferences.interfaceScalePercent)) {
                val theme = remember { ThemeController(ColorSchemeMode.Light) }
                MiuixTheme(controller = theme) {
                    SettingsInterfaceScaleControl(preferences, { next ->
                        requests += next
                        if (acceptChanges) preferences = next
                    }, enabled = enabled)
                }
            }
        }
    }

    private fun ComposePixelScene.description(label: String): Rect = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.filter { it.width > 0f && it.height > 0f }.singleOrNull(), "Missing real scaling control: $label")

    private fun ComposePixelScene.text(label: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.filter { it.width > 0f && it.height > 0f }.singleOrNull(), "Missing real scaling text: $label")

    private fun ComposePixelScene.hasText(label: String): Boolean = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.any { it.width > 0f && it.height > 0f }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    /** The pinned Miuix Slider accepts drags; it does not implement click-to-seek. */
    private fun ComposePixelScene.dragScaleToMiddle() {
        val slider = description("界面缩放比例")
        val start = slider.left + slider.width * .2f
        moveTo(start, slider.center.y)
        pressAt(start, slider.center.y)
        settledFrame(1, 16)
        dragTo(slider.center.x + 22f, slider.center.y)
        settledFrame(1, 16)
        dragTo(slider.center.x, slider.center.y)
        settledFrame(1, 16)
        releaseAt(slider.center.x, slider.center.y)
        settledFrame()
    }

    private fun ComposePixelScene.setScaleSemantically(value: Float): Boolean {
        var applied = false
        semanticsBounds {
            if ("界面缩放比例" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                applied = it.getOrNull(SemanticsActions.SetProgress)?.action?.invoke(value) == true
            false
        }
        return applied
    }

    private fun ComposePixelScene.revealScaleControl(x: Float, y: Float) {
        repeat(20) {
            val slider = semanticsBounds { "界面缩放比例" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                .singleOrNull()
            val automatic = semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "默认界面缩放比例" }
            }.singleOrNull()
            val baseline = systemScaleLabels().singleOrNull()?.second
            val headerBottom = description("返回聊天").bottom + 20f
            if (slider != null && slider.height >= 20f && slider.bottom < 1_230f &&
                automatic != null && automatic.height >= 20f && automatic.top >= headerBottom &&
                baseline != null && baseline.height >= 12f && baseline.bottom < 1_230f) return
            scrollAt(x, y, deltaY = 4f)
            settledFrame(6, 64)
        }
        fail("The sidebar must expose its actual default toggle, slider and host-scale reference together: ${systemScaleLabels()}")
    }

    private fun ComposePixelScene.systemScaleLabels(): List<Pair<String, Rect>> {
        val labels = mutableListOf<String>()
        val bounds = semanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().firstOrNull { text -> text.text.startsWith("系统缩放：") }
                ?.let { text -> labels += text.text; true } ?: false
        }
        return labels.zip(bounds)
    }
}
