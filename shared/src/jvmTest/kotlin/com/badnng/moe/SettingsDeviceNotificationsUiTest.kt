package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.notifications.*
import com.badnng.moe.settings.NotificationPreferences
import com.badnng.moe.settings.NotificationScope
import com.badnng.moe.settings.SettingsActions
import com.badnng.moe.settings.SettingsSection
import com.badnng.moe.settings.SettingsSnapshot
import com.badnng.moe.settings.SettingsToken
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SettingsDeviceNotificationsUiTest {
    @Test fun committedModePersistenceFailureExplainsTheActualModeAndRetriesCurrentPreferences() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val f = Fixture(mode = mode)
            val accepted = f.value.preferences.copy(useWindowsNotifications = true, desktopEnabled = true, volumePercent = 37)
            f.value = f.value.copy(preferences = accepted,
                windows = WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.ENABLED, true),
                error = DeviceNotificationError.MODE_COMMITTED_SAVE_FAILED, modeSavePending = true)
            f.scene().use { scene ->
                scene.settledFrame()
                scene.reveal("重试保存通知偏好", f)
                assertTrue(scene.hasText("当前通知方式尚未保存，重启后可能使用其他通知方式。请重试保存。"))
                assertFalse(scene.hasText("设备通知偏好保存失败，保留原设置。"))
                scene.click(scene.text("重试保存通知偏好")); scene.settledFrame()
                assertEquals(listOf(accepted), f.requests)
                assertTrue(f.value.preferences.useWindowsNotifications)
                assertNull(f.value.error)
            }
        }
    }

    @Test fun persistenceRetryRemainsVisibleAlongsideAnIndependentOutputOrNativeFailure() {
        for (error in listOf(DeviceNotificationError.OUTPUT_FAILED, DeviceNotificationError.NATIVE_FAILED)) {
            val f = Fixture()
            val accepted = f.value.preferences.copy(useWindowsNotifications = true, desktopEnabled = true)
            f.value = f.value.copy(preferences = accepted, modeSavePending = true, error = error,
                windows = WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.ENABLED, true))
            f.scene().use { scene ->
                scene.settledFrame()
                scene.reveal("重试保存通知偏好", f)
                assertTrue(scene.hasText("当前通知方式尚未保存，重启后可能使用其他通知方式。请重试保存。"))
                assertTrue(scene.hasText(if (error == DeviceNotificationError.OUTPUT_FAILED)
                    "系统通知、声音或任务栏闪烁不可用，请重试。" else "原生通知请求失败，请重新启用对应选项重试。"))
                scene.click(scene.text("重试保存通知偏好")); scene.settledFrame()
                assertEquals(listOf(accepted), f.requests)
                assertFalse(f.value.modeSavePending)
            }
        }
    }

    @Test fun nativeBackendUiWaitsForActualEnableAndExposesTheOwnedRemovalWithItsRealTarget() {
        val f = Fixture()
        f.value = f.value.copy(windows = WindowsDeviceNotificationState(WindowsDeviceNotificationStatus.REGISTERED,
            registered = true, executable = "C:/Synthetic App/nngGram.exe"))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.reveal("Windows 原生桌面通知", f)
            scene.click(scene.text("Windows 原生桌面通知"))
            scene.settledFrame()
            assertEquals(listOf<Pair<Long, Boolean>>(7L to true), f.windowsRequests)
            assertFalse(f.value.preferences.useWindowsNotifications, "An OS request is not a successful mode change.")
            SwingUtilities.invokeAndWait { f.value = f.value.copy(windows = f.value.windows.copy(status = WindowsDeviceNotificationStatus.ENABLING)) }
            scene.settledFrame()
            scene.click(scene.text("Windows 原生桌面通知"))
            scene.settledFrame()
            assertEquals(1, f.windowsRequests.size)
            SwingUtilities.invokeAndWait { f.value = f.value.copy(
                preferences = f.value.preferences.copy(useWindowsNotifications = true, desktopEnabled = true),
                windows = f.value.windows.copy(status = WindowsDeviceNotificationStatus.ENABLED, focusBlocked = false)) }
            scene.settledFrame()
            assertTrue(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "应用内通知布局" }
            }.isEmpty(), "System mode must not expose ineffective custom-position/capacity controls.")
            scene.reveal("移除本应用通知注册", f)
            scene.click(scene.text("移除本应用通知注册"))
            scene.settledFrame()
            assertEquals(listOf(7L), f.removalRequests)
        }
    }

    @Test fun realDeviceTogglesCommitIndependentPreferencesAndFailureKeepsAcceptedValue() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.text("应用内通知"))
            scene.settledFrame()
            assertTrue(f.value.preferences.desktopEnabled)
            assertFalse(f.value.preferences.soundEnabled)
            scene.click(scene.text("声音通知"))
            scene.settledFrame()
            assertTrue(f.value.preferences.soundEnabled)
            assertTrue(scene.setVolume(31f))
            scene.settledFrame()
            assertEquals(31, f.value.preferences.volumePercent)
            f.accept = false
            scene.click(scene.text("闪烁任务栏窗口"))
            scene.settledFrame()
            assertTrue(f.requests.last().flashEnabled)
            assertFalse(f.value.preferences.flashEnabled)
            assertTrue(f.value.preferences.desktopEnabled)
            assertTrue(f.value.preferences.soundEnabled)
        }
    }

    @Test fun lowViewportCanReachFolderCountAndPlatformStatusWithoutFakeWindowsSwitch() {
        for (density in listOf(1f, 1.25f, 2f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val f = Fixture(width = 390, height = 480, density = density, mode = mode)
            f.scene().use { scene ->
                val frame = scene.settledFrame()
                assertEquals(255, frame[10, 10] ushr 24, "The actual Miuix host must have a readable opaque background.")
                scene.reveal("文件夹计数包括已静音的聊天", f)
                scene.click(scene.text("文件夹计数包括已静音的聊天"))
                scene.settledFrame()
                assertFalse(f.value.preferences.includeMutedFolders)
                scene.reveal("Windows 原生桌面通知", f)
                assertTrue(scene.text("Windows 原生桌面通知").bottom <= f.height * density)
                assertTrue(scene.reveal("仅发布版 EXE 可用", f).bottom <= f.height * density)
            }
        }
    }

    @Test fun pinnedReminderOnlySavesItsDeviceFieldAndInactiveOrFailedSnapshotsDoNotFakeSuccess() {
        val f = Fixture()
        f.scene(withNotificationSettings = true).use { scene ->
            scene.settledFrame()
            scene.reveal("此设置只影响此设备。关闭后，置顶消息按照普通消息的静音和预览规则处理；桌面通知、声音和任务栏闪烁仍由各自开关控制。", f)
            assertTrue(scene.hasText("此设置只影响此设备。关闭后，置顶消息按照普通消息的静音和预览规则处理；桌面通知、声音和任务栏闪烁仍由各自开关控制。"))
            val original = f.value.preferences
            assertFalse(original.desktopEnabled)
            assertFalse(original.soundEnabled)
            scene.reveal("当有人置顶消息时提醒我", f)
            scene.click(scene.text("当有人置顶消息时提醒我")); scene.settledFrame()
            val disabled = original.copy(notifyAboutPinned = false)
            assertEquals(listOf(disabled), f.requests)
            assertEquals(disabled, f.value.preferences)

            f.accept = false
            scene.click(scene.text("当有人置顶消息时提醒我")); scene.settledFrame()
            assertEquals(listOf(disabled, disabled.copy(notifyAboutPinned = true)), f.requests)
            assertEquals(disabled, f.value.preferences, "A failed persistence callback keeps the visible value unchanged.")
            val requestCount = f.requests.size
            SwingUtilities.invokeAndWait { f.active = false }
            scene.settledFrame()
            scene.click(scene.reveal("当有人置顶消息时提醒我", f)); scene.settledFrame()
            assertEquals(requestCount, f.requests.size, "An inactive settings page cannot save this device preference.")
            assertEquals(disabled, f.value.preferences)
            SwingUtilities.invokeAndWait {
                f.active = true
                f.value = f.value.copy(error = DeviceNotificationError.SAVE_FAILED)
            }
            scene.settledFrame()
            scene.reveal("设备通知偏好保存失败，保留原设置。", f)
            assertTrue(scene.hasText("设备通知偏好保存失败，保留原设置。"))
            assertEquals(disabled, f.value.preferences)
        }
    }

    @Test fun pinnedReminderIsReachableInTheActualNotificationsPageAtWideAndLowLightAndDarkSizes() {
        val cases = listOf(
            Triple(620, 900, ColorSchemeMode.Light),
            Triple(620, 900, ColorSchemeMode.Dark),
            Triple(350, 300, ColorSchemeMode.Light),
            Triple(350, 300, ColorSchemeMode.Dark),
        )
        cases.forEach { (width, height, mode) ->
            val f = Fixture(width = width, height = height, mode = mode)
            f.scene(withNotificationSettings = true).use { scene ->
                scene.settledFrame()
                val bounds = scene.reveal("当有人置顶消息时提醒我", f)
                assertTrue(bounds.top >= 5f * f.density && bounds.bottom < (f.height - 5) * f.density)
                if (width == 350) scene.settledFrame().save("pinned-reminder-offline-${mode.name}-${width}x$height")
                scene.click(bounds); scene.settledFrame()
                assertEquals(listOf(f.value.preferences.copy(notifyAboutPinned = false)), f.requests)
                assertFalse(f.value.preferences.desktopEnabled)
                assertFalse(f.value.preferences.soundEnabled)
            }
        }
    }

    @Test fun actualNotificationCardShowsPrivateContentOnlyWhenProvidedAndClicksUseNativeIdentity() {
        val id = DeviceNotificationIdentity(7, 4, 18)
        var snapshot by mutableStateOf(DeviceNotificationsSnapshot(7, true,
            DeviceNotificationPreferences(desktopEnabled = true), cards = listOf(DeviceNotificationCard(id, "Synthetic native chat", "Synthetic native message", 1))))
        val activated = mutableListOf<DeviceNotificationIdentity>()
        val dismissed = mutableListOf<DeviceNotificationIdentity>()
        ComposePixelScene(640, 480) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                    DeviceNotificationOverlay(snapshot, DeviceNotificationActions(activate = { activated += it }, dismiss = { dismissed += it }))
                }
            }
        }.use { scene ->
            scene.settledFrame()
            assertTrue(scene.hasText("Synthetic native chat"))
            assertTrue(scene.hasText("Synthetic native message"))
            scene.click(scene.text("打开"))
            scene.settledFrame()
            assertEquals(listOf(id), activated)
            scene.click(scene.text("关闭通知"))
            scene.settledFrame()
            assertEquals(listOf(id), dismissed)
            SwingUtilities.invokeAndWait { snapshot = snapshot.copy(cards = listOf(DeviceNotificationCard(id, "nngGram", "你收到了一条新消息", 1))) }
            scene.settledFrame()
            assertFalse(scene.hasText("Synthetic native chat"))
            assertFalse(scene.hasText("Synthetic native message"))
            assertTrue(scene.hasText("你收到了一条新消息"))
            SwingUtilities.invokeAndWait { snapshot = snapshot.copy(error = DeviceNotificationError.NATIVE_FAILED) }
            scene.settledFrame()
            assertTrue(scene.hasText("通知操作失败，请重试。"), "A failed activation remains visible after App returns from settings to chat.")
        }
    }

    private class Fixture(val width: Int = 620, val height: Int = 900, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light) {
        var value by mutableStateOf(DeviceNotificationsSnapshot(generation = 7, enabled = true))
        var active by mutableStateOf(true)
        var accept = true
        val requests = mutableListOf<DeviceNotificationPreferences>()
        val windowsRequests = mutableListOf<Pair<Long, Boolean>>()
        val removalRequests = mutableListOf<Long>()
        private val deviceActions = DeviceNotificationActions(save = { generation, next ->
            assertEquals(7L, generation)
            requests += next
            if (accept) value = value.copy(preferences = next, error = null, modeSavePending = false)
        }, windowsNotifications = { generation, enabled -> windowsRequests += generation to enabled },
            removeWindowsRegistration = { removalRequests += it })

        fun scene(withNotificationSettings: Boolean = false) = ComposePixelScene((width * density).roundToInt(), (height * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active,
                LocalDeviceNotifications provides value, LocalDeviceNotificationActions provides deviceActions) {
                MiuixTheme(controller = theme) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        if (withNotificationSettings) {
                            SettingsNotificationsPage(SettingsSnapshot(token = SettingsToken(7, 1), enabled = true,
                                loaded = setOf(SettingsSection.NOTIFICATIONS),
                                notifications = NotificationScope.entries.associateWith { NotificationPreferences() }),
                                SettingsActions(deviceNotifications = deviceActions))
                        } else SettingsDeviceNotificationsContent(value, deviceActions)
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.hasText(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.any { it.width > 0 && it.height > 0 }
    private fun ComposePixelScene.text(label: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.filter { it.width > 0 && it.height > 0 }.singleOrNull(), label)
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun ComposePixelScene.setVolume(value: Float): Boolean {
        var accepted = false
        semanticsBounds {
            if ("通知音量" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                accepted = it.getOrNull(SemanticsActions.SetProgress)?.action?.invoke(value) == true
            false
        }
        return accepted
    }

    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File("build/settings-pinned-ui-validation/frames/$name.png")
        output.parentFile.mkdirs()
        ImageIO.write(image, "png", output)
    }

    private fun ComposePixelScene.reveal(label: String, f: Fixture): Rect {
        val observed = ArrayDeque<String>()
        repeat(96) {
            val layouts = mutableListOf<TextLayoutResult>()
            val bounds = unmergedSemanticsBounds { config ->
                val match = config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
                if (match) config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                match
            }.singleOrNull()
            val naturalHeight = layouts.singleOrNull()?.size?.height?.toFloat()
            if (observed.size == 8) observed.removeFirst()
            observed.addLast("bounds=$bounds, nativeTextHeight=$naturalHeight")
            // A plain 14sp status row exposes its naturally measured text node; a clickable
            // toggle merges a much taller row. Require the whole native text layout to fit,
            // rather than imposing an invented 20dp minimum on the status label.
            if (bounds != null && naturalHeight != null && naturalHeight > 0 && bounds.width > 0 &&
                bounds.height >= naturalHeight - .5f && bounds.top >= 5f * f.density && bounds.bottom < (f.height - 5) * f.density) {
                println("Notification settings visible: $label at ${f.width}x${f.height}@${f.density}, $bounds, nativeTextHeight=$naturalHeight")
                return bounds
            }
            val above = bounds != null && bounds.width > 0 && bounds.height > 0 && bounds.top < 5f * f.density
            scrollAt((f.width - 30) * f.density, (f.height - 60) * f.density, deltaY = if (above) -2f else 2f)
            settledFrame(4, 64)
        }
        fail("The real device page must show the complete native text for $label at ${f.width}x${f.height}@${f.density}: $observed")
    }
}
