package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.settings.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual form input and app callbacks; clock/availability responses are synthetic and do not use an account. */
class SettingsUsernameAvailabilityUiTest {
    @Test fun noHiddenNameBioShortEmptyOrInternallyInvalidChecksAndEmptySaveStillRemovesUsername() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); assertTrue(f.calls.isEmpty()); assertTrue(f.clock.waits.isEmpty())
            scene.enter(f); scene.clickText("取消", f)
            scene.enter(f); scene.clickText("取消", f)
            assertTrue(f.clock.waits.isEmpty())
            scene.enter(f)
            for (text in listOf("abcd", "ab@cde", "1abcde", "")) {
                scene.replace(text, f)
                assertTrue(f.clock.active(200).isEmpty(), "Only ordinary five-character-or-longer candidates are checked.")
                assertTrue(f.calls.isEmpty())
                assertEquals(text.isEmpty(), scene.enabledButton("保存"))
            }
            scene.clickText("保存", f)
            assertEquals(SettingsProfileEdit(ProfileField.USERNAME, ""), f.saves.single())
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun twoHundredMillisecondDebounceSelectionAndDeleteRetypeHaveSeparateQueryOwnership() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f); scene.replace("target_name", f)
            assertTrue(f.calls.isEmpty())
            assertTrue(f.clock.active(200).isNotEmpty())
            val old = scene.dispatch(f)
            assertTrue(old.current())
            val waitCount = f.clock.waits.count { it.millis == 200L }
            scene.keyStroke(KeyEvent.VK_LEFT); scene.selectAll(); scene.settledFrame()
            assertEquals(waitCount, f.clock.waits.count { it.millis == 200L })
            assertEquals(1, f.calls.size)
            scene.keyStroke(KeyEvent.VK_BACK_SPACE)
            var alive = true
            Thread { alive = old.current() }.apply { start(); join() }
            assertFalse(alive, "The actual deletion synchronously revokes the captured Flow query before a render.")
            scene.settledFrame(); scene.replace("target_name", f)
            val fresh = scene.dispatch(f)
            assertTrue(fresh !== old)
            f.respond(old, SettingsUsernameAvailabilityStatus.CAN_BE_SET); scene.settledFrame()
            assertFalse(scene.hasText(CAN_SET))
            f.respond(fresh, SettingsUsernameAvailabilityStatus.OCCUPIED); scene.settledFrame()
            assertTrue(scene.hasText(OCCUPIED)); assertEquals("target_name", f.probe!!.value.text)
            assertTrue(f.saves.isEmpty())
        }
    }

    @Test fun normalizedCandidateIsIdenticalForCheckAndSaveWithoutChangingDisplayedTextOrSelection() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f); scene.replace(" @Candidate_Name ", f)
            val selection = f.probe!!.value.selection
            val call = scene.dispatch(f)
            assertEquals("Candidate_Name", call.candidate)
            assertEquals(" @Candidate_Name ", f.probe!!.value.text)
            f.respond(call, SettingsUsernameAvailabilityStatus.CAN_BE_SET); scene.settledFrame()
            assertEquals(selection, f.probe!!.value.selection)
            scene.clickText("保存", f)
            assertEquals("Candidate_Name", f.saves.single().value)
            assertEquals(" @Candidate_Name ", f.probe!!.value.text)
            assertTrue(scene.hasText(CAN_SET))
        }
    }

    @Test fun allEightTypedHintsAreTruthfulAndNeverBecomeASavePermissionOrAutomaticPurchase() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f)
            for ((index, status) in SettingsUsernameAvailabilityStatus.entries.withIndex()) {
                val candidate = "candidate_$index"
                scene.replace(candidate, f)
                val call = scene.dispatch(f)
                f.respond(call, status); scene.settledFrame()
                assertTrue(scene.hasText(labels.getValue(status)), status.name)
                assertEquals(candidate != BASE, scene.enabledButton("保存"), status.name)
                assertTrue(f.saves.isEmpty())
                assertTrue(scene.semanticsBounds { config ->
                    config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == labels.getValue(status) } &&
                        config.getOrNull(SemanticsActions.OnClick) != null
                }.isEmpty(), "Hint text must not buy or open a link.")
            }
            scene.replace("Original_name", f)
            val caseOnly = scene.dispatch(f)
            assertEquals("Original_name", caseOnly.candidate)
            assertTrue(caseOnly.candidate != BASE, "Case-only input reaches the actual checker rather than a local current-name shortcut.")
            assertFalse(scene.hasText("准备检查用户名……")); assertFalse(scene.hasText("正在检查用户名……"))
            assertTrue(scene.enabledButton("保存"))
        }
    }

    @Test fun cancelPageTokenSelfBaseAndUnmountRevokeOldChecksWithoutOverwritingTheNextEditor() {
        for (change in listOf("cancel", "page", "token", "self", "base", "unmount")) {
            val f = Fixture()
            f.scene().use { scene ->
                scene.enter(f); scene.replace("target_name", f)
                val old = scene.dispatch(f)
                val oldEditor = assertNotNull(f.probe)
                when (change) {
                    "cancel" -> scene.clickText("取消", f)
                    "page" -> onUi { f.active = false }
                    "token" -> onUi { f.snapshot = f.snapshot.copy(token = f.snapshot.token.copy(revision = 99)) }
                    "self" -> onUi { f.snapshot = f.snapshot.copy(profile = f.snapshot.profile!!.copy(userId = 99)) }
                    "base" -> onUi { f.snapshot = f.snapshot.copy(profile = f.snapshot.profile!!.copy(username = "fresh_base")) }
                    else -> onUi { f.mounted = false }
                }
                scene.settledFrame(); assertFalse(old.current(), change)
                f.respond(old, SettingsUsernameAvailabilityStatus.OCCUPIED); scene.settledFrame()
                assertFalse(scene.hasText(OCCUPIED), change)
                onUi { f.active = true; f.mounted = true }; scene.settledFrame()
                if (scene.inputBounds().isEmpty()) scene.enter(f)
                scene.settledFrame()
                val before = f.probe!!.value.text
                onUi { oldEditor.onValueChange(TextFieldValue("stale_input")); oldEditor.onSave(); oldEditor.onCancel() }
                scene.settledFrame()
                assertEquals(before, f.probe!!.value.text, change)
                assertTrue(scene.inputBounds().isNotEmpty(), change)
                assertTrue(f.saves.isEmpty(), change)
            }
        }
    }

    @Test fun tenSecondTimeoutWrongIdentityAndUnsupportedChecksCanRetryWithoutBlockingSave() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f); scene.replace("target_name", f)
            val timed = scene.dispatch(f)
            assertTrue(f.clock.active(10_000).isNotEmpty())
            onUi { f.clock.latest(10_000).gate.complete(Unit) }; scene.settledFrame()
            assertTrue(scene.hasText("检查超时，请重试；也可以尝试保存。")); assertFalse(timed.current())
            scene.clickText("重新检查用户名", f)
            val malformed = scene.dispatch(f)
            malformed.result(SettingsResult(SettingsUsernameAvailability(999, malformed.candidate, SettingsUsernameAvailabilityStatus.CAN_BE_SET)))
            scene.settledFrame(); assertTrue(scene.hasText(CHECK_ERROR))
            scene.clickText("重新检查用户名", f)
            val unsupported = scene.dispatch(f)
            unsupported.result(SettingsResult(error = SettingsError.UNSUPPORTED)); scene.settledFrame()
            assertTrue(scene.hasText(CHECK_ERROR)); assertTrue(scene.enabledButton("保存"))
            f.respond(timed, SettingsUsernameAvailabilityStatus.CAN_BE_SET); scene.settledFrame()
            assertFalse(scene.hasText(CAN_SET))
            scene.clickText("保存", f)
            assertEquals("target_name", f.saves.single().value)
        }
    }

    @Test fun busyAndOldSaveCallbacksCannotEditOrConsumeADeletedRetypedOrRenormalizedDraft() {
        val f = Fixture()
        f.autoBusy = true
        f.scene().use { scene ->
            scene.enter(f); scene.replace("target_name", f)
            val check = scene.dispatch(f)
            val submitted = assertNotNull(f.probe)
            scene.clickText("保存", f)
            onUi { submitted.onValueChange(TextFieldValue("stale_input")); submitted.onSave(); submitted.onCancel() }
            scene.settledFrame()
            assertEquals(1, f.saves.size); assertEquals("target_name", f.probe!!.value.text)
            check.result(SettingsResult(error = SettingsError.REQUEST_FAILED)); scene.settledFrame()
            onUi { f.snapshot = f.snapshot.copy(saving = emptySet(), failedSaveRevision = mapOf(SettingsSection.PROFILE to 1),
                errors = mapOf(SettingsSection.PROFILE to SettingsError.CONFLICT)) }
            scene.settledFrame(); assertEquals("target_name", f.probe!!.value.text)
            scene.clickText("重新检查用户名", f)
            val retry = scene.dispatch(f)
            f.respond(retry, SettingsUsernameAvailabilityStatus.CAN_BE_SET); scene.settledFrame()
            assertEquals(SettingsError.CONFLICT, f.snapshot.errors[SettingsSection.PROFILE])
            scene.clickText("保存", f)
            val rawAtSubmit = f.probe!!.value.text
            val revisionAtSubmit = f.probe!!.contentRevision
            onUi { f.snapshot = f.snapshot.copy(saving = emptySet()) }; scene.settledFrame()
            assertFalse(scene.enabledButton("取消"), "Unknown native readback retains batch ownership.")
            onUi { submitted.onCancel() }; scene.settledFrame()
            assertTrue(scene.inputBounds().isNotEmpty(), "An old cancel callback cannot drop a pending form.")
            scene.replace("", f); scene.replace(rawAtSubmit, f)
            scene.replace(" @target_name ", f); scene.replace(rawAtSubmit, f)
            assertTrue(f.probe!!.contentRevision > revisionAtSubmit)
            onUi { f.snapshot = f.snapshot.copy(completedSaveRevision = mapOf(SettingsSection.PROFILE to 2),
                profile = f.snapshot.profile!!.copy(username = "target_name")) }
            scene.settledFrame()
            assertTrue(scene.inputBounds().isNotEmpty(), "The old successful save cannot consume an identical new raw revision.")
            assertEquals(rawAtSubmit, f.probe!!.value.text)
        }
    }

    @Test fun cancellingAndReopeningDuringTheExitCannotReviveTheOldFormCallbacks() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f); scene.replace("old_candidate", f)
            val old = assertNotNull(f.probe)
            val cancel = scene.revealText("取消", f)
            scene.moveTo(cancel.center.x, cancel.center.y)
            scene.pressAt(cancel.center.x, cancel.center.y); scene.releaseAt(cancel.center.x, cancel.center.y)
            scene.settledFrame(1, 1)
            assertFalse(old.state.open())
            val pencil = assertNotNull(scene.semanticsBounds {
                "更改个人资料" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.firstOrNull())
            scene.moveTo(pencil.center.x, pencil.center.y)
            scene.pressAt(pencil.center.x, pencil.center.y); scene.releaseAt(pencil.center.x, pencil.center.y)
            scene.settledFrame(2, 16)
            val reopened = assertNotNull(f.probe)
            assertNotSame(old.state, reopened.state, "Reopen before the exit ends needs a fresh form identity.")
            onUi { old.onValueChange(TextFieldValue("stale_input")); old.onSave(); old.onCancel() }
            scene.settledFrame()
            assertEquals(BASE, f.probe!!.value.text)
            assertTrue(f.probe!!.state.open())
            assertEquals(4, scene.semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.size)
            assertTrue(f.saves.isEmpty())
        }
    }

    @Test fun actualUsernameInputHintAndSaveRemainReachableAcrossWidthHeightThemeAndDensity() {
        for ((width, height) in listOf(620 to 900, 350 to 300)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark))
            for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode)
                f.scene().use { scene ->
                    scene.enter(f); scene.replace(" @sample_name ", f)
                    val call = scene.dispatch(f)
                    f.respond(call, SettingsUsernameAvailabilityStatus.OCCUPIED); scene.settledFrame()
                    scene.revealText("保存", f)
                    assertTrue(scene.enabledButton("保存"))
                    val input = assertNotNull(scene.inputBounds().firstOrNull())
                    val hint = assertNotNull(scene.texts(OCCUPIED).firstOrNull())
                    assertTrue(input.height > 0 && input.top >= 0 && input.bottom <= f.pixelHeight)
                    assertTrue(hint.height > 0 && hint.top >= 0 && hint.bottom <= f.pixelHeight)
                    scene.settledFrame().save("username-${width}x$height-$mode-$density")
                    val closing = assertNotNull(f.probe)
                    scene.clickText("取消", f)
                    assertFalse(closing.state.open(), "Cancel revokes the owner before the visual exit finishes.")
                    assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.isEmpty(),
                        "The outgoing drawing cannot expose editable actions.")
                    scene.settledFrame()
                    assertTrue(scene.inputBounds().isEmpty()); assertTrue(f.saves.isEmpty())
                }
            }
    }

    private class Wait(val millis: Long) {
        val gate = CompletableDeferred<Unit>()
        val active = AtomicBoolean(true)
    }
    private class Clock {
        val waits = mutableListOf<Wait>()
        suspend fun wait(millis: Long) {
            val request = Wait(millis).also(waits::add)
            try { request.gate.await() } finally { request.active.set(false) }
        }
        fun active(millis: Long) = waits.filter { it.millis == millis && it.active.get() && !it.gate.isCompleted }
        fun latest(millis: Long) = assertNotNull(active(millis).lastOrNull(), "An actual $millis ms delay must be pending.")
    }
    private class Call(val userId: Long, val candidate: String, val current: () -> Boolean,
        val result: (SettingsResult<SettingsUsernameAvailability>) -> Unit)
    private class Fixture(val width: Int = 620, val height: Int = 900, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light,
    ) {
        val pixelHeight get() = (height * density).roundToInt()
        val clock = Clock(); val calls = mutableListOf<Call>(); val saves = mutableListOf<SettingsProfileEdit>()
        var active by mutableStateOf(true); var mounted by mutableStateOf(true)
        var probe: SettingsUsernameEditorUiProbe? = null
        var autoBusy = false
        var snapshot by mutableStateOf(SettingsSnapshot(token = SettingsToken(7, 3), enabled = true,
            loaded = setOf(SettingsSection.PROFILE), profile = SettingsProfile(43, "Example", "Account", BASE, "", "", bioLengthMax = 70)))
        private val actions = SettingsActions(checkUsername = { _, user, candidate, current, result -> calls += Call(user, candidate, current, result) },
            saveProfile = { _, edit ->
                saves += edit
                if (autoBusy) snapshot = snapshot.copy(saving = setOf(SettingsSection.PROFILE),
                    startedSaveRevision = mapOf(SettingsSection.PROFILE to ((snapshot.startedSaveRevision[SettingsSection.PROFILE] ?: 0) + 1)))
            })
        fun respond(call: Call, status: SettingsUsernameAvailabilityStatus) {
            Thread { call.result(SettingsResult(SettingsUsernameAvailability(call.userId, call.candidate, status))) }.apply { start(); join() }
        }
        fun scene() = ComposePixelScene((width * density).roundToInt(), pixelHeight) {
            val theme = remember { ThemeController(mode) }
            val holder = rememberSaveableStateHolder()
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active,
                LocalUsernameHintDelay provides clock::wait, LocalSettingsUsernameEditorUiProbe provides { probe = it }) {
                MiuixTheme(controller = theme) {
                    if (mounted) holder.SaveableStateProvider("profile") {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState()).padding(12.dp)) {
                            SettingsProfilePage(snapshot, actions, ChatSnapshot(), ChatFeatureActions())
                        }
                    }
                }
            }
        }
    }
    private fun ComposePixelScene.enter(f: Fixture) {
        settledFrame()
        click(reveal(f) { semanticsBounds { "更改个人资料" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } })
        settledFrame()
        assertTrue(f.clock.active(200).isEmpty(), "Opening the full form must not check the unchanged username.")
    }
    private fun ComposePixelScene.dispatch(f: Fixture): Call {
        settledFrame(3, 16)
        val count = f.calls.size
        onUi { f.clock.latest(200).gate.complete(Unit) }
        repeat(24) { settledFrame(2, 16); if (f.calls.size == count + 1) return f.calls.last() }
        assertEquals(count + 1, f.calls.size)
        return f.calls.last()
    }
    private fun ComposePixelScene.texts(label: String) = semanticsBounds { it.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }
    private fun ComposePixelScene.hasText(label: String) = texts(label).isNotEmpty()
    private fun ComposePixelScene.enabledButton(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } && it.getOrNull(SemanticsActions.OnClick) != null &&
            it.getOrNull(SemanticsProperties.Disabled) == null
    }.isNotEmpty()
    private fun ComposePixelScene.inputBounds() = unmergedSemanticsBounds {
        "用户名编辑框" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y); settledFrame(4, 16)
    }
    private fun ComposePixelScene.clickText(label: String, f: Fixture) {
        if (label in listOf("姓名", "用户名", "简介") && semanticsBounds {
            it.getOrNull(SemanticsProperties.EditableText) != null
        }.isEmpty()) {
            click(reveal(f) { semanticsBounds { "更改个人资料" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } })
            settledFrame()
        }
        click(revealText(label, f))
    }
    private fun ComposePixelScene.revealText(label: String, f: Fixture) = reveal(f) {
        if (label in listOf("姓名", "用户名", "简介")) semanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } &&
                it.getOrNull(SemanticsActions.OnClick) != null && it.getOrNull(SemanticsProperties.EditableText) == null
        } else texts(label)
    }
    private fun ComposePixelScene.replace(value: String, f: Fixture) {
        val field = reveal(f) { inputBounds() }
        val editor = assertNotNull(unmergedSemanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }
            .firstOrNull { it.overlaps(field) }, "Actual username editable hitbox")
        click(editor.intersect(field))
        selectAll(); settledFrame(2, 16)
        keyStroke(KeyEvent.VK_BACK_SPACE); settledFrame(2, 16)
        for (character in value) { keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character); settledFrame(1, 16) }
        settledFrame(4, 16)
        assertEquals(value, f.probe!!.value.text, "Actual keyboard input must reach the displayed draft.")
    }
    private fun ComposePixelScene.reveal(f: Fixture, candidates: () -> List<Rect>): Rect {
        fun visible() = candidates().firstOrNull { it.width > 0 && it.height >= 8 * f.density && it.top >= 1 && it.bottom <= f.pixelHeight - 1 }
        var direction = -1; var step = 8f * f.density; var prior = 0
        repeat(180) {
            visible()?.let { return it }
            val target = candidates().firstOrNull { it.height > 0 && it.width > 0 }
            var position = 0f; var maximum = 0f
            val bounds = semanticsBounds { config -> config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let {
                position = it.value(); maximum = it.maxValue()
            }; config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            direction = if (target != null) { if (target.top < 1) -1 else 1 }
                else if (position <= .5f) 1 else if (position >= maximum - .5f) -1 else direction
            if (prior != 0 && prior != direction) step = (step / 2).coerceAtLeast(.02f)
            prior = direction
            val scroller = bounds.lastOrNull { it.height > 20 && it.top < f.pixelHeight && it.bottom > 0 }
            scrollAt(scroller?.center?.x ?: f.width * f.density / 2, scroller?.center?.y ?: f.pixelHeight / 2f, deltaY = direction * step)
            settledFrame(4, 16)
        }
        return assertNotNull(visible(), "Pointer scrolling must expose target at ${f.width}x${f.height}: ${candidates()}")
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File("build/settings-username-ui-validation/frames/$name.png"); output.parentFile.mkdirs(); ImageIO.write(image, "png", output)
    }
    private fun onUi(action: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action) }
    companion object {
        private const val BASE = "original_name"
        private const val CAN_SET = "这个用户名可以设置，保存时会再次确认。"
        private const val OCCUPIED = "这个用户名已被占用。"
        private const val CHECK_ERROR = "暂时无法检查用户名，仍可尝试保存。"
        private val labels = mapOf(SettingsUsernameAvailabilityStatus.CURRENT_USERNAME to "这是你当前使用的用户名。",
            SettingsUsernameAvailabilityStatus.CAN_BE_SET to CAN_SET,
            SettingsUsernameAvailabilityStatus.INVALID to "这个用户名格式无效，请换一个。",
            SettingsUsernameAvailabilityStatus.OCCUPIED to OCCUPIED,
            SettingsUsernameAvailabilityStatus.PURCHASABLE to "这个用户名需要另行购买。",
            SettingsUsernameAvailabilityStatus.PUBLIC_CHATS_TOO_MANY to "公开用户名数量已达上限。",
            SettingsUsernameAvailabilityStatus.PUBLIC_GROUPS_UNAVAILABLE to "此账号暂时无法使用公开用户名。",
            SettingsUsernameAvailabilityStatus.UNKNOWN to "暂时无法确认这个用户名，仍可尝试保存。")
    }
}
