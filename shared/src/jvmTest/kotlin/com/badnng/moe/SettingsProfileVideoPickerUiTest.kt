package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.media.*
import com.badnng.moe.settings.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** UI evidence only. Injected video workers/loop host do not establish codec or native upload success. */
class SettingsProfileVideoPickerUiTest {
    @Test fun unifiedSelectionEntersVideoEditorWithoutOpeningASecondNativeChooserOrUploading() {
        val f = Fixture(initialPath = "selected-by-avatar-camera.mov")
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.ready() != null }
            assertEquals(0, f.pickerCalls)
            assertEquals(1, f.services.reads)
            assertTrue(f.writes.isEmpty())
            assertEquals("selected-by-avatar-camera.mov", f.opening!!.initialPath)
        }
    }

    @Test fun avatarCameraSelectsVideoOnceAndPreparesCoverWithoutStartingSilentPlayback() {
        val f = Fixture(fullProfile = true)
        f.scene().use { scene ->
            scene.settledFrame(); assertEquals(0, f.services.reads)
            assertTrue(scene.unmergedSemanticsBounds {
                "头像操作" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.isEmpty())
            assertTrue(scene.texts("设置视频头像").isEmpty())
            scene.click(scene.description("选择图片或视频头像")); scene.await { f.ready() != null }
            assertEquals(1, f.pickerCalls, "One camera selection must enter video editing without a second chooser.")
            assertEquals(listOf(selectedVideo.absolutePath), f.services.readPaths)
            assertEquals(listOf("选择个人头像"), f.pickerTitles)
            assertTrue(f.pickerExtensions.single().containsAll(setOf("mp4", "mov", "m4v", "png", "jpg")))
            assertEquals(1, f.services.reads); assertEquals(1, f.services.edits.size)
            assertEquals(0.0, f.ready()!!.animation.mainFrameTimestampSeconds)
            assertEquals(f.ready()!!.animation, f.services.coverInputs.single())
            assertTrue(scene.texts("原视频 640 × 360 · 时长 15 秒").isNotEmpty())
            assertTrue(f.preview.playing.isEmpty())
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun actualKeyboardFieldsEnforceOneToNinePointSixSecondsAndTrimRelativeCover() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.ready() != null }
            scene.replaceEditor(0, "2", f); scene.replaceEditor(1, "12", f); scene.replaceEditor(2, "4.5", f)
            assertNull(f.probe!!.editor.draft.edit(assertNotNull(f.probe!!.editor.source)))
            scene.clickText("生成预览", f, requireEnabled = false)
            assertEquals(1, f.services.edits.size, "An invalid ten-second draft must not reach encoding.")
            scene.replaceEditor(1, "9.5", f)
            scene.clickText("生成预览", f); scene.await { f.ready() != null }
            val edit = f.services.edits.last()
            assertEquals(2000L, edit.fromMillis); assertEquals(9500L, edit.tillMillis); assertEquals(4500L, edit.coverMillis)
            assertEquals(2.5, f.ready()!!.animation.mainFrameTimestampSeconds)
            assertEquals("2", f.probe!!.editor.draft.from)
            scene.clickText("上传视频头像", f)
            assertTrue(scene.texts("成片 7.5 秒 · 原片段 2–9.5 秒 · 封面在成片 2.5 秒").isNotEmpty())
            scene.clickText("返回编辑", f)
            scene.replaceEditor(2, "9.5", f)
            assertNull(f.probe!!.editor.draft.edit(assertNotNull(f.probe!!.editor.source)), "The exact end frame is not a valid cover.")
        }
    }

    @Test fun realPointerPanAndMiuixSliderKeyboardChangeTheActualCropAndInvalidatePreparedOwnership() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.ready() != null }
            val old = assertNotNull(f.ready())
            val area = scene.description("视频头像正方形裁切区域")
            scene.moveTo(area.center.x, area.center.y); scene.pressAt(area.center.x, area.center.y)
            scene.dragTo(area.center.x + 42, area.center.y + 15); scene.releaseAt(area.center.x + 42, area.center.y + 15)
            scene.settledFrame()
            assertFalse(old.active())
            assertNotEquals(.5f, f.probe!!.editor.draft.crop.x)
            val slider = scene.revealDescription("视频头像缩放", f)
            scene.click(slider)
            assertTrue(scene.unmergedSemanticsBounds { it.getOrNull(SemanticsProperties.Focused) == true }.any { it.overlaps(slider) })
            val clickedZoom = f.probe!!.editor.draft.crop.zoom
            scene.keyStroke(KeyEvent.VK_RIGHT); scene.settledFrame()
            assertTrue(f.probe!!.editor.draft.crop.zoom > clickedZoom)
            scene.clickText("生成预览", f); scene.await { f.ready() != null }
            assertEquals(f.probe!!.editor.draft.crop, f.services.edits.last().crop)
            assertEquals(1, f.services.animationReleased[0].get()); assertEquals(1, f.services.coverReleased[0].get())
        }
    }

    @Test fun workerFailureKeepsTheActualSourceAndDraftForExplicitPrepareRetry() {
        val f = Fixture()
        f.services.readFailure = ProfilePhotoVideoFailure.Reason.TOOLS_MISSING
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.probe?.editor?.error != null }
            assertTrue(scene.texts("缺少视频处理组件，暂时无法编辑视频头像。").isNotEmpty())
            assertTrue(f.writes.isEmpty())
            f.services.readFailure = null
            scene.clickText("选择其他视频", f); scene.await { f.ready() != null }
            scene.replaceEditor(2, "1.25", f)
            f.services.failPrepare = true
            scene.clickText("生成预览", f); scene.await { f.probe?.editor?.error != null }
            assertEquals("1.25", f.probe!!.editor.draft.cover)
            assertEquals(0, f.services.sourcesReleased[0].get())
            assertTrue(f.writes.isEmpty())
            f.services.failPrepare = false
            scene.clickText("生成预览", f); scene.await { f.ready() != null }
            assertEquals(1.25, f.ready()!!.animation.mainFrameTimestampSeconds)
            assertNull(f.probe!!.editor.error)
        }
    }

    @Test fun lateEncodingAfterSourceReselectionCannotPublishOldArtifactOrReleaseTheNewSource() {
        val f = Fixture()
        f.services.holdPrepare = true
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.services.pendingPrepare.isNotEmpty() }
            val old = f.services.pendingPrepare.single()
            val oldSource = assertNotNull(f.probe!!.editor.source)
            f.services.holdPrepare = false
            scene.clickText("选择其他视频", f); scene.await { f.ready() != null }
            val replacement = assertNotNull(f.ready())
            assertTrue(f.probe!!.editor.source !== oldSource)
            assertEquals(0, f.services.sourcesReleased[0].get(), "The pending worker still owns its retained old source.")
            old.complete(Unit); scene.await { f.services.sourcesReleased[0].get() == 1 }
            assertSame(replacement, f.ready())
            assertEquals(0, f.services.sourcesReleased[1].get())
            assertTrue(f.services.animationReleased.last().get() == 1, "Late animation is released rather than installed.")
        }
    }

    @Test fun lateSelectedCoverAfterEditingAndClosingReleasesItsOwnAnimationAndCover() {
        val f = Fixture()
        f.services.holdCover = true
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.services.pendingCover.isNotEmpty() }
            val pending = f.services.pendingCover.single()
            scene.replaceEditor(2, "1", f)
            val oldDismiss = assertNotNull(f.probe).dismiss
            onUi { oldDismiss() }; scene.settledFrame()
            assertNull(f.opening)
            pending.complete(Unit)
            scene.await { f.services.coverReleased.isNotEmpty() && f.services.coverReleased[0].get() == 1 }
            assertEquals(1, f.services.animationReleased[0].get())
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun frozenOldConfirmationCannotSubmitAfterReturnToEditCancelOrReopeningTheSameAccount() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.ready() != null }
            scene.clickText("上传视频头像", f)
            val old = assertNotNull(f.probe).submit
            val first = assertNotNull(f.probe!!.editor.confirmation)
            scene.clickText("返回编辑", f)
            onUi { old() }; assertTrue(f.writes.isEmpty()); assertFalse(first.active())
            scene.clickText("上传视频头像", f)
            val cancelled = assertNotNull(f.probe).submit
            val dismiss = assertNotNull(f.probe).dismiss
            onUi { dismiss(); f.open(); cancelled() }; scene.settledFrame()
            scene.await { f.ready() != null }
            onUi { old(); cancelled() }
            assertTrue(f.writes.isEmpty())
            assertTrue(assertNotNull(f.opening).lease.active())
        }
    }

    @Test fun explicitUploadRetainsNativeHandleAcrossCloseAndOldAcceptanceCannotConsumeNewOpening() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.ready() != null }
            val value = assertNotNull(f.ready())
            scene.clickText("上传视频头像", f); scene.clickText("确认上传", f)
            assertSame(value.animation, f.writes.single())
            assertEquals(1, f.nativeHandles.size)
            scene.clickText("关闭", f)
            assertFalse(value.active())
            assertEquals(0, f.services.animationReleased[0].get(), "A native retain survives releasing the UI owner.")
            assertEquals(1, f.services.coverReleased[0].get())
            onUi { f.snapshot = f.snapshot.copy(busy = false); f.open() }; scene.settledFrame()
            scene.await { f.ready() != null }
            val fresh = assertNotNull(f.opening)
            onUi { f.snapshot = f.snapshot.copy(acceptedRevision = 1, completedRevision = 1) }
            scene.settledFrame()
            assertSame(fresh, f.opening); assertFalse(fresh.submissionPending)
            f.nativeHandles.removeAt(0).release()
            assertEquals(1, f.services.animationReleased[0].get())
        }
    }

    @Test fun playbackRequiresExplicitClickAndStopsWhenPreviewIsOffscreenUnfocusedOrClosed() {
        // Leave enough scroll travel to move the whole preview out of view,
        // including hosts whose text fallback metrics differ from Windows.
        val f = Fixture(height = 600)
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.ready() != null }
            assertTrue(f.preview.playing.isEmpty())
            scene.clickText("静音播放", f)
            scene.revealDescription("视频头像正方形裁切区域", f); scene.settledFrame()
            assertTrue(f.preview.playing.isNotEmpty())
            onUi { f.focused = false }; scene.settledFrame()
            assertTrue(f.preview.playing.isEmpty())
            onUi { f.focused = true }; scene.settledFrame()
            scene.revealEditor(2, f); scene.settledFrame()
            scene.scrollPreviewOutsideBody(f)
            assertTrue(f.preview.playing.isEmpty(), "The image scrolls out of the dialog body, so its host is removed.")
            onUi { assertNotNull(f.probe).dismiss() }; scene.settledFrame()
            assertTrue(f.preview.playing.isEmpty())
            assertTrue(f.services.sourcesReleased.all { it.get() == 1 })
        }
    }

    @Test fun previewRetainsBothFilesUntilItsActualChildDisposesAfterSynchronousEditInvalidation() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.ready() != null }
            scene.revealDescription("视频头像正方形裁切区域", f); scene.settledFrame()
            val old = assertNotNull(f.ready())
            onUi {
                f.probe!!.editor.change(f.probe!!.editor.draft.copy(cover = "1"))
                assertFalse(old.active())
                assertEquals(0, f.services.animationReleased[0].get())
                assertEquals(0, f.services.coverReleased[0].get())
            }
            scene.settledFrame()
            val events = f.services.events
            val child = events.indexOf("child:${old.animation.path}")
            assertTrue(child >= 0)
            assertTrue(events.indexOf("animation:0") > child, events.toString())
            assertTrue(events.indexOf("cover:0") > child, events.toString())
        }
    }

    @Test fun rejectedQueuedAdmissionWithoutRevisionUnblocksRetryAndOldAckCannotUnsubmitTheNewRequest() {
        val f = Fixture()
        f.holdAdmission = true
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.ready() != null }
            onUi { f.snapshot = f.snapshot.copy(error = SettingsError.CONFLICT, operation = SettingsProfileExtrasOperation.BIRTHDATE) }
            scene.settledFrame(); scene.clickText("上传视频头像", f)
            val oldSubmit = assertNotNull(f.probe).submit
            onUi { f.snapshot = f.snapshot.copy(data = f.snapshot.data!!.copy(photoId = 78)) }; scene.settledFrame()
            onUi { oldSubmit() }
            assertTrue(f.admissions.isEmpty())
            assertEquals(77L, f.opening!!.expectedPhotoId)
            assertTrue(scene.texts("当前头像已更改，请关闭后重新选择。").isNotEmpty())
            onUi { f.snapshot = f.snapshot.copy(data = f.snapshot.data!!.copy(photoId = 77)) }; scene.settledFrame()
            onUi { f.snapshot = f.snapshot.copy(loading = true) }; scene.settledFrame()
            onUi { oldSubmit() }; assertTrue(f.admissions.isEmpty(), "Loading blocks even a previously captured actual submit.")
            onUi { f.snapshot = f.snapshot.copy(loading = false) }; scene.settledFrame()
            scene.clickText("确认上传", f)
            val first = f.admissions.single()
            assertTrue(f.probe!!.editor.submitting)
            assertEquals(0L, f.snapshot.startedRevision)
            Thread { first(null) }.apply { start(); join() }
            scene.await { f.probe?.editor?.submitting == false }
            assertFalse(f.opening!!.submissionPending)
            scene.clickText("确认上传", f)
            assertEquals(2, f.admissions.size)
            val current = f.probe!!.editor.submission
            Thread { first(1L); first(null) }.apply { start(); join() }
            scene.settledFrame()
            assertSame(current, f.probe!!.editor.submission)
            assertTrue(f.probe!!.editor.submitting)
            f.admissions.last()(null); scene.await { f.probe?.editor?.submitting == false }
            f.holdAdmission = false
            scene.clickText("确认上传", f)
            assertEquals(1, f.writes.size)
            assertEquals(1L, f.snapshot.startedRevision)
        }
        f.nativeHandles.forEach { it.release() }; f.nativeHandles.clear()
    }

    @Test fun sameBaseOldAcceptanceCannotConsumeNewRejectedOpeningAndLateExactRevisionConsumesOnlyItsOwnResult() {
        val f = Fixture()
        f.holdAdmission = true
        f.scene().use { scene ->
            scene.settledFrame(); scene.await { f.ready() != null }
            scene.clickText("上传视频头像", f); scene.clickText("确认上传", f)
            val oldAck = f.admissions.single()
            val oldOpening = assertNotNull(f.opening)
            assertEquals(0L, f.snapshot.startedRevision)
            onUi { assertNotNull(f.probe).dismiss(); f.open() }; scene.settledFrame()
            scene.await { f.ready() != null }
            val freshOpening = assertNotNull(f.opening)
            assertTrue(freshOpening !== oldOpening)
            scene.clickText("上传视频头像", f); scene.clickText("确认上传", f)
            val rejectedAck = f.admissions.last()
            assertEquals(0L, f.snapshot.startedRevision, "Both openings saw the same last published revision.")
            onUi {
                oldAck(1L)
                f.snapshot = f.snapshot.copy(startedRevision = 1, acceptedRevision = 1, completedRevision = 1,
                    operation = SettingsProfileExtrasOperation.UPLOAD_ANIMATION)
            }
            scene.settledFrame()
            assertSame(freshOpening, f.opening)
            assertTrue(f.probe!!.editor.submitting)
            assertNull(f.probe!!.editor.submission?.admittedRevision)
            Thread { rejectedAck(null) }.apply { start(); join() }
            scene.await { f.probe?.editor?.submitting == false }
            assertSame(freshOpening, f.opening)
            assertFalse(freshOpening.submissionPending)
            scene.clickText("确认上传", f)
            val ownAck = f.admissions.last()
            assertEquals(3, f.admissions.size, "A rendered enabled retry must create a distinct admission callback.")
            assertTrue(ownAck !== rejectedAck)
            onUi { f.snapshot = f.snapshot.copy(startedRevision = 7, acceptedRevision = 7, completedRevision = 7) }
            scene.settledFrame()
            assertSame(freshOpening, f.opening, "No snapshot is consumed while this request lacks its actor receipt.")
            assertNull(f.probe!!.editor.submission?.admittedRevision)
            Thread { ownAck(7L) }.apply { start(); join() }
            scene.await { f.opening == null }
            assertFalse(freshOpening.lease.active(), "The late real receipt rechecks and consumes the already published exact result.")
        }
    }

    @Test fun actualCoverAndConfirmationAreReachableInLowWindowsAcrossThemeAndDensity() {
        for ((width, height) in listOf(620 to 900, 350 to 300)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark))
            for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode)
                f.scene().use { scene ->
                    scene.settledFrame(); scene.await { f.ready() != null }
                    scene.revealDescription("视频头像正方形裁切区域", f)
                    scene.await { scene.unmergedSemanticsBounds {
                        "合成服务返回的实际封面" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }.any { it.width > 0 && it.height > 0 } }
                    scene.revealDescription("合成服务返回的实际封面", f)
                    val image = scene.description("合成服务返回的实际封面")
                    val body = assertNotNull(f.probe).contentViewport
                    assertTrue(image.width > 0 && image.height > 0)
                    assertTrue(kotlin.math.abs(image.width - image.height) <= 1f, "The complete cover remains square.")
                    assertTrue(image.left >= body.left - .5f && image.top >= body.top - .5f &&
                        image.right <= body.right + .5f && image.bottom <= body.bottom + .5f, "The full cover must be inside the real body.")
                    scene.clickText("静音播放", f)
                    assertTrue(f.preview.playing.isNotEmpty(), "The fixed footer can start playback while the full cover remains visible.")
                    scene.clickText("暂停预览", f)
                    val editorFrame = scene.settledFrame()
                    val color = editorFrame[(image.left + image.width * .25f).roundToInt(), image.center.y.roundToInt()]
                    assertTrue((color and 255) > (color ushr 16 and 255) + 30, "The injected real JPEG must paint its blue half.")
                    editorFrame.save("editor-${width}x$height-$mode-$density")
                    scene.clickText("上传视频头像", f)
                    scene.revealText("确认上传", f)
                    scene.revealDescription("合成服务返回的实际封面", f)
                    val confirmImage = scene.description("合成服务返回的实际封面")
                    val confirmBody = assertNotNull(f.probe).contentViewport
                    assertTrue(kotlin.math.abs(confirmImage.width - confirmImage.height) <= 1f)
                    assertTrue(confirmImage.left >= confirmBody.left - .5f && confirmImage.top >= confirmBody.top - .5f &&
                        confirmImage.right <= confirmBody.right + .5f && confirmImage.bottom <= confirmBody.bottom + .5f)
                    scene.settledFrame().save("confirm-${width}x$height-$mode-$density")
                    scene.clickText("返回编辑", f)
                    scene.clickText("取消", f)
                    assertNull(f.opening)
                }
            }
    }

    private class FakeServices : ProfileVideoEditorServices {
        var reads = 0
        val readPaths = mutableListOf<String>()
        var holdPrepare = false; var holdCover = false; var failPrepare = false
        var readFailure: ProfilePhotoVideoFailure.Reason? = null
        val events = mutableListOf<String>()
        val edits = mutableListOf<ProfilePhotoVideoEdit>()
        val coverInputs = mutableListOf<SettingsPreparedProfileAnimation>()
        val pendingPrepare = mutableListOf<CompletableDeferred<Unit>>()
        val pendingCover = mutableListOf<CompletableDeferred<Unit>>()
        val sourcesReleased = mutableListOf<AtomicInteger>()
        val animationReleased = mutableListOf<AtomicInteger>()
        val coverReleased = mutableListOf<AtomicInteger>()
        override suspend fun read(path: String): ProfilePhotoVideoSource {
            reads++; readPaths += path
            readFailure?.let { throw ProfilePhotoVideoFailure(it) }
            val cleanup = AtomicInteger().also(sourcesReleased::add)
            return ProfilePhotoVideoSource(File(path).toPath(), 12, 640, 360, 15000, 0, "source-$reads",
                ownedAttachmentLease { cleanup.incrementAndGet() })
        }
        override suspend fun prepare(source: ProfilePhotoVideoSource, edit: ProfilePhotoVideoEdit): SettingsPreparedProfileAnimation {
            edits += edit
            val retained = assertNotNull(source.retain())
            try {
                if (holdPrepare) {
                    val pending = CompletableDeferred<Unit>().also(pendingPrepare::add)
                    withContext(NonCancellable) { pending.await() }
                }
                if (failPrepare) error("synthetic preparation failure")
                val cleanup = AtomicInteger().also(animationReleased::add)
                val index = animationReleased.lastIndex
                return SettingsPreparedProfileAnimation(File(directory, "prepared-${animationReleased.size}.mp4").absolutePath,
                    1000, 800, edit.tillMillis - edit.fromMillis, (edit.coverMillis - edit.fromMillis) / 1000.0,
                    "a".repeat(64), ownedAttachmentLease { cleanup.incrementAndGet(); events += "animation:$index" })
            } finally { retained.release() }
        }
        override suspend fun cover(animation: SettingsPreparedProfileAnimation): SettingsPreparedProfilePhoto {
            coverInputs += animation
            val retained = assertNotNull(animation.lease.retain())
            try {
                if (holdCover) {
                    val pending = CompletableDeferred<Unit>().also(pendingCover::add)
                    withContext(NonCancellable) { pending.await() }
                }
                val cleanup = AtomicInteger().also(coverReleased::add)
                val index = coverReleased.lastIndex
                return SettingsPreparedProfilePhoto(coverImage.absolutePath, coverImage.length(), 320,
                    "b".repeat(64), ownedAttachmentLease { cleanup.incrementAndGet(); events += "cover:$index" })
            } finally { retained.release() }
        }
    }

    private class PreviewProbe(val events: MutableList<String>) : ProfileVideoPreviewHost {
        val playing = mutableSetOf<ProfileVideoPrepared>()
        @Composable override fun Content(prepared: ProfileVideoPrepared, playing: Boolean, modifier: Modifier) {
            SideEffect { if (playing) this.playing += prepared else this.playing -= prepared }
            DisposableEffect(prepared) { onDispose { events += "child:${prepared.animation.path}"; this@PreviewProbe.playing -= prepared } }
            Box(modifier) {
                MediaPreview(prepared.cover.path, MediaFormat.PHOTO, Modifier.fillMaxSize(), "合成服务返回的实际封面", animate = false)
                if (playing) Text("合成静音播放中")
            }
        }
    }

    private class Fixture(val width: Int = 620, val height: Int = 900, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light, val fullProfile: Boolean = false,
        val initialPath: String? = null,
    ) {
        var pickerCalls = 0
        val pickerTitles = mutableListOf<String>()
        val pickerExtensions = mutableListOf<Set<String>>()
        val pixelHeight get() = (height * density).roundToInt()
        val services = FakeServices(); val preview = PreviewProbe(services.events)
        var focused by mutableStateOf(true)
        var opening by mutableStateOf<SettingsProfileVideoOpening?>(null)
        var snapshot by mutableStateOf(SettingsProfileExtrasSnapshot(SettingsProfileExtrasToken(7, 3, 43),
            SettingsProfileExtrasData(43, 77, null, SettingsCalendarDate(27, 10, 2026))))
        var probe: ProfileVideoUiProbe? = null
        val writes = mutableListOf<SettingsPreparedProfileAnimation>()
        val nativeHandles = mutableListOf<AttachmentLease>()
        val admissions = mutableListOf<(Long?) -> Unit>()
        var holdAdmission = false
        private fun upload(animation: SettingsPreparedProfileAnimation, admitted: (Long?) -> Unit) {
            admissions += admitted
            if (holdAdmission) return
            nativeHandles += assertNotNull(animation.lease.retain()); writes += animation
            snapshot = snapshot.copy(busy = true, startedRevision = snapshot.startedRevision + 1,
                operation = SettingsProfileExtrasOperation.UPLOAD_ANIMATION, error = null)
            admitted(snapshot.startedRevision)
        }
        init { if (!fullProfile) open() }
        fun open() { opening?.close(); opening = SettingsProfileVideoOpening(snapshot.token, snapshot.data?.photoId, initialPath) }
        fun ready() = probe?.editor?.prepared
        fun scene() = ComposePixelScene((width * density).roundToInt(), pixelHeight) {
            val theme = remember { ThemeController(mode) }
            val nativeWindow = LocalWindowInfo.current
            val latestFocus by rememberUpdatedState(focused)
            val window = remember(nativeWindow) { object : WindowInfo by nativeWindow { override val isWindowFocused get() = latestFocus } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides window,
                LocalSettingsPageActive provides true, LocalSettingsSecurityConfirmationHost provides InlineHost,
                LocalNativeFilePicker provides { title, extensions, _ ->
                    pickerCalls++; pickerTitles += title; pickerExtensions += assertNotNull(extensions); selectedVideo.absolutePath
                },
                LocalProfileVideoEditorServices provides services, LocalProfileVideoPreviewHost provides preview,
                LocalProfileVideoUiProbe provides { probe = it }) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        if (fullProfile) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                            SettingsProfileExtrasContent(SettingsToken(7, 1), 43, snapshot,
                                SettingsProfileExtrasActions(uploadAnimation = { _, _, animation, admitted -> upload(animation, admitted) }), true)
                        }
                        else SettingsProfileVideoPicker(opening, snapshot, true,
                            onDismiss = { expected -> if (opening === expected) { expected.close(); opening = null } },
                            onUpload = { expected, animation, admitted ->
                                if (opening === expected && expected.lease.active() && !snapshot.busy && !expected.submissionPending) {
                                    expected.submissionPending = true; upload(animation, admitted)
                                } else admitted(null)
                            })
                    }
                }
            }
        }
    }

    private object InlineHost : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit,
        ) {
            if (show) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val height = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
                Column(Modifier.widthIn(max = maxWidth).fillMaxWidth().heightIn(max = (height - 24.dp).coerceAtLeast(140.dp))
                    .chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 24.dp).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) { title?.let { Text(it) }; content() }
            }
        }
    }

    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }
    private fun ComposePixelScene.description(label: String) = assertNotNull(unmergedSemanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.lastOrNull(), label)
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y); settledFrame()
    }
    private fun ComposePixelScene.clickText(label: String, f: Fixture, requireEnabled: Boolean = true) {
        val target = revealText(label, f)
        settledFrame(2, 16) // Commit effects/layout before resolving the actual toolkit click target.
        if (requireEnabled) assertTrue(semanticsBounds { config ->
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
                config.getOrNull(SemanticsActions.OnClick) != null && config.getOrNull(SemanticsProperties.Disabled) == null
        }.any { it.overlaps(target) }, "The actual rendered button must be enabled: $label")
        click(target)
    }
    private fun ComposePixelScene.revealText(label: String, f: Fixture) = reveal(f,
        footer = label in listOf("取消", "关闭", "上传视频头像", "确认上传", "返回编辑", "静音播放", "暂停预览")) { texts(label) }
    private fun ComposePixelScene.revealDescription(label: String, f: Fixture) = reveal(f) {
        val measured = unmergedSemanticsBounds { label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        if (label in listOf("视频头像正方形裁切区域", "合成服务返回的实际封面") && measured.isNotEmpty())
            listOfNotNull(f.probe?.previewBounds?.takeIf { it.width > 0 && it.height > 0 })
        else measured
    }
    private fun ComposePixelScene.revealEditor(index: Int, f: Fixture): Rect = reveal(f) {
        val label = "视频头像" + listOf("片段起点（秒）", "片段终点（秒）", "封面位置（秒）")[index]
        unmergedSemanticsBounds { label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .filter { it.height >= 48f * f.density }
    }
    private fun ComposePixelScene.replaceEditor(index: Int, value: String, f: Fixture) {
        val field = revealEditor(index, f)
        val editable = assertNotNull(unmergedSemanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }
            .filter { it.overlaps(field) }.maxByOrNull { it.intersect(field).width * it.intersect(field).height }, "Actual editable text hit region")
        click(editable.intersect(field))
        settledFrame(2, 16)
        val focused = unmergedSemanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null &&
            it.getOrNull(SemanticsProperties.Focused) == true }
        assertTrue(focused.any { it.overlaps(field) }, "Pointer click must focus the actual editor: field=$field editable=$editable focused=$focused")
        selectAll(); settledFrame(2, 16)
        keyStroke(KeyEvent.VK_BACK_SPACE); settledFrame(2, 16)
        value.forEach { character -> keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character); settledFrame(1, 16) }
        settledFrame()
        var typed: String? = null
        unmergedSemanticsBounds { config ->
            if (config.getOrNull(SemanticsProperties.Focused) == true) config.getOrNull(SemanticsProperties.EditableText)?.text?.let { typed = it }
            false
        }
        assertEquals(value, typed, "Real keyboard events must replace the focused field")
    }
    private fun ComposePixelScene.reveal(f: Fixture, footer: Boolean = false, candidates: () -> List<Rect>): Rect {
        val window = Rect(0f, 0f, f.width * f.density, f.pixelHeight.toFloat())
        fun viewport(): Rect {
            val probe = f.probe ?: return window
            return (if (footer) probe.actionsViewport else probe.contentViewport).intersect(window)
        }
        fun visible(): Rect? {
            val clip = viewport()
            if (clip.width <= 0 || clip.height <= 0) return null
            return candidates().firstNotNullOfOrNull { rect ->
                val hit = rect.intersect(clip)
                when {
                    rect.width <= 0 || rect.height <= 0 || hit.width <= 0 || hit.height < 8 * f.density -> null
                    rect.top < clip.top - .5f || rect.bottom > clip.bottom + .5f -> null
                    else -> hit
                }
            }
        }
        var down = false
        var previousDirection = 0
        var step = 8f * f.density
        repeat(240) {
            visible()?.let { return it }
            val target = candidates().firstOrNull { it.height > 0 && it.width > 0 }
            val clip = viewport()
            var position = 0f
            val scrollers = semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { position = it.value() }; config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            }
            if (position <= .5f) down = true
            val body = f.probe?.contentViewport?.intersect(window)
            val scroller = scrollers.lastOrNull { it.height > 12 && (body == null || it.overlaps(body)) }
            val direction = when { target != null -> if (target.top < clip.top - .5f) -1 else 1; down -> 1; else -> -1 }
            if (previousDirection != 0 && direction != previousDirection) step = (step / 2).coerceAtLeast(.01f)
            previousDirection = direction
            val scrollRegion = body?.takeIf { it.width > 0 && it.height > 0 } ?: scroller ?: window
            scrollAt(scrollRegion.right - 2f, scrollRegion.top + 2f,
                deltaY = direction * step); settledFrame(5, 32)
        }
        return assertNotNull(visible(), "Pointer scroll must reveal target in actual dialog viewport ${viewport()} at ${f.width}x${f.height}: ${candidates()}")
    }
    private fun ComposePixelScene.await(predicate: () -> Boolean) {
        repeat(160) {
            settledFrame(3, 16)
            var ready = false
            onUi { ready = predicate() }
            if (ready) {
                settledFrame(2, 16) // State readiness alone is not a committed enabled control/layout.
                onUi { ready = predicate() }
                if (ready) return
            }
            Thread.sleep(5)
        }
        assertTrue(predicate(), "UI worker/owner did not settle.")
    }
    private fun ComposePixelScene.scrollPreviewOutsideBody(f: Fixture) {
        repeat(32) {
            val body = assertNotNull(f.probe).contentViewport
            val preview = assertNotNull(f.probe).previewBounds
            if (!preview.overlaps(body)) {
                settledFrame(2, 16)
                return
            }
            scrollAt(body.right - 2f, body.top + 2f, deltaY = 8f * f.density)
            settledFrame(5, 32)
        }
        assertFalse(assertNotNull(f.probe).previewBounds.overlaps(assertNotNull(f.probe).contentViewport),
            "Pointer scrolling must move the actual preview wholly outside the measured body.")
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File(directory, "frames/$name.png"); output.parentFile.mkdirs(); ImageIO.write(image, "png", output)
    }
    private fun onUi(action: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action) }

    companion object {
        private val directory = File("build/settings-profile-video-ui-validation").absoluteFile.also { it.mkdirs() }
        private val selectedVideo: File by lazy {
            File(directory, "fixture/selected.mov").also { file ->
                file.parentFile.mkdirs()
                // Real content classification rejects this as an image and hands it to the injected video validator.
                file.writeBytes(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109))
            }
        }
        private val coverImage: File by lazy {
            File(directory, "fixture/selected-cover.jpg").also { file ->
                file.parentFile.mkdirs()
                val image = BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until 320) for (x in 0 until 320) image.setRGB(x, y, if (x < 160) 0x3e65cd else 0xd14678)
                ImageIO.write(image, "jpg", file)
            }
        }
    }
}
