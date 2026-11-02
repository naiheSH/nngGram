package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.media.DownloadDestinationRoots
import com.badnng.moe.media.LocalDownloadDestinationRootsResolver
import com.badnng.moe.settings.NotificationPreferences
import com.badnng.moe.settings.NotificationScope
import com.badnng.moe.settings.PrivacyBase
import com.badnng.moe.settings.PrivacyKey
import com.badnng.moe.settings.PrivacyRule
import com.badnng.moe.settings.PrivacyRuleKind
import com.badnng.moe.settings.PrivacyRules
import com.badnng.moe.settings.ProfileField
import com.badnng.moe.settings.SettingsActions
import com.badnng.moe.settings.SettingsProfile
import com.badnng.moe.settings.SettingsProfileEdit
import com.badnng.moe.settings.SettingsProxy
import com.badnng.moe.settings.SettingsProxyChecksActions
import com.badnng.moe.settings.SettingsProxyChecksLoad
import com.badnng.moe.settings.SettingsProxyChecksToken
import com.badnng.moe.settings.SettingsProxyType
import com.badnng.moe.settings.SettingsSection
import com.badnng.moe.settings.SettingsSnapshot
import com.badnng.moe.settings.SettingsStorage
import com.badnng.moe.settings.SettingsToken
import com.badnng.moe.status.RuntimeSnapshot
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real Compose measurement, rendering, pointer input and in-memory keyboard events only. */
class SettingsWorkspaceRenderTest {
    @Test
    fun actualWorkspacePagesShareTheCenteredBoundedValueColumns() {
        // Exercise the actual routed pages once; the shared row matrix covers theme/size variants.
        val samples = listOf(
            Triple(SettingsDestination.CHAT, "字体家族", "默认（Open Sans）"),
            Triple(SettingsDestination.ADVANCED, "本地媒体缓存", formatSettingsBytes(4_830_000_000L)),
            Triple(SettingsDestination.HELP, "选择附件", "Ctrl+O"),
        )
        for ((destination, label, value) in samples) {
            val fixture = Fixture(destination = destination, height = 1100)
            fixture.scene().use { scene ->
                scene.settledFrame()
                fun childText(text: String) = assertNotNull(scene.unmergedSemanticsBounds {
                    it.getOrNull(SemanticsProperties.Text).orEmpty().any { item -> item.text == text }
                }.singleOrNull { it.width > 0 && it.height > 0 }, "$destination / $text")
                val title = childText(label)
                val current = childText(value)
                val summaryText = when (destination) {
                    SettingsDestination.ADVANCED -> "近似统计 · 12345 个文件"
                    SettingsDestination.HELP -> "在聊天输入框内使用。"
                    else -> null
                }
                val summary = summaryText?.let { text ->
                    assertNotNull(scene.unmergedSemanticsBounds {
                        it.getOrNull(SemanticsProperties.Text).orEmpty().any { item -> item.text == text }
                    }.filter { it.width > 0 && it.height > 0 &&
                        kotlin.math.abs(it.left - title.left) <= 1f && it.top >= title.bottom }
                        .minByOrNull { it.top }, "$destination: summary owned by the current title row")
                }
                val leftBottom = summary?.bottom ?: title.bottom
                assertTrue(kotlin.math.abs((title.top + leftBottom) / 2f - current.center.y) <= 1f,
                    "$destination: value centers against complete title/summary column")
                assertTrue(kotlin.math.abs(current.left - title.right - 16f) <= 1f, "$destination: independent columns")
                assertTrue(kotlin.math.abs(current.width - title.width * .8f) <= 2f,
                    "$destination: short values still own the full bounded right column")
                if (summary != null) {
                    assertTrue(summary.top >= title.bottom + 4f)
                    assertEquals(title.left, summary.left)
                }
            }
        }
    }

    @Test
    fun wideWorkspaceKeepsCategoriesBesideActualSettingsAndDispatchesPreferenceClicks() {
        val fixture = Fixture(height = 600, destination = SettingsDestination.CHAT)
        fixture.scene().use { scene ->
            scene.settledFrame().save("wide-chat-light", fixture)
            val category = scene.tab(SettingsDestination.CHAT)
            val theme = scene.text("深色")
            assertTrue(category.right < theme.left, "The selected category and actual controls must occupy different columns.")
            assertTrue(scene.descriptions("返回设置分类").isEmpty())

            scene.click(theme)
            scene.settledFrame()
            assertEquals(ColorSchemeMode.Dark, fixture.preferences.theme)
            assertEquals(1, fixture.preferenceChanges.size, "The actual theme row dispatches one preference change.")

            scene.scrollToText("按 Ctrl+Enter 键发送消息", fixture)
            scene.click(scene.text("按 Ctrl+Enter 键发送消息"))
            scene.settledFrame()
            assertEquals(SettingsSendKey.CTRL_ENTER, fixture.preferences.sendKey)
            assertEquals(2, fixture.preferenceChanges.size)
            assertEquals(ColorSchemeMode.Dark, fixture.preferenceChanges.last().theme,
                "A later row must copy the current preference instead of restoring an earlier theme.")
            assertTrue(fixture.state.scroll.getValue(SettingsDestination.CHAT).value > 0)
            scene.settledFrame().save("wide-chat-dark-send-key", fixture)
        }
    }

    @Test
    fun narrowCategoryDetailAndBackUseActualControlsAndResizeKeepsTheChosenContent() {
        val fixture = Fixture(width = 390, height = 844, destination = SettingsDestination.CHAT, detailOpen = false)
        fixture.scene(maxWidth = 1040).use { scene ->
            scene.settledFrame().save("narrow-categories", fixture)
            assertTrue(scene.texts("跟随系统").isEmpty(), "The retained detail must leave accessibility while categories are shown.")
            scene.click(scene.tab(SettingsDestination.CHAT))
            scene.settledFrame()
            assertTrue(fixture.state.detailOpen)
            assertTrue(scene.tabs().isEmpty(), "A narrow detail has one current page, without overlaid category hit targets.")
            assertNotNull(scene.text("跟随系统"))
            scene.settledFrame().save("narrow-chat-detail", fixture)

            scene.click(scene.description("返回设置分类"))
            scene.settledFrame()
            assertFalse(fixture.state.detailOpen)
            assertNotNull(scene.tab(SettingsDestination.CHAT))

            fixture.width = 760
            scene.settledFrame()
            assertNotNull(scene.tab(SettingsDestination.CHAT))
            assertNotNull(scene.text("跟随系统"))
            assertTrue(scene.descriptions("返回设置分类").isEmpty())
            fixture.width = 759
            scene.settledFrame()
            assertTrue(scene.texts("跟随系统").isEmpty())
            scene.click(scene.tab(SettingsDestination.CHAT))
            scene.settledFrame()
            fixture.width = 1040
            scene.settledFrame()
            fixture.width = 390
            scene.settledFrame()
            assertEquals(SettingsDestination.CHAT, fixture.state.destination)
            assertTrue(fixture.state.detailOpen)
            assertNotNull(scene.text("跟随系统"))
            scene.assertNoPaintOutsideViewport(fixture)
        }
    }

    @Test
    fun onlyVisibleNativeSectionsLoadAndPlaceholdersNeverIssueRequests() {
        val fixture = Fixture(settings = SettingsSnapshot(token = SettingsToken(17), enabled = true))
        fixture.actions = SettingsActions(load = { token, section ->
            fixture.loads += token to section
            fixture.updateSettings { it.copy(loading = it.loading + section) }
        }, proxyChecks = SettingsProxyChecksActions(open = { token, current, opened ->
            if (current()) {
                fixture.loads += token to SettingsSection.ADVANCED
                fixture.updateSettings { it.copy(loading = it.loading + SettingsSection.ADVANCED) }
                opened(SettingsProxyChecksToken(token, 1))
            }
            SettingsProxyChecksLoad { }
        }))
        fixture.scene().use { scene ->
            scene.settledFrame()
            assertEquals(listOf(SettingsSection.PROFILE), fixture.loads.map { it.second })
            assertNotNull(scene.text("正在读取设置……"))
            scene.settledFrame()
            assertEquals(1, fixture.loads.size, "Recomposition must not poll an in-flight section.")
            fixture.complete(SettingsSection.PROFILE)
            for (destination in listOf(SettingsDestination.NOTIFICATIONS, SettingsDestination.PRIVACY, SettingsDestination.ADVANCED)) {
                scene.click(scene.tab(destination))
                scene.settledFrame()
                assertEquals(destination.section, fixture.loads.last().second)
                fixture.complete(checkNotNull(destination.section))
                scene.settledFrame()
            }
            assertEquals(SettingsSection.entries.toSet(), fixture.loads.map { it.second }.toSet())
            assertEquals(4, fixture.loads.size)
            assertTrue(fixture.loads.all { it.first == SettingsToken(17) })
            for (destination in listOf(SettingsDestination.LANGUAGE, SettingsDestination.POWER,
                SettingsDestination.ENHANCED, SettingsDestination.DEVICES, SettingsDestination.FOLDERS)) {
                scene.click(scene.tab(destination))
                scene.settledFrame()
                assertEquals(4, fixture.loads.size, "$destination has no account-settings load operation.")
                if (destination == SettingsDestination.FOLDERS) {
                    assertTrue(scene.text("创建新文件夹").left > scene.tab(destination).right,
                        "The actual folder overview belongs inside the right column.")
                    scene.settledFrame().save("wide-embedded-native-folders", fixture)
                }
            }
            scene.click(scene.tab(SettingsDestination.PROFILE))
            scene.settledFrame()
            assertEquals(4, fixture.loads.size, "Returning to loaded data must reuse the current snapshot.")
        }
    }

    @Test
    fun narrowClassificationAndInactiveWorkspaceDoNotReadHiddenAccountSections() {
        val fixture = Fixture(width = 390, height = 844, detailOpen = false,
            settings = SettingsSnapshot(token = SettingsToken(17), enabled = true))
        fixture.actions = SettingsActions(load = { token, section ->
            fixture.loads += token to section
            fixture.updateSettings { it.copy(loading = it.loading + section) }
        })
        fixture.scene().use { scene ->
            scene.settledFrame()
            assertTrue(fixture.loads.isEmpty(), "Opening only the narrow category list must not load a hidden profile.")
            scene.click(scene.tab(SettingsDestination.PROFILE))
            scene.settledFrame()
            assertEquals(listOf(SettingsSection.PROFILE), fixture.loads.map { it.second })
            fixture.active = false
            fixture.state.destination = SettingsDestination.PRIVACY
            scene.settledFrame()
            assertEquals(1, fixture.loads.size, "A retained inactive workspace must not start section requests.")
            fixture.active = true
            scene.settledFrame()
            assertEquals(listOf(SettingsSection.PROFILE, SettingsSection.PRIVACY), fixture.loads.map { it.second })
        }
    }

    @Test
    fun unsavedProfileTextSurvivesCategoryNavigationAndContinuousWidthChanges() {
        val fixture = Fixture()
        val saves = mutableListOf<SettingsProfileEdit>()
        fixture.actions = SettingsActions(saveProfile = { _, edit -> saves += edit })
        fixture.scene(height = 900).use { scene ->
            scene.settledFrame()
            scene.click(scene.description("更改个人资料"))
            scene.settledFrame()
            val input = scene.editable(syntheticFirstName)
            scene.click(input)
            scene.settledFrame(2, 16)
            println("Profile editor after focus: ${scene.editableState()}")
            assertEquals(1, scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.EditableText)?.text == syntheticFirstName &&
                    it.getOrNull(SemanticsProperties.Focused) == true
            }.size, "Pointer input must focus the actual field before typing: ${scene.editableState()}")
            scene.selectAll()
            scene.settledFrame(2, 16)
            scene.keyStroke(KeyEvent.VK_BACK_SPACE)
            scene.settledFrame(2, 16)
            println("Profile editor after select-all and deletion: ${scene.editableState()}")
            assertNotNull(scene.editable(""), "The real focused field must process selection/deletion before replacement.")
            for (character in "EditedLocalDraft") {
                scene.keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character)
                // The scene's in-memory key API deliberately does not run a frame. Yield
                // after each edit so the controlled TextField receives its current value.
                scene.settledFrame(1, 16)
            }
            scene.settledFrame()
            println("Profile editor after replacement: ${scene.editableState()}")
            assertNotNull(scene.editable("EditedLocalDraft"))
            assertTrue(saves.isEmpty(), "Typing an account field cannot submit it.")

            scene.click(scene.tab(SettingsDestination.CHAT))
            scene.settledFrame()
            scene.click(scene.tab(SettingsDestination.PROFILE))
            scene.settledFrame()
            assertNotNull(scene.editable("EditedLocalDraft"))
            for (width in listOf(800, 760, 759, 640, 390, 759, 760, 1040)) {
                fixture.width = width
                scene.settledFrame()
                assertNotNull(scene.editable("EditedLocalDraft"), "An unsubmitted field must survive width $width.")
            }
            assertTrue(saves.isEmpty())
            scene.click(scene.text("保存"))
            scene.settledFrame()
            assertEquals(listOf(SettingsProfileEdit(ProfileField.NAME, "EditedLocalDraft", syntheticLastName)), saves)
            scene.settledFrame().save("profile-retained-local-edit", fixture)
        }
    }

    @Test
    fun allRequestedSizesThemesAndDensitiesRenderLongValuesWithinTheirMeasuredViewport() {
        val sizes = listOf(1920 to 1080, 1440 to 900, 1280 to 720, 1024 to 768,
            800 to 600, 640 to 480, 390 to 844, 1600 to 360)
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f, 1.5f, 2f)) for ((index, size) in sizes.withIndex()) {
                val destination = if (index % 2 == 0) SettingsDestination.PROFILE else SettingsDestination.ADVANCED
                val fixture = Fixture(size.first, size.second, density, mode, destination)
                fixture.scene().use { scene ->
                    val frame = scene.settledFrame(4, 120)
                    val sample = "${mode.name.lowercase()}-${size.first}x${size.second}-$density-${destination.name.lowercase()}"
                    scene.assertVisibleTextInsideViewport(fixture, sample)
                    scene.assertNoPaintOutsideViewport(fixture, frame)
                    if (density == 1f || density == 1.25f && size.first == 390 || density == 2f && size.first == 800)
                        frame.save(sample, fixture)
                    if (size.first >= 760) {
                        assertNotNull(scene.tab(destination), sample)
                        assertTrue(scene.descriptions("返回设置分类").isEmpty(), sample)
                    } else {
                        assertTrue(scene.tabs().isEmpty(), sample)
                        assertNotNull(scene.description("返回设置分类"), sample)
                    }
                }
            }
        }
    }

    private class Fixture(
        width: Int = 1040,
        val height: Int = 844,
        val density: Float = 1f,
        mode: ColorSchemeMode = ColorSchemeMode.Light,
        destination: SettingsDestination = SettingsDestination.PROFILE,
        detailOpen: Boolean = true,
        settings: SettingsSnapshot = syntheticSettings(),
    ) {
        var width by mutableStateOf(width)
        var runtime by mutableStateOf(RuntimeSnapshot(configPath = "synthetic/settings.properties",
            generation = 17, settings = settings, chats = ChatSnapshot(enabled = true,
                token = ChatToken(17, 0, null), account = AccountProfile(700001,
                    "$syntheticFirstName $syntheticLastName", username = syntheticUsername),
                folders = listOf(ChatFolderInfo(4, "合成工作文件夹")), listEnd = true)))
        var preferences by mutableStateOf(SettingsPreferences(theme = mode))
        var folderPreferences by mutableStateOf(ChatFolderPreferences())
        var active by mutableStateOf(true)
        var actions = SettingsActions()
        val loads = mutableListOf<Pair<SettingsToken, SettingsSection>>()
        val preferenceChanges = mutableListOf<SettingsPreferences>()
        private val downloads = object : DownloadPreferencesAccess {
            private var value = SettingsDownloadPreferences()
            override suspend fun read(): DownloadPreferencesRead = DownloadPreferencesRead.Value(value)
            override suspend fun write(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences): DownloadPreferencesWrite {
                if (expected != value) return DownloadPreferencesWrite.CONFLICT
                value = desired
                return DownloadPreferencesWrite.SAVED
            }
        }
        val state = SettingsWorkspaceState().apply {
            this.destination = destination
            this.detailOpen = detailOpen
        }

        fun updateSettings(update: (SettingsSnapshot) -> SettingsSnapshot) {
            runtime = runtime.copy(settings = update(runtime.settings))
        }

        fun complete(section: SettingsSection) = updateSettings {
            syntheticSettings().copy(token = it.token, loaded = it.loaded + section, loading = it.loading - section)
        }

        fun scene(maxWidth: Int = width, height: Int = this.height) =
            ComposePixelScene(((maxWidth + guardDp) * density).roundToInt(), ((height + guardDp) * density).roundToInt()) {
                Content()
            }

        @Composable
        private fun Content() {
            val theme = remember(preferences.theme) { ThemeController(preferences.theme) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPreferences provides preferences,
                LocalDownloadPreferencesAccess provides downloads,
                LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(
                    java.nio.file.Path.of("C:/Synthetic/Downloads/nngGram"), java.nio.file.Path.of("C:/Synthetic/Account/downloads-temp/user-700001")) },
                LocalDesktopChatLayout provides desktopChatLayout(width.dp, height.dp)) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(guardColor)) {
                        Box(Modifier.requiredSize(width.dp, height.dp)) {
                            SettingsWorkspace(runtime, actions, ChatFeatureActions(), preferences,
                                onPreferences = { preferenceChanges += it; preferences = it },
                                folderPreferences = folderPreferences, onFolderPreferences = { folderPreferences = it },
                                onClose = {}, onOpenConfig = {}, state = state, active = active)
                        }
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.tabs() = semanticsBounds { it.getOrNull(SemanticsProperties.Role) == Role.Tab }

    private fun ComposePixelScene.tab(destination: SettingsDestination) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == destination.title }
    }.singleOrNull(), "Expected one actual category: ${destination.title}")

    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.filter { it.width > 0f && it.height > 0f }

    private fun ComposePixelScene.text(label: String) = assertNotNull(texts(label).singleOrNull(), "Expected one visible setting: $label")

    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.description(label: String) = assertNotNull(descriptions(label).singleOrNull(), label)

    private fun ComposePixelScene.editable(value: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText)?.text == value
    }.singleOrNull(), "Expected a real editable field containing $value; actual ${editableState()}")

    private fun ComposePixelScene.editableState(): List<String> {
        val values = mutableListOf<String>()
        val bounds = unmergedSemanticsBounds { config ->
            val value = config.getOrNull(SemanticsProperties.EditableText)?.text
            if (value != null) values += "value='$value', focused=${config.getOrNull(SemanticsProperties.Focused)}, " +
                "selection=${config.getOrNull(SemanticsProperties.TextSelectionRange)}"
            value != null
        }
        return values.mapIndexed { index, value -> "$value, bounds=${bounds[index]}" }
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun ComposePixelScene.scrollToText(label: String, fixture: Fixture) {
        fun visible() = texts(label).any {
            it.height >= 20f * fixture.density && it.bottom <= fixture.height * fixture.density
        }
        repeat(18) {
            if (visible()) return
            scrollAt((fixture.width - 50) * fixture.density, (fixture.height - 90) * fixture.density, deltaY = 5f)
            settledFrame(4, 100)
        }
        assertTrue(visible(), "Scrolling must make $label reachable with a visible click target; bounds=${texts(label)}")
    }

    private fun ComposePixelScene.assertVisibleTextInsideViewport(fixture: Fixture, sample: String) {
        val width = fixture.width * fixture.density
        val height = fixture.height * fixture.density
        val bounds = unmergedSemanticsBounds { it.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty() }
            .filter { it.width > .5f && it.height > .5f }
        assertTrue(bounds.isNotEmpty(), "$sample must contain actual rendered text nodes.")
        for (rect in bounds) assertTrue(rect.left >= -.6f && rect.top >= -.6f &&
            rect.right <= width + .6f && rect.bottom <= height + .6f,
            "$sample has a measured visible text node outside the viewport: $rect, viewport ${width}x$height")
    }

    private fun ComposePixelScene.assertNoPaintOutsideViewport(fixture: Fixture, frame: ComposePixelFrame = settledFrame()) {
        val right = ((fixture.width + 6) * fixture.density).roundToInt()
        val bottom = ((fixture.height + 6) * fixture.density).roundToInt()
        val viewportWidth = (fixture.width * fixture.density).roundToInt()
        val viewportHeight = (fixture.height * fixture.density).roundToInt()
        val expected = guardColor.toArgb()
        for (y in 0 until viewportHeight step 7) assertEquals(expected, frame[right, y],
            "Actual controls or long values must not paint into the right viewport guard at y=$y.")
        for (x in 0 until viewportWidth step 7) assertEquals(expected, frame[x, bottom],
            "Actual controls must remain inside the bottom viewport guard at x=$x.")
    }

    private fun ComposePixelFrame.save(name: String, fixture: Fixture) {
        val image = BufferedImage((fixture.width * fixture.density).roundToInt(),
            (fixture.height * fixture.density).roundToInt(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, this[x, y])
        val target = File("build/settings-workspace-validation/frames/$name.png")
        target.parentFile.mkdirs()
        assertTrue(ImageIO.write(image, "png", target), "The representative settings frame must be written as PNG.")
    }

    companion object {
        private const val guardDp = 16
        private val guardColor = Color(0xFFFF00FF)
        private val syntheticFirstName = "SyntheticProfile".repeat(3)
        private val syntheticLastName = "合成布局测试姓名".repeat(5)
        private val syntheticUsername = "synthetic_layout_account_123456"

        private fun syntheticSettings() = SettingsSnapshot(token = SettingsToken(17), enabled = true,
            loaded = SettingsSection.entries.toSet(),
            profile = SettingsProfile(700001, syntheticFirstName, syntheticLastName, syntheticUsername,
                "合成简介用于验证较长文字在设置内容中的自然换行。".repeat(5), "15550001111"),
            notifications = NotificationScope.entries.associateWith { NotificationPreferences() },
            privacy = PrivacyKey.entries.associateWith { PrivacyRules(PrivacyBase.CONTACTS,
                listOf(PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS), PrivacyRule(PrivacyRuleKind.RESTRICT_ALL))) },
            accountTtlDays = 365, storage = SettingsStorage(4_830_000_000L, 12345),
            proxies = listOf(SettingsProxy(3, "synthetic-proxy-with-a-long-native-server-name.".repeat(4) + "example.invalid",
                1080, SettingsProxyType.SOCKS5, true)))
    }
}
