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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.LocalLockActions
import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.settings.LocalLockSnapshot
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real composables and synthetic input only; no native session, clipboard or OS credentials. */
class LocalLockUiTest {
    @Test
    fun creationRejectsMismatchedConfirmationAndBusyDoubleEnter() {
        val fixture = FormFixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            assertEquals(2, scene.passwordEditors())
            val fields = scene.editors()
            scene.replace(fields[0], "alpha")
            scene.replace(fields[1], "beta")
            scene.keyStroke(KeyEvent.VK_ENTER)
            scene.settledFrame()
            assertNotNull(scene.text("两次输入的本地密码不一致。"))
            assertEquals(0, fixture.requests.size)

            scene.replace(scene.editors()[1], "alpha")
            scene.keyStroke(KeyEvent.VK_ENTER)
            scene.keyStroke(KeyEvent.VK_ENTER)
            scene.settledFrame()
            assertEquals(listOf("alpha" to "alpha"), fixture.requests)
            assertTrue(scene.editorValues().all { it.isEmpty() }, "The displayed secret clears on submission.")
            assertTrue(scene.texts("alpha").isEmpty())
            fixture.onUi { fixture.snapshot = fixture.snapshot.copy(busy = false, issue = LocalLockIssue.WRONG_PASSWORD,
                operationRevision = fixture.snapshot.operationRevision + 1) }
            scene.settledFrame()
            assertNotNull(scene.text("本地密码不正确，请重试。"))
            scene.replace(scene.editors()[0], "alpha")
            scene.replace(scene.editors()[1], "alpha")
            scene.keyStroke(KeyEvent.VK_ENTER)
            scene.settledFrame()
            assertEquals(2, fixture.requests.size, "A repeated explicit submission after a finished error is allowed.")
        }
    }

    @Test
    fun inactiveAndSaveableRemountClearTheTransientSecret() {
        val fixture = FormFixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.replace(scene.editors()[0], "syntheticsecret")
            fixture.onUi { fixture.active = false }
            scene.settledFrame()
            fixture.onUi { fixture.active = true }
            scene.settledFrame()
            assertTrue(scene.editorValues().all { it.isEmpty() })

            scene.replace(scene.editors()[0], "anothersecret")
            fixture.onUi { fixture.mounted = false }
            scene.settledFrame()
            fixture.onUi { fixture.mounted = true }
            scene.settledFrame()
            assertTrue(scene.editorValues().all { it.isEmpty() }, "SaveableStateHolder cannot retain the form secret.")
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun customTimeParsingPreservesOfficialPresetAndRejectsZeroOrInvalidInputs() {
        assertEquals(listOf("1 分钟", "5 分钟", "1 小时", "5 小时"),
            listOf(60L, 300L, 3600L, 18000L).map(::localLockDurationText))
        assertEquals(3900L, localLockCustomSeconds("1", "05"))
        assertEquals(86_340L, localLockCustomSeconds("23", "59"))
        for ((hours, minutes) in listOf("0" to "00", "" to "1", " 1" to "0", "-1" to "0",
            "0" to "60", "99999999999999999999" to "0")) {
            assertEquals(null, localLockCustomSeconds(hours, minutes))
        }
    }

    @Test
    fun coldAndHotScreensRemainOpaqueAndReachableAtSmallSizesInBothThemesAndDensities() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (scale in listOf(1f, 1.25f)) {
                for (phase in listOf(LocalLockPhase.COLD_LOCKED, LocalLockPhase.HOT_LOCKED)) {
                    val width = ceil(350 * scale).toInt()
                    val height = ceil(300 * scale).toInt()
                    ComposePixelScene(width, height) {
                        val theme = remember { ThemeController(mode) }
                        CompositionLocalProvider(LocalDensity provides Density(scale)) {
                            MiuixTheme(controller = theme) {
                                LocalLockScreen(LocalLockSnapshot(phase = phase, enabled = true, epoch = 3),
                                    LocalLockActions(), windowFocused = true)
                            }
                        }
                    }.use { scene ->
                        val frame = scene.settledFrame()
                        val heading = assertNotNull(scene.text("nngGram 已锁定"))
                        assertTrue(heading.width > 0 && heading.top >= 0 && heading.right <= width)
                        assertEquals(1, scene.passwordEditors())
                        assertEquals(0xff, (frame[2, 2] ushr 24) and 0xff, "The root surface must be opaque.")
                        assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }.isNotEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun unfocusedLockScreenDoesNotRequestPasswordFocus() {
        ComposePixelScene(640, 500) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                LocalLockScreen(LocalLockSnapshot(phase = LocalLockPhase.COLD_LOCKED, enabled = true),
                    LocalLockActions(), windowFocused = false)
            }
        }.use { scene ->
            scene.settledFrame()
            assertTrue(scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.EditableText) != null && it.getOrNull(SemanticsProperties.Focused) == true
            }.isEmpty())
        }
    }

    @Test
    fun removingPasswordRequiresFreshVerificationAndCancellationOrExpiryDoesNotRemoveIt() {
        val f = ManagementFixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(assertNotNull(scene.text("关闭本地密码")))
            scene.settledFrame()
            assertTrue(f.closed > 0, "A previous management proof is revoked before this sensitive action.")
            assertTrue(scene.texts("确认关闭本地密码").isEmpty())
            scene.replace(scene.editors().single(), "synthetic")
            scene.keyStroke(KeyEvent.VK_ENTER)
            scene.settledFrame()
            assertEquals(1, f.verifications)
            assertEquals(0, f.removes)
            f.completeVerification()
            scene.settledFrame()
            assertNotNull(scene.text("确认关闭本地密码"))
            scene.click(assertNotNull(scene.text("取消关闭")))
            scene.settledFrame()
            assertEquals(0, f.removes)

            scene.click(assertNotNull(scene.text("关闭本地密码")))
            scene.settledFrame()
            scene.replace(scene.editors().single(), "synthetic")
            scene.keyStroke(KeyEvent.VK_ENTER)
            scene.settledFrame()
            f.completeVerification()
            scene.settledFrame()
            assertNotNull(scene.text("确认关闭本地密码"))
            f.onUi { f.snapshot = f.snapshot.copy(managementAuthorized = false, canRemove = false, canChange = false) }
            scene.settledFrame()
            assertTrue(scene.texts("确认关闭本地密码").isEmpty(), "An expired proof cannot keep its confirmation active.")
            assertEquals(0, f.removes)
        }
    }

    private class FormFixture {
        var active by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var snapshot by mutableStateOf(LocalLockSnapshot(phase = LocalLockPhase.NEW_ROOT_SETUP, canCreate = true))
        val requests = mutableListOf<Pair<String, String>>()
        private val actions = LocalLockActions(create = { password, confirmation ->
            requests += String(password) to String(confirmation)
            password.fill('\u0000')
            confirmation.fill('\u0000')
            snapshot = snapshot.copy(busy = true, issue = LocalLockIssue.NONE,
                operationRevision = snapshot.operationRevision + 1)
        })

        fun scene() = ComposePixelScene(640, 500) {
            val holder = rememberSaveableStateHolder()
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MiuixTheme(controller = theme) {
                    if (mounted) holder.SaveableStateProvider("synthetic-lock-form") {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState()).padding(16.dp)) {
                            LocalLockCredentialForm(snapshot, actions, LocalLockCredentialKind.CREATE,
                                active = active, windowFocused = true)
                        }
                    }
                }
            }
        }

        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
    }

    private class ManagementFixture {
        var snapshot by mutableStateOf(LocalLockSnapshot(phase = LocalLockPhase.UNLOCKED, enabled = true, epoch = 8,
            canManage = true, canChange = true, canRemove = true, managementAuthorized = true))
        var closed = 0
        var verifications = 0
        var removes = 0
        private val actions = LocalLockActions(
            verifyManagement = { password ->
                verifications++
                password.fill('\u0000')
                snapshot = snapshot.copy(busy = true, operationRevision = snapshot.operationRevision + 1)
            },
            closeManagement = {
                closed++
                snapshot = snapshot.copy(managementAuthorized = false, canChange = false, canRemove = false)
            },
            remove = { removes++ },
        )

        fun completeVerification() = onUi {
            snapshot = snapshot.copy(busy = false, issue = LocalLockIssue.NONE, managementAuthorized = true,
                canChange = true, canRemove = true, operationRevision = snapshot.operationRevision + 1)
        }
        fun scene() = ComposePixelScene(640, 800) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MiuixTheme(controller = theme) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
                        SettingsLocalLockPage(snapshot, actions, active = true, onBack = {})
                    }
                }
            }
        }
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
    }

    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.filter { it.width > 0 && it.height > 0 }

    private fun ComposePixelScene.text(label: String): Rect? = texts(label).firstOrNull()
    private fun ComposePixelScene.editors() = semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.sortedBy { it.top }
    private fun ComposePixelScene.passwordEditors() = semanticsBounds { it.getOrNull(SemanticsProperties.Password) != null }.size
    private fun ComposePixelScene.editorValues(): List<String> = buildList {
        semanticsBounds { config -> config.getOrNull(SemanticsProperties.EditableText)?.text?.let(::add); false }
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }
    private fun ComposePixelScene.focusedSecretEditor(rect: Rect): Pair<String, TextRange> {
        val values = mutableListOf<Pair<String, TextRange>>()
        val bounds = unmergedSemanticsBounds { config ->
            val text = config.getOrNull(SemanticsProperties.EditableText)?.text
            val selection = config.getOrNull(SemanticsProperties.TextSelectionRange)
            if (config.getOrNull(SemanticsProperties.Focused) == true && text != null && selection != null) {
                values.add(text to selection)
                true
            } else false
        }
        val matches = bounds.indices.filter { bounds[it].overlaps(rect) }
        return values[assertNotNull(matches.singleOrNull(), "The pointer-selected secret field must own focus and selection")]
    }
    private fun ComposePixelScene.replace(rect: Rect, value: String) {
        click(rect)
        settledFrame(2, 16)
        val original = focusedSecretEditor(rect)
        selectAll()
        settledFrame(2, 16)
        val selected = focusedSecretEditor(rect)
        assertEquals(original.first, selected.first, "Ctrl+A must preserve the displayed secret value")
        assertEquals(0, selected.second.min, "Ctrl+A must select the secret from the start")
        assertEquals(original.first.length, selected.second.max, "Ctrl+A must select the complete secret")
        keyStroke(KeyEvent.VK_BACK_SPACE)
        settledFrame(2, 16)
        val cleared = focusedSecretEditor(rect)
        assertEquals("", cleared.first, "Backspace must clear the entire selected secret")
        assertEquals(TextRange.Zero, cleared.second, "The cleared secret must retain a valid caret")
        for (character in value) {
            keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character)
            settledFrame(1, 16)
        }
        settledFrame(2, 16)
        val typed = focusedSecretEditor(rect)
        // Password semantics may expose the transformed mask; the business request assertions
        // above still check the exact submitted content after these real keyboard events.
        assertEquals(value.length, typed.first.length, "The replacement must have exactly the requested length")
        assertEquals(TextRange(value.length), typed.second, "The replacement caret must remain at the end")
    }
}
