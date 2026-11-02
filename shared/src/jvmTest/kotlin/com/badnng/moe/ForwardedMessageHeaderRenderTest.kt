package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.ForwardSourceIdentity
import com.badnng.moe.chat.MessageForwardInfo
import com.badnng.moe.chat.MessageForwardOrigin
import com.badnng.moe.chat.MessageImportInfo
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ForwardedMessageHeaderRenderTest {
    @Test
    fun nativeForwardAttributionPrecedesTheBodyInsideIncomingAndOutgoingBubbles() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            for (outgoing in listOf(false, true)) {
                val message = ChatMessage(1, 10, outgoing = outgoing, date = 1_700_000_000,
                    text = "合成转发正文仍然可见", forwardInfo = MessageForwardInfo(MessageForwardOrigin.User(42)))
                val snapshot = snapshot(message).copy(senderNames = mapOf(42L to "合成来源用户"))
                var bubble = Rect.Zero
                var sourceInk = 0
                bubbleScene(message, snapshot, dark, density, onHeaderColor = { sourceInk = it }) { bubble = it }.use { scene ->
                    val frame = scene.settledFrame()
                    val header = scene.text("转发自 合成来源用户")
                    val body = scene.text(message.text)
                    assertContained(header, bubble, density)
                    assertContained(body, bubble, density)
                    assertPainted(frame, header, sourceInk)
                    assertTrue(body.top >= header.bottom + 4f * density,
                        "Forward attribution must be visibly above the forwarded message body.")
                    assertEquals(1, scene.labels().count { it.startsWith("转发自 ") },
                        "A native forward must paint exactly one source header, incoming=$outgoing.")
                    scene.assertNoSourceClick("转发自 合成来源用户")
                    save(frame, "${if (dark) "dark" else "light"}-$density-${if (outgoing) "out" else "in"}")
                }
            }
        }
    }

    @Test
    fun longChineseAndEmojiSourcesWrapWithinTheNarrowBubbleWithoutHidingItsBody() {
        val name = "合成来源🐈‍⬛中文名称与表情连续显示📚非常长的来源名称".repeat(4)
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            val message = ChatMessage(1, 10, outgoing = false, date = 1_700_000_000,
                text = "正文继续显示。", forwardInfo = MessageForwardInfo(MessageForwardOrigin.HiddenUser(name)))
            var bubble = Rect.Zero
            bubbleScene(message, snapshot(message), dark, density, widthDp = 280) { bubble = it }.use { scene ->
                val frame = scene.settledFrame()
                val header = scene.text("转发自 $name")
                val body = scene.text(message.text)
                assertTrue(bubble.left >= 0f && bubble.right <= 280f * density + 1f,
                    "Forward source names must never widen a bubble beyond the narrow viewport.")
                assertContained(header, bubble, density)
                assertContained(body, bubble, density)
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                scene.unmergedSemanticsBounds {
                    if (it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == "转发自 $name" })
                        it.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                    false
                }
                assertEquals(2, layouts.single().lineCount,
                    "A long source uses two lines with the host's actual Chinese/emoji font metrics.")
                assertTrue(body.top >= header.bottom + 4f * density,
                    "The header must reserve actual text height before the message body.")
                save(frame, "narrow-${if (dark) "dark" else "light"}-$density")
            }
        }
    }

    @Test
    fun nativeOriginNamesStayDistinctAndOrdinaryMessagesNeverInventAForwardHeader() {
        val variants = listOf(
            MessageForwardOrigin.User(42) to "转发自 当前用户名称",
            MessageForwardOrigin.Chat(-42) to "转发自 当前群名称",
            MessageForwardOrigin.Channel(-42, 123, "签名✍️") to "转发自 当前群名称 (签名✍️)",
            MessageForwardOrigin.HiddenUser("隐私来源🐈") to "转发自 隐私来源🐈",
            MessageForwardOrigin.User(43) to "转发自 原生用户来源",
            MessageForwardOrigin.Chat(-43) to "转发自 原生群来源",
            MessageForwardOrigin.Unknown to "转发的消息",
        )
        val ordinary = ChatMessage(1, 10, outgoing = true, text = "普通消息只显示正文")
        val snapshot = snapshot(ordinary).copy(
            senderNames = mapOf(42L to "当前用户名称"),
            knownChats = listOf(ChatItem(-42, "当前群名称")),
            forwardSourceNames = mapOf(
                ForwardSourceIdentity.User(42) to "已过时用户名称",
                ForwardSourceIdentity.Chat(-42) to "已过时群名称",
                ForwardSourceIdentity.User(43) to "原生用户来源",
                ForwardSourceIdentity.Chat(-43) to "原生群来源",
            ),
        )
        ComposePixelScene(360, 500) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    variants.forEach { (origin, _) ->
                        ForwardedMessageHeader(MessageForwardInfo(origin), snapshot, Modifier.fillMaxWidth())
                        Text("对应的合成正文", modifier = Modifier.padding(bottom = 5.dp))
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            variants.forEach { (_, expected) ->
                scene.text(expected)
                scene.assertNoSourceClick(expected)
            }
            assertFalse(scene.labels().any { it.contains("已过时") },
                "Known native users/chats must take precedence over a stale resolved-source fallback.")
        }
        for (message in listOf(ordinary, ordinary.copy(importInfo = MessageImportInfo("合成导入作者")))) {
            bubbleScene(message, snapshot.copy(messages = listOf(message)), dark = false, density = 1f) {}.use { scene ->
                scene.settledFrame()
                scene.text(message.text)
                assertFalse(scene.labels().any { it.startsWith("转发") },
                    "Forward attribution belongs only to native forwardInfo, never imported metadata or a source-name cache.")
            }
        }
    }

    private fun snapshot(message: ChatMessage) = ChatSnapshot(enabled = true,
        token = ChatToken(1, 1, message.chatId), selected = ChatItem(message.chatId, "合成会话"),
        messages = listOf(message))

    private fun bubbleScene(message: ChatMessage, snapshot: ChatSnapshot, dark: Boolean, density: Float,
        widthDp: Int = 360, onHeaderColor: (Int) -> Unit = {}, onBounds: (Rect) -> Unit): ComposePixelScene =
        ComposePixelScene((widthDp * density).roundToInt(), (360 * density).roundToInt()) {
            val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true)) {
                MiuixTheme(controller = theme) {
                    onHeaderColor(MiuixTheme.colorScheme.primary.toArgb())
                    Box(Modifier.fillMaxSize().background(Color(0xFF72997B)).padding(top = 12.dp)) {
                        MessageBubble(message = message, sender = "合成发送者", showSender = false, endsGroup = true,
                            onBounds = { if (it != null) onBounds(it) }, retrying = false, highlighted = false,
                            read = false, onRetry = {}, snapshot = snapshot, features = ChatFeatureActions(),
                            visible = false, animate = false, onEnlarge = {}, onSave = { _, _ -> },
                            onPlay = {}, playing = false, onStopPlaying = {}, onVideoBounds = {},
                            playbackSession = null, onExpandVideo = {}, menuOpen = false,
                            onMenuOpen = {}, onMenuDismiss = {}, onReply = {}, onQuote = {}, onJumpToReply = {},
                            onReact = {}, onReadDetails = {}, onEdit = {}, onForward = {}, onDelete = {},
                            onSearchSender = {})
                    }
                }
            }
        }

    private fun assertContained(text: Rect, bubble: Rect, density: Float) {
        assertTrue(text.left + 1f >= bubble.left + 11f * density && text.right <= bubble.right - 11f * density + 1f,
            "Actual painted text must keep both bubble gutters: text=$text, bubble=$bubble.")
        assertTrue(text.top + 1f >= bubble.top + 8f * density && text.bottom <= bubble.bottom - 8f * density + 1f,
            "The source header and body must remain inside the bubble vertically.")
    }

    private fun ComposePixelScene.text(label: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.singleOrNull(), "Expected exactly one actual text node: $label")

    private fun ComposePixelScene.labels(): List<String> = buildList {
        semanticsBounds {
            addAll(it.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text })
            false
        }
    }

    private fun ComposePixelScene.assertNoSourceClick(label: String) {
        assertTrue(semanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } &&
                it.getOrNull(SemanticsActions.OnClick) != null
        }.isEmpty(), "A source label must not fabricate a native source-navigation action.")
    }

    private fun assertPainted(frame: ComposePixelFrame, header: Rect, ink: Int) {
        var sourcePixels = 0
        for (y in header.top.toInt().coerceAtLeast(0) until header.bottom.toInt().coerceAtMost(frame.height)) {
            for (x in header.left.toInt().coerceAtLeast(0) until header.right.toInt().coerceAtMost(frame.width)) {
                if (listOf(16, 8, 0).maxOf { shift ->
                    abs((frame[x, y] ushr shift and 255) - (ink ushr shift and 255))
                } <= 25) sourcePixels++
            }
        }
        assertTrue(sourcePixels >= 20,
            "The actual source label must visibly paint its primary-colour text, not only expose semantics.")
    }

    private fun save(frame: ComposePixelFrame, name: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "forwarded-message-header").apply { mkdirs() }
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", File(directory, "$name.png")))
    }
}
