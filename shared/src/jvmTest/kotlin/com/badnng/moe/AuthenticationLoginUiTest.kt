package com.badnng.moe

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.status.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class AuthenticationLoginUiTest {
    @Test fun initialQrRequestsNativeOnceAndRealDeviceStateCancelsThroughTheCurrentToken() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.settledFrame()
            assertEquals(listOf(LoginRequestKind.QR_CODE), f.requests.map { it.kind })
            f.update(AuthorizationDetails(AuthorizationPhase.OTHER_STEP, otherStep = LoginOtherStep.DEVICE_CONFIRMATION,
                presentation = LoginPresentation(qrLink = "tg://login?token=synthetic_fixture_1")))
            scene.settledFrame()
            assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("使用手机 Telegram 扫描此二维码登录") }.isNotEmpty())
            scene.click(scene.text("使用手机号登录")); scene.settledFrame()
            assertEquals(listOf(LoginToken(7, 1)), f.cancelled)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun phonePageHasCountrySelectionAndNativeCodeStateReplacesThePhoneForm() {
        val f = Fixture(phone = true)
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.text("USA  ▾")); scene.settledFrame()
            scene.replace(scene.editors().single(), "China"); scene.settledFrame()
            scene.click(scene.text("China")); scene.settledFrame()
            assertTrue("+86" in scene.editorValues())
            scene.replace(scene.editors().last(), "19995550123")
            scene.click(scene.text("下一步")); scene.settledFrame()
            assertEquals(LoginRequestKind.PHONE, f.requests.single().kind)
            assertEquals("+8619995550123", f.requests.single().value)
            f.update(AuthorizationDetails(AuthorizationPhase.WAITING_CODE, LoginCodeDelivery.TELEGRAM, 5,
                presentation = LoginPresentation(phoneNumber = "+8619995550123")))
            scene.settledFrame()
            assertNotNull(scene.text("我们已将验证码发送到你其他设备上的 Telegram。"))
            assertEquals(1, scene.editors().size)
            assertTrue(scene.editorValues().single().isEmpty())
        }
    }

    @Test fun actualLengthAutoSubmitsOnceAndSelectionDoesNotRetryARejectedCode() {
        val f = Fixture(AuthorizationDetails(AuthorizationPhase.WAITING_CODE, LoginCodeDelivery.SMS, 4))
        f.scene().use { scene ->
            scene.settledFrame(); scene.replace(scene.editors().single(), "1234")
            assertEquals(1, f.requests.size)
            f.onUi { f.login = f.login.copy(pending = true) }; scene.settledFrame()
            f.onUi { f.login = f.login.copy(pending = false, error = LoginError.INVALID_CODE) }; scene.settledFrame()
            scene.click(scene.editors().single()); scene.keyStroke(KeyEvent.VK_LEFT); scene.keyStroke(KeyEvent.VK_RIGHT); scene.settledFrame()
            assertEquals(1, f.requests.size)
            scene.click(scene.text("下一步")); scene.settledFrame()
            assertEquals(2, f.requests.size)
        }
    }

    @Test fun unsupportedFirebaseDoesNotExposeOrdinaryOtpAndMissingEmailInfoDoesNotClaimSent() {
        for (challenge in listOf(
            AuthorizationDetails(AuthorizationPhase.WAITING_CODE, LoginCodeDelivery.FIREBASE, 6),
            AuthorizationDetails(AuthorizationPhase.OTHER_STEP, otherStep = LoginOtherStep.EMAIL_CODE),
        )) {
            val f = Fixture(challenge)
            f.scene().use { scene ->
                scene.settledFrame()
                if (challenge.codeDelivery == LoginCodeDelivery.FIREBASE) {
                    assertTrue(scene.editors().isEmpty())
                    assertNotNull(scene.text("当前验证需要官方手机客户端，请先在手机端完成验证。"))
                } else assertNotNull(scene.text("邮箱验证信息暂不可用，请返回手机号重新开始。"))
                assertTrue(f.requests.isEmpty())
            }
        }
    }

    @Test fun passwordSpacesArePreservedAndForgotPasswordUsesTheNativeRecoveryRequest() {
        val f = Fixture(AuthorizationDetails(AuthorizationPhase.WAITING_PASSWORD, hasRecoveryEmail = true,
            presentation = LoginPresentation(passwordHint = "合成提示")))
        f.scene().use { scene ->
            scene.settledFrame(); scene.replace(scene.editors().single(), "  synthetic  ")
            scene.click(scene.text("下一步")); scene.settledFrame()
            assertEquals("  synthetic  ", f.requests.single().value)
            assertTrue(scene.editorValues().single().isEmpty())
            f.onUi { f.login = f.login.copy(error = LoginError.INVALID_PASSWORD) }; scene.settledFrame()
            scene.click(scene.text("忘记密码？")); scene.settledFrame()
            assertEquals(LoginRequestKind.PASSWORD_RECOVERY_REQUEST, f.requests.last().kind)
            f.update(f.login.challenge.copy(presentation = LoginPresentation(recoveryEmailPattern = "f***@example.invalid")))
            scene.settledFrame()
            assertNotNull(scene.text("恢复密码"))
            assertNotNull(scene.text("请输入发送到 f***@example.invalid 的恢复码。"))
        }
    }

    @Test fun officialContentRemainsReachableAcrossThemesDensitiesAndLowWindows() {
        val cases = listOf(Triple(960, 720, 1f), Triple(640, 480, 1.25f), Triple(390, 360, 1.25f))
        for ((width, height, density) in cases) for (dark in listOf(false, true)) {
            val f = Fixture(AuthorizationDetails(AuthorizationPhase.OTHER_STEP, otherStep = LoginOtherStep.DEVICE_CONFIRMATION,
                presentation = LoginPresentation(qrLink = "tg://login?token=synthetic_layout_fixture")), width = width, height = height,
                density = density, dark = dark)
            f.scene().use { scene ->
                scene.settledFrame(); scene.reveal("使用手机号登录", height)
                val button = scene.text("使用手机号登录")
                assertTrue(button.top >= 0 && button.bottom <= height, "Actual phone-login target must be reachable.")
                scene.click(button); scene.settledFrame(); assertEquals(1, f.cancelled.size)
                val frame = scene.settledFrame()
                val folder = Path.of(System.getProperty("nnggram.authentication.screenshots", "build/official-login-validation/screenshots"))
                Files.createDirectories(folder)
                val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
                ImageIO.write(image, "png", folder.resolve("qr-${width}x$height-$density-${if (dark) "dark" else "light"}.png").toFile())
            }
        }
    }

    @Test fun outgoingInactivePageClearsItsPrivateInputAndCannotSubmit() {
        val f = Fixture(phone = true)
        f.scene().use { scene ->
            scene.settledFrame(); scene.replace(scene.editors().last(), "19995550123")
            f.onUi { f.active = false }; scene.settledFrame()
            assertTrue(scene.editorValues().last().isEmpty())
            scene.click(scene.text("下一步")); scene.settledFrame()
            assertTrue(f.requests.isEmpty())
        }
    }

    private class Fixture(challenge: AuthorizationDetails = AuthorizationDetails(AuthorizationPhase.WAITING_LOGIN),
        val phone: Boolean = false, val width: Int = 800, val height: Int = 720,
        val density: Float = 1f, val dark: Boolean = false) {
        var login by mutableStateOf(LoginState(challenge))
        var active by mutableStateOf(true)
        val requests = mutableListOf<Request>()
        val cancelled = mutableListOf<LoginToken>()
        fun update(challenge: AuthorizationDetails) = onUi { login = LoginState(challenge, login.revision + 1) }
        fun onUi(work: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) work() else SwingUtilities.invokeAndWait(work) }
        fun scene() = ComposePixelScene(width, height) {
            val window = LocalWindowInfo.current
            val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused = true } }
            val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides focused) {
                MiuixTheme(controller = theme) {
                    AuthenticationLoginPage(login, LoginToken(7, login.revision), { !login.pending && !login.awaitingAuthorization },
                        { token, kind, value -> requests += Request(token, kind, value) }, { cancelled += it }, initialPhoneMode = phone, active = active)
                }
            }
        }
    }
    private class Request(val token: LoginToken, val kind: LoginRequestKind, val value: String)
    private fun ComposePixelScene.text(label: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    }.firstOrNull(), label)
    private fun ComposePixelScene.editors() = semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }
    private fun ComposePixelScene.editorValues() = buildList {
        semanticsBounds { it.getOrNull(SemanticsProperties.EditableText)?.text?.let(::add); false }
    }
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun ComposePixelScene.replace(bounds: Rect, value: String) {
        click(bounds); settledFrame(2, 16); selectAll(); keyStroke(KeyEvent.VK_BACK_SPACE)
        value.forEach { keyStroke(KeyEvent.getExtendedKeyCodeForChar(it.code), it); settledFrame(1, 16) }
        settledFrame(3, 16)
    }
    private fun ComposePixelScene.reveal(label: String, height: Int) {
        repeat(35) {
            val target = text(label)
            if (target.top >= 8 && target.bottom <= height - 8) return
            scrollAt(160f, height * .65f, deltaY = 4f); settledFrame(4, 32)
        }
        fail("Unreachable $label")
    }
}
