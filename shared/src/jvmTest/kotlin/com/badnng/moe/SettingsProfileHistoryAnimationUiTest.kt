package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.media.AnimationPlayback
import com.badnng.moe.media.LocalWindowActivity
import com.badnng.moe.settings.*
import com.badnng.moe.tdlib.OfficialTdDownloadNative
import com.badnng.moe.tdlib.OfficialTdProfilePhotoHistoryFiles
import com.badnng.moe.tdlib.ProfilePhotoHistoryCatalog
import com.badnng.moe.tdlib.SettingsProfilePhotoHistoryAuthorizedFileRequest
import com.badnng.moe.tdlib.TdDownloadBroker
import org.drinkless.tdlib.TdApi
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** All media/tickets here are synthetic; this renderer never tests native codec or server content. */
class SettingsProfileHistoryAnimationUiTest {
    @Test fun savedStickerHistoryConfirmationRevokesThenSameFrameDismissReacquires() {
        val f = Fixture(savedSticker = true)
        f.scene().use { s ->
            s.settledFrame(); assertTrue(f.saved.requests.isEmpty(), "History grid must not request full saved artwork")
            s.clickDescription("查看头像 3", f); s.revealDescription("已保存表情头像预览", f)
            s.await { f.saved.requests.isNotEmpty() && f.selectionCallbacks != null }
            val old = f.saved.requests.last()
            assertEquals(SettingsSavedProfileStickerAvatarRequest.History(f.extras.token, 1, 1002), old.request)
            val clip = old.ready(); s.await { clip.times.isNotEmpty() }
            val callbacks = assertNotNull(f.selectionCallbacks)
            onUi {
                callbacks.reuse(); assertFalse(old.requestCurrent()); assertFalse(old.demand.current())
                assertNotNull(f.state.session?.confirmation).dismiss()
            }
            s.await { f.saved.requests.last() !== old }; assertTrue(old.closed.get()); assertTrue(clip.closed.get())
            val fresh = f.saved.requests.last(); assertTrue(fresh.demand.current()); val next = fresh.ready(animated = true)
            s.await { next.times.size >= 2 && s.descriptions("已保存动态表情头像").isNotEmpty() }; assertTrue(fresh.requestCurrent())
        }
    }

    @Test fun savedStickerHistoryRendersAndNavigationRemainsReachableAcrossWindowMatrix() {
        for ((width, height) in listOf(620 to 900, 350 to 300)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark))
            for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode, headerHeight = if (height == 300) 109 else 0, savedSticker = true)
                f.scene().use { s ->
                    s.settledFrame(); s.clickHistoryGridDescription("查看头像 3", f)
                    assertEquals(1002L, f.state.session?.selection?.photoId)
                    val preview = s.reveal(f, fullSquare = true) {
                        "已保存表情头像预览" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }
                    s.await { f.saved.requests.isNotEmpty() }; val call = f.saved.requests.last(); val clip = call.ready()
                    s.await { clip.times.isNotEmpty() && s.descriptions("已保存静态表情头像").isNotEmpty() }; val frame = s.settledFrame()
                    assertEquals(0xffe6374f.toInt(), frame[preview.center.x.roundToInt(), preview.center.y.roundToInt()])
                    assertEquals(0xff0f8a42.toInt(), frame[(preview.left + preview.width * .85f).roundToInt(), preview.center.y.roundToInt()],
                        "Artwork must stay inside the native 67 percent foreground")
                    frame.save("saved-history-selected-${width}x$height-$mode-$density")
                    s.revealEnabledButton("返回列表", f); s.settledFrame().save("saved-history-navigation-${width}x$height-$mode-$density")
                    s.clickEnabledButton("返回列表", f); assertNull(f.state.session!!.selection); assertFalse(call.requestCurrent())
                }
            }
    }

    @Test fun savedStickerHistoryUsesTheNativeFreeformColors() {
        val background = SettingsProfilePhotoSticker.Background.Freeform(0xff0000, 0x00ff00, 0x0000ff, 0xffff00)
        val f = Fixture(savedSticker = true, savedBackground = background)
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 3", f)
            val preview = s.reveal(f, fullSquare = true) {
                "已保存表情头像预览" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
            s.await { f.saved.requests.isNotEmpty() }; val clip = f.saved.requests.last().ready()
            s.await { clip.times.isNotEmpty() }; s.awaitSlow {
                val frame = s.settledFrame(1, 16); val x = preview.center.x.roundToInt()
                frame[x, (preview.top + preview.height * .1f).roundToInt()] !=
                    frame[x, (preview.bottom - preview.height * .1f).roundToInt()]
            }
            val frame = s.settledFrame(); val x = preview.center.x.roundToInt()
            assertNotEquals(frame[x, (preview.top + preview.height * .1f).roundToInt()],
                frame[x, (preview.bottom - preview.height * .1f).roundToInt()])
        }
    }
    @Test fun onlyExplicitVisibleSelectionRequestsLargeAnimationAndPassesItsActualStart() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); assertTrue(f.animations().isEmpty())
            s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.animations().size == 1 }; val request = f.animations().single()
            assertEquals(1001L, request.request.photoId); assertTrue(request.active()); assertTrue(f.renderer.started.isEmpty())
            worker { request.deliver(f.largeReady) }; s.await { f.renderer.started.isNotEmpty() }
            val playback = f.renderer.started.single(); assertEquals(101, playback.file.id); assertEquals(1250L, playback.start)
            assertTrue(playback.active); assertTrue(s.descriptions("合成动画画面").isNotEmpty())
        }
    }

    @Test fun mismatchedOrUnreadyFileFailsWithoutPlaybackAndExplicitRetryUsesFreshOwner() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.animations().isNotEmpty() }; val old = f.animations().last()
            worker { old.deliver(f.largeReady.copy(uniqueId = "different")) }; s.await { s.texts("重试视频").isNotEmpty() }
            assertTrue(s.texts("视频暂不可用").isNotEmpty()); assertTrue(old.closed.get()); assertTrue(f.renderer.started.isEmpty())
            val firstCount = f.animations().size
            s.clickText("重试视频", f); s.await { f.animations().size > firstCount && f.animations().size >= 2 }; val fresh = f.animations().last()
            assertNotSame(old, fresh); assertEquals(old.request, fresh.request); assertFalse(old.active()); assertTrue(fresh.active())
            worker { old.deliver(f.largeReady); fresh.deliver(f.largeReady.copy(downloaded = false)) }
            s.await { s.texts("重试视频").isNotEmpty() && fresh.closed.get() }; assertTrue(f.renderer.started.isEmpty())
            assertTrue(s.texts("视频暂不可用").isNotEmpty()); val secondCount = f.animations().size
            s.clickText("重试视频", f); s.await { f.animations().size > secondCount && f.animations().size >= 3 }
            val wrongSize = f.animations().last()
            assertNotSame(fresh, wrongSize); assertEquals(fresh.request, wrongSize.request); assertTrue(wrongSize.active())
            worker { wrongSize.deliver(f.largeReady.copy(size = 129)) }
            s.await { wrongSize.closed.get() && s.texts("重试视频").isNotEmpty() }; assertTrue(f.renderer.started.isEmpty())
            assertTrue(s.texts("视频暂不可用").isNotEmpty()); val thirdCount = f.animations().size
            s.clickText("重试视频", f); s.await { f.animations().size > thirdCount && f.animations().size >= 4 }
            val finalOwner = f.animations().last()
            assertNotSame(wrongSize, finalOwner); assertEquals(wrongSize.request, finalOwner.request); assertTrue(finalOwner.active())
            worker { finalOwner.deliver(f.largeReady) }; s.await { f.renderer.started.isNotEmpty() }
            assertEquals(1250L, f.renderer.started.last().start)
        }
    }

    @Test fun invalidLargeMetadataDoesNotBorrowTheSmallVariantOrStartARequest() {
        val cases = listOf(
            SettingsProfilePhotoAnimation(MediaFile(101, size = 128, uniqueId = "history-large"), 640, Double.NaN),
            SettingsProfilePhotoAnimation(MediaFile(101, size = 128, uniqueId = "history-large"), 1281, 1.25),
            SettingsProfilePhotoAnimation(MediaFile(-1, size = 128, uniqueId = "history-large"), 640, 1.25),
            SettingsProfilePhotoAnimation(MediaFile(101, size = 24L * 1024 * 1024 + 1, uniqueId = "history-large"), 640, 1.25),
        )
        for (invalid in cases) {
            val f = Fixture(); onUi { f.replaceAnimation(invalid) }
            f.scene().use { s ->
                s.settledFrame(); s.clickDescription("查看头像 2", f); s.settledFrame()
                assertTrue(s.texts("视频信息暂不可用，当前显示静态封面。").isNotEmpty())
                assertTrue(f.animations().isEmpty()); assertTrue(f.renderer.started.isEmpty())
            }
        }
    }

    @Test fun immediateReadyCallbackWaitsForItsReturnedLeaseBeforePlayback() {
        val f = Fixture(); f.autoAnimation = true
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.renderer.started.isNotEmpty() }; val request = f.animations().single()
            assertFalse(request.closed.get()); assertTrue(request.active()); assertEquals(1250L, f.renderer.started.single().start)
            s.clickText("返回列表", f); assertTrue(request.closed.get()); assertFalse(request.active())
        }
    }

    @Test fun missingMetadataAndStickerOnlyStayHonestWhileSmallVariantUsesItsOwnTimestamp() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 3", f)
            assertTrue(s.texts("表情头像动画暂不可播放，当前显示静态封面。").isNotEmpty()); assertTrue(f.animations().isEmpty())
            s.clickText("下一张", f); s.revealDescription("已选头像动画预览", f); s.await { f.animations().isNotEmpty() }
            val request = f.animations().last(); assertEquals(1003L, request.request.photoId)
            worker { request.deliver(f.smallReady) }; s.await { f.renderer.started.isNotEmpty() }
            assertEquals(202, f.renderer.started.last().file.id); assertEquals(375L, f.renderer.started.last().start)
            s.clickText("下一张", f); s.await { s.texts("视频信息暂不可用，当前显示静态封面。").isNotEmpty() }
            assertEquals(1, f.animations().size); assertTrue(request.closed.get())
        }
    }

    @Test fun focusActivityAndConfirmationEachReleaseTheirOwnLeaseAndRetainSelection() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.animations().isNotEmpty() }; val a = f.animations().last()
            worker { a.deliver(f.largeReady) }; s.await { f.renderer.started.isNotEmpty() }
            val selection = f.state.session!!.selection; val playback = f.renderer.started.last()
            onUi { f.activity.value = false }; s.settledFrame(); assertTrue(a.closed.get()); assertFalse(a.active()); assertTrue(playback.closed.get())
            assertSame(selection, f.state.session!!.selection)
            onUi { f.activity.value = true }; s.await { f.animations().size >= 2 }; val b = f.animations().last()
            onUi { f.focused = false }; s.settledFrame(); assertTrue(b.closed.get()); assertFalse(b.active())
            onUi { f.focused = true }; s.await { f.animations().size >= 3 }; val c = f.animations().last()
            s.clickText("设为当前头像", f); assertTrue(c.closed.get()); assertFalse(c.active())
            s.clickText("取消", f); s.await { f.animations().size >= 4 }
            assertSame(selection, f.state.session!!.selection)
        }
    }

    @Test fun inactiveWithoutSceneFramesClosesDemandRejectsLateCallbacksAndRestoresNewOwner() {
        val f = Fixture(); f.callbackOnAnimationClose = f.largeReady
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.animations().isNotEmpty() }; val old = f.animations().last()
            val selection = assertNotNull(f.state.session!!.selection)
            worker { f.activity.value = false }
            waitWithoutFrames { old.closed.get() }
            assertFalse(old.active()); assertEquals(1, old.closeCount.get())
            val continuedDownloads = AtomicInteger()
            worker { if (old.active()) continuedDownloads.incrementAndGet(); old.deliver(f.largeReady) }
            assertEquals(0, continuedDownloads.get())
            worker { f.activity.value = true }
            assertFalse(old.active(), "A revoked owner cannot revive when focus returns before a frame")
            s.await { f.animations().size >= 2 }; val fresh = f.animations().last()
            assertNotSame(old, fresh); assertTrue(fresh.active()); assertFalse(fresh.closed.get())
            assertTrue(f.renderer.started.isEmpty(), "Close-triggered and late callbacks cannot start the old file")
            worker { fresh.deliver(f.largeReady) }; s.await { f.renderer.started.isNotEmpty() }
            assertSame(selection, f.state.session!!.selection); assertEquals(1250L, f.renderer.started.single().start)
            assertEquals(1, old.closeCount.get())
        }
    }

    @Test fun inactiveBeforeAcquireReturnsClosesLateBoundLeaseExactlyOnce() {
        val f = Fixture(); f.callbackOnAnimationClose = f.largeReady
        f.beforeAnimationReturn = { pending -> pending.deliver(f.largeReady); worker { f.activity.value = false } }
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.animations().isNotEmpty() }; val old = f.animations().first()
            waitWithoutFrames { old.closed.get() }
            assertFalse(old.active()); assertEquals(1, old.closeCount.get()); assertTrue(f.renderer.started.isEmpty())
            onUi { f.beforeAnimationReturn = null }
            worker { f.activity.value = true }
            s.await { f.animations().size >= 2 }; val fresh = f.animations().last()
            assertTrue(fresh.active()); assertFalse(fresh.closed.get()); assertFalse(old.active())
            worker { fresh.deliver(f.largeReady) }; s.await { f.renderer.started.isNotEmpty() }
            assertEquals(1, old.closeCount.get()); assertEquals(1250L, f.renderer.started.single().start)
        }
    }

    @Test fun nativePredicatePermanentlyRevokesAfterSeeingFalseEvenIfObserverMissesTheEdge() {
        val visible = AtomicBoolean(true)
        val window = object : ProfileHistoryWindowActivity {
            override fun active() = visible.get()
            override fun observe(onInactive: () -> Unit, onChange: () -> Unit) = ProfileHistoryWindowActivityObserver { }
        }
        val gate = ProfileHistoryAnimationActivity(window)
        val session = ProfileHistoryUiSession(SettingsProfileExtrasToken(7, 3, 43), 0)
        val selection = ProfileHistoryUiSelection(1, SettingsProfilePhotoIdentity(43, currentPhotoId = 1000), 1001, 1)
        val owner = ProfileHistoryAnimationOwner(session, selection, gate)
        val closes = AtomicInteger()
        try {
            gate.attach(owner)
            assertTrue(owner.bind(object : SettingsProfilePhotoHistoryFileLease { override fun close() { closes.incrementAndGet() } }))
            visible.set(false); assertFalse(owner.active())
            visible.set(true); assertFalse(owner.active(), "Observed false must be one-way even if the flow observer misses it")
            waitWithoutFrames { closes.get() == 1 }
        } finally { gate.close(); selection.close(); session.close() }
        assertEquals(1, closes.get())
    }

    @Test fun inactiveWithoutFramesStopsRealFilesAndBrokerBeforeLateGetFileAndNewOwnerSucceeds() {
        val f = Fixture()
        RealHistoryFileChain(f.large).use { chain ->
            f.animationAcquire = chain::acquire
            f.scene().use { s ->
                s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
                s.await { chain.calls.size == 1 && chain.leases.size == 1 }
                val oldCall = chain.calls.single(); assertIs<TdApi.GetFile>(oldCall.command)
                val oldLease = chain.leases.single(); val selection = assertNotNull(f.state.session!!.selection)
                assertTrue(oldLease.active()); assertFalse(chain.broker.manages(101))
                worker { f.activity.value = false }
                waitWithoutFrames { oldLease.closeCompleted.get() }
                assertFalse(oldLease.active()); assertEquals(SettingsError.NOT_READY, oldLease.results.single().error)
                // Reply to the real file service while the scene has received no additional frame.
                worker { oldCall.reply(chain.nativeFile(chain.unready)) }
                assertEquals(1, chain.calls.size, "No Prefix, DownloadFile, ReadFilePart or next GetFile may be sent")
                assertFalse(chain.broker.manages(101), "The revoked request must not acquire a broker lane")
                assertTrue(f.renderer.started.isEmpty())
                worker { f.activity.value = true }
                assertFalse(oldLease.active(), "Focus return cannot revive the revoked owner")
                s.await { chain.calls.size >= 2 && chain.leases.size >= 2 }
                val freshLease = chain.leases.last(); assertTrue(freshLease.active()); assertFalse(freshLease.closeCompleted.get())
                val freshGet = chain.calls[1]; assertIs<TdApi.GetFile>(freshGet.command)
                worker { oldCall.reply(chain.nativeFile(chain.unready)); freshGet.reply(chain.nativeFile(chain.unready)) }
                waitWithoutFrames { chain.calls.size >= 3 }
                val prefix = chain.calls[2]; assertIs<TdApi.GetFileDownloadedPrefixSize>(prefix.command)
                assertTrue(chain.broker.manages(101))
                worker { prefix.reply(TdApi.FileDownloadedPrefixSize(0)) }
                waitWithoutFrames { chain.calls.size >= 4 }
                val downloadCall = chain.calls[3]; val download = assertIs<TdApi.DownloadFile>(downloadCall.command)
                assertEquals(101, download.fileId); assertEquals(0L, download.offset)
                worker { downloadCall.reply(chain.nativeFile(chain.ready)) }
                chain.runImmediate()
                waitWithoutFrames { chain.calls.size >= 5 }
                val finalGet = chain.calls[4]; assertIs<TdApi.GetFile>(finalGet.command)
                worker { finalGet.reply(chain.nativeFile(chain.ready)) }
                s.await { f.renderer.started.isNotEmpty() }
                assertEquals(1, f.renderer.started.size); assertEquals(1250L, f.renderer.started.single().start)
                assertSame(selection, f.state.session!!.selection); assertTrue(freshLease.active())
                assertEquals(1, oldLease.results.size); assertFalse(oldLease.active())
                assertFalse(chain.calls.any { it.command is TdApi.ReadFilePart })
            }
        }
    }

    @Test fun selectedCoverAndVideoHaveIndependentWindowOwnersAndCoverRecoversAfterNoFrameLoss() {
        val f = Fixture(includeCover = true)
        RealHistoryFileChain(f.large, f.cover).use { chain ->
            f.animationAcquire = chain::acquire; f.previewAcquire = chain::acquire
            f.scene().use { s ->
                s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
                s.await { chain.calls.size >= 2 && chain.leases.size >= 2 }
                val oldCover = chain.leases.single { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW }
                val oldVideo = chain.leases.single { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION }
                val oldCoverGet = chain.getFiles(111).single(); val oldVideoGet = chain.getFiles(101).single()
                assertTrue(oldCover.active()); assertTrue(oldVideo.active())
                worker { f.activity.value = false }
                waitWithoutFrames { oldCover.closeCompleted.get() && oldVideo.closeCompleted.get() }
                assertFalse(oldCover.active()); assertFalse(oldVideo.active())
                assertEquals(SettingsError.NOT_READY, oldCover.results.single().error)
                assertEquals(SettingsError.NOT_READY, oldVideo.results.single().error)
                worker {
                    oldCoverGet.reply(chain.nativeFile(checkNotNull(chain.coverUnready)))
                    oldVideoGet.reply(chain.nativeFile(chain.unready))
                }
                assertEquals(2, chain.calls.size, "Neither stale GetFile may start a broker or another command")
                assertFalse(chain.broker.manages(111)); assertFalse(chain.broker.manages(101))
                worker { f.activity.value = true }
                assertFalse(oldCover.active()); assertFalse(oldVideo.active())
                s.await { chain.leases.size >= 4 && chain.getFiles(111).size >= 2 && chain.getFiles(101).size >= 2 }
                val newCover = chain.leases.last { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW }
                val newVideo = chain.leases.last { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION }
                assertTrue(newCover.active()); assertTrue(newVideo.active())
                worker { oldCoverGet.reply(chain.nativeFile(checkNotNull(chain.coverUnready))) }
                assertEquals(4, chain.calls.size, "A stale cover callback cannot disturb either new owner")
                val readyCover = checkNotNull(chain.coverReady)
                worker { chain.getFiles(111)[1].reply(chain.nativeFile(readyCover)) }
                chain.runImmediate()
                waitWithoutFrames { chain.getFiles(111).size >= 3 }
                worker { chain.getFiles(111)[2].reply(chain.nativeFile(readyCover)) }
                s.awaitSlow { s.descriptions("已选头像静态预览 已载入").isNotEmpty() }
                assertEquals(111, newCover.results.single().value?.id)
                assertTrue(newCover.active()); assertTrue(newVideo.active()); assertFalse(newVideo.closeCompleted.get())
                assertTrue(f.renderer.started.isEmpty()); assertFalse(oldCover.active()); assertFalse(oldVideo.active())
            }
        }
    }

    @Test fun sameFrameConfirmationRevokesCoverBeforeLateGetFileAndDismissReacquiresIt() {
        val f = Fixture(includeCover = true)
        RealHistoryFileChain(f.large, f.cover).use { chain ->
            f.animationAcquire = chain::acquire; f.previewAcquire = chain::acquire
            f.scene().use { s ->
                s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
                s.await { chain.calls.size >= 2 && chain.leases.size >= 2 }
                val selected = assertNotNull(f.state.session!!.selection)
                val callbacks = assertNotNull(f.selectionCallbacks); assertSame(selected, callbacks.selection)
                val oldCover = chain.leases.single { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW }
                val oldVideo = chain.leases.single { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION }
                val oldCoverOwner = assertNotNull(selected.coverOwner)
                val oldCoverGet = chain.getFiles(111).single(); val oldVideoGet = chain.getFiles(101).single()
                onUi {
                    callbacks.reuse() // The actual selected-row confirmation action.
                    assertNotNull(f.state.session!!.confirmation)
                    assertTrue(oldCover.closeCompleted.get()); assertTrue(oldVideo.closeCompleted.get())
                    assertFalse(oldCover.active()); assertFalse(oldVideo.active()); assertTrue(f.activity.value)
                    worker {
                        oldCoverGet.reply(chain.nativeFile(checkNotNull(chain.coverUnready)))
                        oldVideoGet.reply(chain.nativeFile(chain.unready))
                    }
                    assertEquals(2, chain.calls.size, "Before a frame, stale queries cannot reach the broker")
                    assertFalse(chain.broker.manages(111)); assertFalse(chain.broker.manages(101))
                    assertNotNull(f.state.session!!.confirmation).dismiss() // Actual host dismiss, in the same UI frame.
                    assertNull(f.state.session!!.confirmation)
                }
                s.await { chain.leases.size >= 4 && chain.getFiles(111).size >= 2 && chain.getFiles(101).size >= 2 }
                val newCover = chain.leases.last { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW }
                val newVideo = chain.leases.last { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION }
                assertTrue(newCover.active()); assertTrue(newVideo.active())
                val newCoverOwner = assertNotNull(selected.coverOwner); assertNotSame(oldCoverOwner, newCoverOwner)
                onUi { selected.releaseCover(oldCoverOwner) } // The actual old effect's release method, delivered late.
                assertSame(newCoverOwner, selected.coverOwner)
                assertTrue(newCover.active()); assertFalse(newCover.closeCompleted.get()); assertTrue(newVideo.active())
                worker { oldCoverGet.reply(chain.nativeFile(checkNotNull(chain.coverUnready))) }
                assertEquals(4, chain.calls.size); assertTrue(newCover.active()); assertTrue(newVideo.active())
                val ready = checkNotNull(chain.coverReady)
                worker { chain.getFiles(111)[1].reply(chain.nativeFile(ready)) }
                chain.runImmediate(); waitWithoutFrames { chain.getFiles(111).size >= 3 }
                worker { chain.getFiles(111)[2].reply(chain.nativeFile(ready)) }
                s.awaitSlow { s.descriptions("已选头像静态预览 已载入").isNotEmpty() }
                assertSame(selected, f.state.session!!.selection); assertEquals(111, newCover.results.single().value?.id)
                assertTrue(newCover.active()); assertTrue(newVideo.active()); assertFalse(oldCover.active()); assertFalse(oldVideo.active())
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun actualHistoryPlayerUsesAvatarFailureAndSemanticsWhileGifDefaultsStayUnchanged() {
        Dispatchers.setMain(object : MainCoroutineDispatcher() {
            override val immediate get() = this
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        val missing = Files.createTempFile("synthetic-history-unavailable-", ".mp4")
        Files.delete(missing) // A real failed-player path, independent of a fake renderer or codec success.
        try {
            val f = Fixture(); f.useNativeRenderer = true
            f.scene().use { s ->
                s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
                s.await { f.animations().isNotEmpty() }; val request = f.animations().last()
                worker { request.deliver(f.largeReady.copy(localPath = missing.toString())) }
                s.awaitSlow { s.texts("头像动画 暂时无法播放").isNotEmpty() }
                assertTrue(s.descriptions("头像动画").isNotEmpty())
                assertTrue(s.texts("GIF 暂时无法播放").isEmpty()); assertTrue(s.descriptions("GIF 动画").isEmpty())
                assertTrue(f.renderer.started.isEmpty())
            }
            ComposePixelScene(320, 240) {
                val lifecycleOwner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
                } }
                val theme = remember { ThemeController(ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    MiuixTheme(controller = theme) { AnimationPlayback(missing.toString(), true, Modifier.fillMaxSize()) }
                }
            }.use { s ->
                s.awaitSlow { s.texts("GIF 暂时无法播放").isNotEmpty() }
                assertTrue(s.descriptions("GIF 动画").isNotEmpty())
                assertTrue(s.texts("头像动画 暂时无法播放").isEmpty())
            }
        } finally { Dispatchers.resetMain(); Files.deleteIfExists(missing) }
    }

    @Test fun openingAndDismissingConfirmationInOneFrameReacquiresTheAnimationOwner() {
        val f = Fixture(); f.autoAnimation = true
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.renderer.started.isNotEmpty() }
            val selection = assertNotNull(f.state.session!!.selection)
            val old = f.animations().single()
            val callbacks = assertNotNull(f.selectionCallbacks)
            assertSame(selection, callbacks.selection)
            onUi {
                callbacks.reuse() // This exact closure is attached to the real button.
                assertTrue(old.closed.get()); assertFalse(old.active())
                val confirmation = assertNotNull(f.state.session!!.confirmation)
                confirmation.dismiss() // This exact closure is passed to the real confirmation host.
                assertNull(f.state.session!!.confirmation)
            }
            s.await { f.animations().size >= 2 && f.renderer.started.size >= 2 }
            assertSame(selection, f.state.session!!.selection)
            assertTrue(f.renderer.started.first().closed.get())
            assertFalse(f.animations().last().closed.get()); assertTrue(f.animations().last().active())
        }
    }

    @Test fun oldSelectionAndSamePhotoReselectionRejectLateResultsWithoutClosingNewOwner() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.animations().isNotEmpty() }; val old = f.animations().last()
            s.clickText("返回列表", f); assertTrue(old.closed.get())
            s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.animations().size >= 2 }; val fresh = f.animations().last()
            worker { old.deliver(f.largeReady) }; s.settledFrame(); assertTrue(f.renderer.started.isEmpty()); assertFalse(fresh.closed.get())
            worker { fresh.deliver(f.largeReady) }; s.await { f.renderer.started.isNotEmpty() }
            assertEquals(1, f.renderer.started.size); assertFalse(fresh.closed.get())
        }
    }

    @Test fun leavingThePageClosesTheSelectedAnimationAndLateWorkerCannotStartPlayback() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.animations().isNotEmpty() }; val old = f.animations().last()
            onUi { f.active = false }; s.settledFrame(); assertTrue(old.closed.get()); assertFalse(old.active())
            worker { old.deliver(f.largeReady) }; s.settledFrame(); assertTrue(f.renderer.started.isEmpty())
            assertNull(f.state.session)
        }
    }

    @Test fun sameIdentityMetadataReplacementRejectsOldFileAndUsesNewNativeTimestamp() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.animations().isNotEmpty() }; val old = f.animations().last()
            onUi { f.replaceAnimation(SettingsProfilePhotoAnimation(MediaFile(303, size = 222, uniqueId = "new-real-id"), 640, 2.75)) }
            s.await { old.closed.get() && f.animations().size >= 2 }; val fresh = f.animations().last()
            worker { old.deliver(f.largeReady); fresh.deliver(MediaFile(303, size = 222, uniqueId = "new-real-id", downloaded = true, localPath = "synthetic://new")) }
            s.await { f.renderer.started.isNotEmpty() }; assertEquals(1, f.renderer.started.size)
            assertEquals(303, f.renderer.started.single().file.id); assertEquals(2750L, f.renderer.started.single().start)
        }
    }

    @Test fun clippedPreviewStopsItsOwnerWhileSelectionAndControlsStayAvailable() {
        // Tao title bar, divider and single-column settings header occupy 109 dp.
        val f = Fixture(height = 300, width = 350, headerHeight = 109)
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealFullPreview(f)
            s.await { f.animations().isNotEmpty() }; val old = f.animations().last()
            val viewportBeforeScroll = f.viewportSize
            assertEquals(191, viewportBeforeScroll.height)
            assertEquals(183, old.request.targetWidthPx); assertEquals(183, old.request.targetHeightPx)
            repeat(35) {
                if (!old.closed.get()) {
                    s.scrollAt(2f * f.density, f.pixelHeight / 2f, deltaY = 7f * f.density); s.settledFrame(3, 16)
                    assertEquals(viewportBeforeScroll, f.viewportSize, "Scroll position must not resize the viewport")
                }
            }
            assertTrue(old.closed.get()); assertFalse(old.active()); assertNotNull(f.state.session!!.selection)
            s.revealFullPreview(f); s.await { f.animations().size >= 2 }
            val restored = f.animations().last()
            assertNotSame(old, restored); assertEquals(old.request, restored.request); assertTrue(restored.active())
            val beforeResize = f.animations().size
            onUi { f.headerHeight = 64 }
            s.await { f.viewportSize.height == 236 && f.animations().size > beforeResize &&
                f.animations().last().request.targetWidthPx == 216 }
            s.revealFullPreview(f)
            assertTrue(restored.closed.get()); assertEquals(216, f.animations().last().request.targetHeightPx)
            s.clickEnabledButton("返回列表", f); assertNull(f.state.session!!.selection)
        }
    }

    @Test fun internalScrollViewportRevokesEvenWhilePreviewCoordinatesRemainInsideTheWindow() {
        val f = Fixture(width = 620, height = 900, viewportInset = 250)
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f, 214f)
            s.await { f.animations().isNotEmpty() }; val old = f.animations().last()
            worker { old.deliver(f.largeReady) }; s.await { f.renderer.started.isNotEmpty() }
            val playback = f.renderer.started.single()
            repeat(120) {
                if (!old.closed.get()) { s.scrollAt(2f, f.pixelHeight / 2f, deltaY = .8f); s.settledFrame(3, 16) }
            }
            assertTrue(old.closed.get()); assertFalse(old.active()); assertTrue(playback.closed.get())
            val last = assertNotNull(playback.lastBounds)
            assertTrue(last.top > 0f && last.bottom < f.pixelHeight,
                "The last actual renderer position remains inside window bounds: $last")
            assertTrue(last.bottom <= f.viewportInset + 32f,
                "One small scroll step reaches the internal parent's clipped top: $last")
            assertNotNull(f.state.session!!.selection)
        }
    }

    @Test fun focusedNextButtonAcceptsKeyboardAndClosesThePreviousAnimationOwner() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickDescription("查看头像 2", f); s.revealDescription("已选头像动画预览", f)
            s.await { f.animations().isNotEmpty() }; val previous = f.animations().last()
            s.revealText("下一张", f)
            repeat(32) {
                if (!s.focusedText("下一张")) {
                    s.keyStroke(java.awt.event.KeyEvent.VK_TAB); s.settledFrame(2, 16)
                }
            }
            assertTrue(s.focusedText("下一张"), "Tab must focus the actual enabled navigation button")
            s.keyStroke(java.awt.event.KeyEvent.VK_ENTER); s.settledFrame()
            assertEquals(1002L, f.state.session!!.selection!!.photoId)
            assertTrue(previous.closed.get()); assertFalse(previous.active())
        }
    }

    @Test fun selectedAnimationAndHistoryNavigationAreReachableAtBothWindowSizesThemesAndDensities() {
        for ((width,height) in listOf(620 to 900, 350 to 300)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark))
            for (density in listOf(1f,1.25f)) {
                val f = Fixture(width, height, density, mode, headerHeight = if (height == 300) 109 else 0); f.autoAnimation = true
                f.scene().use { s ->
                    s.settledFrame(); s.clickDescription("查看头像 2", f)
                    val preview = s.revealFullPreview(f)
                    if (height == 300) assertEquals(f.pixelHeight - (109f * density).roundToInt(), f.viewportSize.height)
                    val side = minOf((216f * density).roundToInt(), (f.viewportSize.height - 8f * density).roundToInt()).toFloat()
                    assertEquals(side, preview.width, .5f); assertEquals(side, preview.height, .5f)
                    s.await { f.renderer.started.isNotEmpty() }
                    val selected = s.settledFrame()
                    assertEquals(0xFF6728C4.toInt(), selected[preview.center.x.roundToInt(), preview.center.y.roundToInt()],
                        "The selected screenshot must contain the injected synthetic renderer's real pixels")
                    selected.save("selected-${width}x$height-$mode-$density")
                    s.revealEnabledButton("返回列表", f); s.settledFrame().save("navigation-${width}x$height-$mode-$density")
                    s.clickEnabledButton("上一张", f); assertEquals(1000L, f.state.session!!.selection!!.photoId)
                    s.clickEnabledButton("下一张", f); assertEquals(1001L, f.state.session!!.selection!!.photoId)
                    s.clickEnabledButton("返回列表", f); assertNull(f.state.session!!.selection)
                }
            }
    }

    private class Pending(val request: SettingsProfilePhotoHistoryMediaRequest, val active: () -> Boolean,
        val callback: (SettingsResult<MediaFile>) -> Unit, val onFirstClose: (() -> Unit)? = null) : SettingsProfilePhotoHistoryFileLease {
        val closed = AtomicBoolean(); val closeCount = AtomicInteger()
        override fun close() { if (closeCount.incrementAndGet() == 1) { closed.set(true); onFirstClose?.invoke() } }
        fun deliver(value: MediaFile) = callback(SettingsResult(value = value))
    }
    private class Renderer : ProfileHistoryAnimationRenderer {
        data class Playback(val file: MediaFile, val start: Long, val active: Boolean, val closed: AtomicBoolean = AtomicBoolean(),
            var lastBounds: Rect? = null)
        val started = mutableListOf<Playback>()
        @Composable override fun Content(file: MediaFile, startPositionMillis: Long, active: Boolean, modifier: Modifier) {
            val value = remember(file,startPositionMillis) { Playback(file,startPositionMillis,active) }
            DisposableEffect(value) { started += value; onDispose { value.closed.set(true) } }
            Box(modifier.onGloballyPositioned {
                val position = it.positionInWindow()
                value.lastBounds = Rect(position, Size(it.size.width.toFloat(), it.size.height.toFloat()))
            }.background(Color(0xFF6728C4)).semantics { contentDescription = "合成动画画面" })
        }
    }
    /** Real Files, broker and TDLib command adapter; only the catalog and sender are controlled. */
    private class RealHistoryFileChain(private val animation: SettingsProfilePhotoAnimation,
        private val cover: SettingsProfilePhotoVariant? = null) : AutoCloseable {
        data class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit)
        class Lease(val request: SettingsProfilePhotoHistoryMediaRequest, val active: () -> Boolean,
            private val native: SettingsProfilePhotoHistoryFileLease,
            val results: CopyOnWriteArrayList<SettingsResult<MediaFile>>) : SettingsProfilePhotoHistoryFileLease {
            val closeCompleted = AtomicBoolean()
            override fun close() { native.close(); closeCompleted.set(true) }
        }

        val calls = CopyOnWriteArrayList<Call>()
        val leases = CopyOnWriteArrayList<Lease>()
        private val tasks = ConcurrentLinkedQueue<Pair<Long, () -> Unit>>()
        private val path = Files.createTempFile("synthetic-history-files-chain-", ".bin").also { Files.write(it, ByteArray(128)) }
        private val coverPath = cover?.let {
            Files.createTempFile("synthetic-history-cover-", ".png").also { file ->
                val bitmap = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until 32) for (x in 0 until 32) bitmap.setRGB(x, y, 0xffe63ac4.toInt())
                check(ImageIO.write(bitmap, "png", file.toFile()))
            }
        }
        val unready = animation.file.copy(canDownload = true, downloaded = false, localPath = null, downloadedSize = 0)
        val ready = unready.copy(downloaded = true, localPath = path.toString(), downloadedSize = 128)
        val coverUnready = cover?.file?.copy(canDownload = true, downloaded = false, localPath = null, downloadedSize = 0)
        val coverReady = coverUnready?.let { value ->
            val actualPath = checkNotNull(coverPath); val actualSize = Files.size(actualPath)
            value.copy(size = actualSize, downloaded = true, localPath = actualPath.toString(), downloadedSize = actualSize)
        }
        private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit = { command, reply, _ ->
            calls += Call(command, reply)
        }
        private fun schedule(delay: Long, action: () -> Unit) { tasks.add(delay to action) }
        val broker = TdDownloadBroker(OfficialTdDownloadNative(send), ::schedule, { 0L })
        private val files = OfficialTdProfilePhotoHistoryFiles(object : ProfilePhotoHistoryCatalog {
            override fun resolve(request: SettingsProfilePhotoHistoryAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoVariant> =
                if (request.userId == 43L && request.authorization == 2L && request.photoId == 1001L && request.rawOffset == 1 &&
                    request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW && cover != null) SettingsResult(cover)
                else SettingsResult(error = SettingsError.UNSUPPORTED)
            override fun resolveAnimation(request: SettingsProfilePhotoHistoryAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoAnimation> =
                if (request.userId == 43L && request.authorization == 2L && request.photoId == 1001L && request.rawOffset == 1 &&
                    request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION) SettingsResult(animation)
                else SettingsResult(error = SettingsError.CONFLICT)
        }, broker, send, ::schedule, { 0L })

        fun acquire(request: SettingsProfilePhotoHistoryMediaRequest, active: () -> Boolean,
            result: (SettingsResult<MediaFile>) -> Unit): SettingsProfilePhotoHistoryFileLease {
            check(request.parent == SettingsProfileExtrasToken(7, 3, 43) && request.epoch == 1L && request.photoId == 1001L &&
                request.use in setOf(SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION, SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW))
            val results = CopyOnWriteArrayList<SettingsResult<MediaFile>>()
            val authorized = SettingsProfilePhotoHistoryAuthorizedFileRequest(43, 2, 1001, 1, request.use,
                request.targetWidthPx, request.targetHeightPx)
            val native = files.read(authorized, active) { value -> results += value; result(value) }
            return Lease(request, active, native, results).also { leases += it }
        }

        fun getFiles(fileId: Int) = calls.filter { (it.command as? TdApi.GetFile)?.fileId == fileId }

        fun nativeFile(value: MediaFile) = TdApi.File().apply {
            id = value.id; size = value.size; expectedSize = value.expectedSize
            remote = TdApi.RemoteFile().apply { uniqueId = value.uniqueId }
            local = TdApi.LocalFile().apply {
                path = value.localPath.orEmpty(); isDownloadingCompleted = value.downloaded
                isDownloadingActive = value.downloading; downloadedSize = value.downloadedSize; canBeDownloaded = value.canDownload
            }
        }
        fun runImmediate() {
            repeat(1000) {
                val next = tasks.firstOrNull { it.first == 0L } ?: return
                if (tasks.remove(next)) next.second()
            }
            error("Unexpected unbounded broker scheduling")
        }
        override fun close() { files.retire(); broker.close(); Files.deleteIfExists(path); coverPath?.let { Files.deleteIfExists(it) } }
    }
    private class Fixture(val width: Int = 620, val height: Int = 900, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light, val viewportInset: Int = 0, val includeCover: Boolean = false,
        headerHeight: Int = 0, savedSticker: Boolean = false,
        savedBackground: SettingsProfilePhotoSticker.Background = SettingsProfilePhotoSticker.Background.Solid(0x0f8a42)) {
        val pixelHeight get() = (height*density).roundToInt(); val activity = MutableStateFlow(true)
        var headerHeight by mutableStateOf(headerHeight)
        var viewportSize by mutableStateOf(IntSize.Zero)
        var focused by mutableStateOf(true); var active by mutableStateOf(true); var mounted by mutableStateOf(true)
        var autoAnimation = false; var useNativeRenderer = false; var selectionCallbacks: ProfileHistorySelectionCallbacks? = null
        var callbackOnAnimationClose: MediaFile? = null; var beforeAnimationReturn: ((Pending) -> Unit)? = null
        var animationAcquire: ((SettingsProfilePhotoHistoryMediaRequest, () -> Boolean,
            (SettingsResult<MediaFile>) -> Unit) -> SettingsProfilePhotoHistoryFileLease)? = null
        var previewAcquire: ((SettingsProfilePhotoHistoryMediaRequest, () -> Boolean,
            (SettingsResult<MediaFile>) -> Unit) -> SettingsProfilePhotoHistoryFileLease)? = null
        val state = SettingsProfilePhotoHistoryUiState(); val renderer = Renderer(); val saved = SavedAvatarSyntheticProvider()
        val files = mutableListOf<Pending>()
        val identity = SettingsProfilePhotoIdentity(43,currentPhotoId = 1000)
        val large = SettingsProfilePhotoAnimation(MediaFile(101,size=128,uniqueId="history-large"),640,1.25)
        val small = SettingsProfilePhotoAnimation(MediaFile(202,size=96,uniqueId=""),160,.375)
        val cover = SettingsProfilePhotoVariant(MediaFile(111, uniqueId = "history-cover"), 160, 160)
        val entries = listOf(
            SettingsProfilePhotoHistoryEntry(1000, 1_700_000_000,0, emptyList(), authorization=1),
            SettingsProfilePhotoHistoryEntry(1001, 1_700_000_000,1, if (includeCover) listOf(cover) else emptyList(),
                hasAnimation=true, authorization=2, animation=large, smallAnimation=small),
            SettingsProfilePhotoHistoryEntry(1002, 1_700_000_000,2, emptyList(), hasSticker=true, authorization=3,
                sticker = if (savedSticker) SettingsProfilePhotoSticker(SettingsProfilePhotoSticker.Type.CustomEmoji(555),
                    savedBackground) else null),
            SettingsProfilePhotoHistoryEntry(1003, 1_700_000_000,3, emptyList(), hasAnimation=true, authorization=4, smallAnimation=small),
            SettingsProfilePhotoHistoryEntry(1004, 1_700_000_000,4, emptyList(), hasAnimation=true, authorization=5))
        val largeReady = MediaFile(101,size=128,uniqueId="history-large",downloaded=true,localPath="synthetic://large")
        val smallReady = MediaFile(202,size=96,uniqueId="verified-small",downloaded=true,localPath="synthetic://small")
        var extras by mutableStateOf(SettingsProfileExtrasSnapshot(SettingsProfileExtrasToken(7,3,43),
            SettingsProfileExtrasData(43,1000,null,SettingsCalendarDate(27,10,2026))))
        val actions = SettingsProfilePhotoHistoryActions(
            open = { _, _ -> extras = extras.copy(history = SettingsProfilePhotoHistorySnapshot(opened=true,epoch=1,identity=identity,entries=entries)) },
            close = {}, acquireFile = { request, current, result ->
                val isAnimation = request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION
                val actualAcquire = if (isAnimation) animationAcquire else if (request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW) previewAcquire else null
                if (actualAcquire != null) actualAcquire(request, current, result)
                else {
                    val closeFile = callbackOnAnimationClose.takeIf { isAnimation }
                    Pending(request,current,result,closeFile?.let { value -> { result(SettingsResult(value = value)) } }).also { files += it
                        if (isAnimation) beforeAnimationReturn?.invoke(it)
                        if (autoAnimation && isAnimation) it.deliver(largeReady)
                    }
                }
            })
        init { state.open(extras,actions) }
        fun animations() = files.filter { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION }
        fun replaceAnimation(animation: SettingsProfilePhotoAnimation) {
            extras = extras.copy(history = extras.history.copy(entries = extras.history.entries.map {
                if (it.photoId == 1001L) it.copy(animation=animation) else it
            }))
        }
        fun scene() = ComposePixelScene((width*density).roundToInt(), pixelHeight) {
            val theme = remember { ThemeController(mode) }; val native = LocalWindowInfo.current
            val currentFocus by rememberUpdatedState(focused)
            val window = remember(native) { object : WindowInfo by native { override val isWindowFocused get() = currentFocus } }
            val defaultRenderer = LocalProfileHistoryAnimationRenderer.current
            val lifecycleOwner = remember { object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides window,
                LocalWindowActivity provides activity, LocalSettingsPageActive provides active,
                LocalLifecycleOwner provides lifecycleOwner,
                LocalSettingsSecurityConfirmationHost provides InlineConfirmation,
                LocalProfileHistorySelectionCallbacks provides { selectionCallbacks = it },
                LocalProfileStickerAvatarArtwork provides ProfileStickerAvatarArtworkClient(saved, 7),
                LocalProfileHistoryAnimationRenderer provides if (useNativeRenderer) defaultRenderer else renderer) {
                MiuixTheme(controller=theme) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        if (mounted) Column(Modifier.fillMaxSize().padding(top = (viewportInset + headerHeight).dp, bottom = viewportInset.dp)
                            .onSizeChanged { viewportSize = it }.verticalScroll(rememberScrollState()).padding(12.dp)) {
                            CompositionLocalProvider(LocalSettingsDetailViewportSize provides viewportSize) {
                                SettingsProfilePhotoHistoryUi(state,extras,actions,true)
                            }
                            Spacer(Modifier.height(300.dp)) // Lets the test move the selected preview completely above its real scroll viewport.
                        }
                    }
                }
            }
        }
    }
    private object InlineConfirmation : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
                Column(Modifier.fillMaxWidth().heightIn(max=(height-16.dp).coerceAtLeast(120.dp))
                    .chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer,24.dp).padding(12.dp)) {
                    title?.let { Text(it) }; content()
                }
            }
        }
    }
    private fun ComposePixelScene.texts(label: String) = semanticsBounds { c -> c.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }
    private fun ComposePixelScene.focusedText(label: String) = semanticsBounds { c ->
        c.getOrNull(SemanticsProperties.Focused) == true && c.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    }.isNotEmpty()
    private fun ComposePixelScene.descriptions(label: String) = unmergedSemanticsBounds { label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    private fun ComposePixelScene.click(rect: Rect) { moveTo(rect.center.x,rect.center.y); pressAt(rect.center.x,rect.center.y); releaseAt(rect.center.x,rect.center.y); settledFrame() }
    private fun ComposePixelScene.clickText(label: String,f: Fixture) = click(revealText(label,f))
    private fun ComposePixelScene.revealText(label: String,f: Fixture) = reveal(f) { it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label } }
    private fun ComposePixelScene.clickDescription(label: String,f: Fixture) = click(revealDescription(label,f))
    private fun ComposePixelScene.clickEnabledButton(label: String, f: Fixture) = click(revealEnabledButton(label, f))
    private fun ComposePixelScene.revealEnabledButton(label: String, f: Fixture) = reveal(f, minHeightDp = 35f, withinViewport = true) {
        it.getOrNull(SemanticsProperties.Role) == Role.Button && !it.contains(SemanticsProperties.Disabled) &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.revealDescription(label: String,f: Fixture, minHeightDp: Float = 8f) =
        reveal(f, minHeightDp) { label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    /** Scroll the bounded lazy grid itself; the outer page's left-strip wheel cannot reach later rows. */
    private fun ComposePixelScene.clickHistoryGridDescription(label: String, f: Fixture) {
        val anchor = reveal(f, withinViewport = true) {
            "查看头像 1" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
        val top = (f.viewportInset + f.headerHeight) * f.density + 2f * f.density
        val bottom = f.pixelHeight - f.viewportInset * f.density - 2f * f.density
        fun target() = semanticsBounds { label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .firstOrNull { it.width > 0 && it.height >= 8f * f.density && it.top >= top && it.bottom <= bottom }
        repeat(80) {
            target()?.let { click(it); return }
            // Nested scrolling may move the outer page too. Resolve the grid's
            // current visible hit region instead of reusing the first row's old position.
            val viewport = Rect(0f, top, f.width * f.density, bottom)
            val grid = semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue()?.let { it > 0f } == true
            }.lastOrNull { it.width > 0 && it.height > 0 && it.overlaps(viewport) }?.intersect(viewport)
            val hit = grid ?: anchor
            scrollAt(hit.center.x, hit.center.y, deltaY = 2f * f.density); settledFrame(5, 32)
        }
        if (target() == null) settledFrame().save("grid-not-reachable-${f.width}x${f.height}-${f.density}")
        val actual = semanticsBounds { label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        click(assertNotNull(target(), "Pointer scrolling inside the real history grid must expose $label at ${f.width}x${f.height}; viewport=$top..$bottom, anchor=$anchor, actual=$actual"))
    }
    private fun ComposePixelScene.revealFullPreview(f: Fixture) = reveal(f, fullSquare = true) {
        "已选头像动画预览" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.reveal(f: Fixture, minHeightDp: Float = 8f, fullSquare: Boolean = false,
        withinViewport: Boolean = false, predicate: (SemanticsConfiguration)->Boolean): Rect {
        val margin = 2f*f.density
        val viewportTop = ((f.viewportInset + f.headerHeight) * f.density).roundToInt().toFloat()
        val viewportBottom = f.pixelHeight - (f.viewportInset * f.density).roundToInt().toFloat()
        val expectedSide = minOf((216f * f.density).roundToInt(),
            (f.viewportSize.height - 8f * f.density).roundToInt()).toFloat()
        val constrainedToViewport = fullSquare || withinViewport
        fun visible() = semanticsBounds(predicate).firstOrNull {
            it.width > 0 && it.height >= minHeightDp * f.density &&
                it.top >= (if (constrainedToViewport) viewportTop else 0f) + margin &&
                it.bottom <= (if (constrainedToViewport) viewportBottom else f.pixelHeight.toFloat()) - margin &&
                (!fullSquare || kotlin.math.abs(it.width - expectedSide) < .5f && kotlin.math.abs(it.height - expectedSide) < .5f)
        }
        var down = false
        repeat(240) {
            visible()?.let { return it }
            val target = semanticsBounds(predicate).firstOrNull { it.width>0 && it.height>0 }
            var outerPosition = 0f
            semanticsBounds { config -> config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { outerPosition = it.value() }; false }
            if (outerPosition <= .5f) down = true
            // Windows scales wheel units by viewport height / 20. A 7-unit step jumps
            // past the narrow interval where a full preview fits in a low viewport.
            val step = if (fullSquare && target != null) .25f else 7f
            val top = if (constrainedToViewport) viewportTop else 0f
            val bottom = if (constrainedToViewport) viewportBottom else f.pixelHeight.toFloat()
            val delta = when {
                target != null && target.top < top + margin -> -step
                target != null && target.bottom > bottom - margin -> step
                target != null && target.height < minHeightDp*f.density -> if (target.center.y < (top + bottom)/2f) -step else step
                target != null -> step
                down -> step
                else -> -step
            }
            scrollAt(2f*f.density,f.pixelHeight/2f,deltaY=delta*f.density); settledFrame(5,32)
        }
        return assertNotNull(visible(),"Actual clipped target must be reachable at ${f.width}x${f.height}: ${semanticsBounds(predicate)}")
    }
    private fun ComposePixelScene.await(predicate: ()->Boolean) {
        repeat(160) { if (predicate()) return; settledFrame(2,16); Thread.sleep(5) }
        assertTrue(predicate(),"Synthetic UI did not settle")
    }
    private fun ComposePixelScene.awaitSlow(predicate: () -> Boolean) {
        repeat(750) { if (predicate()) return; settledFrame(1, 16); Thread.sleep(20) }
        assertTrue(predicate(), "Real player did not reach its failure UI")
    }
    private fun waitWithoutFrames(predicate: () -> Boolean) {
        repeat(200) { if (predicate()) return; Thread.sleep(5) }
        assertTrue(predicate(), "Off-scene revocation must not require a Compose frame")
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x,y,this[x,y])
        File("build/settings-profile-history-animation-ui-validation/$name.png").also { it.parentFile.mkdirs(); ImageIO.write(image,"png",it) }
    }
    private fun onUi(block: ()->Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
    private fun worker(block: ()->Unit) { Thread(block).apply { start(); join() } }
}
