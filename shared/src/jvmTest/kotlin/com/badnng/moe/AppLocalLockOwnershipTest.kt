package com.badnng.moe

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.settings.LocalLockSnapshot
import com.badnng.moe.settings.LocalLockActions
import com.badnng.moe.settings.SettingsActions
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.ClientStatus
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.ProxyStatus
import com.badnng.moe.status.RuntimeSnapshot
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** Real App root and popup with synthetic snapshots and callbacks only. */
class AppLocalLockOwnershipTest {
    @Test
    fun hotLockDisposesPopupAndOldAcceptanceCannotConsumeIdenticalRetypedDraft() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.replace(scene.composer(), "SameDraft")
            scene.click(scene.description("发送消息"))
            scene.settledFrame()
            assertEquals(1, f.sent.size)
            scene.replace(scene.composer(), "")
            scene.replace(scene.composer(), "SameDraft")
            val trigger = scene.description("贴纸")
            scene.moveTo(trigger.center.x, trigger.center.y)
            scene.settledFrame()
            assertNotNull(scene.description("贴纸选择面板"))
            val oldCell = scene.description("发送贴纸 🙂")

            f.onUi { f.runtime = f.runtime.copy(localLock = f.runtime.localLock.copy(phase = LocalLockPhase.HOT_LOCKED, epoch = 2)) }
            scene.settledFrame(2, 16)
            assertNotNull(scene.text("nngGram 已锁定"))
            assertTrue(scene.descriptions("消息输入区域").isEmpty())
            assertTrue(scene.descriptions("贴纸选择面板").isEmpty())
            assertTrue(scene.texts("Sensitive synthetic message").isEmpty())
            scene.click(oldCell)
            assertTrue(f.stickersSent.isEmpty())

            // The already-submitted native acceptance arrives while the page is unmounted.
            f.onUi { f.runtime = f.runtime.copy(chats = f.runtime.chats.copy(acceptedSendId = 1,
                acceptedSendChatId = f.selected.id, acceptedSendText = "SameDraft")) }
            scene.settledFrame(2, 16)
            f.onUi { f.runtime = f.runtime.copy(localLock = f.runtime.localLock.copy(phase = LocalLockPhase.UNLOCKED, epoch = 3)) }
            scene.settledFrame()
            assertEquals("SameDraft", scene.composerText())
            assertEquals(1, f.sent.size, "Unlock never replays the old send.")
            assertTrue(f.stickersSent.isEmpty())
        }
    }

    @Test
    fun coldLockHidesRealRuntimeEvenWhenItsChatSnapshotContainsSensitiveContent() {
        val f = Fixture()
        f.onUi { f.runtime = f.runtime.copy(localLock = LocalLockSnapshot(phase = LocalLockPhase.COLD_LOCKED,
            enabled = true, epoch = 4)) }
        f.scene().use { scene ->
            scene.settledFrame()
            assertNotNull(scene.text("nngGram 已锁定"))
            assertTrue(scene.texts("Sensitive synthetic message").isEmpty())
            assertTrue(scene.descriptions("消息输入区域").isEmpty())
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test
    fun trueAcceptanceDuringLockConsumesItsMatchingDraftOnceAndIsNotReplayed() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.replace(scene.composer(), "AcceptedDraft")
            scene.click(scene.description("发送消息"))
            scene.settledFrame()
            assertEquals(1, f.sent.size)
            f.onUi { f.runtime = f.runtime.copy(localLock = f.runtime.localLock.copy(phase = LocalLockPhase.HOT_LOCKED, epoch = 2)) }
            scene.settledFrame(2, 16)
            f.onUi { f.runtime = f.runtime.copy(chats = f.runtime.chats.copy(acceptedSendId = 1,
                acceptedSendChatId = f.selected.id, acceptedSendText = "AcceptedDraft")) }
            scene.settledFrame(2, 16)
            f.onUi { f.runtime = f.runtime.copy(localLock = f.runtime.localLock.copy(phase = LocalLockPhase.UNLOCKED, epoch = 3)) }
            scene.settledFrame()
            assertEquals("", scene.composerText())
            scene.replace(scene.composer(), "AcceptedDraft")
            f.onUi { f.runtime = f.runtime.copy(chats = f.runtime.chats.copy(listLoading = true)) }
            scene.settledFrame()
            assertEquals("AcceptedDraft", scene.composerText(), "An old acceptance cannot consume a new identical draft.")
            assertEquals(1, f.sent.size)
        }
    }

    @Test
    fun manualLockImmediatelyUnmountsThePageWhileItsNativeProjectionIsPending() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            assertNotNull(scene.description("消息输入区域"))
            scene.click(scene.description("锁定应用"))
            scene.settledFrame(2, 16)
            assertEquals(1, f.lockRequests)
            assertNotNull(scene.text("正在锁定……"))
            assertTrue(scene.descriptions("消息输入区域").isEmpty())
            assertTrue(scene.texts("Sensitive synthetic message").isEmpty())
            f.onUi { f.runtime = f.runtime.copy(localLock = f.runtime.localLock.copy(phase = LocalLockPhase.HOT_LOCKED, epoch = 2)) }
            scene.settledFrame()
            assertNotNull(scene.text("nngGram 已锁定"))
        }
    }

    private class Fixture {
        val selected = ChatItem(10, "Synthetic conversation", order = 2_000)
        var runtime by mutableStateOf(RuntimeSnapshot(
            configPath = "synthetic/settings.properties", generation = 17, clientActive = true,
            status = ClientStatus(EnginePhase.INITIALIZED, AuthorizationPhase.READY, ConnectionPhase.READY),
            proxy = ProxyStatus.DIRECT,
            localLock = LocalLockSnapshot(phase = LocalLockPhase.UNLOCKED, enabled = true, canLockNow = true, epoch = 1),
            chats = ChatSnapshot(enabled = true, token = ChatToken(17, 1, selected.id), selected = selected,
                account = AccountProfile(1, "Synthetic account"), items = listOf(selected), knownChats = listOf(selected),
                messages = listOf(ChatMessage(1, selected.id, senderId = 2, text = "Sensitive synthetic message")),
                historyEnd = true, listEnd = true,
                stickers = listOf(StickerItem(501, 601, "🙂",
                    MessageMedia(MediaFile(701, canDownload = false), format = MediaFormat.WEBP)))),
        ))
        val sent = mutableListOf<Pair<String, Long>>()
        val stickersSent = mutableListOf<Long>()
        var lockRequests = 0
        private val features = ChatFeatureActions(sendSticker = { _, sticker -> stickersSent += sticker.id })
        private val settings = SettingsActions(localLock = LocalLockActions(lockNow = { lockRequests++ }))

        fun scene() = ComposePixelScene(1160, 840) {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                App(runtime = runtime, windowFocused = true, features = features,
                    initialTheme = ColorSchemeMode.Light, persistUiPreferences = false,
                    settingsActions = settings,
                    onSendText = { _, text, revision -> sent += text.text to revision })
            }
        }
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
    }

    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.filter { it.width > 0 && it.height > 0 }
    private fun ComposePixelScene.description(label: String) = assertNotNull(descriptions(label).singleOrNull(), label)
    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.filter { it.width > 0 && it.height > 0 }
    private fun ComposePixelScene.text(label: String) = texts(label).firstOrNull()

    private fun ComposePixelScene.composer(): Rect {
        val region = description("消息输入区域")
        return assertNotNull(semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }
            .singleOrNull { it.width > 0 && it.height > 0 && region.contains(it.center) })
    }

    private fun ComposePixelScene.composerText(): String {
        val region = description("消息输入区域")
        val values = mutableListOf<String>()
        val bounds = semanticsBounds {
            it.getOrNull(SemanticsProperties.EditableText)?.let { text -> values += text.text; true } ?: false
        }
        return assertNotNull(values.zip(bounds).singleOrNull { (_, rect) ->
            rect.width > 0 && rect.height > 0 && region.contains(rect.center)
        }?.first)
    }

    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }
    private fun ComposePixelScene.replace(rect: Rect, value: String) {
        click(rect)
        settledFrame(2, 16)
        selectAll()
        keyStroke(KeyEvent.VK_BACK_SPACE)
        settledFrame(2, 16)
        for (character in value) {
            keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character)
            settledFrame(1, 16)
        }
        settledFrame(2, 16)
    }
}
