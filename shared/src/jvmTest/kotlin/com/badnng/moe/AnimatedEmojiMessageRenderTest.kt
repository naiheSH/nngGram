package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual transparent source pixels and pointer input; all messages and files are synthetic. */
class AnimatedEmojiMessageRenderTest {
    @Test
    fun nativeAnimatedEmojiUsesItsOwnTransparent112DpCanvasAndKeepsTheSourceAspect() = withTransparentArtwork { path ->
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f)) for (widthDp in listOf(130, 180, 480)) {
                val outgoing = widthDp == 480
                val edge = if (widthDp == 130) 90f else 112f
                val message = emoji(path, outgoing)
                var bubble = Rect.Zero
                scene({ message }, mode, density, widthDp, onBounds = { bubble = it },
                    snapshot = { snapshot(it).copy(senderAvatars = mapOf(SENDER_ID to readyFile(path, 72))) }).use { scene ->
                    val pixels = scene.awaitDecodedEmoji()
                    val canvas = scene.emojiCanvas()
                    val image = assertNotNull(scene.descriptionBounds("动画表情 $EMOJI").singleOrNull())
                    assertClose(edge * density, canvas.width, "Animated emoji keeps its 112dp cap and fits the actual available width.")
                    assertClose(edge * density, canvas.height, "Narrow constraints must shrink both canvas axes together.")
                    assertEquals(canvas, image, "The decoded source belongs to the full transparent emoji canvas.")
                    assertClose(canvas.left, bubble.left, "No ordinary message inset may surround the emoji.")
                    assertClose(canvas.top, bubble.top, "A hidden sender name must not reserve vertical space.")
                    assertClose(canvas.width, bubble.width, "A standalone emoji must not acquire an ordinary bubble minimum.")
                    assertTrue(canvas.right <= widthDp * density + 1f && canvas.left >= 0f)
                    assertTransparentCorners(pixels, canvas)
                    val sourcePixels = greenBounds(pixels, canvas)
                    assertClose(edge * density, sourcePixels.width,
                        "The landscape source should reach the canvas width.", tolerance = 3f)
                    assertClose(edge / 2f * density, sourcePixels.height,
                        "Fit must keep the native source aspect rather than stretching the artwork to a square.", tolerance = 3f)
                    assertTrue(scene.textBounds(SENDER).isEmpty(), "Unwrapped animated emoji suppresses the normal sender header.")
                    assertTrue(scene.textBounds(EMOJI).isEmpty(), "The native emoji string must not be repeated below ready artwork.")
                    assertTrue(scene.textBounds(messageClock(message.date)).isEmpty(), "A settled emoji has no permanent timestamp.")
                    assertTrue(scene.textBounds("其他消息").isEmpty() && scene.textBounds("[其他消息]").isEmpty())
                    if (!outgoing) {
                        val avatar = scene.awaitDescription("聊天图片")
                        assertClose(33f * density, avatar.width, "The existing group-tail avatar keeps its official slot.")
                        assertClose(33f * density, avatar.height, "The avatar is independent of the emoji size.")
                        assertClose(7f * density, canvas.left - avatar.right, "The sender slot keeps its 7dp separation.")
                    }
                    export(pixels, "${modeName(mode)}-${densityName(density)}-${when (widthDp) {
                        130 -> "available90"
                        180 -> "narrow"
                        else -> "wide"
                    }}")
                }
            }
        }
    }

    @Test
    fun hoveringShowsTimeInsideTheExistingCanvasWithoutChangingMessageHeight() = withTransparentArtwork { path ->
        for (outgoing in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            val message = emoji(path, outgoing)
            var bubble = Rect.Zero
            scene({ message }, ColorSchemeMode.Light, density, 480, onBounds = { bubble = it }).use { scene ->
                scene.awaitDecodedEmoji()
                val original = bubble
                val canvas = scene.emojiCanvas()
                val clock = messageClock(message.date)
                assertTrue(scene.textBounds(clock).isEmpty())
                scene.moveTo(canvas.center.x, canvas.center.y)
                val hovered = scene.settledFrame()
                val time = assertNotNull(scene.textBounds(clock).singleOrNull(), "The actual pointer must reveal the emoji's time.")
                assertEquals(original, bubble, "The time is an overlay, so hover cannot insert a footer or move following messages.")
                assertEquals(canvas, scene.emojiCanvas())
                assertTrue(time.left >= canvas.left - 1f && time.right <= canvas.right + 1f &&
                    time.top >= canvas.top - 1f && time.bottom <= canvas.bottom + 1f)
                scene.moveTo(478f * density, 480f * density)
                scene.settledFrame()
                assertTrue(scene.textBounds(clock).isEmpty(), "Leaving the real emoji target must remove its transient time.")
                assertEquals(original, bubble)
                if (!outgoing && density == 1.25f) export(hovered, "hover-time-light-125")
            }
        }
    }

    @Test
    fun missingNativeArtworkFallsBackToTheActualEmojiInA38DpStaticCanvas() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            val message = emoji(null)
            scene({ message }, mode, density, 180).use { scene ->
                val pixels = scene.settledFrame()
                val canvas = scene.emojiCanvas()
                assertClose(38f * density, canvas.width, "LargeEmoji fallback uses its separate static canvas.")
                assertClose(38f * density, canvas.height, "A missing animated asset must not leave an empty 112dp placeholder.")
                assertEquals(1, scene.textBounds(EMOJI).size, "Fallback must retain the actual native emoji string.")
                assertTrue(scene.descriptionBounds("动画表情 $EMOJI").isEmpty())
                assertTrue(scene.textBounds(SENDER).isEmpty())
                assertTrue(scene.textBounds(messageClock(message.date)).isEmpty())
                assertTrue(scene.textBounds("其他消息").isEmpty() && scene.textBounds("[其他消息]").isEmpty(),
                    "A native animated emoji is never an unsupported-message label.")
                fallbackReference(mode, density).use { reference ->
                    val natural = reference.settledFrame()
                    assertCompleteFallbackGlyph(pixels, canvas, natural)
                }
                export(pixels, "fallback-${modeName(mode)}-${densityName(density)}")
            }
        }
    }

    @Test
    fun aSingleCustomEmojiDrawsRectangularNativeArtworkFromTheCanvasTopStart() = withTransparentArtwork { path ->
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            val original = emoji(path)
            val media = checkNotNull(original.media)
            val message = original.copy(media = media.copy(animatedEmoji =
                checkNotNull(media.animatedEmoji).copy(isRegularEmoji = false)))
            val state = mutableStateOf(snapshot(message).copy(
                mediaFiles = mapOf(FILE_ID to MediaFile(FILE_ID, downloading = true)),
                stickerOutlines = mapOf(FILE_ID to StickerOutline("M128 0 L256 64 L128 128 L0 64 Z", 256, 128))))
            scene({ message }, mode, density, 180, snapshot = { state.value }).use { scene ->
                scene.settledFrame()
                val loadingCanvas = scene.emojiCanvas()
                val outline = assertNotNull(scene.descriptionBounds("贴纸加载轮廓").singleOrNull())
                assertClose(112f * density, outline.width, "The native loading outline keeps its 2:1 coordinate aspect.")
                assertClose(56f * density, outline.height, "A custom outline must not stretch to the square cell.")
                assertClose(loadingCanvas.left, outline.left, "Custom outline loading owns the same top-start alignment.")
                assertClose(loadingCanvas.top, outline.top, "Custom outline loading begins at the canvas top.")
                state.value = state.value.copy(mediaFiles = mapOf(FILE_ID to readyFile(path)))
                val pixels = scene.awaitDecodedEmoji(sampleY = .25f)
                val canvas = scene.emojiCanvas()
                val artwork = greenBounds(pixels, canvas)
                assertClose(112f * density, canvas.width, "A single custom emoji retains the official 112dp cell.")
                assertClose(112f * density, canvas.height, "Custom artwork does not change the cell's square geometry.")
                assertClose(canvas.left, artwork.left, "The native rectangular custom artwork starts at the canvas's left edge.")
                assertClose(canvas.top, artwork.top, "Custom emoji artwork starts at the top rather than using regular-emoji centering.")
                assertClose(112f * density, artwork.width, "Custom artwork keeps the source width.", tolerance = 3f)
                assertClose(56f * density, artwork.height, "Custom artwork preserves the native 2:1 source aspect.", tolerance = 3f)
                for (y in (canvas.top + canvas.height * .55f).roundToInt() until canvas.bottom.roundToInt()) {
                    for (x in canvas.left.roundToInt() until canvas.right.roundToInt()) {
                        assertTrue(colorDistance(pixels[x, y], BACKDROP) <= 1,
                            "The unused lower half remains transparent instead of centering or stretching the custom artwork.")
                    }
                }
                assertTransparentCorners(pixels, canvas)
                assertTrue(scene.textBounds(EMOJI).isEmpty() && scene.textBounds(SENDER).isEmpty())
                export(pixels, "custom-top-start-${modeName(mode)}-${densityName(density)}")
            }
        }
    }

    @Test
    fun replyForwardAndReactionsStaySeparateFromTheTransparentEmojiArtwork() = withTransparentArtwork { path ->
        val reactions = listOf("👍", "❤️", "🔥", "🎉", "😁", "🤔").mapIndexed { index, value ->
            MessageReaction(value, index + 1, isChosen = index == 0)
        }
        for (density in listOf(1f, 1.25f)) for (widthDp in listOf(180, 480)) {
            val message = emoji(path).copy(
                replyTo = MessageReply(CHAT_ID, 9, text = REPLY),
                forwardInfo = MessageForwardInfo(MessageForwardOrigin.HiddenUser(FORWARD)),
                reactions = reactions,
            )
            var bubble = Rect.Zero
            scene({ message }, ColorSchemeMode.Dark, density, widthDp, onBounds = { bubble = it }).use { scene ->
                val pixels = scene.awaitDecodedEmoji()
                val canvas = scene.emojiCanvas()
                val reply = assertNotNull(scene.textBounds(REPLY).singleOrNull())
                val forward = assertNotNull(scene.textBounds("转发自 $FORWARD").singleOrNull())
                assertTrue(forward.bottom <= reply.top + 1f && reply.bottom <= canvas.top + 1f,
                    "Native forward attribution and reply preview belong above the unwrapped artwork independently.")
                val chips = scene.unmergedSemanticsBounds {
                    it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.endsWith("条回应") }
                }
                assertEquals(reactions.size, chips.size, "All native reaction controls survive the animated-emoji presentation.")
                chips.forEach { chip ->
                    assertTrue(chip.top >= canvas.bottom - 1f && chip.bottom <= bubble.bottom + 1f,
                        "Reactions belong below the artwork and inside the complete message bounds.")
                    assertTrue(chip.left >= bubble.left - 1f && chip.right <= bubble.right + 1f,
                        "Narrow messages must wrap reaction controls without clipping.")
                }
                if (widthDp == 180) assertTrue(chips.map { it.top.roundToInt() }.distinct().size > 1)
                assertTrue(scene.textBounds(EMOJI).isEmpty() && scene.textBounds(SENDER).isEmpty())
                assertTrue(scene.textBounds(messageClock(message.date)).isEmpty())
                assertTransparentCorners(pixels, canvas)
                export(pixels, "context-dark-${densityName(density)}-${if (widthDp == 180) "narrow" else "wide"}")
            }
        }
    }

    @Test
    fun pendingAndFailedDeliveryShowStatusWithoutRequiringPointerHover() = withTransparentArtwork { path ->
        for (delivery in listOf(MessageDelivery.PENDING, MessageDelivery.FAILED)) {
            val message = emoji(path, outgoing = true).copy(delivery = delivery)
            scene({ message }, ColorSchemeMode.Light, 1.25f, 180).use { scene ->
                val pixels = scene.awaitDecodedEmoji()
                val canvas = scene.emojiCanvas()
                val time = assertNotNull(scene.textBounds(messageClock(message.date)).singleOrNull(),
                    "The native pending/failure state must be visible even without hover.")
                assertTrue(time.top >= canvas.top && time.bottom <= canvas.bottom + 1f)
                assertClose(140f, canvas.width, "Native delivery state cannot change the 112dp artwork canvas.")
                assertTrue(scene.textBounds(EMOJI).isEmpty() && scene.textBounds(SENDER).isEmpty())
                export(pixels, "delivery-${delivery.name.lowercase()}-light-125")
            }
        }
    }

    @Test
    fun onlyTheNativeLatestOutgoingEmojiKeepsItsTimeVisibleOutsideSavedMessages() = withTransparentArtwork { path ->
        val message = emoji(path, outgoing = true)
        val accountId = 19L
        val state = mutableStateOf(snapshot(message).copy(account = AccountProfile(accountId, "Synthetic account")))
        var bubble = Rect.Zero
        scene({ message }, ColorSchemeMode.Light, 1f, 480, snapshot = { state.value },
            onBounds = { bubble = it }).use { scene ->
            scene.awaitDecodedEmoji()
            val original = bubble
            val clock = messageClock(message.date)
            assertTrue(scene.textBounds(clock).isEmpty(),
                "Being the final visible history row does not establish the native chat's last-message identity.")
            state.value = state.value.copy(selected = state.value.selected!!.copy(lastMessage = message.copy(id = 99)))
            scene.settledFrame()
            assertTrue(scene.textBounds(clock).isEmpty(), "An unrelated native latest message cannot make this timestamp permanent.")
            state.value = state.value.copy(selected = state.value.selected!!.copy(lastMessage = message))
            val latest = scene.settledFrame()
            assertEquals(1, scene.textBounds(clock).size,
                "The confirmed native latest outgoing emoji keeps the official timestamp visible without hover.")
            assertEquals(original, bubble)
            state.value = state.value.copy(selected = state.value.selected!!.copy(privateUserId = accountId))
            scene.settledFrame()
            assertTrue(scene.textBounds(clock).isEmpty(), "A confirmed Saved Messages identity suppresses this latest-outgoing exception.")
            state.value = state.value.copy(selected = state.value.selected!!.copy(privateUserId = accountId + 1))
            scene.settledFrame()
            assertEquals(1, scene.textBounds(clock).size, "Another real private user is not the Saved Messages account.")
            state.value = state.value.copy(selected = state.value.selected!!.copy(id = CHAT_ID + 1))
            scene.settledFrame()
            assertTrue(scene.textBounds(clock).isEmpty(), "The native last-message exception also requires the current chat identity.")
            assertEquals(original, bubble, "Every timestamp ownership change remains an overlay.")
            export(latest, "native-latest-outgoing-light-100")
        }
    }

    @Test
    fun nativeDownloadsRequireVisibleActiveOwnershipAndCompletionDoesNotDownloadAgain() = withTransparentArtwork { path ->
        val message = emoji(path).copy(media = emoji(path).media!!.copy(file = MediaFile(FILE_ID, canDownload = true)))
        val state = mutableStateOf(snapshot(message))
        val visible = mutableStateOf(false)
        val active = mutableStateOf(false)
        val outlines = mutableListOf<Int>()
        val transfer = ArtworkDownloadBrokerFixture { id -> state.value.mediaFiles[id] ?: checkNotNull(message.media).file }
        val features = transfer.actions { _, id -> outlines += id }
        transfer.use {
        scene({ message }, ColorSchemeMode.Light, 1f, 180, visible = { visible.value }, active = { active.value },
            features = features, snapshot = { state.value }).use { scene ->
            scene.settledFrame()
            val originalCanvas = scene.emojiCanvas()
            assertTrue(transfer.windows.isEmpty() && transfer.acquired.isEmpty() && outlines.isEmpty(), "Offscreen inactive emoji must not request native resources.")
            visible.value = true
            scene.settledFrame()
            assertTrue(transfer.windows.isEmpty() && transfer.acquired.isEmpty() && outlines.isEmpty(), "Visibility alone cannot authorize downloads on an inactive page.")
            active.value = true
            scene.settledFrame()
            assertEquals(listOf(FILE_ID), transfer.windows.map { it.fileId })
            assertEquals(1, transfer.acquired.size)
            assertEquals(MediaDownloadPurpose.ARTWORK, transfer.acquired.single().purpose)
            assertEquals(listOf(FILE_ID), outlines)
            state.value = state.value.copy(mediaFiles = mapOf(FILE_ID to MediaFile(FILE_ID, downloading = true, canDownload = true)),
                stickerOutlines = mapOf(FILE_ID to StickerOutline("M128 0 L256 64 L128 128 L0 64 Z", 256, 128)))
            scene.settledFrame()
            val outline = assertNotNull(scene.descriptionBounds("贴纸加载轮廓").singleOrNull())
            assertClose(112f, outline.width, "A regular emoji's loading outline retains its native width.")
            assertClose(56f, outline.height, "A regular outline retains its native 2:1 aspect instead of stretching.")
            assertClose(originalCanvas.center.y, outline.center.y, "Regular loading artwork is centered within the emoji cell.")
            active.value = false
            scene.settledFrame()
            active.value = true
            scene.settledFrame()
            assertEquals(listOf(FILE_ID), transfer.windows.map { it.fileId }, "A retired DownloadFile must cross its old acceptance barrier before a replacement can start.")
            assertEquals(2, transfer.acquired.size, "Reactivation must obtain its own live ARTWORK owner even while native progress still says pending.")
            assertEquals(transfer.acquired.first(), transfer.released.single())
            assertTrue(transfer.cancellations.isEmpty(), "Cancellation must wait for the old false-acceptance barrier.")
            transfer.windows.first().accept(ChatResult(state.value.mediaFiles.getValue(FILE_ID)))
            assertTrue(transfer.cancellations.isEmpty(), "A reactivated full-file owner must retain the compatible accepted native range.")
            assertEquals(listOf(FILE_ID), transfer.windows.map { it.fileId }, "Reactivation must share existing native work instead of downloading the same range again.")
            active.value = false
            scene.settledFrame()
            assertEquals(listOf(FILE_ID), transfer.cancellations.toList())
            assertEquals(transfer.acquired.toList(), transfer.released.toList(), "An inactive page must release every artwork owner before cancelling its now-unneeded native range.")
            active.value = true
            scene.settledFrame()
            assertEquals(3, transfer.acquired.size, "Reactivation after cancellation must obtain a fresh live ARTWORK owner.")
            assertEquals(listOf(FILE_ID, FILE_ID), transfer.windows.map { it.fileId }, "After its own retirement, a still-visible owner may resume only after native cancellation is acknowledged.")
            transfer.windows.last().accept(ChatResult(state.value.mediaFiles.getValue(FILE_ID)))
            assertEquals(listOf(FILE_ID), outlines, "An available offline native outline must not be fetched again.")
            state.value = state.value.copy(mediaFiles = mapOf(FILE_ID to readyFile(path)))
            transfer.observe(state.value.mediaFiles.getValue(FILE_ID))
            val ready = scene.awaitDecodedEmoji()
            assertEquals(originalCanvas, scene.emojiCanvas(), "Native file completion must not change the emoji layout.")
            repeat(2) {
                active.value = false
                visible.value = false
                scene.settledFrame()
                active.value = true
                visible.value = true
                scene.settledFrame()
            }
            assertEquals(listOf(FILE_ID, FILE_ID), transfer.windows.map { it.fileId }, "A ready local asset must stay visible without any extra native download.")
            assertEquals(listOf(FILE_ID), outlines)
            assertTrue(isGreen(ready[originalCanvas.center.x.roundToInt(), originalCanvas.center.y.roundToInt()]))
        }
        }
    }

    @Test
    fun aFailedLocalDecodeKeepsTheRealEmojiWithoutAnAutomaticDownloadRetry() {
        val path = Files.createTempFile("nnggram-animated-emoji-invalid-", ".webp")
        try {
            Files.writeString(path, "synthetic invalid animated-emoji image")
            val message = emoji(path)
            val downloads = mutableListOf<Int>()
            val active = mutableStateOf(true)
            scene({ message }, ColorSchemeMode.Dark, 1.25f, 180,
                features = ChatFeatureActions(downloadFile = { _, id -> downloads += id }), active = { active.value }).use { scene ->
                repeat(16) { scene.settledFrame(2); Thread.sleep(5) }
                val canvas = scene.emojiCanvas()
                assertEquals(1, scene.textBounds(EMOJI).size, "Decode failure preserves the native text rather than a generic failure label.")
                assertTrue(scene.descriptionBounds("动画表情 $EMOJI").isEmpty())
                assertTrue(scene.textBounds("媒体暂时无法显示").isEmpty() && scene.textBounds("[其他消息]").isEmpty())
                assertClose(140f, canvas.width, "Decode failure retains its already reserved native-media canvas.")
                active.value = false
                scene.settledFrame()
                active.value = true
                val pixels = scene.settledFrame()
                assertEquals(canvas, scene.emojiCanvas())
                assertTrue(downloads.isEmpty(), "A completed immutable local source cannot enter an automatic re-download loop.")
                export(pixels, "decode-failure-dark-125")
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun unusableCachedNativeOutlinesKeepTheRealGlyphWithoutRepeatedResourceRequests() {
        val message = emoji(null).copy(media = MessageMedia(MediaFile(FILE_ID, downloading = true, canDownload = true),
            width = 512, height = 256, format = MediaFormat.WEBP, emoji = EMOJI,
            animatedEmoji = AnimatedEmojiMedia(512, 256, 0, isRegularEmoji = true)))
        val unusable = listOf(
            StickerOutline("", 256, 128),
            StickerOutline("synthetic invalid SVG path", 256, 128),
            StickerOutline("M0 0 L256 0 L256 128 Z", 0, 128),
            StickerOutline("M0 0 L256 0 L256 128 Z", 256, 0),
        )
        for ((index, outline) in unusable.withIndex()) {
            val lookups = mutableListOf<Int>()
            val active = mutableStateOf(true)
            val state = snapshot(message).copy(stickerOutlines = mapOf(FILE_ID to outline))
            val transfer = ArtworkDownloadBrokerFixture { id -> state.mediaFiles[id] ?: checkNotNull(message.media).file }
            transfer.seedPendingUser(state.token, FILE_ID)
            transfer.use {
            scene({ message }, ColorSchemeMode.Dark, 1.25f, 180, snapshot = { state }, active = { active.value },
                features = transfer.actions { _, id -> lookups += id }).use { scene ->
                var pixels = scene.settledFrame()
                val canvas = scene.emojiCanvas()
                assertEquals(1, scene.textBounds(EMOJI).size, "An unusable cached outline must show the actual native emoji.")
                assertTrue(scene.descriptionBounds("贴纸加载轮廓").isEmpty(), "Invalid outline data cannot leave an empty outline canvas.")
                fallbackReference(ColorSchemeMode.Dark, 1.25f).use { reference ->
                    assertCompleteFallbackGlyph(pixels, canvas, reference.settledFrame())
                }
                repeat(2) {
                    active.value = false
                    scene.settledFrame()
                    active.value = true
                    pixels = scene.settledFrame()
                }
                assertEquals(1, scene.textBounds(EMOJI).size)
                assertTrue(lookups.isEmpty(), "A cached unusable outline is already a completed native lookup, so reactivation cannot retry it.")
                assertEquals(1, transfer.windows.size, "Fallback may join the real pending transfer but cannot start a second native range.")
                assertEquals(3, transfer.acquired.size, "Each visible reentry must own its actual ARTWORK lease.")
                assertEquals(2, transfer.released.size, "Each inactive interval retires only the UI's preceding ARTWORK lease.")
                assertTrue(transfer.acquired.all { it.purpose == MediaDownloadPurpose.ARTWORK })
                assertTrue(transfer.cancellations.isEmpty(), "Fallback lifecycle cannot cancel the independent pending USER owner.")
                if (index == 0) export(pixels, "empty-outline-fallback-dark-125")
            }
            }
        }
    }

    private fun scene(message: () -> ChatMessage, mode: ColorSchemeMode, density: Float, widthDp: Int,
        visible: () -> Boolean = { true }, active: () -> Boolean = { false },
        features: ChatFeatureActions = ChatFeatureActions(), snapshot: (ChatMessage) -> ChatSnapshot = ::snapshot,
        onBounds: (Rect) -> Unit = {}) =
        ComposePixelScene((widthDp * density).roundToInt(), (500 * density).roundToInt()) {
            val controller = remember { ThemeController(mode) }
            val current = message()
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true)) {
                MiuixTheme(controller = controller) {
                    Box(Modifier.fillMaxSize().background(Color(BACKDROP)).padding(top = 24.dp)) {
                        Bubble(current, snapshot(current), features, visible(), active(), onBounds)
                    }
                }
            }
        }

    @Composable
    private fun Bubble(message: ChatMessage, snapshot: ChatSnapshot, features: ChatFeatureActions,
        visible: Boolean, active: Boolean, onBounds: (Rect) -> Unit) {
        MessageBubble(message = message, sender = SENDER, showSender = true, endsGroup = true,
            onBounds = { if (it != null) onBounds(it) }, retrying = false, highlighted = false,
            read = false, onRetry = {}, snapshot = snapshot, features = features,
            visible = visible, animate = active, onEnlarge = {}, onSave = { _, _ -> }, onPlay = {},
            playing = false, onStopPlaying = {}, onVideoBounds = {}, playbackSession = null,
            onExpandVideo = {}, menuOpen = false, onMenuOpen = {}, onMenuDismiss = {},
            onReply = {}, onQuote = {}, onJumpToReply = {}, onReact = {}, onReadDetails = {},
            onEdit = {}, onForward = {}, onDelete = {}, onSearchSender = {})
    }

    private fun emoji(path: Path?, outgoing: Boolean = false) = ChatMessage(
        1, CHAT_ID, senderId = SENDER_ID, outgoing = outgoing, date = 1_700_000_000,
        text = EMOJI, kind = MessageKind.ANIMATED_EMOJI,
        media = path?.let { MessageMedia(readyFile(it), width = 512, height = 256, format = MediaFormat.WEBP,
            emoji = EMOJI, animatedEmoji = AnimatedEmojiMedia(512, 256, 0, isRegularEmoji = true)) },
    )

    private fun snapshot(message: ChatMessage) = ChatSnapshot(enabled = true, token = ChatToken(1, 1, CHAT_ID),
        selected = ChatItem(CHAT_ID, "Synthetic emoji chat", basicGroupId = 2), messages = listOf(message),
        senderNames = mapOf(SENDER_ID to SENDER))

    private fun readyFile(path: Path, id: Int = FILE_ID) = MediaFile(id, localPath = path.toString(), downloaded = true)

    private fun ComposePixelScene.emojiCanvas() = assertNotNull(descriptionBounds("独立表情画布").singleOrNull())

    private fun ComposePixelScene.descriptionBounds(description: String) = unmergedSemanticsBounds {
        description in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.textBounds(text: String) = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }

    private fun ComposePixelScene.awaitDecodedEmoji(sampleY: Float = .5f): ComposePixelFrame {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            val frame = settledFrame(2)
            val bounds = descriptionBounds("动画表情 $EMOJI").singleOrNull()
            if (bounds != null && isGreen(frame[bounds.center.x.roundToInt(),
                    (bounds.top + bounds.height * sampleY).roundToInt()])) return frame
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("The synthetic transparent animated-emoji image did not decode.")
    }

    private fun ComposePixelScene.awaitDescription(description: String): Rect {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            settledFrame(2)
            descriptionBounds(description).singleOrNull()?.let { return it }
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("The synthetic $description image did not decode.")
    }

    private fun greenBounds(frame: ComposePixelFrame, within: Rect): Rect {
        return colorBounds(frame, within, ::isGreen)
    }

    private fun colorBounds(frame: ComposePixelFrame, within: Rect, predicate: (Int) -> Boolean): Rect {
        var left = frame.width
        var top = frame.height
        var right = -1
        var bottom = -1
        for (y in within.top.roundToInt() until within.bottom.roundToInt()) {
            for (x in within.left.roundToInt() until within.right.roundToInt()) if (predicate(frame[x, y])) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        assertTrue(right >= left && bottom >= top, "The test must inspect actual rendered color coverage.")
        return Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    }

    private fun fallbackReference(mode: ColorSchemeMode, density: Float) =
        ComposePixelScene((160 * density).roundToInt(), (160 * density).roundToInt()) {
            val controller = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = controller) {
                    Box(Modifier.fillMaxSize().background(Color(BACKDROP)).padding(24.dp)) {
                        // This independent, roomy reference has no emoji canvas or fitting layout.
                        Text(EMOJI, fontSize = 36.sp, lineHeight = 38.sp, maxLines = 1,
                            softWrap = false, overflow = TextOverflow.Visible)
                    }
                }
            }
        }

    private fun assertCompleteFallbackGlyph(frame: ComposePixelFrame, canvas: Rect, natural: ComposePixelFrame) {
        val naturalRegion = Rect(0f, 0f, natural.width.toFloat(), natural.height.toFloat())
        val ink = colorBounds(frame, canvas) { colorDistance(it, BACKDROP) > 12 }
        val naturalInk = colorBounds(natural, naturalRegion) { colorDistance(it, BACKDROP) > 12 }
        assertTrue(ink.left > canvas.left && ink.top > canvas.top &&
            ink.right < canvas.right && ink.bottom < canvas.bottom,
            "All four canvas edges must retain background around the complete fallback glyph: ink=$ink, canvas=$canvas")
        for (x in canvas.left.roundToInt() until canvas.right.roundToInt()) {
            assertTrue(colorDistance(frame[x, canvas.top.roundToInt()], BACKDROP) <= 3 &&
                colorDistance(frame[x, canvas.bottom.roundToInt() - 1], BACKDROP) <= 3,
                "Fallback glyph ink cannot be cropped by the canvas's top or bottom edge.")
        }
        for (y in canvas.top.roundToInt() until canvas.bottom.roundToInt()) {
            assertTrue(colorDistance(frame[canvas.left.roundToInt(), y], BACKDROP) <= 3 &&
                colorDistance(frame[canvas.right.roundToInt() - 1, y], BACKDROP) <= 3,
                "Fallback glyph ink cannot be cropped by the canvas's left or right edge.")
        }
        assertClose(naturalInk.width / naturalInk.height, ink.width / ink.height,
            "Fitting must retain the actual natural glyph aspect.", tolerance = .08f)
        for ((label, predicate) in listOf("yellow face" to ::isYellow, "blue tears" to ::isBlue)) {
            val actualColor = colorBounds(frame, canvas, predicate)
            val referenceColor = colorBounds(natural, naturalRegion, predicate)
            val actualFraction = colorFraction(frame, ink, predicate)
            val referenceFraction = colorFraction(natural, naturalInk, predicate)
            assertTrue(actualFraction > .03f && referenceFraction > .03f,
                "The comparison must observe the real $label pixels.")
            assertClose(referenceFraction, actualFraction,
                "The complete $label must retain its coverage relative to the natural glyph.", tolerance = .08f)
            fun relative(bounds: Rect, complete: Rect) = listOf(
                (bounds.left - complete.left) / complete.width,
                (bounds.top - complete.top) / complete.height,
                (bounds.right - complete.left) / complete.width,
                (bounds.bottom - complete.top) / complete.height,
            )
            relative(actualColor, ink).zip(relative(referenceColor, naturalInk)).forEach { (actual, reference) ->
                assertClose(reference, actual, "The $label boundary must survive fitting in all directions.", tolerance = .08f)
            }
        }
    }

    private fun colorFraction(frame: ComposePixelFrame, bounds: Rect, predicate: (Int) -> Boolean): Float {
        var matches = 0
        var total = 0
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt()) {
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) {
                total++
                if (predicate(frame[x, y])) matches++
            }
        }
        return matches.toFloat() / total.coerceAtLeast(1)
    }

    private fun assertTransparentCorners(frame: ComposePixelFrame, bounds: Rect) {
        for ((x, y) in listOf(bounds.left + 4f to bounds.top + 4f, bounds.right - 5f to bounds.top + 4f,
            bounds.left + 4f to bounds.bottom - 5f, bounds.right - 5f to bounds.bottom - 5f)) {
            assertTrue(colorDistance(frame[x.roundToInt(), y.roundToInt()], BACKDROP) <= 1,
                "Transparent emoji pixels must show the real backdrop without any message or media-card fill.")
        }
    }

    private fun withTransparentArtwork(test: (Path) -> Unit) {
        val path = Files.createTempFile("nnggram-animated-emoji-", ".png")
        try {
            val image = BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                // Broad axis markers reach all four source edges while keeping each
                // corner transparent. Unlike a diamond's subpixel tips, their full
                // native span remains measurable after pixel-area downsampling.
                val inside = x in 128 until 384 || y in 64 until 192
                image.setRGB(x, y, if (inside) GREEN else 0)
            }
            check(ImageIO.write(image, "png", path.toFile()))
            test(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun export(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "animated-emoji-message-final")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private fun assertClose(expected: Float, actual: Float, reason: String, tolerance: Float = 1.5f) =
        assertTrue(abs(expected - actual) <= tolerance, "$reason Expected=$expected, actual=$actual")

    private fun isGreen(pixel: Int) = (pixel ushr 8 and 255) > 130 &&
        (pixel ushr 16 and 255) < 80 && (pixel and 255) in 70..170

    private fun isYellow(pixel: Int): Boolean {
        val red = pixel ushr 16 and 255
        val green = pixel ushr 8 and 255
        val blue = pixel and 255
        return red > 150 && green > 100 && red > blue + 40 && green > blue + 20
    }

    private fun isBlue(pixel: Int): Boolean {
        val red = pixel ushr 16 and 255
        val green = pixel ushr 8 and 255
        val blue = pixel and 255
        return blue > 110 && blue > red + 45 && blue > green + 20
    }

    private fun colorDistance(first: Int, second: Int) = listOf(24, 16, 8, 0).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }

    private fun modeName(mode: ColorSchemeMode) = if (mode == ColorSchemeMode.Light) "light" else "dark"
    private fun densityName(density: Float) = if (density == 1f) "100" else "125"

    private companion object {
        const val CHAT_ID = 10L
        const val SENDER_ID = 17L
        const val FILE_ID = 71
        const val EMOJI = "😭"
        const val SENDER = "Synthetic sender"
        const val REPLY = "Synthetic reply"
        const val FORWARD = "Synthetic source"
        val BACKDROP = 0xFFEF24DB.toInt()
        val GREEN = 0xFF24BD76.toInt()
    }
}
