package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.media.LocalWindowActivity
import com.badnng.moe.settings.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Synthetic provider/tickets only; these tests do not validate TDLib, actual codec or server pixels. */
class SettingsProfileStickerAvatarUiTest {
    @Test fun pickerOpenLeaseRemainsValidThroughNativeDefaultSelectAndSaveUntilClose() {
        val f = Fixture(); f.strictOwner = true
        f.scene().use { s ->
            s.selectFirst(f)
            val picker = assertNotNull(f.opening!!.picker)
            val openActive = assertNotNull(f.openOwners[picker]); assertTrue(openActive())
            val selection = assertNotNull(f.opening!!.editor.selection)
            assertTrue(f.ownerChecks.contains("default" to true)); assertTrue(f.ownerChecks.contains("select" to true))
            s.clickText("保存"); s.clickText("确认保存")
            assertTrue(f.ownerChecks.contains("save" to true)); assertSame(selection, f.writes.single().selection)
            assertTrue(openActive()); s.clickText("关闭")
            assertFalse(openActive()); assertTrue(f.closed.any { it === picker })
        }
    }

    @Test fun visibleCellsAcquireOnlyThumbnailsAndPointerSelectionAcquiresASeparateTransparentPreview() {
        val f = Fixture()
        f.scene().use { s ->
            s.await { f.opening?.candidates?.isNotEmpty() == true && f.provider.thumbnails.isNotEmpty() }
            assertTrue(f.provider.thumbnails.size < f.candidates.size)
            assertEquals(0, f.provider.previews.size)
            s.await { s.enabledDescription("候选 1") }; s.clickDescription("候选 1"); s.await { f.provider.previews.isNotEmpty() && f.provider.frames.isNotEmpty() }
            val frame = s.settledFrame(); val rect = s.description("头像预览")
            val center = frame[rect.center.x.toInt(), rect.center.y.toInt()] and 0xFFFFFF
            val outer = frame[(rect.left + rect.width * .08f).toInt(), rect.center.y.toInt()] and 0xFFFFFF
            assertEquals(0xED3344, center, "The explicit synthetic artwork is rendered in its original color.")
            assertNotEquals(center, outer, "Transparent art leaves the actual gradient visible outside its shape.")
            assertEquals(1, f.selects.size); assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun realPackTabAndKeyboardFilterOpenOnlyTheChosenInstalledPack() {
        val f = Fixture()
        f.scene().use { s ->
            s.await { f.opening?.picker != null }; s.clickText("表情包")
            s.await { s.texts("Moon pack").isNotEmpty() }
            s.replaceEditor("Moon"); s.clickText("搜索已安装的包")
            s.await { s.texts("Moon pack").isNotEmpty() && s.texts("Other pack").isEmpty() }
            s.clickText("Moon pack"); s.await { f.packOpens.size == 1 && f.opening?.candidates?.isNotEmpty() == true }
            assertSame(f.packs[0].ticket, f.packOpens.single()); assertTrue(f.provider.previews.isEmpty())
            s.await { s.enabledDescription("候选 1") }; s.clickDescription("候选 1"); s.await { f.selects.isNotEmpty() }
        }
    }

    @Test fun actualHslSliderAndColorButtonsCancelRestoreAndRetainConfirmedCustomColors() {
        val f = Fixture()
        f.scene().use { s ->
            s.await { f.opening?.picker != null }; val before = f.opening!!.editor.colors.values()
            s.clickText("自定义颜色"); s.clickText("－"); s.clickText("－"); s.clickText("－")
            assertEquals(1, f.opening!!.colorsEdit!!.colors().size)
            s.clickText("＋"); assertEquals(2, f.opening!!.colorsEdit!!.colors().size)
            val edit = assertNotNull(f.opening!!.colorsEdit); val slider = s.description("色相")
            s.click(slider)
            assertTrue(s.unmergedSemanticsBounds { it.getOrNull(SemanticsProperties.Focused) == true }.any { it.overlaps(slider) },
                "The actual pointer press must focus this slider.")
            val afterPointer = f.opening!!.colorHsl(edit).hue; val pointerRgb = edit.colors().last()
            s.keyStroke(KeyEvent.VK_RIGHT); s.settledFrame(3, 16)
            val afterRight = f.opening!!.colorHsl(edit).hue
            assertEquals(afterPointer + 12f, afterRight, .01f); assertNotEquals(pointerRgb, edit.colors().last())
            s.keyStroke(KeyEvent.VK_LEFT); s.settledFrame(3, 16)
            assertEquals(afterPointer, f.opening!!.colorHsl(edit).hue, .01f)
            s.clickText("取消颜色"); assertEquals(before, f.opening!!.editor.colors.values())
            s.clickText("自定义颜色"); s.clickText("－"); s.clickText("应用颜色")
            val custom = f.opening!!.editor.colors.values(); assertEquals(3, custom.size)
            s.clickDescription("预设颜色 2"); assertNotEquals(custom, f.opening!!.editor.colors.values())
            s.clickText("恢复自定义"); assertEquals(custom, f.opening!!.editor.colors.values())
        }
    }

    @Test fun disabledAndDetachedHslKeyboardActionsCannotMutateAnEditOrReselectedSlot() {
        val f = Fixture()
        f.scene().use { s ->
            s.await { f.opening?.picker != null }; s.clickText("自定义颜色")
            repeat(3) { s.clickText("－") }; s.clickText("＋")
            val edit = assertNotNull(f.opening!!.colorsEdit); val slider = s.description("色相")
            s.click(slider); s.await { f.hslProbes.any { it.label == "色相" } }
            assertTrue(s.unmergedSemanticsBounds { it.getOrNull(SemanticsProperties.Focused) == true }.any { it.overlaps(slider) })
            val staleStep = f.hslProbes.last { it.label == "色相" }.keyStep
            onUi { f.snapshot = f.snapshot.copy(busy = true) }; s.settledFrame(3, 16)
            val disabledHsl = f.opening!!.colorHsl(edit); val disabledRgb = edit.colors()
            s.keyStroke(KeyEvent.VK_RIGHT); onUi { staleStep(1) }; s.settledFrame(3, 16)
            assertEquals(disabledHsl, f.opening!!.colorHsl(edit)); assertEquals(disabledRgb, edit.colors())
            onUi { f.snapshot = f.snapshot.copy(busy = false) }; s.settledFrame(3, 16)

            s.clickDescription("颜色 1"); val first = f.opening!!.colorHsl(edit); val firstRgb = edit.colors()
            onUi { staleStep(1) }; s.settledFrame(3, 16)
            assertEquals(first, f.opening!!.colorHsl(edit)); assertEquals(firstRgb, edit.colors())
            s.clickDescription("颜色 2"); val reselected = f.opening!!.colorHsl(edit); val reselectedRgb = edit.colors()
            onUi { staleStep(1) }; s.settledFrame(3, 16)
            assertEquals(reselected, f.opening!!.colorHsl(edit)); assertEquals(reselectedRgb, edit.colors())

            s.clickText("取消颜色"); val original = f.opening!!.editor.colors.values()
            s.clickText("自定义颜色"); val next = assertNotNull(f.opening!!.colorsEdit)
            assertNotSame(edit, next); val nextHsl = f.opening!!.colorHsl(next); val nextRgb = next.colors()
            onUi { staleStep(1) }; s.settledFrame(3, 16)
            assertSame(next, f.opening!!.colorsEdit); assertEquals(nextHsl, f.opening!!.colorHsl(next)); assertEquals(nextRgb, next.colors())
            s.clickText("取消颜色"); assertEquals(original, f.opening!!.editor.colors.values())
        }
    }

    @Test fun grayHueSurvivesSaturationChangeCancelAndConfirmedCustomRestoration() {
        val f = Fixture()
        f.scene().use { s ->
            s.await { f.opening?.picker != null }; val initial = f.opening!!.editor.colors.values()
            s.clickText("自定义颜色"); repeat(3) { s.clickText("－") }
            val edit = assertNotNull(f.opening!!.colorsEdit)
            val slotRevision = f.opening!!.colorControlRevision
            s.slider("饱和度", 0f); val gray = edit.colors().single()
            assertEquals(0f, f.opening!!.colorHsl(edit).saturation, .001f)
            assertEquals(slotRevision, f.opening!!.colorControlRevision, "Dragging the same slot does not retire its pointer callbacks.")
            s.slider("色相", .65f); val chosenHue = f.opening!!.colorHsl(edit).hue
            assertTrue(chosenHue > 180f); assertEquals(gray, edit.colors().single())
            s.slider("饱和度", 1f); assertTrue(f.opening!!.colorHsl(edit).saturation > .95f)
            assertEquals(slotRevision, f.opening!!.colorControlRevision)
            val saturated = edit.colors().single()
            assertTrue((saturated and 255) > (saturated shr 16 and 255), "Increasing saturation must use the selected blue hue.")
            s.clickText("取消颜色"); assertEquals(initial, f.opening!!.editor.colors.values())

            s.clickText("自定义颜色"); repeat(3) { s.clickText("－") }
            val applied = assertNotNull(f.opening!!.colorsEdit)
            s.slider("饱和度", 0f); s.slider("色相", .65f)
            val retainedHue = f.opening!!.colorHsl(applied).hue
            s.clickText("应用颜色"); val custom = f.opening!!.editor.colors.values()
            s.clickDescription("预设颜色 2"); s.clickText("恢复自定义")
            assertEquals(custom, f.opening!!.editor.colors.values()); s.clickText("自定义颜色")
            val restored = assertNotNull(f.opening!!.colorsEdit)
            assertEquals(retainedHue, f.opening!!.colorHsl(restored).hue, .01f)
            s.slider("饱和度", 1f)
            assertTrue((restored.colors().single() and 255) > (restored.colors().single() shr 16 and 255))
        }
    }

    @Test fun blackAndWhiteRetainSaturationAndEachColorSlotKeepsItsOwnHsl() {
        val f = Fixture()
        f.scene().use { s ->
            s.await { f.opening?.picker != null }; s.clickText("自定义颜色"); repeat(3) { s.clickText("－") }
            val edit = assertNotNull(f.opening!!.colorsEdit)
            s.slider("色相", .6f); s.slider("亮度", 0f); assertEquals(0, edit.colors().single())
            s.slider("饱和度", 1f); assertTrue(f.opening!!.colorHsl(edit).saturation > .95f)
            assertEquals(0, edit.colors().single()); s.slider("亮度", .5f)
            assertTrue(f.opening!!.colorHsl(edit).saturation > .95f); assertNotEquals(0, edit.colors().single())
            val firstHue = f.opening!!.colorHsl(edit).hue
            s.clickText("＋"); s.slider("色相", .2f); s.clickDescription("颜色 1")
            assertEquals(firstHue, f.opening!!.colorHsl(edit).hue, .01f)
            s.slider("亮度", 1f); assertEquals(0xFFFFFF, edit.colors().first())
            s.slider("饱和度", .35f); val saturation = f.opening!!.colorHsl(edit).saturation
            assertTrue(saturation in 0.2f..0.5f); assertEquals(0xFFFFFF, edit.colors().first())
            s.slider("亮度", .5f); assertEquals(saturation, f.opening!!.colorHsl(edit).saturation, .001f)
            assertNotEquals(0xFFFFFF, edit.colors().first()); s.clickText("取消颜色")
        }
    }

    @Test fun limitedPackPageIsDisclosedAndReplacedByTheNextCompletePage() {
        val f = Fixture(); f.limitedPacks = true
        f.scene().use { s ->
            s.await { f.opening?.picker != null }; s.clickText("表情包")
            s.await { s.texts("部分来源条目身份重复或冲突，无法选择。").isNotEmpty() }
            s.clickText("Moon pack"); s.await { f.opening!!.candidates.isNotEmpty() }
            assertTrue(s.texts("部分来源条目身份重复或冲突，无法选择。").isEmpty())
        }
    }

    @Test fun actualNextPageKeepsSelectionSuspendsPreviewAndSavesTheSameTicket() {
        val f = Fixture().apply { enableWindows = true; holdWindow = true; strictOwner = true }
        f.scene().use { s ->
            s.selectFirst(f)
            val selection = assertNotNull(f.opening!!.editor.selection)
            val oldPreview = f.provider.previews.last()
            val oldPixel = s.previewPixel()
            assertEquals(0xED3344, oldPixel)
            val previewRequests = f.provider.previews.size
            s.clickText("下一页")
            assertTrue(f.opening!!.loading)
            assertSame(selection, f.opening!!.editor.selection)
            assertTrue(s.disabledText("保存"))
            assertTrue(oldPreview.lease.closed.get())
            assertFalse(f.opening!!.selectionUsable)
            assertEquals(oldPixel, s.previewPixel(), "Previously prepared pixels remain while the old media grant is closed")
            val frozen = f.provider.frozenDisplays.last()
            assertFalse(frozen.closed.get())
            assertEquals(previewRequests, f.provider.previews.size, "No new full-media request during revalidation")
            assertEquals(1, f.windowCalls.size)
            val next = ProfileStickerAvatarCandidatePage(f.nextCandidates, false, previous = f.backCursor)
            worker { f.windowCalls.single()(SettingsResult(ProfileStickerAvatarCatalogWindow.Candidates(next))) }
            s.await { !f.opening!!.loading && f.opening!!.candidates == f.nextCandidates }
            assertSame(selection, f.opening!!.editor.selection)
            s.await { f.provider.previews.size >= 2 }
            assertFalse(f.provider.previews.last().lease.closed.get())
            s.await { frozen.closed.get() }
            s.clickText("保存"); s.clickText("确认保存")
            assertSame(selection, f.writes.single().selection)
            assertEquals(1, f.writes.size)
        }
    }

    @Test fun staleFrameCannotOverwriteFrozenPixelsWhileTheNewWindowIsPending() {
        val f = Fixture().apply { enableWindows = true; holdWindow = true; strictOwner = true }
        f.provider.animated = true; f.provider.holdFrames = true; f.provider.duration = 1800; f.provider.frameInterval = 33
        f.scene().use { s ->
            s.selectFirst(f, waitPreview = false)
            s.await { f.provider.pendingFrames.isNotEmpty() }
            val first = f.provider.pendingFrames.first()
            worker { f.provider.completeFrame(first) }
            s.await { f.provider.pendingFrames.size >= 2 }
            val shown = s.previewPixel(); assertEquals(first.color, shown)
            val delayed = f.provider.pendingFrames.last()
            val oldPreview = f.provider.previews.last()
            val previewRequests = f.provider.previews.size
            s.clickText("下一页")
            assertTrue(oldPreview.lease.closed.get()); assertTrue(delayed.lease.closed.get())
            assertFalse(delayed.active()); assertFalse(f.opening!!.selectionUsable)
            assertTrue(s.disabledText("保存")); assertEquals(shown, s.previewPixel())
            worker { f.provider.completeFrame(delayed) }
            s.settledFrame(4, 16)
            assertEquals(shown, s.previewPixel(), "A late old frame cannot replace the retained preview")
            assertEquals(previewRequests, f.provider.previews.size)
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun unavailableDisplayRetentionKeepsThePageAndAllowsAnExplicitRetry() {
        val f = Fixture().apply { enableWindows = true; holdWindow = true; strictOwner = true }
        f.scene().use { s ->
            s.selectFirst(f)
            val selected = assertNotNull(f.opening!!.editor.selection)
            val lease = f.provider.previews.last().lease
            val pixel = s.previewPixel()
            f.provider.failFreeze = true
            s.clickText("下一页")
            assertTrue(f.windowCalls.isEmpty())
            assertFalse(f.opening!!.loading); assertTrue(f.opening!!.selectionUsable)
            assertSame(selected, f.opening!!.editor.selection); assertFalse(lease.closed.get())
            assertEquals(pixel, s.previewPixel())
            s.await { s.texts("无法保留当前预览，未能翻页。请重试，或重新读取并选择。").isNotEmpty() }
            f.provider.failFreeze = false
            s.clickText("下一页")
            assertEquals(1, f.windowCalls.size)
            assertTrue(lease.closed.get()); assertEquals(pixel, s.previewPixel())
        }
    }

    @Test fun failedSaveReleasesDisplayHolderSoTheSameFrameCanBeRetainedForPaging() {
        for (admitted in listOf(false, true)) {
            val f = Fixture().apply { enableWindows = true; holdWindow = true; strictOwner = true }
            f.scene().use { s ->
                s.selectFirst(f)
                val selection = assertNotNull(f.opening!!.editor.selection)
                val pixel = s.previewPixel()
                s.clickText("保存"); s.clickText("确认保存")
                val write = f.writes.single()
                val savingHolder = f.provider.frozenDisplays.last()
                if (admitted) worker {
                    write.admit(17); write.result(ProfileStickerAvatarMutationResult(f.snapshot.token, 17, SettingsError.RATE_LIMITED))
                } else worker { write.admit(null) }
                s.await { !f.opening!!.waitingForSave && savingHolder.closed.get() }
                assertSame(selection, f.opening!!.editor.selection)
                assertTrue(f.opening!!.selectionUsable)
                assertEquals(pixel, s.previewPixel())
                s.clickText("下一页")
                assertEquals(1, f.windowCalls.size)
                val nextHolder = f.provider.frozenDisplays.last()
                assertNotSame(savingHolder, nextHolder)
                assertFalse(nextHolder.closed.get())
                assertEquals(pixel, s.previewPixel())
            }
        }
    }

    @Test fun failedWindowRetainsDraftButRequiresReloadAndARealNewSelection() {
        for (error in listOf(SettingsError.RATE_LIMITED, SettingsError.CONFLICT)) {
            val f = Fixture().apply { enableWindows = true; holdWindow = true; strictOwner = true }
            f.scene().use { s ->
                s.selectFirst(f)
                val oldSelection = assertNotNull(f.opening!!.editor.selection)
                val oldPreview = f.provider.previews.last()
                val originalPixel = s.previewPixel()
                val oldPreviewCount = f.provider.previews.size
                s.clickText("下一页")
                val frozen = f.provider.frozenDisplays.last()
                val callback = f.windowCalls.single()
                worker { callback(SettingsResult(error = error)) }
                s.await { !f.opening!!.loading && f.opening!!.error != null }
                assertSame(oldSelection, f.opening!!.editor.selection)
                assertTrue(f.opening!!.selectionNeedsReselection)
                assertTrue(s.disabledText("保存")); assertTrue(oldPreview.lease.closed.get())
                assertEquals(originalPixel, s.previewPixel())
                assertEquals(oldPreviewCount, f.provider.previews.size)
                assertTrue(f.writes.isEmpty())

                s.clickText("重试读取内容")
                s.await { !f.opening!!.loading && f.opening!!.candidates == f.candidates }
                assertSame(oldSelection, f.opening!!.editor.selection)
                assertFalse(f.opening!!.selectionUsable); assertTrue(s.disabledText("保存"))
                assertEquals(oldPreviewCount, f.provider.previews.size)
                worker { callback(SettingsResult(ProfileStickerAvatarCatalogWindow.Candidates(
                    ProfileStickerAvatarCandidatePage(f.nextCandidates, false, previous = f.backCursor)))) }
                s.settledFrame(4, 16)
                assertEquals(f.candidates, f.opening!!.candidates, "A late old window cannot overwrite the reload")
                assertFalse(f.opening!!.selectionUsable); assertTrue(f.writes.isEmpty())

                s.clickDescription("候选 1")
                s.await { f.opening!!.selectionUsable && f.opening!!.editor.selection !== oldSelection }
                s.await { frozen.closed.get() }
                assertEquals(2, f.selects.size)
                s.clickText("保存"); s.clickText("确认保存")
                assertSame(f.opening!!.editor.selection, f.writes.single().selection)
                assertNotSame(oldSelection, f.writes.single().selection)
            }
        }
    }

    @Test fun narrowDesignKeepsPreparedDraftPixelsAfterCatalogWindowFails() {
        val f = Fixture(350, 300).apply { enableWindows = true; holdWindow = true; strictOwner = true }
        f.scene().use { s ->
            s.selectFirst(f, waitPreview = false)
            val selection = assertNotNull(f.opening!!.editor.selection)
            s.click(s.revealCatalogPageControl("返回设计", f))
            s.scrollUntilRedPreview(f)
            val preview = f.provider.previews.last()
            val previews = f.provider.previews.size
            s.click(s.revealCatalogPageControl("选择表情或贴纸", f))
            assertTrue(preview.lease.closed.get())
            s.click(s.revealCatalogPageControl("下一页", f))
            val callback = f.windowCalls.single()
            worker { callback(SettingsResult(error = SettingsError.CONFLICT)) }
            s.await { !f.opening!!.loading && f.opening!!.selectionNeedsReselection }
            s.click(s.revealCatalogPageControl("返回设计", f))
            s.scrollUntilRedPreview(f)
            assertSame(selection, f.opening!!.editor.selection)
            assertFalse(f.opening!!.selectionUsable); assertTrue(s.disabledText("保存"))
            assertEquals(previews, f.provider.previews.size, "A stale draft cannot request a fresh full grant")
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun fullPackNamesCanBeExpandedAndMatchedAtEitherTail() {
        val f = Fixture()
        val title = "T".repeat(140) + "titleTail"
        val shortName = "s".repeat(140) + "nameTail"
        f.packs = listOf(ProfileStickerAvatarPack(ProfileStickerAvatarPackTicket(), title, 1,
            ProfileStickerAvatarPackKind.CUSTOM_EMOJI, shortName))
        f.scene().use { s ->
            s.await { f.opening?.picker != null }; s.clickText("表情包")
            s.await { s.texts("查看完整名称").isNotEmpty() }
            s.clickText("查看完整名称")
            s.await { s.texts(title).isNotEmpty() && s.texts("@$shortName").isNotEmpty() }
            s.clickText("收起完整名称")
            s.replaceEditor("titleTail"); s.clickText("搜索已安装的包")
            s.await { f.opening!!.packs.singleOrNull()?.title == title }
            assertEquals(shortName, f.opening!!.packs.single().shortName)
            s.replaceEditor("nameTail"); s.clickText("搜索已安装的包")
            s.await { f.opening!!.packs.singleOrNull()?.shortName == shortName }
            assertEquals(title, f.opening!!.packs.single().title)
        }
    }

    @Test fun localCatalogPagesRemainReachableAcrossWindowThemesAndDensities() {
        for ((width, height) in listOf(1000 to 700, 350 to 300))
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode).apply { enableWindows = true; strictOwner = true }
                f.scene().use { scene ->
                    scene.selectFirst(f, waitPreview = height > 400)
                    val selection = assertNotNull(f.opening!!.editor.selection)
                    val next = scene.revealCatalogPageControl("下一页", f)
                    scene.settledFrame().save("first-${width}x$height-$mode-$density", "profile-sticker-catalog")
                    scene.click(next)
                    scene.await { !f.opening!!.loading && f.opening!!.candidates == f.nextCandidates }
                    assertSame(selection, f.opening!!.editor.selection)
                    val previous = scene.revealCatalogPageControl("上一页", f)
                    scene.settledFrame().save("next-${width}x$height-$mode-$density", "profile-sticker-catalog")
                    assertTrue(previous.width > 0 && previous.height > 0)
                    scene.click(previous)
                    scene.await { !f.opening!!.loading && f.opening!!.candidates == f.candidates }
                    assertSame(selection, f.opening!!.editor.selection)
                    assertTrue(f.writes.isEmpty())
                    assertTrue(f.ownerChecks.contains("window" to true))
                    if (width == 350) {
                        scene.click(scene.revealCatalogPageControl("返回设计", f))
                        scene.await { scene.texts("选择表情或贴纸").isNotEmpty() }
                        scene.clickText("保存"); scene.clickText("确认保存")
                        assertSame(selection, f.writes.single().selection)
                    }
                }
            }
    }

    @Test fun catalogTabsAndWholeSquareRowsRemainReachableAcrossWindowThemesAndDensities() {
        for ((width, height) in listOf(1000 to 700, 350 to 300))
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode).apply { enableWindows = true; strictOwner = true }
                f.scene().use { scene ->
                    val suffix = "${width}x$height-$mode-$density"
                    scene.await { f.opening?.candidates?.isNotEmpty() == true && !f.opening!!.loading }
                    if (width == 350) {
                        scene.click(scene.completeCatalogControl("选择表情或贴纸", f))
                        val back = scene.completeCatalogControl("返回设计", f)
                        scene.settledFrame().save("return-$suffix", "profile-sticker-catalog-viewport")
                        scene.click(back)
                        scene.click(scene.completeCatalogControl("选择表情或贴纸", f))
                    }
                    scene.click(scene.completeCatalogControl("表情包", f))
                    scene.await { !f.opening!!.loading && (f.opening!!.source as? AvatarCatalogSource.Packs)?.kind == ProfileStickerAvatarPackKind.CUSTOM_EMOJI }
                    scene.click(scene.completeCatalogControl("贴纸", f))
                    scene.await { !f.opening!!.loading && (f.opening!!.source as? AvatarCatalogSource.Packs)?.kind == ProfileStickerAvatarPackKind.REGULAR }
                    scene.click(scene.completeCatalogControl("表情", f))
                    scene.await { !f.opening!!.loading && f.opening!!.source === AvatarCatalogSource.Default && f.opening!!.candidates == f.candidates }
                    for (label in listOf("表情", "表情包", "贴纸")) scene.completeCatalogControl(label, f)
                    scene.settledFrame().save("tabs-$suffix", "profile-sticker-catalog-viewport")

                    val firstRow = scene.revealWholeCatalogRow(f)
                    scene.settledFrame().save("first-row-$suffix", "profile-sticker-catalog-viewport")
                    scene.click(firstRow.first().full)
                    scene.await { f.opening!!.editor.selection != null }
                    assertSame(f.candidates.first().ticket, f.selects.single())
                    val firstSelection = assertNotNull(f.opening!!.editor.selection)
                    scene.click(scene.completeCatalogControl("下一页", f))
                    scene.await { !f.opening!!.loading && f.opening!!.candidates == f.nextCandidates }
                    assertSame(firstSelection, f.opening!!.editor.selection)

                    val nextRow = scene.revealWholeCatalogRow(f)
                    scene.settledFrame().save("next-row-$suffix", "profile-sticker-catalog-viewport")
                    scene.click(nextRow.first().full)
                    scene.await { f.opening!!.editor.selection != null && f.opening!!.editor.selection !== firstSelection }
                    assertSame(f.nextCandidates.first().ticket, f.selects.last())
                    assertEquals(2, f.selects.size)
                    val nextSelection = assertNotNull(f.opening!!.editor.selection)
                    scene.click(scene.completeCatalogControl("上一页", f))
                    scene.await { !f.opening!!.loading && f.opening!!.candidates == f.candidates }
                    assertSame(nextSelection, f.opening!!.editor.selection)
                    if (width == 350) {
                        scene.click(scene.completeCatalogControl("返回设计", f))
                        scene.completeCatalogControl("选择表情或贴纸", f)
                    }
                    assertTrue(f.writes.isEmpty())
                    assertTrue(f.ownerChecks.contains("window" to true))
                    assertTrue(f.ownerChecks.filter { it.first in listOf("default", "packs", "select", "window") }.all { it.second })
                }
            }
    }

    @Test fun nativeCatalogUpdateRetiresConfirmationAndPreviewAndRequiresFreshSelection() {
        val f = Fixture().apply { strictOwner = true }
        f.scene().use { scene ->
            scene.selectFirst(f)
            val picker = assertNotNull(f.opening!!.picker)
            val oldPreview = f.provider.previews.last()
            scene.clickText("保存")
            val oldSubmit = assertNotNull(f.probe).submit
            worker { picker.invalidateCatalog() }
            scene.await { f.opening!!.catalogInvalidated && f.opening!!.editor.selection == null }
            scene.await { oldPreview.lease.closed.get() }
            assertTrue(oldPreview.lease.closed.get())
            assertNull(f.opening!!.confirmation)
            onUi { oldSubmit() }; scene.settledFrame()
            assertTrue(f.writes.isEmpty())
            scene.clickText("重试读取内容")
            scene.await { !f.opening!!.catalogInvalidated && f.opening!!.candidates.isNotEmpty() }
            assertNull(f.opening!!.editor.selection)
            scene.await { scene.enabledDescription("候选 1") }; scene.clickDescription("候选 1")
            scene.await { f.opening!!.editor.selection != null }
            scene.clickText("保存"); scene.clickText("确认保存")
            assertEquals(1, f.writes.size)
        }
    }

    @Test fun frozenOldConfirmationAndDismissCannotAffectReselectionOrReopening() {
        val f = Fixture()
        f.scene().use { s ->
            s.selectFirst(f); s.clickText("保存"); val oldSubmit = assertNotNull(f.probe).submit
            val oldDismiss = assertNotNull(f.probe).dismiss; s.clickText("返回编辑")
            onUi { oldSubmit() }; assertTrue(f.writes.isEmpty())
            s.clickDescription("候选 1"); s.await { f.selects.size == 2 }
            onUi { oldSubmit() }; assertTrue(f.writes.isEmpty())
            s.clickText("取消"); onUi { f.reopen() }; s.await { f.opening?.picker != null }
            val fresh = f.opening; onUi { oldDismiss(); oldSubmit() }; s.settledFrame()
            assertSame(fresh, f.opening); assertTrue(fresh!!.active); assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun resultBeforeAdmissionMatchesTheActualRevisionAndNeverPredictsNext() {
        val f = Fixture()
        f.scene().use { s ->
            s.selectFirst(f); s.clickText("保存"); s.clickText("确认保存"); val write = f.writes.single()
            worker { write.result(ProfileStickerAvatarMutationResult(f.snapshot.token, 41, null)) }
            s.settledFrame(); assertTrue(f.opening!!.waitingForSave); assertTrue(f.mounted)
            worker { write.admit(41) }; s.await { !f.mounted }
            assertEquals(listOf(41L), f.returnedRevisions); assertEquals(1, f.closed.size)
        }
    }

    @Test fun wrongRevisionStaysPendingAndFailureKeepsSelectionColorsForAnExplicitNewSubmission() {
        val f = Fixture()
        f.scene().use { s ->
            s.selectFirst(f); s.clickDescription("预设颜色 3"); val colors = f.opening!!.editor.colors.values()
            s.clickText("保存"); s.clickText("确认保存"); val first = f.writes.single()
            worker { first.result(ProfileStickerAvatarMutationResult(f.snapshot.token, 8, null)); first.admit(9) }
            s.settledFrame(); assertTrue(f.opening!!.waitingForSave); assertTrue(f.mounted)
            worker { first.result(ProfileStickerAvatarMutationResult(f.snapshot.token, 9, SettingsError.RATE_LIMITED)) }
            s.await { !f.opening!!.waitingForSave }; assertEquals(colors, f.opening!!.editor.colors.values())
            val selection = assertNotNull(f.opening!!.editor.selection)
            assertTrue(f.opening!!.selectionUsable, "A resource error allows an explicit retry of the same selection")
            s.clickText("保存"); s.clickText("确认保存"); assertEquals(2, f.writes.size)
            worker { first.admit(10) }; s.settledFrame(); assertTrue(f.opening!!.waitingForSave)
            val second = f.writes.last(); assertSame(selection, second.selection)
            worker { second.admit(11); second.result(ProfileStickerAvatarMutationResult(f.snapshot.token, 11, SettingsError.CONFLICT)) }
            s.await { !f.opening!!.waitingForSave }
            assertSame(selection, f.opening!!.editor.selection)
            assertEquals(colors, f.opening!!.editor.colors.values())
            assertFalse(f.opening!!.selectionUsable); assertTrue(f.opening!!.selectionNeedsReselection)
            s.await { s.disabledText("保存") }
            assertTrue(s.disabledText("保存")); assertEquals(2, f.writes.size)
            s.clickText("重试读取内容")
            s.await { !f.opening!!.loading && f.opening!!.candidates.isNotEmpty() }
            assertSame(selection, f.opening!!.editor.selection); assertTrue(s.disabledText("保存"))
            s.clickDescription("候选 1"); s.await { f.opening!!.selectionUsable && f.opening!!.editor.selection !== selection }
        }
    }

    @Test fun oldCatalogResponseCannotReplaceNewPackOrApplyWhileSaveIsPending() {
        val f = Fixture(); f.holdDefault = true
        f.scene().use { s ->
            s.await { f.defaultCallbacks.isNotEmpty() }; val old = f.defaultCallbacks.single()
            assertTrue(f.opening!!.loading)
            assertFalse(f.opening!!.editingEnabled, "A pending catalog read must still disable editing")
            assertTrue(s.disabledText("保存"), "A pending catalog read must still disable saving")
            s.clickText("表情包"); s.await { s.texts("Moon pack").isNotEmpty() }
            worker { old(SettingsResult(value = ProfileStickerAvatarCandidatePage(f.candidates, false))) }
            s.settledFrame(); assertTrue(s.texts("Moon pack").isNotEmpty()); assertTrue(f.opening!!.candidates.isEmpty())
            s.clickText("Moon pack"); s.await { f.opening!!.candidates.isNotEmpty() && f.provider.thumbnails.isNotEmpty() }
            s.await { s.enabledDescription("候选 1") }; s.clickDescription("候选 1"); s.await { f.opening?.editor?.selection != null }
            s.clickText("保存"); s.clickText("确认保存")
            worker { old(SettingsResult(value = ProfileStickerAvatarCandidatePage(f.candidates, false))) }
            s.settledFrame(); assertTrue(f.opening!!.waitingForSave); assertEquals(1, f.writes.size)
            assertFalse(f.opening!!.catalogNavigationEnabled, "An actual pending write must still disable source navigation")
        }
    }

    @Test fun catalogReloadAndPackSwitchInvalidateTheSelectionUntilAnExplicitNewChoice() {
        val f = Fixture(); f.strictOwner = true; f.provider.holdPreview = true
        f.scene().use { s ->
            s.selectFirst(f, waitPreview = false); s.await { f.provider.previews.isNotEmpty() }
            val oldSelection = assertNotNull(f.opening!!.editor.selection)
            val oldPreview = f.provider.previews.last(); val oldConfirm = assertNotNull(f.probe).confirm

            s.clickText("表情") // Re-read the same catalog; the native child retires its old selection.
            s.await { f.opening!!.editor.selection == null && f.opening!!.candidates.isNotEmpty() }
            assertTrue(s.disabledText("保存")); assertTrue(oldPreview.lease.closed.get())
            worker { oldPreview.callback(SettingsResult(value = oldPreview.clip)) }
            s.settledFrame(3, 16); assertTrue(oldPreview.clip.closed.get())
            onUi { oldConfirm() }; s.settledFrame(3, 16)
            assertNull(f.opening!!.editor.selection); assertNull(f.opening!!.confirmation); assertTrue(f.writes.isEmpty())

            onUi { f.provider.holdPreview = false }
            s.await { s.enabledDescription("候选 1") }; s.clickDescription("候选 1")
            s.await { f.opening!!.editor.selection != null && f.opening!!.editor.selection !== oldSelection }
            val refreshed = assertNotNull(f.opening!!.editor.selection); assertFalse(s.disabledText("保存"))
            s.clickText("表情包"); s.await { s.texts("Moon pack").isNotEmpty() }
            assertNull(f.opening!!.editor.selection); assertTrue(s.disabledText("保存"))
            s.clickText("Moon pack"); s.await { s.enabledDescription("候选 1") }
            onUi { f.provider.failPreview = true }; s.clickDescription("候选 1")
            s.await { f.opening!!.editor.selection != null && f.opening!!.editor.selection !== refreshed }
            val finalSelection = assertNotNull(f.opening!!.editor.selection)
            s.await { s.texts("预览不可用").isNotEmpty() }
            assertFalse(s.disabledText("保存"), "An unavailable preview does not revoke a real current ticket.")
            s.clickText("保存"); s.clickText("确认保存")
            assertSame(finalSelection, f.writes.single().selection)
        }
    }

    @Test fun stalePackAndReturnClosuresCannotReplaceAReselectedCurrentCatalog() {
        val f = Fixture(); f.strictOwner = true
        f.scene().use { s ->
            s.await { f.opening?.picker != null }; s.clickText("表情包")
            s.await { s.texts("Moon pack").isNotEmpty() && f.catalogProbes.any { it.label == "Moon pack" } }
            val oldPackAction = f.catalogProbes.last { it.label == "Moon pack" }.action
            s.clickText("Moon pack")
            s.await { s.texts("返回已安装的包").isNotEmpty() && f.catalogProbes.any { it.label == "返回已安装的包" } }
            val oldReturn = f.catalogProbes.last { it.label == "返回已安装的包" }.action
            s.clickText("返回已安装的包"); s.await { s.texts("Moon pack").isNotEmpty() }
            s.clickText("Moon pack"); s.await { s.enabledDescription("候选 1") }
            s.clickDescription("候选 1"); s.await { f.opening!!.editor.selection != null }
            val selected = f.opening!!.editor.selection; val source = f.opening!!.source
            val calls = f.ownerChecks.size; val packReads = f.packOpens.size
            onUi { oldPackAction(); oldReturn() }; s.settledFrame(3, 16)
            assertSame(selected, f.opening!!.editor.selection); assertSame(source, f.opening!!.source)
            assertEquals(calls, f.ownerChecks.size); assertEquals(packReads, f.packOpens.size)
            s.clickText("表情"); s.await { f.opening!!.source === AvatarCatalogSource.Default }
            val afterDefault = f.ownerChecks.size; onUi { oldReturn() }; s.settledFrame(3, 16)
            assertSame(AvatarCatalogSource.Default, f.opening!!.source); assertEquals(afterDefault, f.ownerChecks.size)
        }
    }

    @Test fun staleConditionalNavigationAndRetryCannotClearTheCurrentChoiceOrReuseAnEqualError() {
        val f = Fixture(); f.strictOwner = true; f.failSelect = true
        f.scene().use { s ->
            s.await { s.enabledDescription("候选 1") }; s.clickDescription("候选 1")
            s.await { f.catalogProbes.any { it.label == "重试读取内容" } }
            val oldRetry = f.catalogProbes.last { it.label == "重试读取内容" }.action
            val firstError = f.opening!!.errorRevision
            s.clickDescription("候选 1"); s.await { f.opening!!.errorRevision > firstError && f.opening!!.error != null }
            val checksAfterEqualError = f.ownerChecks.size; onUi { oldRetry() }; s.settledFrame(3, 16)
            assertEquals(checksAfterEqualError, f.ownerChecks.size, "An equal but newer error is a different retry owner.")

            onUi { f.failSelect = false }; s.clickDescription("候选 1"); s.await { f.opening!!.editor.selection != null }
            val selected = f.opening!!.editor.selection; val calls = f.ownerChecks.size
            onUi { oldRetry() }; s.settledFrame(3, 16)
            assertSame(selected, f.opening!!.editor.selection); assertEquals(calls, f.ownerChecks.size)

            s.clickText("贴纸"); s.await { f.catalogProbes.any { it.label == "面具贴纸" } }
            val oldMask = f.catalogProbes.last { it.label == "面具贴纸" }.action
            s.clickText("表情"); s.await { s.enabledDescription("候选 1") }
            s.clickDescription("候选 1"); s.await { f.opening!!.editor.selection != null }
            val fresh = f.opening!!.editor.selection; val source = f.opening!!.source; val reads = f.ownerChecks.size
            onUi { oldMask(); oldRetry() }; s.settledFrame(3, 16)
            assertSame(fresh, f.opening!!.editor.selection); assertSame(source, f.opening!!.source); assertEquals(reads, f.ownerChecks.size)
        }
    }

    @Test fun staleThumbnailAndPreviewRetryClosuresCannotRestartRecoveredMedia() {
        val f = Fixture(); f.provider.failThumbnail = true
        f.scene().use { s ->
            s.await { f.catalogProbes.any { it.label == "重试缩略图 1" } }
            val oldThumbnail = f.catalogProbes.last { it.label == "重试缩略图 1" }.action
            onUi { f.provider.failThumbnail = false }; s.clickText("表情")
            s.await { s.enabledDescription("候选 1") }; val thumbs = f.provider.thumbnails.size
            onUi { oldThumbnail() }; s.settledFrame(3, 16); assertEquals(thumbs, f.provider.thumbnails.size)

            onUi { f.provider.failPreview = true }; s.clickDescription("候选 1")
            s.await { f.catalogProbes.any { it.label == "重试预览" } }
            val oldPreview = f.catalogProbes.last { it.label == "重试预览" }.action
            onUi { f.activity.value = false }; s.settledFrame(3, 16)
            onUi { f.provider.failPreview = false; f.activity.value = true }
            s.await { f.provider.frames.isNotEmpty() && s.texts("预览不可用").isEmpty() }
            val previews = f.provider.previews.size; onUi { oldPreview() }; s.settledFrame(3, 16)
            assertEquals(previews, f.provider.previews.size)
        }
    }

    @Test fun focusLossReleasesOnlyItsOwnMediaAndLateClipCannotReviveTheOldOwner() {
        val f = Fixture(); f.provider.holdPreview = true
        f.scene().use { s ->
            s.selectFirst(f, waitPreview = false); s.await { f.provider.previews.isNotEmpty() }; val old = f.provider.previews.single()
            onUi { f.activity.value = false }; s.settledFrame(); assertTrue(old.lease.closed.get())
            worker { old.callback(SettingsResult(value = old.clip)) }; s.settledFrame()
            assertTrue(old.clip.closed.get()); assertTrue(f.provider.frames.isEmpty()); assertNotNull(f.opening!!.editor.selection)
            onUi { f.provider.holdPreview = false; f.activity.value = true }; s.await { f.provider.previews.size >= 2 && f.provider.frames.isNotEmpty() }
            assertNotSame(old.clip, f.provider.previews.last().clip)
            s.clickText("取消"); s.settledFrame(); assertTrue(f.provider.previews.last().lease.closed.get())
        }
    }

    @Test fun failedPreviewRecoversOnFocusReturnWithoutIdleRetryOrDiscardingSelection() {
        val f = Fixture(); f.provider.failPreview = true
        f.scene().use { s ->
            s.selectFirst(f, waitPreview = false); s.await { s.texts("预览不可用").isNotEmpty() }
            val selection = assertNotNull(f.opening!!.editor.selection)
            val failedAttempts = f.provider.previews.size; s.settledFrame(15, 32)
            assertEquals(failedAttempts, f.provider.previews.size, "An immutable failure must not idle-retry.")
            onUi { f.activity.value = false }; s.settledFrame(3, 16)
            onUi { f.provider.failPreview = false; f.activity.value = true }
            s.await { f.provider.previews.size > failedAttempts && f.provider.frames.isNotEmpty() && s.texts("预览不可用").isEmpty() }
            assertSame(selection, f.opening!!.editor.selection); assertEquals(0xED3344, s.previewPixel())
            val activeClip = f.provider.previews.last(); val colors = f.opening!!.editor.colors.values()
            onUi { f.focused = false }; s.settledFrame(3, 16); assertTrue(activeClip.lease.closed.get())
            assertSame(selection, f.opening!!.editor.selection); assertEquals(colors, f.opening!!.editor.colors.values())
            onUi { f.focused = true }; s.await { f.provider.previews.size > failedAttempts + 1 && f.provider.frames.size > 1 }
            val frameRequests = f.provider.frames.size; s.settledFrame(20, 32)
            assertEquals(frameRequests, f.provider.frames.size, "Static artwork has no idle frame loop.")
        }
    }

    @Test fun animatedClipPlaysOnceRestsOnZeroAndPointerReplaySchedulesAnotherPass() {
        val f = Fixture(); f.provider.animated = true
        f.scene().use { s ->
            s.selectFirst(f); s.await { f.provider.frames.any { it > 0 } }
            s.settledFrame(30, 32); assertEquals(0L, f.provider.frames.last())
            val count = f.provider.frames.size; s.settledFrame(20, 32); assertEquals(count, f.provider.frames.size)
            s.clickText("重播"); s.await { f.provider.frames.size > count && f.provider.frames.drop(count).any { it > 0 } }
            s.settledFrame(30, 32); assertEquals(0L, f.provider.frames.last())
        }
    }

    @Test fun slowFramesDeliverDuringContinuousClockAndFinishOnZeroWithoutLateRegression() {
        val f = Fixture(); f.provider.animated = true; f.provider.holdFrames = true
        f.provider.frameInterval = 33; f.provider.duration = 1800
        f.scene().use { s ->
            s.selectFirst(f, waitPreview = false); s.await { f.provider.pendingFrames.isNotEmpty() }
            val first = f.provider.pendingFrames.single(); assertEquals(0L, first.time)
            s.settledFrame(7, 16) // More than three 33 ms clock intervals elapse before the simulated worker returns.
            assertFalse(first.lease.closed.get()); assertTrue(first.active()); assertEquals(1, f.provider.pendingFrames.size)
            worker { f.provider.completeFrame(first) }
            s.await { f.provider.pendingFrames.size == 2 }; assertEquals(first.color, s.previewPixel())
            val moving = f.provider.pendingFrames[1]; assertTrue(moving.time > 0)
            s.settledFrame(5, 16); assertFalse(moving.lease.closed.get())
            worker { f.provider.completeFrame(moving) }
            s.await { f.provider.pendingFrames.size >= 3 }; assertEquals(moving.color, s.previewPixel())
            val delayed = f.provider.pendingFrames.last(); assertTrue(delayed.time > moving.time)
            val lastVisible = moving.color
            s.settledFrame(125, 16) // Playback ends while this physical request is still in flight.
            s.await { f.provider.pendingFrames.size >= 4 }
            assertTrue(delayed.lease.closed.get(), "The ended playback cancels its own in-flight consumer.")
            val terminal = f.provider.pendingFrames.last(); assertEquals(0L, terminal.time)
            assertFalse(terminal.started.get(), "The provider keeps the terminal request pending until physical exit.")
            worker { f.provider.completeFrame(delayed) }; assertTrue(terminal.started.get())
            assertEquals(lastVisible, s.previewPixel(), "A nonzero frame arriving after the end cannot overwrite the displayed frame.")
            worker { f.provider.completeFrame(terminal) }; s.await { s.previewPixel() == terminal.color }
            val beforeIdle = f.provider.pendingFrames.size; s.settledFrame(20, 16)
            assertEquals(beforeIdle, f.provider.pendingFrames.size); assertEquals(1, terminal.clip.maximumPhysical.get())
            s.clickText("重播"); s.await { f.provider.pendingFrames.size > beforeIdle }
            assertEquals(0L, f.provider.pendingFrames.last().time)
            worker { f.provider.completeFrame(delayed) }; s.settledFrame(3, 16); assertEquals(terminal.color, s.previewPixel())
        }
    }

    @Test fun explicitReplaySupersedesSlowPreviousPlaybackAndStartsAfterItsPhysicalExit() {
        val f = Fixture(); f.provider.animated = true; f.provider.holdFrames = true
        f.provider.frameInterval = 33; f.provider.duration = 1800
        f.scene().use { s ->
            s.selectFirst(f, waitPreview = false); s.await { f.provider.pendingFrames.isNotEmpty() }
            val first = f.provider.pendingFrames.single(); s.settledFrame(5, 16)
            worker { f.provider.completeFrame(first) }; s.await { f.provider.pendingFrames.size >= 2 }
            val old = f.provider.pendingFrames.last(); assertTrue(old.time > 0)
            val shown = s.previewPixel(); s.clickText("重播")
            s.await { f.provider.pendingFrames.size >= 3 }
            assertTrue(old.lease.closed.get()); val replay = f.provider.pendingFrames.last()
            assertEquals(0L, replay.time); assertFalse(replay.started.get())
            worker { f.provider.completeFrame(old) }; assertTrue(replay.started.get())
            s.settledFrame(3, 16); assertEquals(shown, s.previewPixel(), "A late frame from the prior play cannot replace replay's current frame.")
            worker { f.provider.completeFrame(replay) }; s.await { s.previewPixel() == replay.color }
            assertEquals(1, replay.clip.maximumPhysical.get())
        }
    }

    @Test fun lateSlowFrameAfterReselectionCannotReplaceNewSelectionOrReopenClosedLease() {
        val f = Fixture(); f.provider.animated = true; f.provider.holdFrames = true; f.provider.duration = 1800
        f.scene().use { s ->
            s.selectFirst(f, waitPreview = false); s.await { f.provider.pendingFrames.isNotEmpty() }
            val old = f.provider.pendingFrames.single(); val oldSelection = f.opening!!.editor.selection
            s.await { s.enabledDescription("候选 2") }; s.clickDescription("候选 2")
            s.await { f.opening!!.editor.selection !== oldSelection && f.provider.pendingFrames.size >= 2 }
            assertTrue(old.lease.closed.get()); assertFalse(old.active()); assertTrue(old.clip.closed.get())
            val fresh = f.provider.pendingFrames.last(); worker { f.provider.completeFrame(fresh) }
            s.await { s.previewPixel() == fresh.color }; val shown = s.previewPixel()
            worker { f.provider.completeFrame(old) }; s.settledFrame(3, 16); assertEquals(shown, s.previewPixel())
            assertSame(fresh.clip, f.provider.previews.last().clip); assertEquals(1, old.clip.maximumPhysical.get())
        }
    }

    @Test fun failedFullPreviewCanBeRetriedWithoutDiscardingTheSelectedTicketOrColors() {
        val f = Fixture(); f.provider.failPreview = true; f.provider.failThumbnail = true
        f.scene().use { s ->
            s.await { s.enabledDescription("候选 1") }; assertTrue(f.provider.previews.isEmpty())
            val cell = s.description("候选 1")
            s.moveTo(cell.left + 4, cell.top + 4); s.pressAt(cell.left + 4, cell.top + 4); s.releaseAt(cell.left + 4, cell.top + 4)
            s.await { s.texts("重试预览").isNotEmpty() }
            val selection = f.opening!!.editor.selection; val colors = f.opening!!.editor.colors.values()
            assertTrue(f.provider.previews.last().lease.closed.get())
            s.clickText("保存"); s.await { s.texts("预览不可用").isNotEmpty() }
            s.clickText("确认保存"); assertSame(selection, f.writes.single().selection)
            worker { f.writes.single().admit(null) }; s.await { !f.opening!!.waitingForSave }
            s.await { s.texts("重试预览").isNotEmpty() }
            onUi { f.provider.failPreview = false }; s.clickText("重试预览")
            s.await { f.provider.frames.isNotEmpty() }
            assertSame(selection, f.opening!!.editor.selection); assertEquals(colors, f.opening!!.editor.colors.values())
            assertEquals(1, f.writes.size)
        }
    }

    @Test fun synchronousThumbnailFailureClosesItsOwnLeaseBeforeRetryIsVisible() {
        val f = Fixture(); f.provider.failThumbnail = true
        f.scene().use { s ->
            s.await { f.catalogProbes.any { it.label == "重试缩略图 1" } }
            val request = assertNotNull(f.provider.thumbnailRequests.lastOrNull { it.first === f.candidates.first().ticket })
            assertTrue(request.second.closed.get(), "The returned thumbnail lease is closed before publishing failure.")
            assertTrue(s.enabledDescription("候选 1")); assertTrue(f.provider.previews.isEmpty())
        }
    }

    @Test fun thumbnailCallbackThenThrowCannotPublishItsStaleImageAndCanBeRetriedByPointer() {
        val f = Fixture(); f.provider.throwAfterThumbnailCallback = true
        f.scene().use { s ->
            s.await { f.catalogProbes.any { it.label == "重试缩略图 1" } }
            val old = assertNotNull(f.provider.thumbnailRequests.lastOrNull { it.first === f.candidates.first().ticket })
            assertTrue(old.second.closed.get()); assertTrue(f.provider.previews.isEmpty())
            onUi { f.provider.throwAfterThumbnailCallback = false }
            s.click(s.description("候选 1")) // The visible retry button is centered in the failed cell.
            s.await { f.provider.thumbnailRequests.count { it.first === f.candidates.first().ticket } >= 2 &&
                s.enabledDescription("候选 1") && s.pixelAtDescription("候选 1") == 0xED3344 }
            s.clickDescription("候选 1"); s.await { f.opening!!.editor.selection != null }
        }
    }

    @Test fun synchronousPreviewCallbackThenThrowClosesTheDeliveredClipAndAllowsExplicitRetry() {
        val f = Fixture(); f.provider.throwAfterPreviewCallback = true
        f.scene().use { s ->
            s.selectFirst(f, waitPreview = false)
            s.await { s.texts("预览不可用").isNotEmpty() && f.provider.previews.isNotEmpty() }
            val failed = f.provider.previews.last()
            s.await { failed.clip.closed.get() && failed.lease.closed.get() }
            assertTrue(failed.clip.closed.get()); assertTrue(failed.lease.closed.get())
            assertTrue(f.provider.frames.isEmpty(), "A clip returned before a throwing acquisition must not start frames.")
            val selected = f.opening!!.editor.selection
            onUi { f.provider.throwAfterPreviewCallback = false }; s.clickText("重试预览")
            s.await { f.provider.frames.isNotEmpty() }
            assertSame(selected, f.opening!!.editor.selection)
        }
    }

    @Test fun reentrantCloseBeforePreviewReturnsReleasesItsOwnLeaseAndLateClip() {
        val f = Fixture()
        f.provider.beforePreviewReturn = {
            assertTrue(f.provider.frames.isEmpty(), "A callback cannot publish its clip before the returned lease is bound.")
            assertNotNull(f.probe).dismiss()
        }
        f.scene().use { s ->
            s.await { s.enabledDescription("候选 1") }; s.clickDescription("候选 1")
            s.await { !f.mounted && f.provider.previews.isNotEmpty() }
            val request = f.provider.previews.last()
            s.await { request.lease.closed.get() && request.clip.closed.get() }
            assertTrue(request.lease.closed.get()); assertTrue(request.clip.closed.get()); assertTrue(f.provider.frames.isEmpty())
        }
    }

    @Test fun lateOpenClosesItsExactPickerWithoutClosingFreshOpening() {
        val f = Fixture(); f.holdOpen = true
        f.scene().use { s ->
            s.await { f.openCallbacks.isNotEmpty() }; val old = f.openCallbacks.single(); val oldTicket = ProfileStickerAvatarPickerTicket()
            s.clickText("取消"); onUi { f.holdOpen = false; f.reopen() }; s.await { f.opening?.picker != null }
            val fresh = f.opening!!.picker
            worker { old(SettingsResult(value = oldTicket)) }; s.await { f.closed.contains(oldTicket) }
            assertSame(fresh, f.opening!!.picker); assertFalse(f.closed.contains(fresh))
        }
    }

    @Test fun realBodyAndFixedFooterRemainUsableInWideAndLowWindowsAcrossThemeAndDensity() {
        for ((width, height) in listOf(620 to 900, 350 to 300)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark))
            for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode)
                f.scene().use { s ->
                    s.await { f.opening?.picker != null }
                    val cancel = s.text("取消"); val save = s.text("保存")
                    assertTrue(cancel.top >= 0 && cancel.bottom <= f.pixelHeight && save.bottom <= f.pixelHeight)
                    val body = s.tag("sticker-avatar-body"); val footer = s.tag("sticker-avatar-actions")
                    assertTrue(body.width > 0 && body.height > 0 && body.bottom <= footer.top)
                    assertTrue(kotlin.math.abs(footer.top - body.bottom - 10f * density) <= 2f * density,
                        "The footer follows the measured content viewport with its real spacing.")
                    if (width == 620) assertTrue(body.height < 500f * density, "A wide dialog must not reserve a 620 dp empty body.")
                    else assertTrue(body.height < f.pixelHeight - 60f * density, "The low body is bounded separately from the footer.")
                    if (width == 620) {
                        s.await { f.provider.thumbnails.isNotEmpty() }
                        val cells = s.descriptionsStarting("候选 ").filter { it.width > 0 && it.height > 0 }
                        assertTrue(cells.map { it.left.roundToInt() }.distinct().size >= 3)
                    }
                    s.selectFirst(f)
                    if (width == 350) {
                        s.click(s.revealCatalogPageControl("返回设计", f))
                        s.scrollUntilRedPreview(f)
                        val visiblePreview = f.provider.previews.last()
                        s.click(s.revealCatalogPageControl("选择表情或贴纸", f))
                        assertTrue(visiblePreview.lease.closed.get(), "Leaving the design page releases its borrowed preview.")
                        s.click(s.revealCatalogPageControl("返回设计", f))
                    }
                    s.settledFrame().save("${mode}-${width}x$height-$density-editor")
                    s.clickText("保存"); assertTrue(s.texts("确认保存").isNotEmpty())
                    s.settledFrame().save("${mode}-${width}x$height-$density-confirm")
                    s.clickText("返回编辑")
                    s.clickText("取消"); assertFalse(f.mounted)
                }
            }
    }

    private class Fixture(val width: Int = 620, val height: Int = 900, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light) {
        val pixelHeight get() = (height * density).roundToInt()
        val activity = MutableStateFlow(true)
        var mounted by mutableStateOf(true); var serial by mutableIntStateOf(0)
        var focused by mutableStateOf(true)
        var opening: SettingsProfileStickerAvatarOpening? = null
        var probe: ProfileStickerAvatarUiProbe? = null
        val catalogProbes = mutableListOf<ProfileStickerAvatarCatalogProbe>()
        val hslProbes = mutableListOf<ProfileStickerAvatarHslProbe>()
        var snapshot by mutableStateOf(SettingsProfileExtrasSnapshot(SettingsProfileExtrasToken(7, 3, 43),
            SettingsProfileExtrasData(43, 77, null, SettingsCalendarDate(25, 10, 2026))))
        val candidates = (1..50).map { i -> ProfileStickerAvatarCandidate(ProfileStickerAvatarCandidateTicket(),
            ProfileStickerAvatarIdentity.CustomEmoji(i.toLong()), i.toLong(), 0, 20, 20, false) }
        var packs = listOf(ProfileStickerAvatarPack(ProfileStickerAvatarPackTicket(), "Moon pack", 50, ProfileStickerAvatarPackKind.CUSTOM_EMOJI),
            ProfileStickerAvatarPack(ProfileStickerAvatarPackTicket(), "Other pack", 50, ProfileStickerAvatarPackKind.CUSTOM_EMOJI))
        val provider = SyntheticProvider(); var holdOpen = false; var holdDefault = false; var strictOwner = false; var limitedPacks = false; var failSelect = false
        var enableWindows = false; var holdWindow = false
        val forwardCursor = ProfileStickerAvatarCatalogCursor(); val backCursor = ProfileStickerAvatarCatalogCursor()
        val nextCandidates = (101..110).map { i -> ProfileStickerAvatarCandidate(ProfileStickerAvatarCandidateTicket(),
            ProfileStickerAvatarIdentity.CustomEmoji(i.toLong()), i.toLong(), 0, 20, 20, false) }
        val windowCalls = mutableListOf<(SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit>()
        val openCallbacks = mutableListOf<(SettingsResult<ProfileStickerAvatarPickerTicket>) -> Unit>()
        val defaultCallbacks = mutableListOf<(SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit>()
        val closed = mutableListOf<ProfileStickerAvatarPickerTicket>(); val selects = mutableListOf<ProfileStickerAvatarCandidateTicket>()
        val packOpens = mutableListOf<ProfileStickerAvatarPackTicket>(); val writes = mutableListOf<Write>(); val returnedRevisions = mutableListOf<Long>()
        val openOwners = mutableMapOf<ProfileStickerAvatarPickerTicket, () -> Boolean>()
        val nativeSelection = mutableMapOf<ProfileStickerAvatarPickerTicket, ProfileStickerAvatarSelectionTicket>()
        val ownerChecks = mutableListOf<Pair<String, Boolean>>()
        private fun allowed(picker: ProfileStickerAvatarPickerTicket, active: () -> Boolean, operation: String): Boolean {
            if (!strictOwner) return true
            val value = openOwners[picker]?.invoke() == true && active()
            ownerChecks += operation to value
            return value
        }
        data class Write(val selection: ProfileStickerAvatarSelectionTicket, val background: ProfileStickerAvatarBackground, val admit: (Long?) -> Unit,
            val result: (ProfileStickerAvatarMutationResult) -> Unit)
        val actions = SettingsProfileStickerAvatarActions(
            open = { _, active, callback -> if (holdOpen) openCallbacks += callback else {
                val picker = ProfileStickerAvatarPickerTicket(); openOwners[picker] = active; callback(SettingsResult(value = picker))
            } },
            close = { _, picker -> closed += picker },
            loadDefault = { _, picker, active, callback ->
                if (!allowed(picker, active, "default")) callback(SettingsResult(error = SettingsError.NOT_READY))
                else { nativeSelection.remove(picker); if (holdDefault) defaultCallbacks += callback else callback(SettingsResult(value =
                    ProfileStickerAvatarCandidatePage(candidates, false, next = if (enableWindows) forwardCursor else null))) }
            },
            loadInstalledPacks = { _, picker, _, active, callback ->
                if (!allowed(picker, active, "packs")) callback(SettingsResult(error = SettingsError.NOT_READY))
                else { nativeSelection.remove(picker); callback(SettingsResult(value = ProfileStickerAvatarPackPage(packs, limitedPacks))) }
            },
            openPack = { _, picker, ticket, active, callback ->
                if (!allowed(picker, active, "pack")) callback(SettingsResult(error = SettingsError.NOT_READY))
                else { nativeSelection.remove(picker); packOpens += ticket; callback(SettingsResult(value = ProfileStickerAvatarCandidatePage(candidates, false))) }
            },
            select = { _, picker, candidate, active, callback ->
                if (!allowed(picker, active, "select")) callback(SettingsResult(error = SettingsError.NOT_READY))
                else {
                    selects += candidate
                    if (failSelect) callback(SettingsResult(error = SettingsError.CONFLICT))
                    else { val selection = ProfileStickerAvatarSelectionTicket(); nativeSelection[picker] = selection; callback(SettingsResult(value = selection)) }
                }
            },
            loadWindow = { _, picker, cursor, active, callback ->
                if (!allowed(picker, active, "window")) callback(SettingsResult(error = SettingsError.CONFLICT))
                else when (cursor) {
                    forwardCursor -> if (holdWindow) windowCalls += callback
                        else callback(SettingsResult(ProfileStickerAvatarCatalogWindow.Candidates(
                            ProfileStickerAvatarCandidatePage(nextCandidates, false, previous = backCursor))))
                    backCursor -> callback(SettingsResult(ProfileStickerAvatarCatalogWindow.Candidates(
                        ProfileStickerAvatarCandidatePage(candidates, false, next = forwardCursor))))
                    else -> callback(SettingsResult(error = SettingsError.CONFLICT))
                }
            },
            searchInstalled = { _, picker, kind, query, active, callback ->
                if (!allowed(picker, active, "search")) callback(SettingsResult(error = SettingsError.NOT_READY))
                else {
                    nativeSelection.remove(picker)
                    callback(SettingsResult(ProfileStickerAvatarPackPage(packs.filter {
                        it.kind == kind && (it.title.contains(query, ignoreCase = true) || it.shortName.contains(query, ignoreCase = true))
                    }, false)))
                }
            },
            save = { _, picker, selection, _, bg, active, admit, result ->
                if (!allowed(picker, active, "save") || strictOwner && nativeSelection[picker] !== selection) admit(null)
                else writes += Write(selection, bg, { value -> value?.let(returnedRevisions::add); admit(value) }, result)
            })
        fun reopen() { mounted = true; serial++ }
        fun scene() = ComposePixelScene((width * density).roundToInt(), pixelHeight) {
            val theme = remember { ThemeController(mode) }; val native = LocalWindowInfo.current
            val latestFocus by rememberUpdatedState(focused)
            val window = remember(native) { object : WindowInfo by native { override val isWindowFocused get() = latestFocus } }
            val scope = rememberCoroutineScope()
            val value = remember(serial) { SettingsProfileStickerAvatarOpening(snapshot.token, snapshot.data?.photoId, actions, scope) }
            SideEffect { opening = value }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides window,
                LocalWindowActivity provides activity, LocalSettingsPageActive provides true,
                LocalSettingsSecurityConfirmationHost provides InlineHost,
                LocalProfileStickerAvatarUiProbe provides { probe = it },
                LocalProfileStickerAvatarCatalogProbe provides { catalogProbes += it },
                LocalProfileStickerAvatarHslProbe provides { hslProbes += it }) {
                MiuixTheme(controller = theme) { ProfileStickerAvatarArtworkHost(provider, 7) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        if (mounted) SettingsProfileStickerAvatarBuilder(value, snapshot, true) { opened ->
                            if (opening === opened) mounted = false
                        }
                    }
                } }
            }
        }
    }

    private object InlineHost : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
                Column(Modifier.fillMaxWidth().heightIn(max = (height - 24.dp).coerceAtLeast(140.dp))
                    .chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 24.dp).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) { title?.let { Text(it) }; content() }
            }
        }
    }

    private class SyntheticProvider : ProfileStickerAvatarArtworkProvider {
        override val generation = 7L
        class Lease : ProfileStickerAvatarArtworkLease { val closed = AtomicBoolean(); override fun close() { closed.set(true) } }
        class Frozen(private val bitmap: ImageBitmap?, override val needsRepainting: Boolean) : ProfileStickerAvatarFrozenDisplay {
            val closed = AtomicBoolean()
            override val image: ImageBitmap? get() = if (closed.get()) null else bitmap
            override fun close() { closed.set(true) }
        }
        inner class Clip(val ordinal: Int, private val openLease: Lease) : ProfileStickerAvatarPreviewClip {
            val closed = AtomicBoolean(); override val animated get() = this@SyntheticProvider.animated
            val physical = AtomicInteger(); val maximumPhysical = AtomicInteger()
            private val lock = Any()
            private var running: FrameCall? = null
            private var pending: FrameCall? = null
            override val durationMillis get() = this@SyntheticProvider.duration
            override val frameDurationMillis get() = this@SyntheticProvider.frameInterval
            private fun start(call: FrameCall) {
                check(running == null); running = call; call.started.set(true)
                val count = physical.incrementAndGet(); maximumPhysical.updateAndGet { maxOf(it, count) }
            }
            fun finish(call: FrameCall) = synchronized(lock) {
                check(running === call); running = null; physical.decrementAndGet()
                val next = pending; pending = null
                if (!closed.get() && next != null && !next.lease.closed.get() && next.active()) start(next)
            }
            override fun frame(timeMillis: Long, active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit): ProfileStickerAvatarArtworkLease {
                val lease = Lease(); frames += timeMillis
                if (holdFrames) {
                    val color = if (ordinal > 0) 0x28C47D else if (timeMillis == 0L) 0xED3344
                        else ((0x20 + (timeMillis / 33L % 180).toInt()) shl 16) or 0x5566
                    val call = FrameCall(this, timeMillis, lease, active, result, color, makeArtwork(color))
                    pendingFrames += call
                    synchronized(lock) {
                        if (running == null) start(call) else { pending?.lease?.close(); pending = call }
                    }
                } else result(SettingsResult(value = artwork))
                return lease
            }
            override fun close() {
                closed.set(true); openLease.close()
                synchronized(lock) { pending?.lease?.close(); pending = null }
            }
        }
        data class Preview(val lease: Lease, val clip: SyntheticProvider.Clip, val callback: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit)
        data class FrameCall(val clip: SyntheticProvider.Clip, val time: Long, val lease: Lease, val active: () -> Boolean,
            val callback: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit, val color: Int,
            val artwork: ProfileStickerAvatarArtwork, val completed: AtomicBoolean = AtomicBoolean(),
            val started: AtomicBoolean = AtomicBoolean())
        val thumbnails = mutableListOf<Lease>(); val thumbnailRequests = mutableListOf<Pair<ProfileStickerAvatarCandidateTicket, Lease>>()
        val previews = mutableListOf<Preview>(); val frames = mutableListOf<Long>()
        val frozenDisplays = mutableListOf<Frozen>()
        val pendingFrames = mutableListOf<FrameCall>()
        var holdPreview = false; var failPreview = false; var failThumbnail = false; var animated = false; var holdFrames = false; var failFreeze = false
        var throwAfterPreviewCallback = false; var throwAfterThumbnailCallback = false
        var beforePreviewReturn: (() -> Unit)? = null
        var duration = 180L; var frameInterval = 40L
        private fun makeArtwork(rgb: Int) = object : ProfileStickerAvatarArtwork {
            override val needsRepainting: Boolean = false
            override val image = ImageBitmap(20, 20).also { bitmap ->
                val canvas = androidx.compose.ui.graphics.Canvas(bitmap); val paint = Paint().apply { color = Color(0xFF000000L or rgb.toLong()) }
                canvas.drawRect(Rect(4f, 4f, 16f, 16f), paint)
            }
            override fun freezeDisplay(): ProfileStickerAvatarFrozenDisplay? =
                if (failFreeze || frozenDisplays.any { !it.closed.get() }) null
                else Frozen(image, needsRepainting).also { frozenDisplays += it }
        }
        private val artwork = makeArtwork(0xED3344)
        fun completeFrame(call: FrameCall) {
            if (call.completed.compareAndSet(false, true)) { check(call.started.get()); call.clip.finish(call) }
            // Also deliberately permits a duplicate or late callback after cancellation.
            call.callback(SettingsResult(value = call.artwork))
        }
        override fun thumbnail(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
            candidate: ProfileStickerAvatarCandidateTicket, physicalSizePx: Int, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit): ProfileStickerAvatarArtworkLease {
            val lease = Lease(); thumbnails += lease; thumbnailRequests += candidate to lease
            if (throwAfterThumbnailCallback) {
                result(SettingsResult(value = artwork)); lease.close(); throw IllegalStateException("synthetic thumbnail callback then throw")
            } else if (failThumbnail) result(SettingsResult(error = SettingsError.REQUEST_FAILED)) else result(SettingsResult(value = artwork))
            return lease
        }
        override fun preview(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
            selection: ProfileStickerAvatarSelectionTicket, physicalSizePx: Int, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit): ProfileStickerAvatarArtworkLease {
            val lease = Lease(); val clip = Clip(previews.size, lease); previews += Preview(lease, clip, result)
            if (!holdPreview) {
                if (throwAfterPreviewCallback) {
                    result(SettingsResult(value = clip)); throw IllegalStateException("synthetic callback then throw")
                } else if (failPreview) result(SettingsResult(error = SettingsError.REQUEST_FAILED)) else result(SettingsResult(value = clip))
            }
            beforePreviewReturn?.invoke()
            return lease
        }
        override fun close() = Unit
    }

    private fun ComposePixelScene.texts(label: String) = semanticsBounds { it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }
    private fun ComposePixelScene.text(label: String): Rect {
        await { texts(label).any { it.width > 0 && it.height > 0 } }
        return assertNotNull(texts(label).lastOrNull { it.width > 0 && it.height > 0 }, label)
    }
    private fun ComposePixelScene.disabledText(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Disabled) != null && it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.isNotEmpty()
    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds { label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    private fun ComposePixelScene.enabledDescription(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
            it.getOrNull(SemanticsActions.OnClick) != null && it.getOrNull(SemanticsProperties.Disabled) == null
    }.any { it.width > 0 && it.height > 0 }
    private fun ComposePixelScene.descriptionsStarting(prefix: String) = semanticsBounds { config -> config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(prefix) } }
    private fun ComposePixelScene.description(label: String): Rect {
        await { descriptions(label).any { it.width > 0 && it.height > 0 } }
        return assertNotNull(descriptions(label).lastOrNull { it.width > 0 && it.height > 0 }, label)
    }
    private fun ComposePixelScene.tag(label: String): Rect {
        fun matches() = unmergedSemanticsBounds { it.getOrNull(SemanticsProperties.TestTag) == label }
            .filter { it.width > 0 && it.height > 0 }
        await { matches().isNotEmpty() }
        return assertNotNull(matches().lastOrNull(), label)
    }
    private fun ComposePixelScene.previewPixel(): Int {
        return pixelAtDescription("头像预览")
    }
    private fun ComposePixelScene.containsRedPreview(): Boolean {
        val rect = descriptions("头像预览").firstOrNull { it.width > 0 && it.height > 0 } ?: return false
        val frame = settledFrame(2, 16)
        val left = rect.left.toInt().coerceIn(0, frame.width - 1)
        val right = rect.right.toInt().coerceIn(0, frame.width - 1)
        val top = rect.top.toInt().coerceIn(0, frame.height - 1)
        val bottom = rect.bottom.toInt().coerceIn(0, frame.height - 1)
        for (y in top..bottom step 2) for (x in left..right step 2)
            if ((frame[x, y] and 0xFFFFFF) == 0xED3344) return true
        return false
    }
    private fun ComposePixelScene.scrollUntilRedPreview(f: Fixture) {
        for (attempt in 0 until 80) {
            if (containsRedPreview()) return
            val body = tag("sticker-avatar-body")
            scrollAt(body.center.x, body.center.y, deltaY = 2f * f.density)
            settledFrame(2, 16)
        }
        assertTrue(containsRedPreview(), "The real narrow viewport must show the selected preview pixels")
    }
    private fun ComposePixelScene.pixelAtDescription(label: String): Int {
        val rect = description(label); val frame = settledFrame(2, 16)
        return frame[rect.center.x.toInt(), rect.center.y.toInt()] and 0xFFFFFF
    }
    private fun ComposePixelScene.click(rect: Rect) { moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y); settledFrame(5, 16) }
    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    private fun ComposePixelScene.catalogPageGeometry(label: String): Pair<Rect, Rect>? {
        var result: Pair<Rect, Rect>? = null
        onUi {
            val field = ComposePixelScene::class.java.getDeclaredField("scene")
            check(field.type == ImageComposeScene::class.java && field.trySetAccessible())
            val scene = field.get(this) as ImageComposeScene
            fun visit(node: SemanticsNode) {
                val config = node.config
                if (config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
                    config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                    config.getOrNull(SemanticsProperties.Disabled) == null) {
                    val position = node.positionInRoot; val size = node.size
                    result = Rect(position.x, position.y, position.x + size.width, position.y + size.height) to node.boundsInRoot
                }
                node.children.forEach(::visit)
            }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        }
        return result
    }
    private fun ComposePixelScene.revealCatalogPageControl(label: String, f: Fixture): Rect {
        fun complete(bounds: Pair<Rect, Rect>?, body: Rect): Boolean {
            if (bounds == null) return false
            val (full, clipped) = bounds
            return full.width > 0 && full.height > 0 && full.left >= body.left && full.right <= body.right &&
                full.top >= body.top && full.bottom <= body.bottom &&
                abs(full.left - clipped.left) <= .75f && abs(full.right - clipped.right) <= .75f &&
                abs(full.top - clipped.top) <= .75f && abs(full.bottom - clipped.bottom) <= .75f
        }
        repeat(180) {
            val body = tag("sticker-avatar-body")
            val bounds = catalogPageGeometry(label)
            if (complete(bounds, body)) {
                settledFrame()
                val currentBody = tag("sticker-avatar-body")
                val current = catalogPageGeometry(label)
                if (complete(current, currentBody)) return checkNotNull(current).first
            }
            val direction = if (bounds != null && bounds.first.top < body.top) -1f else 1f
            val x = body.left + 4f * f.density; val y = body.center.y
            moveTo(x, y); scrollAt(x, y, deltaY = direction * 6f * f.density); settledFrame()
        }
        error("The actual $label control must fit the visible builder body: ${catalogPageGeometry(label)}")
    }
    private fun ComposePixelScene.completeCatalogControl(label: String, f: Fixture): Rect {
        revealCatalogPageControl(label, f)
        val body = tag("sticker-avatar-body")
        val geometry = assertNotNull(catalogPageGeometry(label), "The actual $label control must remain attached")
        val (full, clipped) = geometry
        assertTrue(full.height >= 35f * f.density - 1f, "The whole natural $label button must be visible: $geometry")
        assertTrue(full.left >= body.left && full.right <= body.right && full.top >= body.top && full.bottom <= body.bottom,
            "The complete $label button must fit the real body: $geometry in $body")
        assertTrue(full.left >= 0 && full.top >= 0 && full.right <= f.width * f.density && full.bottom <= f.pixelHeight,
            "The complete $label button must fit the window")
        assertTrue(sameCatalogBounds(full, clipped), "The $label button cannot be partially clipped: $geometry")
        return full
    }
    private data class CatalogScrollGeometry(val full: Rect, val clipped: Rect, val value: Float, val maximum: Float)
    private data class CatalogCellGeometry(val ordinal: Int, val full: Rect, val clipped: Rect, val enabled: Boolean,
        val scrolls: List<CatalogScrollGeometry>)
    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    private fun ComposePixelScene.catalogCellGeometry(): List<CatalogCellGeometry> {
        val result = mutableListOf<CatalogCellGeometry>()
        onUi {
            val field = ComposePixelScene::class.java.getDeclaredField("scene")
            check(field.type == ImageComposeScene::class.java && field.trySetAccessible())
            val scene = field.get(this) as ImageComposeScene
            fun visit(node: SemanticsNode, ancestors: List<CatalogScrollGeometry>) {
                val config = node.config
                val position = node.positionInRoot; val size = node.size
                val natural = Rect(position.x, position.y, position.x + size.width, position.y + size.height)
                val axis = config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                val scrolls = if (axis == null) ancestors else ancestors + CatalogScrollGeometry(
                    natural, node.boundsInRoot, axis.value(), axis.maxValue())
                val ordinal = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .mapNotNull { Regex("候选 (\\d+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.singleOrNull()
                if (ordinal != null && config.getOrNull(SemanticsActions.OnClick)?.action != null) {
                    result.add(CatalogCellGeometry(ordinal,
                        natural, node.boundsInRoot, config.getOrNull(SemanticsProperties.Disabled) == null, scrolls))
                }
                node.children.forEach { visit(it, scrolls) }
            }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode, emptyList()) }
        }
        return result.distinctBy { it.ordinal }.sortedBy { it.ordinal }
    }
    private fun sameCatalogBounds(first: Rect, second: Rect) =
        abs(first.left - second.left) <= .75f && abs(first.top - second.top) <= .75f &&
            abs(first.right - second.right) <= .75f && abs(first.bottom - second.bottom) <= .75f
    private fun catalogContains(viewport: Rect, full: Rect) =
        full.left >= viewport.left && full.top >= viewport.top && full.right <= viewport.right && full.bottom <= viewport.bottom
    private fun catalogIntersection(first: Rect, second: Rect): Rect? {
        val left = maxOf(first.left, second.left); val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right); val bottom = minOf(first.bottom, second.bottom)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }
    /** A full-width grid has no horizontal gutter: select an actual visible outer-only strip. */
    private fun catalogOuterScrollPoint(body: Rect, inner: Rect?, density: Float): Pair<Float, Float>? {
        if (inner == null || !body.overlaps(inner)) return body.center.x to body.center.y
        val strips = listOf(
            Rect(body.left, body.top, body.right, maxOf(body.top, inner.top)),
            Rect(body.left, minOf(body.bottom, inner.bottom), body.right, body.bottom),
            Rect(body.left, body.top, maxOf(body.left, inner.left), body.bottom),
            Rect(minOf(body.right, inner.right), body.top, body.right, body.bottom),
        )
        return strips.firstOrNull { it.width >= 4f * density && it.height >= 4f * density }
            ?.let { it.center.x to it.center.y }
    }
    private fun ComposePixelScene.revealWholeCatalogRow(f: Fixture): List<CatalogCellGeometry> {
        var last = emptyList<CatalogCellGeometry>()
        var lastBody = Rect.Zero
        val recentSteps = ArrayDeque<String>(16)
        repeat(180) {
            val body = tag("sticker-avatar-body")
            lastBody = body
            val cells = catalogCellGeometry()
            val first = cells.firstOrNull { it.ordinal == 1 }
            val grid = (first ?: cells.firstOrNull())?.scrolls?.lastOrNull()
            val outer = (first ?: cells.firstOrNull())?.scrolls?.dropLast(1)?.lastOrNull()
            val row = if (first == null) emptyList() else cells.filter { abs(it.full.top - first.full.top) <= .75f }
            last = row
            if (row.isNotEmpty()) {
                assertTrue(row.all { abs(it.full.width - it.full.height) <= .75f && it.full.width >= 56f * f.density - 1f },
                    "Natural catalog cells must retain their square shape and 56 dp minimum: $row")
                assertTrue(row.all { it.full.height <= body.height + .75f },
                    "The real body must be tall enough for a whole natural cell row: $body / $row")
                if (row.size >= 3 && row.map { it.ordinal } == (1..row.size).toList() &&
                    cells.firstOrNull { it.ordinal > row.size }?.ordinal == row.size + 1 && row.all {
                        it.full.left >= body.left && it.full.right <= body.right && it.full.top >= body.top && it.full.bottom <= body.bottom &&
                            it.full.left >= 0 && it.full.top >= 0 && it.full.right <= f.width * f.density && it.full.bottom <= f.pixelHeight &&
                            sameCatalogBounds(it.full, it.clipped) && it.enabled && it.scrolls.size >= 2 &&
                            it.scrolls.all { viewport -> catalogContains(viewport.clipped, it.full) }
                    }) {
                    settledFrame()
                    val stable = catalogCellGeometry().filter { it.ordinal in 1..row.size }
                    if (stable.size == row.size && stable.zip(row).all { (now, before) ->
                            sameCatalogBounds(now.full, before.full) && sameCatalogBounds(now.full, now.clipped) && now.enabled &&
                                now.scrolls.size >= 2 && now.scrolls.all { viewport -> catalogContains(viewport.clipped, now.full) }
                        }) return stable
                }
            }
            val innerVisible = grid?.let { catalogIntersection(body, it.clipped) }
            val resetInner = grid != null && (first == null || first.full.top < grid.full.top - .75f || grid.value > .001f)
            val outerVisible = outer?.let { catalogIntersection(body, it.clipped) } ?: body
            val outerPoint = catalogOuterScrollPoint(outerVisible, innerVisible, f.density)
            val direction = if (first != null && first.full.top < outerVisible.top) -1f else 1f
            val point: Pair<Float, Float>
            val delta: Float
            if (innerVisible != null && (resetInner || outerPoint == null)) {
                // Restore the grid's own first row. At its top, negative nested scrolling
                // exposes outer content so the next pass can target a true outer-only strip.
                point = innerVisible.center.x to innerVisible.center.y
                delta = -1f
            } else {
                point = outerPoint ?: (outerVisible.center.x to outerVisible.center.y)
                delta = direction
            }
            if (recentSteps.size == 16) recentSteps.removeFirst()
            recentSteps.addLast("body=$body; grid=$grid; outer=$outer; resetInner=$resetInner; outerPoint=$outerPoint; point=$point; delta=${delta * .5f * f.density}")
            moveTo(point.first, point.second)
            scrollAt(point.first, point.second, deltaY = delta * .5f * f.density); settledFrame()
        }
        error("A complete first row of natural square catalog cells must be reachable: body=$lastBody cells=$last\nRecent actual pointer steps:\n${recentSteps.joinToString("\n")}")
    }
    private fun ComposePixelScene.clickText(label: String) {
        fun targets() = semanticsBounds { config ->
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
                config.getOrNull(SemanticsActions.OnClick) != null && config.getOrNull(SemanticsProperties.Disabled) == null
        }.filter { it.width > 0 && it.height > 0 }
        await { targets().isNotEmpty() }; settledFrame(2, 16)
        await { targets().isNotEmpty() }
        click(assertNotNull(targets().lastOrNull(), "Actual enabled control is not attached: $label"))
    }
    private fun ComposePixelScene.clickDescription(label: String) {
        if (label.startsWith("候选 ")) { await { enabledDescription(label) }; settledFrame(2, 16); await { enabledDescription(label) } }
        click(description(label))
    }
    private fun ComposePixelScene.slider(label: String, fraction: Float) {
        val bounds = description(label)
        // Pinned Miuix maps a horizontal track from half its height to width minus half its height.
        val radius = bounds.height / 2f
        val left = bounds.left + radius; val right = bounds.right - radius
        check(right - left > 10f) { "The actual slider track is too narrow for a drag." }
        val value = fraction.coerceIn(0f, 1f)
        val start = left + (right - left) * (if (value < .5f) .8f else .2f)
        val target = when (value) { 0f -> left - 1f; 1f -> right + 1f; else -> left + (right - left) * value }
        val y = bounds.center.y
        moveTo(start, y); pressAt(start, y)
        dragTo(start + (target - start) * .5f, y); settledFrame(2, 16) // Cross slop and allow recomposition while held.
        dragTo(target, y); settledFrame(2, 16)
        releaseAt(target, y); settledFrame(5, 16)
    }
    private fun ComposePixelScene.focusedCatalogFilter(field: Rect): Pair<String, TextRange> {
        val values = mutableListOf<Pair<String, TextRange>>()
        val bounds = unmergedSemanticsBounds { config ->
            val text = config.getOrNull(SemanticsProperties.EditableText)?.text
            val selection = config.getOrNull(SemanticsProperties.TextSelectionRange)
            if (config.getOrNull(SemanticsProperties.Focused) == true && text != null && selection != null) {
                values.add(text to selection)
                true
            } else false
        }
        val matches = bounds.indices.filter { bounds[it].overlaps(field) }
        return values[assertNotNull(matches.singleOrNull(), "The pointer-selected catalog filter must own focus and selection")]
    }
    private fun ComposePixelScene.replaceEditor(value: String) {
        await { semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.any { it.width > 0 && it.height > 0 } }
        val field = assertNotNull(semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.firstOrNull())
        click(field)
        val original = focusedCatalogFilter(field)
        selectAll(); settledFrame(2, 16)
        val selected = focusedCatalogFilter(field)
        assertEquals(original.first, selected.first, "Ctrl+A must preserve the current filter text")
        assertEquals(0, selected.second.min, "Ctrl+A must select the filter from the start")
        assertEquals(original.first.length, selected.second.max, "Ctrl+A must select the entire current filter")
        keyStroke(KeyEvent.VK_BACK_SPACE); settledFrame(2, 16)
        val cleared = focusedCatalogFilter(field)
        assertEquals("", cleared.first, "Backspace must clear the entire selected filter")
        assertEquals(TextRange.Zero, cleared.second, "The cleared filter must retain its caret")
        value.forEach {
            keyStroke(KeyEvent.getExtendedKeyCodeForChar(it.code), it)
            settledFrame(1, 16)
        }
        settledFrame(2, 16)
        val typed = focusedCatalogFilter(field)
        assertEquals(value, typed.first, "Real keyboard input must produce the exact requested filter")
        assertEquals(TextRange(value.length), typed.second, "The filter caret must remain after the replacement")
    }
    private fun ComposePixelScene.selectFirst(f: Fixture, waitPreview: Boolean = true) {
        if (f.width < 520) {
            await { texts("选择表情或贴纸").isNotEmpty() }
            clickText("选择表情或贴纸")
        }
        if (f.height <= 400) repeat(40) {
            val found = descriptions("候选 1").firstOrNull { it.width > 0 && it.height > 0 && it.top >= 0 && it.bottom < f.pixelHeight - 50 * f.density }
            if (found != null && f.provider.thumbnails.isNotEmpty()) return@repeat
            scrollAt(f.width * f.density / 2f, f.pixelHeight / 2f, deltaY = 5f * f.density); settledFrame(3, 16)
        }
        await { f.opening?.candidates?.isNotEmpty() == true && enabledDescription("候选 1") }
        clickDescription("候选 1"); await { f.opening?.editor?.selection != null }
        if (waitPreview && f.height > 400) await { f.provider.frames.isNotEmpty() }
    }
    private fun ComposePixelScene.await(predicate: () -> Boolean) {
        repeat(160) { if (predicate()) return; settledFrame(2, 16); Thread.sleep(5) }
        assertTrue(predicate(), "Synthetic UI request did not settle")
    }
    private fun ComposePixelFrame.save(name: String, directory: String = "settings-profile-sticker-avatar-ui-validation") {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x,y])
        File("build/$directory/$name.png").also { it.parentFile.mkdirs(); ImageIO.write(image, "png", it) }
    }
    private fun onUi(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
    private fun worker(block: () -> Unit) { Thread(block).apply { start(); join() } }
}
