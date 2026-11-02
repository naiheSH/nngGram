package com.badnng.moe

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSearchQuery
import com.badnng.moe.chat.ChatSearchState
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageActions
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.settings.SettingsActions
import com.badnng.moe.settings.SettingsProfile
import com.badnng.moe.settings.SettingsProfileEdit
import com.badnng.moe.settings.SettingsSection
import com.badnng.moe.settings.SettingsSnapshot
import com.badnng.moe.settings.SettingsToken
import com.badnng.moe.settings.SettingsSupportActions
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.ClientStatus
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.RuntimeSnapshot
import com.badnng.moe.status.ProxyStatus
import com.badnng.moe.status.LoginRequestKind
import com.badnng.moe.settings.LoginEmailSnapshot
import com.badnng.moe.settings.LoginEmailSuggestions
import com.badnng.moe.settings.LoginEmailActions
import com.badnng.moe.notifications.DeviceNotificationsSnapshot
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * Uses the real App route, account menu and settings controls. All native replies
 * are synthetic; no preference/configuration reads, account I/O or platform input.
 */
class AppSettingsNavigationRenderTest {
    @Test
    fun supportNavigationWaitsForTheExactNativeTokenAndKeepsThePreviousComposerDraft() {
        val fixture = Fixture(supportMode = true)
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.replace(scene.composer(), "RetainedBeforeSupport")
            scene.openSettings()
            scene.openSupportConfirmation()
            scene.settledFrame().save("app-support-confirmation-inline")
            assertTrue(fixture.supportCalls.isEmpty(), "Showing the confirmation must not resolve an account.")
            scene.click(scene.text("打开支持聊天"))
            scene.settledFrame()
            val call = fixture.supportCalls.single()
            assertTrue(call.active())
            val target = ChatToken(17, call.token.selection + 1, 92_001)
            call.completed(SettingsResult(value = target))
            scene.settledFrame()
            assertNotNull(scene.description("返回聊天"), "An outcome alone must not draw the old conversation as the target.")
            val support = ChatItem(92_001, "Synthetic native support", privateUserId = 92_002)
            fixture.updateChats { it.copy(token = target, selected = support, messages = emptyList(),
                items = listOf(fixture.selected) + it.items, knownChats = it.knownChats + support) }
            scene.settledFrame()
            assertTrue(scene.descriptions("返回聊天").isEmpty())
            assertEquals("", scene.composerText())
            // Prepending a native card preserves the previous LazyColumn key anchor.
            // Reveal it through actual wheel input before clicking under the fixed header.
            repeat(4) {
                scene.scrollAt(220f, 540f, deltaY = -5f)
                scene.settledFrame()
            }
            scene.settledFrame().save("app-support-before-return")
            scene.click(scene.text(fixture.selected.title))
            scene.settledFrame()
            scene.settledFrame().save("app-support-after-return")
            assertEquals(fixture.selected.id, fixture.runtime.chats.selected?.id,
                "The actual list click must select the original native chat; requests=${fixture.selections}, token=${fixture.runtime.chats.token}")
            assertEquals("RetainedBeforeSupport", scene.composerText())
            scene.settledFrame().save("app-support-restored-draft")
            assertTrue(fixture.sent.isEmpty())
            assertTrue(fixture.edited.isEmpty())
        }
    }

    @Test
    fun leavingHelpRevokesSupportAndALateNativeOutcomeCannotDismissAnotherSettingsPage() {
        val fixture = Fixture(supportMode = true)
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.openSettings()
            scene.openSupportConfirmation()
            scene.click(scene.text("打开支持聊天"))
            scene.settledFrame()
            val call = fixture.supportCalls.single()
            scene.click(scene.text("Enhanced Settings"))
            scene.settledFrame()
            assertFalse(call.active())
            call.completed(SettingsResult(value = ChatToken(17, call.token.selection + 1, 92_001)))
            scene.settledFrame()
            assertNotNull(scene.description("返回聊天"))
            assertNotNull(scene.text("此分类暂未启用。"))
            assertEquals(fixture.selected.id, fixture.runtime.chats.token.chatId)
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test
    fun mandatoryUnlockDoesNotReplayAnAlreadyConsumedNotificationJumpOverSettingsDraft() {
        val fixture = Fixture()
        fixture.updateRuntime { it.copy(deviceNotifications = DeviceNotificationsSnapshot(
            generation = 17, openChatRevision = 1)) }
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.openSettings()
            scene.click(scene.description("更改个人资料"))
            scene.settledFrame()
            scene.replace(scene.editable(FIRST_NAME), "RetainedSettingsAcrossEmail")
            fixture.updateRuntime { it.copy(loginEmail = requiredEmail()) }
            scene.settledFrame(2)
            assertNotNull(scene.text("设置登录邮箱"))
            assertTrue(scene.descriptions("返回聊天").isEmpty())
            fixture.updateRuntime { it.copy(loginEmail = LoginEmailSnapshot(generation = 17)) }
            scene.settledFrame()
            assertNotNull(scene.description("返回聊天"), "Unlock must restore the previous settings route.")
            assertNotNull(scene.editable("RetainedSettingsAcrossEmail"))
            assertEquals(1L, fixture.runtime.deviceNotifications.openChatRevision)
            assertTrue(fixture.profileSaves.isEmpty())
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test
    fun mandatoryEmailImmediatelyDisposesTheRealPopupAndRestoresTheUntouchedComposerDraft() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.replace(scene.composer(), "RetainedBeforeMandatoryEmail")
            scene.moveTo(scene.description("贴纸").center.x, scene.description("贴纸").center.y)
            scene.settledFrame()
            assertNotNull(scene.description("贴纸选择面板"))
            fixture.visibility.clear()
            fixture.updateRuntime { it.copy(loginEmail = requiredEmail()) }
            scene.settledFrame(2)
            assertNotNull(scene.text("设置登录邮箱"))
            assertTrue(scene.descriptions("消息输入区域").isEmpty())
            assertTrue(scene.descriptions("贴纸选择面板").isEmpty())
            assertTrue(fixture.visibility.any { !it.active && it.ids.isEmpty() })
            assertTrue(fixture.sent.isEmpty())
            fixture.updateRuntime { it.copy(loginEmail = LoginEmailSnapshot(generation = 17)) }
            scene.settledFrame().save("app-mandatory-email-restored")
            assertEquals("RetainedBeforeMandatoryEmail", scene.composerText())
            assertTrue(fixture.stickersSent.isEmpty())
        }
    }

    @Test
    fun mandatoryEmailUsesTheActualRuntimeEvenWhenPreviewWasSelected() {
        val fixture = Fixture()
        fixture.updateChats { it.copy(enabled = false) }
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.text("查看界面预览"))
            scene.settledFrame()
            assertNotNull(scene.text("返回真实运行状态"))
            fixture.updateRuntime { it.copy(loginEmail = requiredEmail()) }
            scene.settledFrame(2)
            assertNotNull(scene.text("设置登录邮箱"))
            assertTrue(fixture.emailOpens > 0)
            scene.click(scene.text("退出应用"))
            assertEquals(1, fixture.exits)
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test
    fun earlyMandatoryEmailKeepsStopAvailableAndWaitsForActualAuthorizationBeforeOpening() {
        val fixture = Fixture()
        fixture.updateRuntime { it.copy(status = it.status.copy(authorization = AuthorizationPhase.UNKNOWN),
            loginEmail = requiredEmail()) }
        fixture.scene().use { scene ->
            scene.settledFrame()
            assertNotNull(scene.text("正在连接 Telegram……"))
            assertEquals(0, fixture.emailOpens)
            scene.click(scene.text("停止连接"))
            assertEquals(1, fixture.stops)
            fixture.updateRuntime { it.copy(status = it.status.copy(authorization = AuthorizationPhase.READY)) }
            scene.settledFrame()
            assertEquals(1, fixture.emailOpens)
        }
    }

    private fun requiredEmail() = LoginEmailSnapshot(generation = 17, mandatory = true,
        suggestions = LoginEmailSuggestions(revision = 1, requiredPresent = true, requiredEpoch = 1))

    @Test
    fun accountSettingsRoundTripRetainsActualComposerListAndReadingPixels() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.replace(scene.composer(), "RetainedNormalDraft")
            scene.settledFrame()

            repeat(3) {
                scene.scrollAt(220f, 540f, deltaY = 5f)
                scene.settledFrame(8)
            }
            val listBefore = scene.chatRows()
            assertTrue(listBefore.isNotEmpty())
            assertTrue(scene.leftScrollValue() > 0f, "The real chat-list scroll range must move.")
            assertFalse(listBefore.containsKey(fixture.chats.first().title),
                "The test must first move the real LazyColumn away from its initial position.")

            fixture.scrollTimelineToOlder(scene)
            val anchorId = assertNotNull(fixture.anchors.lastOrNull())
            val messageBefore = assertNotNull(fixture.messageBounds[anchorId])
            assertTrue(anchorId < fixture.messages.last().id,
                "The test must preserve a real older reading position, not merely reopen latest.")

            scene.openSettings()
            assertTrue(scene.descriptions("消息输入区域").isEmpty())
            fixture.visibility.clear()
            scene.click(scene.description("返回聊天"))
            scene.settledFrame()

            assertEquals("RetainedNormalDraft", scene.composerText())
            val listAfter = scene.chatRows()
            assertEquals(listBefore.keys, listAfter.keys,
                "App route replacement must retain the exact visible chat rows.")
            for ((title, before) in listBefore) assertClose(before, assertNotNull(listAfter[title]), title)
            assertTrue(anchorId in fixture.restores,
                "The remounted conversation must request its actual recorded native reading anchor.")
            assertClose(messageBefore, assertNotNull(fixture.messageBounds[anchorId]), "restored message")
            assertEquals(fixture.selected.id, fixture.runtime.chats.selected?.id)
            assertTrue(fixture.sent.isEmpty(), "Opening settings cannot submit the retained composer draft.")
        }
    }

    @Test
    fun unsubmittedMessageEditAndSettingsProfileEditSurviveTheRealAppRoute() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.replace(scene.composer(), "UnderlyingNormalDraft")
            val editableMessage = fixture.messages.last()
            val bounds = assertNotNull(fixture.messageBounds[editableMessage.id])
            scene.secondaryClickAt(bounds.center.x, bounds.center.y)
            scene.settledFrame()
            scene.click(scene.text("编辑消息"))
            scene.settledFrame()
            assertEquals(editableMessage.id, fixture.runtime.chats.editTarget?.id)
            scene.replace(scene.composer(), "UnsubmittedMessageEdit")
            scene.settledFrame()

            scene.openSettings()
            scene.click(scene.description("更改个人资料"))
            scene.settledFrame()
            scene.replace(scene.editable(FIRST_NAME), "UnsubmittedSettingsProfile")
            scene.settledFrame()
            assertTrue(fixture.profileSaves.isEmpty())
            scene.click(scene.description("返回聊天"))
            scene.settledFrame()
            assertEquals("UnsubmittedMessageEdit", scene.composerText(),
                "The same native edit target must restore its locally modified rich draft.")
            assertEquals(editableMessage.id, fixture.runtime.chats.editTarget?.id)

            scene.openSettings()
            assertNotNull(scene.editable("UnsubmittedSettingsProfile"),
                "App must own the SettingsWorkspace SaveableStateHolder across chat route disposal.")
            assertTrue(fixture.profileSaves.isEmpty(), "Returning to a form cannot submit it.")
            scene.click(scene.description("返回聊天"))
            scene.settledFrame()
            scene.click(scene.description("取消编辑"))
            scene.settledFrame()
            scene.click(scene.text("放弃修改"))
            scene.settledFrame()
            assertEquals("UnderlyingNormalDraft", scene.composerText(),
                "The separate ordinary draft must remain intact after discarding the retained edit.")
            assertTrue(fixture.edited.isEmpty())
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test
    fun activeSearchQueryAndActualResultScrollSurviveSettingsNavigation() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.description("搜索当前聊天列表"))
            scene.settledFrame()
            scene.replace(scene.description("搜索消息"), "RetainedSearch")
            scene.await("The real search debounce must submit the typed condition.") {
                fixture.searches.lastOrNull()?.text == "RetainedSearch"
            }
            scene.settledFrame()
            assertEquals("RetainedSearch", scene.editableTextAtLeft())
            repeat(3) {
                scene.scrollAt(220f, 540f, deltaY = 5f)
                scene.settledFrame(8)
            }
            val resultsBefore = scene.resultRows()
            assertTrue(resultsBefore.isNotEmpty())
            assertTrue(scene.leftScrollValue() > 0f, "The real search-result scroll range must move.")
            assertFalse(resultsBefore.containsKey(fixture.searchResults.first().title),
                "The test must move the actual result LazyColumn before leaving.")

            scene.openSettings()
            scene.click(scene.description("返回聊天"))
            scene.settledFrame()
            assertNotNull(scene.description("搜索消息"),
                "An open search must return as search, not silently fall back to the chat list.")
            assertEquals("RetainedSearch", scene.editableTextAtLeft())
            val resultsAfter = scene.resultRows()
            assertEquals(resultsBefore.keys, resultsAfter.keys)
            for ((title, before) in resultsBefore) assertClose(before, assertNotNull(resultsAfter[title]), title)
        }
    }

    @Test
    fun settingsDepartureMakesRealNativePopupActionsAndVisibilityReceiptsInactive() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.await("The initial visible incoming messages must establish active read ownership.") {
                fixture.visibility.any { it.active && it.ids.isNotEmpty() }
            }
            val trigger = scene.description("贴纸")
            scene.moveTo(trigger.center.x, trigger.center.y)
            scene.settledFrame()
            val staleCell = scene.description("发送贴纸 🙂")
            assertNotNull(scene.description("贴纸选择面板"))

            scene.openSettings(beforeSettingClick = { fixture.visibility.clear() })
            assertTrue(scene.descriptions("消息输入区域").isEmpty())
            assertTrue(scene.descriptions("贴纸选择面板").isEmpty())
            assertTrue(scene.descriptions("发送贴纸 🙂").isEmpty())
            assertTrue(fixture.visibility.any { !it.active && it.ids.isEmpty() },
                "App's departing timeline must revoke its native visible/read ownership.")
            scene.click(staleCell)
            fixture.updateChats { it.copy(senderNames = it.senderNames + (900L to "Unrelated synthetic sender")) }
            scene.advanceWallClock(420)
            assertTrue(fixture.visibility.none { it.active && it.ids.isNotEmpty() })
            assertTrue(fixture.stickersSent.isEmpty(), "A stale native popup coordinate must never send from settings.")
            scene.moveTo(trigger.center.x, trigger.center.y)
            scene.settledFrame()
            assertTrue(scene.descriptions("贴纸选择面板").isEmpty(),
                "Hover at the outgoing composer coordinates cannot reopen its native popup.")
            assertTrue(fixture.sent.isEmpty())
        }
    }

    private class Fixture(val supportMode: Boolean = false) {
        val selected = ChatItem(10, "Selected synthetic conversation", order = 2_000)
        val chats = List(60) { index -> ChatItem(100L + index, "Synthetic chat ${index.toString().padStart(2, '0')}",
            order = 1_000L - index, lastMessage = ChatMessage(2_000L + index, 100L + index, text = "Synthetic list preview")) }
        val messages = List(100) { index -> ChatMessage(index + 1L, selected.id, senderId = if (index == 99) SELF_ID else 71,
            outgoing = index == 99, date = 1_700_000_000 + index * 60,
            text = "Synthetic timeline message ${index + 1}\nMeasured older reading position") }
        val searchResults = List(60) { index -> ChatItem(5_000L + index,
            "Synthetic result ${index.toString().padStart(2, '0')}", order = 1_000L - index) }
        var runtime by mutableStateOf(RuntimeSnapshot(configPath = "synthetic/settings.properties", generation = 17,
            clientActive = true, status = ClientStatus(EnginePhase.INITIALIZED, AuthorizationPhase.READY, ConnectionPhase.READY),
            proxy = ProxyStatus.DIRECT,
            settings = SettingsSnapshot(token = SettingsToken(17), enabled = true, loaded = setOf(SettingsSection.PROFILE),
                profile = SettingsProfile(SELF_ID, FIRST_NAME, "Profile", "synthetic_profile", "Synthetic biography", "15550001111")),
            chats = ChatSnapshot(enabled = true, token = ChatToken(17, 1, selected.id), selected = selected,
                account = AccountProfile(SELF_ID, "$FIRST_NAME Profile"), items = chats,
                knownChats = listOf(selected) + chats, messages = messages, historyEnd = true, listEnd = true,
                stickers = listOf(StickerItem(501, 601, "🙂",
                    MessageMedia(MediaFile(701, canDownload = false), format = MediaFormat.WEBP))))))
        val anchors = CopyOnWriteArrayList<Long>()
        val restores = CopyOnWriteArrayList<Long>()
        val messageBounds = ConcurrentHashMap<Long, Rect>()
        val visibility = CopyOnWriteArrayList<Visibility>()
        val sent = CopyOnWriteArrayList<ChatToken>()
        val edited = CopyOnWriteArrayList<String>()
        val stickersSent = CopyOnWriteArrayList<Long>()
        val searches = CopyOnWriteArrayList<ChatSearchQuery>()
        val profileSaves = CopyOnWriteArrayList<SettingsProfileEdit>()
        val supportCalls = CopyOnWriteArrayList<SupportCall>()
        val selections = CopyOnWriteArrayList<Long>()
        var emailOpens = 0
        var stops = 0
        var exits = 0
        private val features = ChatFeatureActions(
            visibleMessages = { token, ids, active -> visibility += Visibility(token, ids.toList(), active) },
            rememberHistoryAnchor = { _, id -> anchors += id },
            loadHistoryAnchor = { _, id ->
                restores += id
                updateChats { it.copy(jumpMessageId = id, jumpRevision = it.jumpRevision + 1, jumpHighlight = false) }
            },
            loadMessageActions = { _, id -> updateChats { it.copy(actionsMessageId = id,
                messageActions = MessageActions(canEditText = true), actionsLoading = false) } },
            closeMessageActions = { _ -> updateChats { it.copy(actionsMessageId = null, messageActions = null) } },
            startEdit = { _, id -> updateChats { state -> state.copy(editTarget = state.messages.first { it.id == id },
                editDraftRevision = state.editDraftRevision + 1) } },
            cancelEdit = { _ -> updateChats { it.copy(editTarget = null, editDraftRevision = it.editDraftRevision + 1) } },
            editMessage = { _, text -> edited += text.text },
            search = { _, query, _ ->
                searches += query
                updateChats { it.copy(search = ChatSearchState(query = query,
                    chats = if (query.text.isBlank()) emptyList() else searchResults, revision = it.search.revision + 1)) }
            },
            sendSticker = { _, sticker -> stickersSent += sticker.id },
        )

        fun updateChats(update: (ChatSnapshot) -> ChatSnapshot) {
            updateRuntime { it.copy(chats = update(it.chats)) }
        }

        fun updateRuntime(update: (RuntimeSnapshot) -> RuntimeSnapshot) {
            val apply = { runtime = update(runtime) }
            if (SwingUtilities.isEventDispatchThread()) apply() else SwingUtilities.invokeAndWait(apply)
        }

        fun scene() = ComposePixelScene(WIDTH, HEIGHT) {
            val confirmationHost = if (supportMode) SupportOfflineConfirmation else LocalSettingsSecurityConfirmationHost.current
            CompositionLocalProvider(LocalDensity provides Density(1f),
                LocalSettingsSecurityConfirmationHost provides confirmationHost,
                LocalConversationPreviewMessageBounds provides { id, bounds ->
                    if (bounds == null) messageBounds.remove(id) else messageBounds[id] = bounds
                    Unit
                }) {
                App(runtime = runtime, windowFocused = true, features = features,
                    initialTheme = ColorSchemeMode.Light, persistUiPreferences = false,
                    settingsActions = SettingsActions(saveProfile = { _, edit -> profileSaves += edit },
                        support = SettingsSupportActions(open = { token, active, completed ->
                            supportCalls += SupportCall(token, active, completed)
                            true
                        }),
                        loginEmail = LoginEmailActions(open = { _, _, _, _ -> emailOpens++ })),
                    onSelectChat = { _, id -> if (supportMode) { selections += id; updateChats { state ->
                        state.copy(token = ChatToken(17, state.token.selection + 1, id),
                            selected = state.knownChats.firstOrNull { it.id == id },
                            messages = if (id == selected.id) messages else emptyList())
                    } } },
                    onStop = { stops++ }, onExit = { exits++ },
                    onSendText = { token, _, _ -> sent += token })
            }
        }

        fun scrollTimelineToOlder(scene: ComposePixelScene) {
            val initial = anchors.lastOrNull()
            for (delta in listOf(-4f, 4f)) repeat(4) {
                scene.scrollAt(850f, 340f, deltaY = delta)
                scene.settledFrame(8)
                if (anchors.lastOrNull()?.let { it != initial && it < messages.last().id } == true) return
            }
            assertTrue(anchors.lastOrNull()?.let { it != initial && it < messages.last().id } == true,
                "Actual wheel input must leave the latest reading position.")
        }
    }

    private data class Visibility(val token: ChatToken, val ids: List<Long>, val active: Boolean)
    private data class SupportCall(val token: ChatToken, val active: () -> Boolean,
        val completed: (SettingsResult<ChatToken>) -> Unit)

    /** The real App route and Miuix confirmation controls, hosted inline for the offline scene. */
    private object SupportOfflineConfirmation : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit,
        ) {
            if (show) Column(Modifier.fillMaxWidth()) { content() }
        }
    }

    private fun ComposePixelScene.openSupportConfirmation() {
        repeat(4) {
            scrollAt(220f, 540f, deltaY = 5f)
            settledFrame()
        }
        click(text("帮助"))
        settledFrame()
        click(text("向志愿者提问"))
        settledFrame()
        revealSupportControl("打开支持聊天")
    }

    private fun ComposePixelScene.revealSupportControl(label: String): Rect {
        repeat(24) {
            val bounds = semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
            }.singleOrNull { it.width > 0f && it.height > 0f }
            if (bounds != null && bounds.top >= 90f && bounds.bottom <= HEIGHT - 12f) return bounds
            scrollAt(1_000f, 540f, deltaY = if (bounds != null && bounds.bottom < 90f) -4f else 4f)
            settledFrame(5, 32)
        }
        error("Could not reveal the actual support confirmation control: $label")
    }

    private fun ComposePixelScene.openSettings(beforeSettingClick: () -> Unit = {}) {
        click(description("打开账号菜单"))
        settledFrame()
        beforeSettingClick()
        click(text("设置"))
        settledFrame()
        assertNotNull(description("返回聊天"), "The actual account-menu row must enter the App settings route.")
    }

    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.filter { it.width > 0f && it.height > 0f }

    private fun ComposePixelScene.description(label: String): Rect =
        assertNotNull(descriptions(label).singleOrNull(), "Missing actual App action: $label")

    private fun ComposePixelScene.text(label: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.filter { it.width > 0f && it.height > 0f }.singleOrNull(), "Missing actual App text: $label")

    private fun ComposePixelScene.editable(value: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText)?.text == value
    }.filter { it.width > 0f && it.height > 0f }.singleOrNull(), "Missing actual editable value: $value")

    private fun ComposePixelScene.composer(): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText) != null
    }.filter { it.width > 0f && it.height > 0f && it.right > WIDTH * .7f }.singleOrNull(), "One real conversation editor must remain accessible.")

    private fun ComposePixelScene.composerText(): String {
        val region = description("消息输入区域")
        val values = mutableListOf<String>()
        val bounds = semanticsBounds {
            it.getOrNull(SemanticsProperties.EditableText)?.let { text -> values += text.text; true } ?: false
        }
        val fields = values.zip(bounds)
        // A freshly mounted composer can be hovered by the retained pointer and
        // legitimately reopen the sticker search. Inspect the actual composer
        // subtree's bounds rather than counting every editor in the right pane.
        return assertNotNull(fields.singleOrNull { (_, rect) ->
            rect.width > 0f && rect.height > 0f && region.contains(rect.center)
        }?.first, "Expected one editor inside the actual composer $region; all fields: $fields")
    }

    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val target = File("build/settings-workspace-validation/frames/$name.png")
        target.parentFile.mkdirs()
        assertTrue(ImageIO.write(image, "png", target))
    }

    private fun ComposePixelScene.editableTextAtLeft(): String {
        val values = mutableListOf<String>()
        val bounds = semanticsBounds {
            it.getOrNull(SemanticsProperties.EditableText)?.let { text -> values += text.text; true } ?: false
        }
        val fields = values.zip(bounds)
        return assertNotNull(fields.singleOrNull { it.second.right < 450f && it.second.width > 0f }?.first,
            "Expected one actual left-column editor; all editable values/bounds: $fields")
    }

    private fun ComposePixelScene.chatRows() = labelledRows("Synthetic chat ")
    private fun ComposePixelScene.resultRows() = labelledRows("Synthetic result ")
    private fun ComposePixelScene.leftScrollValue(): Float {
        val values = mutableListOf<Float>()
        val bounds = semanticsBounds {
            it.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range ->
                values += range.value()
                true
            } ?: false
        }
        return values.zip(bounds).filter { it.second.right <= 450f }.maxOfOrNull { it.first } ?: 0f
    }

    private fun ComposePixelScene.labelledRows(prefix: String): Map<String, Rect> {
        val values = mutableListOf<String>()
        val bounds = placedSemanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().firstOrNull { text -> text.text.startsWith(prefix) }
                ?.let { text -> values += text.text; true } ?: false
        }
        return values.zip(bounds).filter { (_, rect) -> rect.width > 0f && rect.height > 5f && rect.top >= 150f && rect.bottom < HEIGHT }
            .toMap()
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun ComposePixelScene.replace(bounds: Rect, value: String) {
        click(bounds)
        settledFrame(2, 16)
        assertEquals(1, focusedEditorValues().size,
            "Pointer input must focus one real editable field before replacement.")
        selectAll()
        settledFrame(2, 16)
        keyStroke(KeyEvent.VK_BACK_SPACE)
        settledFrame(2, 16)
        assertEquals(listOf(""), focusedEditorValues(),
            "The controlled field must process select-all/deletion before receiving new characters.")
        for (character in value) {
            keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character)
            // ImageComposeScene's key input does not run a frame. Recompose each
            // controlled value before the next character reads its current props.
            settledFrame(1, 16)
        }
        settledFrame(2, 16)
        assertEquals(listOf(value), focusedEditorValues())
    }

    private fun ComposePixelScene.focusedEditorValues(): List<String> = buildList {
        unmergedSemanticsBounds {
            if (it.getOrNull(SemanticsProperties.Focused) == true)
                it.getOrNull(SemanticsProperties.EditableText)?.text?.let(::add)
            false
        }
    }

    /** Frame clocks do not advance delay(); wait only for a real bounded debounce. */
    private fun ComposePixelScene.await(message: String, milliseconds: Long = 1_200, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + milliseconds * 1_000_000
        do {
            settledFrame(2, 16)
            if (predicate()) return
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        assertTrue(predicate(), message)
    }

    private fun ComposePixelScene.advanceWallClock(milliseconds: Long) {
        val deadline = System.nanoTime() + milliseconds * 1_000_000
        do {
            settledFrame(2, 16)
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
    }

    private fun assertClose(before: Rect, after: Rect, label: String) {
        assertTrue(abs(before.left - after.left) <= 1f && abs(before.top - after.top) <= 1f &&
            abs(before.right - after.right) <= 1f && abs(before.bottom - after.bottom) <= 1f,
            "$label moved after the real settings route: before=$before after=$after")
    }

    companion object {
        private const val WIDTH = 1_280
        private const val HEIGHT = 800
        private const val SELF_ID = 70_001L
        private const val FIRST_NAME = "Synthetic"
    }
}
