package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.unit.Density
import com.badnng.moe.settings.*
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual Miuix editors and pointer/IME sessions, with synthetic responses and no account or OS UI. */
@OptIn(ExperimentalComposeUiApi::class)
class LoginEmailSetupUiTest {
    @Test fun invalidAddressNeverSendsAndFailureRetainsAddressForAnExplicitRetry() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.replace(scene.editor(), "invalid")
            scene.click(scene.text("发送验证码")); scene.settledFrame()
            assertTrue(f.emails.isEmpty())
            assertNotNull(scene.text("邮箱地址无效，请检查后重试。"))
            scene.replace(scene.editor(), "setup@example.invalid")
            scene.click(scene.text("发送验证码")); scene.settledFrame()
            assertEquals(1, f.emails.size)
            scene.click(scene.text("发送验证码")); scene.settledFrame()
            assertEquals(1, f.emails.size, "A pending native send cannot be duplicated by another pointer click.")
            f.replyEmail(0, issue = LoginEmailIssue.EMAIL_INVALID); scene.settledFrame()
            assertEquals("setup@example.invalid", scene.editorValues().single())
            assertTrue(scene.texts("setup@example.invalid").isEmpty(), "Only the private editor may expose the typed address.")
            scene.click(scene.text("发送验证码")); scene.settledFrame()
            assertEquals(2, f.emails.size)
            assertEquals("setup@example.invalid", f.emails.last().value)
        }
    }

    @Test fun actualLengthSubmitsOnceSelectionChangesDoNotRetryAndAcceptanceWaitsForProof() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.startCode(f, 4)
            scene.replace(scene.editor(), "1234")
            assertEquals(1, f.checks.size)
            f.replyCheck(0, LoginEmailIssue.CODE_INVALID); scene.settledFrame()
            scene.keyStroke(KeyEvent.VK_LEFT); scene.settledFrame(2, 16)
            scene.keyStroke(KeyEvent.VK_RIGHT); scene.settledFrame(2, 16)
            assertEquals(1, f.checks.size, "Selection-only changes cannot silently retry a rejected code.")
            scene.click(scene.text("验证")); scene.settledFrame()
            assertEquals(2, f.checks.size)
            f.replyCheck(1); scene.settledFrame()
            assertNotNull(scene.text("验证码已接受，正在核对实际登录邮箱状态。"))
            assertTrue(scene.texts("登录邮箱已设置").isEmpty())
            assertTrue(scene.editorValues().isEmpty(), "Accepted code and address no longer remain in an editor.")
            scene.click(scene.text("刷新验证状态")); scene.settledFrame()
            assertEquals(listOf(f.snapshot.token), f.refreshes)
            assertEquals(2, f.checks.size, "Proof retries cannot resend the previously accepted code.")
            f.onUi { f.snapshot = f.snapshot.copy(phase = LoginEmailPhase.COMPLETE, issue = null) }
            scene.settledFrame()
            assertNotNull(scene.text("登录邮箱已设置"))
        }
    }

    @Test fun zeroLengthRemainsManualAndResendUsesTheActualCurrentChallenge() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.startCode(f, 0)
            scene.replace(scene.editor(), "12345")
            assertTrue(f.checks.isEmpty(), "A native code length of zero must never imply six or another guessed length.")
            scene.click(scene.text("验证")); scene.settledFrame()
            assertEquals("12345", f.checks.single().value)
            f.replyCheck(0, LoginEmailIssue.CODE_EXPIRED); scene.settledFrame()
            scene.click(scene.text("重新发送验证码")); scene.settledFrame()
            val original = f.resends.single().challenge
            assertEquals(f.snapshot.challenge, original)
            f.replyResend(0, 4); scene.settledFrame()
            assertTrue(scene.editorValues().single().isEmpty())
            scene.replace(scene.editor(), "5678")
            assertEquals(2, f.checks.size)
            assertEquals(f.snapshot.challenge, f.checks.last().challenge)
            assertTrue(f.checks.last().challenge.revision > original.revision)
        }
    }

    @Test fun aRealImeCompositionWaitsForCommitBeforeCollectingTheCode() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.startCode(f, 4)
            scene.focusEditor(f)
            f.ime.edit(SetComposingTextCommand("1234", 1)); scene.settledFrame(3, 16)
            assertNotNull(assertNotNull(f.ime.current.get()).value().composition)
            assertTrue(f.checks.isEmpty(), "A complete preedit string is not an accepted code entry.")
            f.ime.edit(FinishComposingTextCommand()); scene.settledFrame(3, 16)
            assertEquals("1234", f.checks.single().value)
        }
    }

    @Test fun codePhaseWithoutActualInfoOrChallengeNeverClaimsDeliveryOrAllowsVerification() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.startCode(f, 0)
            scene.replace(scene.editor(), "123")
            f.onUi { f.snapshot = f.snapshot.copy(codeInfo = null) }; scene.settledFrame()
            assertNotNull(scene.text("验证码信息暂不可用，请返回邮箱地址重新开始。"))
            assertTrue(scene.texts("验证码已发送至 s***@example.invalid。").isEmpty())
            scene.click(scene.text("验证")); scene.settledFrame()
            assertTrue(f.checks.isEmpty())
            f.onUi { f.snapshot = f.snapshot.copy(codeInfo = SettingsEmailCodeInfo("s***@example.invalid", 0), challenge = null) }
            scene.settledFrame()
            assertNotNull(scene.text("验证码信息暂不可用，请返回邮箱地址重新开始。"))
            scene.click(scene.text("验证")); scene.settledFrame()
            assertTrue(f.checks.isEmpty())
        }
    }

    @Test fun addressAndCodeInputRemainBoundedThroughTheActualInputSession() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.focusEditor(f)
            f.ime.edit(CommitTextCommand("a".repeat(321), 1)); scene.settledFrame(3, 16)
            assertTrue(scene.editorValues().single().length <= 320)
            scene.replace(scene.editor(), "setup@example.invalid")
            scene.click(scene.text("发送验证码")); scene.settledFrame()
            f.replyEmail(0, length = 0); scene.settledFrame()
            scene.focusEditor(f)
            f.ime.edit(CommitTextCommand("1".repeat(101), 1)); scene.settledFrame(3, 16)
            assertTrue(scene.editorValues().single().length <= 100)
            assertTrue(f.checks.isEmpty())
        }
    }

    @Test fun returningToEmailRetiresTheChallengeAndRejectsItsLateCallback() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.startCode(f, 0)
            scene.replace(scene.editor(), "123")
            scene.click(scene.text("验证")); scene.settledFrame()
            val old = assertNotNull(f.snapshot.token)
            val oldOpening = f.openings.single()
            scene.click(scene.text("返回邮箱地址")); scene.settledFrame()
            assertFalse(oldOpening.lease())
            assertTrue(old in f.closed)
            assertTrue(f.snapshot.token != old)
            assertEquals("", scene.editorValues().single())
            f.onUi { f.checks.single().done(LoginEmailOutcome(verificationAccepted = true, completed = true)) }
            scene.settledFrame()
            assertNotNull(scene.text("发送验证码"))
            assertTrue(scene.texts("登录邮箱已设置").isEmpty())
        }
    }

    @Test fun staleOpeningAndAReplacementTokenCannotExposeOrUseAnOldChallenge() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.startCode(f, 0)
            val old = f.snapshot
            f.onUi { f.autoOpen = false; f.mounted = false }; scene.settledFrame()
            f.onUi { f.mounted = true }; scene.settledFrame()
            assertEquals(old, f.snapshot, "The fake intentionally leaves an old snapshot visible after remount.")
            assertTrue(scene.editorValues().isEmpty())
            assertTrue(scene.texts("验证码已发送至 s***@example.invalid。").isEmpty())
            f.publish(f.openings.last()); scene.settledFrame()
            assertNotNull(scene.text("发送验证码"))
            f.onUi { f.snapshot = f.snapshot.copy(token = assertNotNull(f.snapshot.token).copy(owner = 999)) }
            scene.settledFrame()
            assertTrue(scene.editorValues().isEmpty(), "Even the same openingId cannot replace an already bound full native token.")
        }
    }

    @Test fun inactiveAndSaveableRoundTripClearSecretsAndRevokeTheExternalLease() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.replace(scene.editor(), "setup@example.invalid")
            scene.click(scene.text("发送验证码")); scene.settledFrame()
            val first = f.openings.single()
            f.onUi { f.active = false }; scene.settledFrame()
            assertFalse(first.lease())
            f.onUi { f.active = true }; scene.settledFrame()
            assertEquals("", scene.editorValues().single())
            f.onUi { f.emails.single().done(LoginEmailOutcome(issue = LoginEmailIssue.EMAIL_INVALID)) }
            scene.settledFrame()
            assertTrue(scene.texts("邮箱地址无效，请检查后重试。").isEmpty())
            scene.replace(scene.editor(), "second@example.invalid")
            f.onUi { f.mounted = false }; scene.settledFrame()
            f.onUi { f.mounted = true }; scene.settledFrame()
            assertEquals("", scene.editorValues().single(), "The parent's SaveableStateHolder cannot retain private form inputs.")
        }
    }

    @Test fun optionalDismissAndNativeHideAreSeparateActions() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.click(scene.text("稍后提醒")); scene.settledFrame()
            assertEquals(listOf(7L to 11L), f.hidden)
            assertEquals(0, f.closes, "A hide request must wait for native state instead of pretending it succeeded.")
            scene.click(scene.text("关闭邮箱设置")); scene.settledFrame()
            assertEquals(1, f.closes)
            assertFalse(f.openings.single().lease())
        }
    }

    @Test fun mandatoryHasOnlyRealStopAndExitWithoutAnOrdinaryDismissal() {
        val f = Fixture(kind = LoginEmailEntryKind.MANDATORY)
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.texts("关闭邮箱设置").isEmpty())
            assertTrue(scene.texts("稍后提醒").isEmpty())
            scene.click(scene.text("停止连接")); scene.settledFrame()
            scene.click(scene.text("退出应用")); scene.settledFrame()
            assertEquals(1, f.stops); assertEquals(1, f.exits)
            assertEquals(0, f.closes)
            assertTrue(f.hidden.isEmpty())
            assertFalse(f.openings.single().lease())
        }
    }

    @Test fun narrowLowAndWideViewportsKeepTheirTrailingActionReachableInBothThemes() {
        for (case in listOf(Layout(280, 240, 1f, false), Layout(390, 300, 1.25f, true),
            Layout(1200, 320, 1f, true), Layout(600, 520, 1.25f, false))) {
            val f = Fixture(case.width, case.height, kind = LoginEmailEntryKind.MANDATORY,
                density = case.density, dark = case.dark)
            f.scene().use { scene ->
                scene.settledFrame()
                scene.reveal("退出应用", f)
                val bounds = scene.text("退出应用")
                assertTrue(bounds.top >= 8 && bounds.bottom <= f.height - 8)
                scene.click(bounds); scene.settledFrame()
                assertEquals(1, f.exits, "The visible trailing action must receive real pointer input: $case")
            }
        }
    }

    @Test fun anExistingSettingsScrollerCanHostTheSameNaturalHeightBody() {
        val f = Fixture(width = 390, height = 300, nested = true)
        f.scene().use { scene ->
            scene.settledFrame()
            scene.reveal("关闭邮箱设置", f)
            scene.click(scene.text("关闭邮箱设置")); scene.settledFrame()
            assertEquals(1, f.closes)
        }
    }

    private data class Layout(val width: Int, val height: Int, val density: Float, val dark: Boolean)
    private data class Opening(val generation: Long, val id: Long, val kind: LoginEmailEntryKind, val lease: () -> Boolean)
    private data class EmailRequest(val token: LoginEmailSetupToken, val value: String, val done: (LoginEmailOutcome) -> Unit)
    private data class CodeRequest(val challenge: LoginEmailChallengeToken, val value: String, val done: (LoginEmailOutcome) -> Unit)
    private data class ResendRequest(val challenge: LoginEmailChallengeToken, val done: (LoginEmailOutcome) -> Unit)

    private class Fixture(
        val width: Int = 500, val height: Int = 844, val kind: LoginEmailEntryKind = LoginEmailEntryKind.OPTIONAL,
        val density: Float = 1f, val dark: Boolean = false, val nested: Boolean = false,
    ) {
        var mounted by mutableStateOf(true)
        var active by mutableStateOf(true)
        var autoOpen = true
        var snapshot by mutableStateOf(LoginEmailSnapshot(generation = 7,
            suggestions = LoginEmailSuggestions(revision = 1, optionalPresent = kind == LoginEmailEntryKind.OPTIONAL,
                requiredPresent = kind == LoginEmailEntryKind.MANDATORY, optionalEpoch = 11, requiredEpoch = 21),
            mandatory = kind == LoginEmailEntryKind.MANDATORY))
        var nextOwner = 0L
        val openings = mutableListOf<Opening>()
        val emails = mutableListOf<EmailRequest>()
        val checks = mutableListOf<CodeRequest>()
        val resends = mutableListOf<ResendRequest>()
        val closed = mutableListOf<LoginEmailSetupToken>()
        val hidden = mutableListOf<Pair<Long, Long>>()
        val refreshes = mutableListOf<LoginEmailSetupToken>()
        var closes = 0
        var stops = 0
        var exits = 0
        val ime = ImeProbe()
        val actions = LoginEmailActions(open = { generation, id, kind, lease ->
            val opened = Opening(generation, id, kind, lease)
            openings += opened
            if (autoOpen) publish(opened)
        }, close = { closed += it }, submitEmail = { token, value, done -> emails += EmailRequest(token, value, done) },
            check = { token, value, done -> checks += CodeRequest(token, value, done) },
            resend = { token, done -> resends += ResendRequest(token, done) },
            refreshProof = { refreshes += it }, hideOptional = { gen, epoch -> hidden += gen to epoch })

        fun publish(opening: Opening) = onUi {
            snapshot = snapshot.copy(generation = opening.generation, openingId = opening.id,
                token = LoginEmailSetupToken(opening.generation, ++nextOwner, snapshot.suggestions.epoch(opening.kind), opening.kind),
                availability = LoginEmailAvailability.ALLOWED, phase = LoginEmailPhase.EMAIL, busy = false,
                codeInfo = null, challenge = null, issue = null, verificationAccepted = false)
        }
        fun replyEmail(index: Int, length: Int = 4, issue: LoginEmailIssue? = null) = onUi {
            val request = emails[index]
            val info = SettingsEmailCodeInfo("s***@example.invalid", length)
            val challenge = LoginEmailChallengeToken(request.token, 1)
            if (snapshot.token == request.token && issue == null)
                snapshot = snapshot.copy(phase = LoginEmailPhase.CODE, codeInfo = info, challenge = challenge, issue = null)
            request.done(LoginEmailOutcome(codeInfo = info.takeIf { issue == null },
                challenge = challenge.takeIf { issue == null }, issue = issue))
        }
        fun replyCheck(index: Int, issue: LoginEmailIssue? = null) = onUi {
            val request = checks[index]
            if (snapshot.challenge == request.challenge && issue == null)
                snapshot = snapshot.copy(phase = LoginEmailPhase.PROOF_PENDING, verificationAccepted = true, issue = null)
            request.done(LoginEmailOutcome(issue = issue, verificationAccepted = issue == null))
        }
        fun replyResend(index: Int, length: Int) = onUi {
            val request = resends[index]
            val info = SettingsEmailCodeInfo("s***@example.invalid", length)
            val challenge = request.challenge.copy(revision = request.challenge.revision + 1)
            snapshot = snapshot.copy(challenge = challenge, codeInfo = info)
            request.done(LoginEmailOutcome(codeInfo = info, challenge = challenge))
        }
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
        fun scene() = ComposePixelScene(width, height) {
            val holder = rememberSaveableStateHolder()
            val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
            val window = LocalWindowInfo.current
            val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused = true } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides focused) {
                MiuixTheme(controller = theme) {
                    InterceptPlatformTextInput(ime) {
                        if (mounted) holder.SaveableStateProvider("login-email") {
                            val scroll = if (nested) Modifier.verticalScroll(rememberScrollState()) else Modifier
                            Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).then(scroll)) {
                                LoginEmailSetupPage(snapshot, actions, kind, active = active,
                                    onClose = { closes++ }, onStop = { stops++ }, onExit = { exits++ })
                            }
                        }
                    }
                }
            }
        }
    }

    private class ImeProbe : PlatformTextInputInterceptor {
        val current = AtomicReference<PlatformTextInputMethodRequest?>()
        override suspend fun interceptStartInputMethod(request: PlatformTextInputMethodRequest,
            nextHandler: PlatformTextInputSession): Nothing {
            current.set(request)
            try { awaitCancellation() } finally { current.compareAndSet(request, null) }
        }
        fun edit(vararg commands: EditCommand) {
            val send = { assertNotNull(current.get(), "The actual focused editor needs an input session.").onEditCommand(commands.toList()) }
            if (SwingUtilities.isEventDispatchThread()) send() else SwingUtilities.invokeAndWait(send)
        }
    }
    private fun ComposePixelScene.startCode(f: Fixture, length: Int) {
        settledFrame(); replace(editor(), "setup@example.invalid")
        click(text("发送验证码")); settledFrame()
        assertEquals(1, f.emails.size)
        f.replyEmail(0, length); settledFrame()
        assertNotNull(text("验证码"))
    }
    private fun ComposePixelScene.focusEditor(f: Fixture) {
        click(editor())
        repeat(8) { settledFrame(1, 16); if (f.ime.current.get() != null) return }
        assertNotNull(f.ime.current.get(), "The actual editor must establish an intercepted Compose IME session.")
    }
    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.text(label: String): Rect = assertNotNull(texts(label).firstOrNull(), label)
    private fun ComposePixelScene.editor(): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText) != null
    }.singleOrNull(), "exactly one current private editor")
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
        repeat(50) {
            val candidates = texts(label)
            if (candidates.any { it.width > 0 && it.height >= 12 && it.top >= 8 && it.bottom <= f.height - 8 }) return
            val scroll = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.height > 20 && it.bottom > 0 && it.top < f.height }
            val direction = if (candidates.any { it.width > 0 && it.height > 0 && it.bottom < 8 }) -5f else 5f
            scrollAt(scroll?.center?.x ?: f.width / 2f,
                scroll?.center?.y?.coerceIn(8f, f.height - 8f) ?: f.height * .65f, deltaY = direction)
            settledFrame(5, 32)
        }
        assertTrue(texts(label).any { it.width > 0 && it.height > 0 && it.top >= 8 && it.bottom <= f.height - 8 }, label)
    }
}
