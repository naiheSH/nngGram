package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.media.LocalMediaDrawObserver
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual Skottie data, real renderer callbacks and local pointer replay; no sound or native click action. */
class AnimatedEmojiPlaybackRenderTest {
    @Test
    fun regularEmojiFinishesItsCyclePausesWithoutIdleCallbacksAndReplaysOnlyOnClick() = withAnimation { path ->
        val active = mutableStateOf(true)
        val draws = AtomicInteger()
        scene(path, active = { active.value }, regular = true, draws = draws).use { scene ->
            scene.awaitArtwork()
            scene.waitForDraws(draws, minimum = 3)
            active.value = false
            scene.settledFrame(2)
            val paused = draws.get()
            scene.renderFor(700)
            assertEquals(paused, draws.get(), "Inactive artwork owns no animation callbacks.")
            active.value = true
            scene.renderFor(700)
            val completed = draws.get()
            assertTrue(completed > paused, "Activation resumes the interrupted first cycle.")
            scene.renderFor(250)
            assertEquals(completed, draws.get(), "A completed regular emoji must release its frame timer.")
            active.value = false
            scene.settledFrame(2)
            active.value = true
            scene.settledFrame(2)
            val reactivated = draws.get()
            scene.renderFor(200)
            assertEquals(reactivated, draws.get(), "An unrelated page activation cannot replay a completed emoji.")
            scene.pressAt(56f, 56f)
            scene.releaseAt(56f, 56f)
            scene.renderFor(700)
            assertTrue(draws.get() >= reactivated + 3, "Explicit local clicking starts a fresh real animation cycle.")
            val replayed = draws.get()
            scene.renderFor(250)
            assertEquals(replayed, draws.get(), "Explicit replay also stops after its one cycle.")
            assertGreen(scene.settledFrame(1)[56, 56])
        }
    }

    @Test
    fun aSingleCustomEmojiRetainsItsIndependentLoopAndReplacingTheMessageStartsANewOwner() = withAnimation { path ->
        val draws = AtomicInteger()
        val messageId = mutableStateOf(1L)
        scene(path, active = { true }, regular = false, draws = draws, messageId = { messageId.value }).use { scene ->
            scene.awaitArtwork()
            scene.renderFor(650)
            val oneCycle = draws.get()
            // Assert continued playback, without requiring a fixed rendering throughput
            // from a shared GitHub runner during this 300ms observation window.
            scene.waitForDraws(draws, minimum = oneCycle + 3)
            assertTrue(draws.get() > oneCycle + 2, "CustomEmoji::setCustomEmojiPart must not inherit the regular one-shot rule.")
        }
        // A new message can reference the same immutable file. Its playback owner must
        // still be new, otherwise a completed holder would suppress the new message.
        scene(path, active = { true }, regular = true, draws = draws, messageId = { messageId.value }).use { scene ->
            scene.awaitArtwork()
            scene.renderFor(700)
            val beforeReplacement = draws.get()
            messageId.value = 2L
            scene.settledFrame(2)
            scene.awaitArtwork()
            scene.renderFor(700)
            assertTrue(draws.get() >= beforeReplacement + 3,
                "Message identity changes must replace a completed decoder even when its file path is shared.")
            val completed = draws.get()
            scene.renderFor(200)
            assertEquals(completed, draws.get())
        }
    }

    private fun scene(path: Path, active: () -> Boolean, regular: Boolean, draws: AtomicInteger,
        messageId: () -> Long = { 1L }) = ComposePixelScene(160, 160) {
        val controller = remember { ThemeController(ColorSchemeMode.Light) }
        val message = ChatMessage(messageId(), 10, text = "🙂", kind = MessageKind.ANIMATED_EMOJI,
            media = MessageMedia(MediaFile(31, localPath = path.toString(), downloaded = true),
                width = 100, height = 100, format = MediaFormat.TGS,
                animatedEmoji = AnimatedEmojiMedia(100, 100, 0, regular)))
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10), selected = ChatItem(10, "Synthetic"))
        CompositionLocalProvider(LocalMediaDrawObserver provides { draws.incrementAndGet(); Unit }) {
            MiuixTheme(controller = controller) {
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    AnimatedEmojiMessageContent(message, snapshot, ChatFeatureActions(), visible = true,
                        active = active(), modifier = Modifier.size(112.dp))
                }
            }
        }
    }

    private fun ComposePixelScene.awaitArtwork() {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            val frame = settledFrame(2)
            if (isGreen(frame[56, 56])) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("The generated TGS must actually decode and draw before playback assertions.")
    }

    private fun ComposePixelScene.waitForDraws(draws: AtomicInteger, minimum: Int) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (draws.get() < minimum && System.nanoTime() < deadline) {
            settledFrame(1)
            Thread.sleep(20)
        }
        assertTrue(draws.get() >= minimum, "The active real TGS must advance before pausing.")
    }

    private fun ComposePixelScene.renderFor(millis: Long) {
        val deadline = System.nanoTime() + millis * 1_000_000
        do {
            settledFrame(1)
            Thread.sleep(20)
        } while (System.nanoTime() < deadline)
        settledFrame(2)
    }

    private fun withAnimation(test: (Path) -> Unit) {
        val path = Files.createTempFile("animated-emoji-playback-", ".tgs")
        val json = """{"v":"5.5.2","fr":20,"ip":0,"op":10,"w":100,"h":100,"nm":"Synthetic animation","ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":1,"sw":40,"sh":40,"sc":"#24bd76","ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":0,"k":[50,50,0]},"a":{"a":0,"k":[20,20,0]},"s":{"a":0,"k":[100,100,100]}},"ip":0,"op":10,"st":0,"bm":0}]}"""
        try {
            GZIPOutputStream(Files.newOutputStream(path)).use { it.write(json.toByteArray()) }
            test(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun isGreen(pixel: Int) = (pixel ushr 8 and 255) > 140 && (pixel ushr 16 and 255) < 80 &&
        (pixel and 255) in 60..160
    private fun assertGreen(pixel: Int) = assertTrue(isGreen(pixel), "The actual synthetic native artwork stays visible on completion.")
}
