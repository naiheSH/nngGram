package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.badnng.moe.settings.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults

/** Real controls, pointer and keyboard events with entirely synthetic native responses. */
class SettingsSecurityUiTest {
    @Test
    fun unknownSecurityMutationExplainsPendingDisablesWritesAndKeepsReadbackAvailable() {
        val website = SettingsWebsite(41, "synthetic.example.invalid", 73, "Synthetic browser", "Synthetic platform",
            1_700_000_000, 1_700_000_100, "192.0.2.9", "Synthetic location")
        val fixture = Fixture(section = SettingsSecuritySection.WEBSITES)
        fixture.onUi {
            fixture.settings = fixture.settings.copy(security = fixture.settings.security.copy(
                error = SettingsError.RESULT_UNKNOWN, websites = listOf(website), websitesTotalCount = 1))
        }
        fixture.scene().use { scene ->
            scene.settledFrame()
            assertNotNull(scene.text("请求已提交，结果尚未确认；请先刷新状态"))
            scene.click(scene.text("断开 ${website.domain}"))
            scene.settledFrame()
            assertTrue(fixture.websiteRequests.isEmpty(), "An unresolved native write must not open a blind retry flow.")
            assertTrue(scene.texts("断开授权").isEmpty(), "The confirmation action must remain unreachable while the write is pending.")

            scene.click(scene.text("刷新列表"))
            scene.settledFrame()
            assertEquals(1, fixture.refreshes, "An explicit readback must remain available while writes are pending.")
            assertNotNull(scene.text("请求已提交，结果尚未确认；请先刷新状态"),
                "A readback does not release or relabel the unresolved write in the UI.")
        }
    }

    @Test
    fun wrongCurrentPasswordRetainsInputAndPendingSubmissionCannotDuplicate() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.replace(scene.editors().single(), "syntheticsecret")
            assertTrue(scene.passwordEditors() == 1, "The actual editable field must expose password semantics.")
            assertTrue(scene.texts("syntheticsecret").isEmpty(), "The plaintext password must never be drawn as a Text label.")
            scene.click(scene.text("继续"))
            scene.settledFrame()
            assertEquals(1, fixture.requests.size)
            scene.click(scene.text("继续"))
            scene.settledFrame()
            assertEquals(1, fixture.requests.size, "A pending native submission must gate further clicks.")
            fixture.reply(0, SettingsResult(SettingsPasswordOutcome(issue = SettingsPasswordIssue.PASSWORD_INVALID),
                error = SettingsError.INVALID_VALUE))
            scene.settledFrame()
            assertNotNull(scene.text("密码不正确，请重试。"))
            scene.click(scene.text("继续"))
            scene.settledFrame()
            assertEquals(2, fixture.requests.size)
            assertTrue(fixture.requests.last().command.currentPassword == "syntheticsecret",
                "A first verification failure must retain the actual typed password for explicit retry.")
            fixture.reply(1, SettingsResult(SettingsPasswordOutcome(state = state(), verified = true)))
            scene.settledFrame()
            assertNotNull(scene.text("管理两步验证"))
            assertNotNull(scene.text("更改密码"))
        }
    }

    @Test
    fun pendingEmailCodeRejectsSelectionResubmissionAndUnknownPasswordDoesNotUnlockManage() {
        val pending = state(hasPassword = false, codeInfo = SettingsEmailCodeInfo("s***@example.invalid", 4))
        val fixture = Fixture(password = pending)
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.replace(scene.editors().single(), "1234")
            scene.settledFrame()
            assertEquals(1, fixture.requests.size, "Known complete native code length should submit once.")
            assertEquals(SettingsPasswordOperation.CONFIRM_RECOVERY_EMAIL, fixture.requests.single().command.operation)
            assertTrue(fixture.requests.single().command.currentPassword.isEmpty())
            assertTrue(fixture.requests.single().command.email.isEmpty())
            fixture.reply(0, SettingsResult(SettingsPasswordOutcome(state = pending,
                issue = SettingsPasswordIssue.CONFIRMATION_NOT_ACCEPTED), error = SettingsError.INVALID_VALUE))
            scene.settledFrame()
            assertNotNull(scene.text("确认恢复邮箱"))
            scene.keyStroke(KeyEvent.VK_LEFT)
            scene.settledFrame()
            scene.keyStroke(KeyEvent.VK_RIGHT)
            scene.settledFrame()
            assertEquals(1, fixture.requests.size,
                "Selection-only TextFieldValue changes after an incorrect code cannot retry it automatically.")
            scene.click(scene.text("验证"))
            scene.settledFrame()
            assertEquals(2, fixture.requests.size, "The same rejected code still permits an explicit retry.")
            fixture.reply(1, SettingsResult(SettingsPasswordOutcome(state = state(), verified = false)))
            scene.settledFrame()
            assertNotNull(scene.text("输入当前密码"))
            assertTrue(scene.texts("管理两步验证").isEmpty(), "Resumed mailbox confirmation does not grant password knowledge.")
            assertTrue(scene.texts("更改密码").isEmpty())
        }
    }

    @Test
    fun recoveryCodeWithUnknownLengthRequiresManualConfirmationAndDoesNotChangePasswordYet() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.text("忘记密码？"))
            scene.settledFrame()
            assertEquals(SettingsPasswordOperation.REQUEST_RECOVERY, fixture.requests.single().command.operation)
            fixture.reply(0, SettingsResult(SettingsPasswordOutcome(codeInfo = SettingsEmailCodeInfo("s***@example.invalid", 0))))
            scene.settledFrame()
            scene.replace(scene.editors().single(), "54321")
            assertEquals(1, fixture.requests.size, "A native length of zero must retain manual confirmation.")
            scene.click(scene.text("验证"))
            scene.settledFrame()
            assertEquals(SettingsPasswordOperation.VERIFY_RECOVERY_CODE, fixture.requests.last().command.operation)
            fixture.reply(1, SettingsResult(SettingsPasswordOutcome(verified = true)))
            scene.settledFrame()
            assertNotNull(scene.text("设置恢复后的密码"))
            assertEquals(2, scene.passwordEditors())
            assertFalse(fixture.requests.any { it.command.operation == SettingsPasswordOperation.RECOVER_PASSWORD },
                "Checking the recovery code must not itself change or remove the password.")
        }
    }

    @Test
    fun inactiveAndSaveableCategoryRoundTripClearSecretsAndRejectAnOldVerification() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.replace(scene.editors().single(), "syntheticsecret")
            scene.click(scene.text("继续"))
            scene.settledFrame()
            fixture.onUi { fixture.active = false }
            scene.settledFrame()
            assertTrue(fixture.closed.isNotEmpty(), "An inactive page must retire its native security owner.")
            fixture.reply(0, SettingsResult(SettingsPasswordOutcome(state = state(), verified = true)))
            fixture.onUi { fixture.active = true }
            scene.settledFrame()
            assertNotNull(scene.text("输入当前密码"))
            assertTrue(scene.texts("更改密码").isEmpty(), "A late verification cannot reopen management.")
            assertTrue(scene.editorValues().single().isEmpty(), "Inactivity clears the password itself.")

            scene.replace(scene.editors().single(), "anothersecret")
            fixture.onUi { fixture.mounted = false }
            scene.settledFrame()
            fixture.onUi { fixture.mounted = true }
            scene.settledFrame()
            assertTrue(scene.editorValues().single().isEmpty(),
                "The surrounding SaveableStateHolder must not retain password, email or recovery-code forms.")
        }
    }

    @Test
    fun narrowLowPageCanReachItsLastSecurityActionAndBlockedPagesRemainExplicit() {
        val fixture = Fixture(width = 390, height = 360)
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.scrollTo("清空输入并刷新状态", fixture)
            val refresh = scene.text("清空输入并刷新状态")
            assertTrue(refresh.top >= 0 && refresh.bottom <= fixture.height,
                "The actual trailing action must remain reachable in a 390×360 content viewport.")
            scene.click(refresh)
            scene.settledFrame()
            assertEquals(1, fixture.refreshes)
        }
        val blocked = Fixture(width = 390, height = 360, section = SettingsSecuritySection.BLOCKED)
        blocked.onUi {
            blocked.settings = blocked.settings.copy(security = blocked.settings.security.copy(
                blocked = List(3) { SettingsBlockedSender(100L + it, false, "Synthetic sender $it") },
                blockedTotal = 6, blockedEnd = false))
        }
        blocked.scene().use { scene ->
            scene.settledFrame()
            scene.scrollTo("加载更多", blocked)
            scene.click(scene.text("加载更多"))
            scene.settledFrame()
            assertEquals(1, blocked.moreRequests)
            assertTrue(scene.texts("黑名单为空").isEmpty())
        }
    }

    @Test
    fun websitePartialBlockingFailureAndSuccessfulDisconnectWithRefreshFailureStayDistinct() {
        val website = SettingsWebsite(41, "synthetic.example.invalid", 73, "Synthetic browser", "Synthetic platform",
            1_700_000_000, 1_700_000_100, "192.0.2.9", "Synthetic location")
        val fixture = Fixture(section = SettingsSecuritySection.WEBSITES)
        fixture.onUi { fixture.settings = fixture.settings.copy(security = fixture.settings.security.copy(
            websites = listOf(website), websitesTotalCount = 1)) }
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.text("断开 ${website.domain}"))
            scene.settledFrame()
            scene.scrollTo("同时屏蔽该网站的机器人", fixture)
            scene.click(scene.text("同时屏蔽该网站的机器人"))
            scene.settledFrame()
            scene.scrollTo("断开授权", fixture)
            scene.click(scene.text("断开授权"))
            scene.settledFrame()
            assertEquals(1, fixture.websiteRequests.size)
            assertTrue(fixture.websiteRequests.single().second)
            fixture.onUi {
                fixture.settings = fixture.settings.copy(security = fixture.settings.security.copy(websites = emptyList(), websitesTotalCount = 0))
                fixture.websiteRequests.single().third(SettingsResult(SettingsWebsiteDisconnectOutcome(true, false), SettingsError.REQUEST_FAILED))
            }
            scene.settledFrame()
            assertNotNull(scene.text("网站授权已断开，机器人未被屏蔽。"))
            assertTrue(scene.texts("网站授权未能断开，请重试。").isEmpty())
        }
        val all = Fixture(section = SettingsSecuritySection.WEBSITES)
        all.onUi { all.settings = all.settings.copy(security = all.settings.security.copy(websites = listOf(website), websitesTotalCount = 1)) }
        all.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.text("断开所有网站"))
            scene.settledFrame()
            scene.scrollTo("全部断开", all)
            scene.click(scene.text("全部断开"))
            scene.settledFrame()
            assertEquals(1, all.disconnectAll.size)
            all.onUi { all.disconnectAll.single()(SettingsResult(Unit, SettingsError.REQUEST_FAILED)) }
            scene.settledFrame()
            assertNotNull(scene.text("所有网站授权已断开，列表刷新失败，请刷新列表。"))
            assertTrue(all.websiteRequests.isEmpty(), "Disconnecting all sites must never submit optional bot blocking.")
        }
    }

    @Test
    fun lowNarrowResetConfirmationBodyScrollsToItsActualActionWithoutOpeningPlatformWindow() {
        val fixture = Fixture(width = 390, height = 360, password = state().copy(hasPassportData = true))
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.scrollTo("无法使用恢复邮箱", fixture)
            scene.click(scene.text("无法使用恢复邮箱"))
            scene.settledFrame()
            val warning = "该账号含 Telegram Passport 数据；移除或重置密码可能使受保护数据无法恢复。"
            scene.scrollTo(warning, fixture)
            val warningBounds = scene.text(warning)
            assertTrue(warningBounds.height >= 24f, "The actual multiline security explanation must be visible.")
            scene.settledFrame().assertReadableBody(warningBounds, fixture.dialogBackground)
            scene.scrollTo("确认", fixture)
            val confirmation = scene.text("确认")
            assertTrue(confirmation.height > 0 && confirmation.top >= 0 && confirmation.bottom <= fixture.height)
            val frame = scene.settledFrame()
            frame.assertReadableBody(scene.text(warning), fixture.dialogBackground)
            frame.save("reset-confirmation-390x360")
            scene.click(confirmation)
            scene.settledFrame()
            assertEquals(SettingsPasswordOperation.REQUEST_RESET, fixture.requests.single().command.operation)
        }
    }

    private data class PasswordRequest(val command: SettingsPasswordCommand,
        val reply: (SettingsResult<SettingsPasswordOutcome>) -> Unit)

    private class Fixture(val width: Int = 640, val height: Int = 800,
        password: SettingsPasswordState = state(), section: SettingsSecuritySection = SettingsSecuritySection.PASSWORD) {
        var active by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var settings by mutableStateOf(SettingsSnapshot(token = SettingsToken(17, 1), enabled = true,
            loaded = setOf(SettingsSection.PRIVACY), security = SettingsSecuritySnapshot(
                token = SettingsSecurityToken(17, 9), section = section, password = password)))
        val requests = mutableListOf<PasswordRequest>()
        val closed = mutableListOf<SettingsSecurityToken>()
        val websiteRequests = mutableListOf<Triple<SettingsWebsite, Boolean, (SettingsResult<SettingsWebsiteDisconnectOutcome>) -> Unit>>()
        val disconnectAll = mutableListOf<(SettingsResult<Unit>) -> Unit>()
        var refreshes = 0
        var moreRequests = 0
        var dialogBackground = 0
        private val actions = SettingsActions(password = { _, command, result -> requests += PasswordRequest(command, result) },
            closeSecurity = { closed += it }, refreshSecurity = { refreshes++ }, loadMoreBlocked = { moreRequests++ },
            disconnectWebsite = { _, target, block, reply -> websiteRequests += Triple(target, block, reply) },
            disconnectAllWebsites = { _, reply -> disconnectAll += reply })

        fun reply(index: Int, result: SettingsResult<SettingsPasswordOutcome>) = onUi {
            result.value?.state?.let { settings = settings.copy(security = settings.security.copy(password = it)) }
            requests[index].reply(result)
        }

        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }

        fun scene() = ComposePixelScene(width, height) {
            val holder = rememberSaveableStateHolder()
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            val confirmationHost = remember { OfflineConfirmationHost(height.dp) }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsPageActive provides active,
                LocalSettingsSecurityConfirmationHost provides confirmationHost) {
                MiuixTheme(controller = theme) {
                    dialogBackground = DialogDefaults.backgroundColor().toArgb()
                    if (mounted) holder.SaveableStateProvider("privacy") {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState()).padding(16.dp)) {
                            SettingsSecurityPage(settings, actions)
                        }
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }
    private fun ComposePixelScene.text(label: String) = assertNotNull(texts(label).firstOrNull(), label)
    private fun ComposePixelScene.editors() = semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.sortedBy { it.top }
    private fun ComposePixelScene.editorValues(): List<String> = buildList {
        semanticsBounds { config -> config.getOrNull(SemanticsProperties.EditableText)?.text?.let(::add); false }
    }
    private fun ComposePixelScene.passwordEditors() = semanticsBounds { it.getOrNull(SemanticsProperties.Password) != null }.size
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }
    private fun ComposePixelScene.replace(rect: Rect, value: String) {
        click(rect)
        settledFrame(2, 16)
        selectAll()
        settledFrame(2, 16)
        keyStroke(KeyEvent.VK_BACK_SPACE)
        settledFrame(2, 16)
        for (character in value) {
            keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character)
            settledFrame(1, 16)
        }
        settledFrame(3, 16)
    }
    private fun ComposePixelScene.scrollTo(label: String, fixture: Fixture) {
        fun visible() = texts(label).any { it.width > 0 && it.height >= 12 && it.top >= 8 && it.bottom <= fixture.height - 8 }
        fun ranges(): List<Pair<Float, Float>> = buildList {
            semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { add(it.value() to it.maxValue()) }
                false
            }
        }
        val initiallyVisible = visible()
        val initialRanges = ranges()
        var moved = false
        repeat(40) {
            val candidates = texts(label)
            val currentRanges = ranges()
            if (currentRanges.zip(initialRanges).any { (current, original) -> kotlin.math.abs(current.first - original.first) > .5f }) moved = true
            if (visible()) {
                assertTrue(initiallyVisible || moved, "A previously clipped action must be reached through actual scrolling: $label; initial=$initialRanges current=$currentRanges")
                return
            }
            val scrollers = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            val scroll = scrollers.lastOrNull { it.height > 20 && it.bottom > 0 && it.top < fixture.height }
            // A fully clipped node is reported as Rect.Zero, which has no direction.
            // Only real nonempty geometry may mean "above"; otherwise search down.
            val direction = if (candidates.any { it.width > 0 && it.height > 0 && it.bottom < 8 }) -5f else 5f
            scrollAt(scroll?.center?.x ?: fixture.width / 2f,
                scroll?.center?.y?.coerceIn(8f, fixture.height - 8f) ?: fixture.height * .65f, deltaY = direction)
            settledFrame(5, 32)
        }
        assertTrue(visible(), "Action remains unreachable: $label; bounds=${texts(label)}; initial=$initialRanges current=${ranges()}; moved=$moved")
        assertTrue(initiallyVisible || moved, "Reaching a clipped action must move a real scroll range: $label")
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File("build/settings-security-validation/frames/$name.png")
        output.parentFile.mkdirs()
        ImageIO.write(image, "png", output)
    }
    private fun ComposePixelFrame.assertReadableBody(bounds: Rect, background: Int) {
        val left = floor(bounds.left).toInt().coerceIn(0, width - 1)
        val top = floor(bounds.top).toInt().coerceIn(0, height - 1)
        val right = ceil(bounds.right).toInt().coerceIn(left + 1, width)
        val bottom = ceil(bounds.bottom).toInt().coerceIn(top + 1, height)
        var surfacePixels = 0
        var visibleInkPixels = 0
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val encoded = ((color ushr shift) and 255) / 255.0
                return if (encoded <= .04045) encoded / 12.92 else ((encoded + .055) / 1.055).pow(2.4)
            }
            return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        }
        val backdropLuminance = luminance(background)
        for (y in top until bottom) for (x in left until right) {
            val pixel = this[x, y]
            if ((pixel ushr 24) < 250) continue
            val distance = listOf(0, 8, 16).maxOf { shift ->
                abs(((pixel ushr shift) and 255) - ((background ushr shift) and 255))
            }
            if (distance <= 8) surfacePixels++
            val inkLuminance = luminance(pixel)
            val contrast = (max(inkLuminance, backdropLuminance) + .05) / (min(inkLuminance, backdropLuminance) + .05)
            if (distance > 8 && contrast >= 3.0) visibleInkPixels++
        }
        val area = (right - left) * (bottom - top)
        assertTrue(surfacePixels > area / 2, "Security body must have an opaque dialog surface behind its text; surface=$surfacePixels area=$area")
        assertTrue(visibleInkPixels >= 24, "Security explanation needs actual opaque readable glyph pixels with at least 3:1 contrast; ink=$visibleInkPixels")
    }
    private class OfflineConfirmationHost(private val viewportHeight: Dp) : SettingsSecurityConfirmationHost {
        @Composable
        override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().widthIn(max = maxWidth)
                .heightIn(max = (viewportHeight - 32.dp).coerceAtLeast(96.dp))
                .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp)) {
                title?.let { Text(it) }
                summary?.let { Text(it) }
                content()
            }
        }
    }
    companion object {
        private fun state(hasPassword: Boolean = true, codeInfo: SettingsEmailCodeInfo? = null) = SettingsPasswordState(
            hasPassword, "synthetic hint", true, false, codeInfo, "", 0)
    }
}
