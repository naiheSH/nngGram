package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.media.DownloadDestinationRoots
import com.badnng.moe.media.LocalDownloadDestinationRootsResolver
import com.badnng.moe.media.AttachmentClipboard
import com.badnng.moe.media.ClipboardAttachmentContent
import com.badnng.moe.media.LocalAttachmentClipboard
import com.badnng.moe.settings.*
import com.badnng.moe.status.ClientStatus
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.RuntimeSnapshot
import java.awt.image.BufferedImage
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real workspace controls and pointer events, with only synthetic links and an in-memory clipboard. */
@Suppress("DEPRECATION")
class SettingsProxySharingUiTest {
    @Test
    fun displayReversesSourceOrderWhileKeepingEachOriginalCheckIdentity() {
        val checks = rows.mapIndexed { index, proxy ->
            SettingsProxyCheckRow(index, proxy, SettingsProxyCheckStatus.Available(10L + index))
        }
        val display = settingsProxyDisplayRows(rows, checks)
        assertEquals(listOf(2, 1, 0), display.map { it.index })
        assertEquals(rows.reversed(), display.map { it.proxy })
        assertEquals(checks.reversed(), display.map { it.check })
        val changed = rows.mapIndexed { index, row -> row.copy(enabled = index == 1) }
        val updatedChecks = changed.mapIndexed { index, proxy ->
            SettingsProxyCheckRow(index, proxy, SettingsProxyCheckStatus.Available(100L - index))
        }
        val updated = settingsProxyDisplayRows(changed, updatedChecks)
        assertEquals(display.map { it.proxy.id }, updated.map { it.proxy.id })
        assertEquals(updatedChecks.reversed(), updated.map { it.check })

        val added = changed + SettingsProxy(104, "new.invalid", 80, SettingsProxyType.WEB, false)
        val addedChecks = added.mapIndexed { index, proxy ->
            SettingsProxyCheckRow(index, proxy, SettingsProxyCheckStatus.NotTested)
        }
        assertEquals(104, settingsProxyDisplayRows(added, addedChecks).first().proxy.id)
        val edited = added.toMutableList().also { it[1] = it[1].copy(port = 444) }
        val editedChecks = edited.mapIndexed { index, proxy ->
            SettingsProxyCheckRow(index, proxy, SettingsProxyCheckStatus.NotTested)
        }
        val editedDisplay = settingsProxyDisplayRows(edited, editedChecks)
        assertEquals(listOf(104, 103, 102, 101), editedDisplay.map { it.proxy.id })
        assertEquals(444, editedDisplay.single { it.proxy.id == 102 }.check?.proxy?.port)
    }

    @Test
    fun httpRowPopupDoesNotFabricateSingleShareOrQrActions() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); f.publish(f.pages.single(), 2); scene.settledFrame()
            scene.click(scene.revealDescription("代理操作 beta.invalid:443", f)); scene.settledFrame()
            assertTrue(scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == "HTTP 代理不支持分享。" }
            }.isNotEmpty())
            assertTrue(scene.actions("分享代理").isEmpty())
            assertTrue(scene.actions("显示二维码").isEmpty())
            assertTrue(f.shares.isEmpty())
        }
    }

    @Test
    fun allHttpVisibleRowsCannotProveThatTheHiddenNativeListIsUnshareable() {
        val parent = SettingsToken(31, 1)
        val token = SettingsProxyChecksToken(parent, 2)
        val visible = listOf(rows[1])
        val snapshot = SettingsSnapshot(token = parent, enabled = true, loaded = setOf(SettingsSection.ADVANCED),
            proxies = visible, proxyChecks = SettingsProxyChecksSnapshot(token, 3,
                listOf(SettingsProxyCheckRow(0, visible.single(), SettingsProxyCheckStatus.NotTested))))
        val request = SettingsProxyShareRequest(token, 3, SettingsProxyShareTarget.All(visible), SettingsProxyShareFormat.PUBLIC)
        assertTrue(settingsProxyShareRequestIsCurrent(snapshot, request),
            "Only the complete native response can decide whether all items are HTTP.")
    }

    @Test
    fun normalAndModifierPointerActionsRequestTheCorrectLinkAndQrFormat() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); f.publish(f.pages.single(), 1); scene.settledFrame()
            for ((ctrl, alt, shift) in listOf(Triple(false, false, false), Triple(true, false, false),
                Triple(false, true, false), Triple(false, false, true))) {
                onUi { f.ctrl = ctrl; f.alt = alt; f.shift = shift }
                scene.click(scene.revealDescription("代理操作 alpha.invalid:1080", f)); scene.settledFrame()
                scene.click(scene.revealAction("分享代理", f)); scene.settledFrame()
                val call = f.shares.last()
                val expected = if (ctrl || alt || shift) SettingsProxyShareFormat.INTERNAL else SettingsProxyShareFormat.PUBLIC
                assertEquals(expected, call.request.format)
                val target = assertIs<SettingsProxyShareTarget.One>(call.request.target)
                assertEquals(0, target.index)
                assertEquals(rows[0], target.proxy)
                assertTrue(f.clipboard.writes.isEmpty() || f.clipboard.writes.size == f.shares.size - 1)
                f.complete(call, if (expected == SettingsProxyShareFormat.PUBLIC) PUBLIC_LINK else INTERNAL_LINK)
                scene.settledFrame()
                scene.click(scene.revealAction("复制链接", f)); scene.settledFrame()
                assertEquals(f.shares.size, f.clipboard.writes.size)
                assertTrue(call.content?.closed == true)
            }
            onUi { f.ctrl = false; f.alt = false; f.shift = false }
            scene.click(scene.revealDescription("代理操作 gamma.invalid:80", f)); scene.settledFrame()
            scene.click(scene.revealAction("显示二维码", f)); scene.settledFrame()
            val qr = f.shares.last()
            assertEquals(SettingsProxyShareFormat.INTERNAL, qr.request.format)
            assertEquals(2, assertIs<SettingsProxyShareTarget.One>(qr.request.target).index)
            val copies = f.clipboard.writes.size
            f.complete(qr, INTERNAL_LINK); scene.settledFrame()
            assertTrue(scene.descriptions("代理二维码").isNotEmpty())
            assertEquals(copies, f.clipboard.writes.size, "Preparation and QR rendering must not copy automatically.")
            scene.click(scene.revealAction("复制链接", f)); scene.settledFrame()
            assertEquals(copies + 1, f.clipboard.writes.size)
        }
    }

    @Test
    fun allShareAndPopupActionsRemainReachableAcrossViewportThemeAndDensity() {
        for ((width, height) in listOf(1000 to 700, 350 to 300))
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode)
                f.scene().use { scene ->
                    scene.settledFrame(); f.publish(f.pages.single(), 3); scene.settledFrame()
                    scene.click(scene.revealDescription("代理操作 gamma.invalid:80", f)); scene.settledFrame()
                    scene.click(scene.revealAction("显示二维码", f)); scene.settledFrame()
                    val qr = f.shares.last()
                    assertEquals(SettingsProxyShareFormat.INTERNAL, qr.request.format)
                    f.complete(qr, INTERNAL_LINK); scene.settledFrame()
                    assertTrue(scene.descriptions("代理二维码").isNotEmpty())
                    val qrBounds = scene.revealCompleteQr(f)
                    val qrFrame = scene.settledFrame()
                    assertQrPainted(qrFrame, qrBounds)
                    qrFrame.save("qr-${width}x$height-$mode-$density", f)
                    scene.click(scene.revealAction("复制链接", f)); scene.settledFrame()
                    assertEquals(1, f.clipboard.writes.size)

                    val before = scene.reveal(f) { scene.textBounds(ALL_DISCLOSURE).take(1) }
                    assertNotePainted(scene.settledFrame(), before)
                    scene.click(scene.revealAction("分享代理列表", f)); scene.settledFrame()
                    val all = f.shares.last()
                    assertEquals(SettingsProxyShareFormat.PUBLIC, all.request.format)
                    assertEquals(rows, assertIs<SettingsProxyShareTarget.All>(all.request.target).rows)
                    f.complete(all, "$PUBLIC_LINK\n\n$PUBLIC_LINK"); scene.settledFrame()
                    val disclosure = scene.reveal(f) { scene.textBounds(ALL_DISCLOSURE).takeLast(1) }
                    assertNotePainted(scene.settledFrame(), disclosure)
                    scene.settledFrame().save("list-${width}x$height-$mode-$density", f)
                    scene.click(scene.revealAction("复制链接", f)); scene.settledFrame()
                    assertEquals(2, f.clipboard.writes.size)
                }
            }
    }

    @Test
    fun leavingAndRefreshingRetireTheOldPopupAndItsLateContent() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); f.publish(f.pages.single(), 5); scene.settledFrame()
            scene.click(scene.revealDescription("代理操作 alpha.invalid:1080", f)); scene.settledFrame()
            scene.click(scene.revealAction("分享代理", f)); scene.settledFrame()
            val old = f.shares.single()
            scene.click(scene.tab(SettingsDestination.CHAT))
            assertFalse(old.page.current(), "Navigation must revoke before the outgoing page is removed.")
            f.complete(old, PUBLIC_LINK); scene.settledFrame()
            assertTrue(old.content?.closed == true)
            assertTrue(f.clipboard.writes.isEmpty())
            scene.click(scene.tab(SettingsDestination.ADVANCED)); scene.settledFrame()
            f.publish(f.pages.last(), 6); scene.settledFrame()
            assertTrue(scene.actions("复制链接").isEmpty())

            scene.click(scene.revealDescription("代理操作 alpha.invalid:1080", f)); scene.settledFrame()
            scene.click(scene.revealAction("分享代理", f)); scene.settledFrame()
            val current = f.shares.last()
            f.complete(current, PUBLIC_LINK); scene.settledFrame()
            val oldCopyBounds = scene.revealAction("复制链接", f)
            onUi { f.state.proxyChecksOwner.current?.refresh() }
            assertTrue(current.content?.closed == true)
            scene.settledFrame(); scene.click(oldCopyBounds); scene.settledFrame()
            assertTrue(f.clipboard.writes.isEmpty(), "A stale popup's coordinates cannot copy an old result.")
        }
    }

    @Test
    fun clipboardFailureAndReentrantPageCloseDoNotProduceAnExtraWrite() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); f.publish(f.pages.single(), 9); scene.settledFrame()
            scene.click(scene.revealDescription("代理操作 alpha.invalid:1080", f)); scene.settledFrame()
            scene.click(scene.revealAction("分享代理", f)); scene.settledFrame()
            val failed = f.shares.last(); f.complete(failed, PUBLIC_LINK); scene.settledFrame()
            onUi { f.clipboard.fail = true }
            scene.click(scene.revealAction("复制链接", f)); scene.settledFrame()
            assertTrue(f.clipboard.writes.isEmpty())
            assertFalse(failed.content!!.closed, "A failed publication must remain retryable.")
            onUi { f.clipboard.fail = false }
            scene.click(scene.revealAction("复制链接", f)); scene.settledFrame()
            assertTrue(failed.content!!.closed)
            assertEquals(1, f.clipboard.writes.size)
            scene.click(scene.revealDescription("代理操作 alpha.invalid:1080", f)); scene.settledFrame()
            scene.click(scene.revealAction("分享代理", f)); scene.settledFrame()
            val success = f.shares.last(); f.complete(success, PUBLIC_LINK); scene.settledFrame()
            onUi { f.clipboard.onWrite = { f.state.proxyChecksOwner.close() } }
            scene.click(scene.revealAction("复制链接", f)); scene.settledFrame()
            assertEquals(2, f.clipboard.writes.size)
            assertTrue(success.content?.closed == true)
        }
    }

    @Test
    fun copyShortcutInTheProxyGroupSharesTheListButTheAddFormKeepsTextCopy() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); f.publish(f.pages.single(), 11); scene.settledFrame()
            scene.click(scene.revealAction("alpha.invalid:1080", f)); scene.settledFrame()
            scene.shortcut(KeyEvent.VK_C); scene.settledFrame()
            assertEquals(1, f.shares.size)
            assertIs<SettingsProxyShareTarget.All>(f.shares.single().request.target)
            assertTrue(f.clipboard.writes.isEmpty())
            scene.click(scene.revealAction("关闭", f)); scene.settledFrame()

            scene.click(scene.revealAction("添加代理", f)); scene.settledFrame()
            val label = scene.reveal(f) { scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == "服务器" }
            } }
            val field = assertNotNull(scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.EditableText) != null
            }.firstOrNull { it.overlaps(label) })
            scene.click(field.intersect(label)); scene.settledFrame(2, 16)
            for (character in "synthetic") {
                scene.keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character)
                scene.settledFrame(1, 16)
            }
            scene.selectAll(); scene.settledFrame()
            val previousWrites = f.clipboard.writes.size + f.modernClipboard.writes
            scene.shortcut(KeyEvent.VK_C); scene.settledFrame()
            assertEquals(1, f.shares.size, "Text-field copy cannot initiate a proxy-list share.")
            assertTrue(f.clipboard.writes.size + f.modernClipboard.writes > previousWrites,
                "The real focused field must receive its copy shortcut using an in-memory clipboard.")
        }
    }

    @Test
    fun qrImageCopyIsExplicitUsesTheActualMatrixAndCanRetryABusyClipboard() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); f.publish(f.pages.single(), 12); scene.settledFrame()
            scene.click(scene.revealDescription("代理操作 gamma.invalid:80", f)); scene.settledFrame()
            scene.click(scene.revealAction("显示二维码", f)); scene.settledFrame()
            val request = f.shares.single()
            assertEquals(SettingsProxyShareFormat.INTERNAL, request.request.format)
            f.complete(request, INTERNAL_LINK); scene.settledFrame()
            assertEquals(0, f.qrClipboard.attempts)
            assertTrue(f.qrClipboard.copies.isEmpty())
            onUi { f.qrClipboard.busy = true }
            scene.click(scene.revealAction("复制二维码", f)); scene.settledFrame()
            assertEquals(1, f.qrClipboard.attempts)
            assertEquals(1, f.qrClipboard.closedPreparations)
            assertTrue(f.qrClipboard.copies.isEmpty())
            assertFalse(request.content!!.closed, "A busy clipboard must leave the current result retryable.")
            assertTrue(scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == "无法复制，请重试。" }
            }.isNotEmpty())
            onUi { f.qrClipboard.busy = false }
            scene.click(scene.revealAction("复制二维码", f)); scene.settledFrame()
            val actual = f.qrClipboard.copies.single()
            val expected = assertNotNull(LoginQrMatrix.encode(INTERNAL_LINK))
            assertEquals(expected.size, actual.first)
            for (y in 0 until expected.size) for (x in 0 until expected.size) {
                assertEquals(expected[x, y], actual.second[y * expected.size + x])
            }
            assertTrue(f.qrClipboard.lastArgument!!.all { !it }, "The UI must clear its temporary matrix after the call.")
            assertEquals(2, f.qrClipboard.closedPreparations)
            assertTrue(request.content!!.closed)
            assertTrue(f.clipboard.writes.isEmpty(), "Image copy must not silently copy text.")
        }
    }

    @Test
    fun qrCopyRechecksOwnerAtTheActualSynchronousPublishBoundary() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); f.publish(f.pages.single(), 13); scene.settledFrame()
            scene.click(scene.revealDescription("代理操作 gamma.invalid:80", f)); scene.settledFrame()
            scene.click(scene.revealAction("显示二维码", f)); scene.settledFrame()
            val request = f.shares.single(); f.complete(request, INTERNAL_LINK); scene.settledFrame()
            onUi { f.qrClipboard.beforePublish = { f.state.proxyChecksOwner.current?.refresh() } }
            scene.click(scene.revealAction("复制二维码", f)); scene.settledFrame()
            assertEquals(1, f.qrClipboard.attempts)
            assertTrue(f.qrClipboard.copies.isEmpty())
            assertTrue(request.content!!.closed)
            assertTrue(f.clipboard.writes.isEmpty())
        }
    }

    @Test
    fun textCopyAlsoRechecksOwnerAtTheActualSynchronousPublishBoundary() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); f.publish(f.pages.single(), 14); scene.settledFrame()
            scene.click(scene.revealDescription("代理操作 alpha.invalid:1080", f)); scene.settledFrame()
            scene.click(scene.revealAction("分享代理", f)); scene.settledFrame()
            val request = f.shares.single(); f.complete(request, PUBLIC_LINK); scene.settledFrame()
            onUi { f.qrClipboard.beforeTextPublish = { f.state.proxyChecksOwner.current?.refresh() } }
            scene.click(scene.revealAction("复制链接", f)); scene.settledFrame()
            assertTrue(f.clipboard.writes.isEmpty())
            assertTrue(request.content!!.closed)
            assertEquals(1, f.qrClipboard.closedTextPreparations)
        }
    }

    @Test
    fun pageLossDuringEitherPreparationPreventsTheLaterPublication() {
        for (qr in listOf(false, true)) {
            val f = Fixture()
            f.scene().use { scene ->
                scene.settledFrame(); f.publish(f.pages.single(), 15); scene.settledFrame()
                scene.click(scene.revealDescription("代理操作 alpha.invalid:1080", f)); scene.settledFrame()
                scene.click(scene.revealAction(if (qr) "显示二维码" else "分享代理", f)); scene.settledFrame()
                val request = f.shares.single(); f.complete(request, if (qr) INTERNAL_LINK else PUBLIC_LINK); scene.settledFrame()
                onUi {
                    if (qr) f.qrClipboard.beforeQrPrepare = { f.state.proxyChecksOwner.current?.refresh() }
                    else f.qrClipboard.beforeTextPrepare = { f.state.proxyChecksOwner.current?.refresh() }
                }
                scene.click(scene.revealAction(if (qr) "复制二维码" else "复制链接", f)); scene.settledFrame()
                assertEquals(0, f.qrClipboard.attempts)
                assertEquals(0, f.qrClipboard.textAttempts)
                assertTrue(f.qrClipboard.copies.isEmpty())
                assertTrue(f.clipboard.writes.isEmpty())
                assertTrue(request.content!!.closed)
                if (qr) assertEquals(1, f.qrClipboard.closedPreparations)
                else assertEquals(1, f.qrClipboard.closedTextPreparations)
            }
        }
    }

    private class Page(val parent: SettingsToken, val current: () -> Boolean,
        val opened: (SettingsProxyChecksToken?) -> Unit)
    private class Share(val page: Page, val request: SettingsProxyShareRequest,
        val reply: (SettingsResult<SettingsProxyShareContent>) -> Unit) { var content: Content? = null }
    private class Content(private val text: String, private val valid: () -> Boolean) : SettingsProxyShareContent {
        var closed = false
            private set
        override fun textIfCurrent() = text.takeIf { !closed && valid() }
        override fun useIfCurrent(commit: (String) -> Unit): Boolean {
            val value = textIfCurrent() ?: return false
            runCatching { commit(value) }
            return true
        }
        override fun close() { closed = true }
        override fun toString() = "Content(<redacted>)"
    }
    private class MemoryClipboard : ClipboardManager {
        val writes = mutableListOf<String>()
        var fail = false
        var onWrite: () -> Unit = {}
        override fun setText(annotatedString: AnnotatedString) {
            if (fail) throw IllegalStateException("synthetic clipboard failure")
            onWrite()
            writes += annotatedString.text
        }
        override fun getText(): AnnotatedString? = writes.lastOrNull()?.let(::AnnotatedString)
    }
    private class ModernMemoryClipboard : Clipboard {
        private var entry: ClipEntry? = null
        var writes = 0
            private set
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry; writes++ }
    }
    private class QrMemoryClipboard(private val clipboard: MemoryClipboard) :
        AttachmentClipboard, SettingsProxyQrClipboard, SettingsProxyTextClipboard {
        val copies = mutableListOf<Pair<Int, BooleanArray>>()
        var attempts = 0
        var busy = false
        var beforeQrPrepare: () -> Unit = {}
        var beforeTextPrepare: () -> Unit = {}
        var beforePublish: () -> Unit = {}
        var lastArgument: BooleanArray? = null
        var closedPreparations = 0
        var closedTextPreparations = 0
        var textAttempts = 0
        var beforeTextPublish: () -> Unit = {}
        override fun prepareProxyQr(size: Int, modules: BooleanArray): SettingsProxyQrPrepared? {
            lastArgument = modules
            beforeQrPrepare()
            val prepared = modules.copyOf()
            return object : SettingsProxyQrPrepared {
                var closed = false
                override fun publishIfCurrent(isCurrent: () -> Boolean): Boolean {
                    attempts++
                    if (closed || !isCurrent() || busy) return false
                    beforePublish()
                    if (!isCurrent()) return false
                    copies += size to prepared.copyOf()
                    return true
                }
                override fun close() {
                    if (!closed) { closed = true; prepared.fill(false); closedPreparations++ }
                }
            }
        }
        override fun hasAttachments() = false
        override suspend fun readAttachments(): ClipboardAttachmentContent? = null
        override suspend fun writeFiles(paths: List<String>) = error("unused")
        override suspend fun writePng(bytes: ByteArray) = error("unused")
        override fun prepareProxyText(value: String): SettingsProxyTextPrepared {
            beforeTextPrepare()
            return object : SettingsProxyTextPrepared {
                private var text: String? = value
                override fun publishIfCurrent(isCurrent: () -> Boolean): Boolean {
                    textAttempts++
                    val currentText = text ?: return false
                    if (!isCurrent()) return false
                    beforeTextPublish()
                    if (!isCurrent()) return false
                    return runCatching { clipboard.setText(AnnotatedString(currentText)); true }.getOrDefault(false)
                }
                override fun close() { if (text != null) { text = null; closedTextPreparations++ } }
            }
        }
    }

    private class Fixture(val width: Int = 1000, val height: Int = 700, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light) {
        var ctrl by mutableStateOf(false)
        var alt by mutableStateOf(false)
        var shift by mutableStateOf(false)
        val pages = mutableListOf<Page>()
        val shares = mutableListOf<Share>()
        val clipboard = MemoryClipboard()
        val modernClipboard = ModernMemoryClipboard()
        val qrClipboard = QrMemoryClipboard(clipboard)
        val state = SettingsWorkspaceState().apply { destination = SettingsDestination.ADVANCED; detailOpen = true }
        var runtime by mutableStateOf(RuntimeSnapshot(configPath = "synthetic/settings.properties", generation = 17,
            status = ClientStatus(connection = ConnectionPhase.READY), chats = ChatSnapshot(),
            settings = SettingsSnapshot(token = SettingsToken(17, 3), enabled = true,
                loaded = setOf(SettingsSection.ADVANCED), proxies = rows)))
        val actions = SettingsActions(proxyChecks = SettingsProxyChecksActions(open = { parent, current, opened ->
            val page = Page(parent, current, opened).also(pages::add)
            object : SettingsProxyChecksLoad {
                override fun close() {}
                override fun refresh() {}
                override fun share(request: SettingsProxyShareRequest, result: (SettingsResult<SettingsProxyShareContent>) -> Unit) {
                    shares += Share(page, request, result)
                }
            }
        }))
        val downloads = object : DownloadPreferencesAccess {
            private var value = SettingsDownloadPreferences()
            override suspend fun read(): DownloadPreferencesRead = DownloadPreferencesRead.Value(value)
            override suspend fun write(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences): DownloadPreferencesWrite {
                if (expected != value) return DownloadPreferencesWrite.CONFLICT
                value = desired
                return DownloadPreferencesWrite.SAVED
            }
        }
        fun publish(page: Page, owner: Long) = onUi {
            val token = SettingsProxyChecksToken(page.parent, owner)
            page.opened(token)
            val checks = rows.mapIndexed { index, proxy ->
                SettingsProxyCheckRow(index, proxy, SettingsProxyCheckStatus.NotTested)
            }
            runtime = runtime.copy(settings = runtime.settings.copy(proxyChecks = SettingsProxyChecksSnapshot(token, owner, checks)))
        }
        fun complete(share: Share, text: String) = onUi {
            val content = Content(text, share.page.current)
            share.content = content
            share.reply(SettingsResult(content))
        }
        fun scene() = ComposePixelScene((width * density).roundToInt(), (height * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            val preferences = remember { SettingsPreferences(theme = mode) }
            val host = LocalWindowInfo.current
            val window = remember(host) { object : WindowInfo by host {
                override val keyboardModifiers get() = PointerKeyboardModifiers(isCtrlPressed = ctrl, isAltPressed = alt, isShiftPressed = shift)
                override val containerSize get() = IntSize((width * density).roundToInt(), (height * density).roundToInt())
            } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides window,
                LocalClipboardManager provides clipboard, LocalClipboard provides modernClipboard,
                LocalAttachmentClipboard provides qrClipboard,
                LocalSettingsPreferences provides preferences,
                LocalDownloadPreferencesAccess provides downloads,
                LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(
                    java.nio.file.Path.of("C:/Synthetic/Downloads/nngGram"), java.nio.file.Path.of("C:/Synthetic/Account/downloads-temp/user-1")) },
                LocalDesktopChatLayout provides desktopChatLayout(width.dp, height.dp),
                LocalSettingsSecurityConfirmationHost provides InlineConfirmation) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        SettingsWorkspace(runtime, actions, ChatFeatureActions(), preferences, {}, ChatFolderPreferences(), {},
                            onClose = {}, onOpenConfig = {}, state = state, active = true)
                    }
                }
            }
        }
    }

    private object InlineConfirmation : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().widthIn(max = maxWidth).heightIn(max = 260.dp)
                .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp)) {
                title?.let { Text(it) }; content()
            }
        }
    }

    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.actions(label: String) = semanticsBounds {
        it.getOrNull(SemanticsActions.OnClick) != null &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.textBounds(label: String) = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.revealDescription(label: String, f: Fixture) = reveal(f) { descriptions(label) }
    private fun ComposePixelScene.revealAction(label: String, f: Fixture) = reveal(f) { actions(label) }
    private fun ComposePixelScene.tab(destination: SettingsDestination) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == destination.title }
    }.firstOrNull(), destination.title)
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun ComposePixelScene.reveal(f: Fixture, candidates: () -> List<Rect>): Rect {
        val top = 64f * f.density
        val bottom = f.height * f.density
        fun visible() = candidates().firstOrNull { it.width > 0 && it.height >= 8 * f.density &&
            it.top >= top && it.bottom <= bottom - 1 }
        var direction = 1
        var step = 8f * f.density
        var previous = 0
        repeat(180) {
            visible()?.let { return it }
            val target = candidates().firstOrNull { it.width > 0 && it.height > 0 }
            var position = 0f; var maximum = 0f
            val scrollers = semanticsBounds { config -> config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let {
                position = it.value(); maximum = it.maxValue()
            }; config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            direction = if (target != null) { if (target.top < top) -1 else 1 }
                else if (position <= .5f) 1 else if (position >= maximum - .5f) -1 else direction
            if (target == null) step = 8f * f.density
            else if (previous != 0 && previous != direction) step = (step / 2).coerceAtLeast(.02f)
            previous = direction
            val x = if (f.width >= 760) f.width * f.density * .78f else f.width * f.density / 2f
            val scroll = scrollers.lastOrNull { it.left <= x && it.right >= x && it.height > 20 }
            scrollAt(x, scroll?.center?.y ?: bottom / 2f, deltaY = direction * step)
            settledFrame()
        }
        return assertNotNull(visible(), "The actual pointer must expose the requested control.")
    }
    private fun ComposePixelFrame.save(name: String, f: Fixture) {
        val image = BufferedImage((f.width * f.density).roundToInt(), (f.height * f.density).roundToInt(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, this[x, y])
        val target = File("build/settings-workspace-validation/proxy-sharing/$name.png")
        target.parentFile.mkdirs()
        assertTrue(ImageIO.write(image, "png", target))
    }
    private data class QrScroll(val full: Rect, val clipped: Rect, val position: Float, val maximum: Float)
    private data class QrGeometry(val full: Rect, val clipped: Rect, val parents: List<QrScroll>)
    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    private fun ComposePixelScene.qrGeometry(): QrGeometry? {
        var result: QrGeometry? = null
        onUi {
            val field = ComposePixelScene::class.java.getDeclaredField("scene")
            check(field.type == ImageComposeScene::class.java && field.trySetAccessible())
            val scene = field.get(this) as ImageComposeScene
            fun visit(node: SemanticsNode, parents: List<QrScroll>) {
                val position = node.positionInRoot; val size = node.size
                val full = Rect(position.x, position.y, position.x + size.width, position.y + size.height)
                val axis = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                val next = if (axis == null) parents else parents + QrScroll(full, node.boundsInRoot, axis.value(), axis.maxValue())
                if ("代理二维码" in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                    result = QrGeometry(full, node.boundsInRoot, next)
                node.children.forEach { visit(it, next) }
            }
            scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode, emptyList()) }
        }
        return result
    }
    private fun containsWhole(parent: Rect, child: Rect) = child.left >= parent.left - .75f &&
        child.top >= parent.top - .75f && child.right <= parent.right + .75f && child.bottom <= parent.bottom + .75f
    private fun sameBounds(first: Rect, second: Rect) = abs(first.left - second.left) <= .75f &&
        abs(first.top - second.top) <= .75f && abs(first.right - second.right) <= .75f && abs(first.bottom - second.bottom) <= .75f
    private fun ComposePixelScene.revealCompleteQr(f: Fixture): Rect {
        val window = Rect(0f, 64f * f.density, f.width * f.density, f.height * f.density)
        val trace = ArrayDeque<String>()
        var previousDirection = 0f
        var step = 1f * f.density
        fun complete(value: QrGeometry?): Boolean = value != null && value.full.width > 0 &&
            abs(value.full.width - value.full.height) <= .75f && containsWhole(window, value.full) &&
            sameBounds(value.full, value.clipped) && value.parents.all { containsWhole(it.clipped, value.full) }
        repeat(180) { attempt ->
            val geometry = assertNotNull(qrGeometry(), "The actual QR canvas must remain attached.")
            if (complete(geometry)) {
                settledFrame()
                val stable = qrGeometry()
                if (stable != null && complete(stable) && sameBounds(geometry.full, stable.full)) return stable.full
            }
            val scroll = assertNotNull(geometry.parents.firstOrNull {
                it.clipped.width > 0 && it.clipped.height > 20 * f.density && !containsWhole(it.clipped, geometry.full)
            }, "The real QR must fit an actual scroll viewport: $geometry")
            assertTrue(geometry.full.height <= scroll.full.height + .75f,
                "A complete natural QR must fit its real ancestor viewport: $geometry")
            val direction = if (geometry.full.top < scroll.clipped.top) -1f else 1f
            val x = scroll.clipped.left + 4f * f.density
            val y = scroll.clipped.center.y
            // Natural QR geometry is always known here, so halve only a measured
            // overshoot; never let clipped-away semantics shrink a blind search.
            if (previousDirection != 0f && previousDirection != direction) step = (step / 2f).coerceAtLeast(.02f)
            previousDirection = direction
            val delta = direction * step
            trace.addLast("step=$attempt qr=$geometry pointer=($x,$y) delta=$delta")
            if (trace.size > 16) trace.removeFirst()
            moveTo(x, y); scrollAt(x, y, deltaY = delta); settledFrame()
        }
        error("The complete QR canvas and quiet zone must be reachable by real wheel input: ${qrGeometry()}; $trace")
    }
    private fun assertQrPainted(frame: ComposePixelFrame, bounds: Rect) {
        val matrix = assertNotNull(LoginQrMatrix.encode(INTERNAL_LINK))
        val side = minOf(bounds.width, bounds.height)
        val cell = floor(side / (matrix.size + 8))
        assertTrue(cell >= 1f, "A visible QR must retain at least one physical pixel per module.")
        val start = (side - matrix.size * cell) / 2f
        assertTrue(start >= 4f * cell - .75f, "The real white quiet zone must span four modules.")
        fun rgb(x: Float, y: Float): Int = frame[floor(x).toInt(), floor(y).toInt()] and 0xFFFFFF
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            val actual = rgb(bounds.left + start + (x + .5f) * cell, bounds.top + start + (y + .5f) * cell)
            assertEquals(if (matrix[x, y]) 0x000000 else 0xFFFFFF, actual, "Actual QR module ($x,$y) must be painted.")
        }
        for (offset in 1 until floor(side).toInt() - 1) {
            val quiet = cell
            assertEquals(0xFFFFFF, rgb(bounds.left + offset, bounds.top + quiet), "Top quiet zone")
            assertEquals(0xFFFFFF, rgb(bounds.left + offset, bounds.bottom - quiet), "Bottom quiet zone")
            assertEquals(0xFFFFFF, rgb(bounds.left + quiet, bounds.top + offset), "Left quiet zone")
            assertEquals(0xFFFFFF, rgb(bounds.right - quiet, bounds.top + offset), "Right quiet zone")
        }
    }
    private fun assertNotePainted(frame: ComposePixelFrame, bounds: Rect) {
        assertTrue(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= frame.width && bounds.bottom <= frame.height,
            "The disclosure must be fully within the rendered viewport before copying.")
        val left = kotlin.math.floor(bounds.left).toInt().coerceIn(0, frame.width - 1)
        val right = kotlin.math.ceil(bounds.right).toInt().coerceIn(left + 1, frame.width)
        val top = kotlin.math.floor(bounds.top).toInt().coerceIn(0, frame.height - 1)
        val bottom = kotlin.math.ceil(bounds.bottom).toInt().coerceIn(top + 1, frame.height)
        var darkest = 255
        var lightest = 0
        for (y in top until bottom) for (x in left until right) {
            val color = frame[x, y]
            val luminance = ((color shr 16 and 255) * 299 + (color shr 8 and 255) * 587 + (color and 255) * 114) / 1000
            darkest = minOf(darkest, luminance)
            lightest = maxOf(lightest, luminance)
        }
        assertTrue(lightest - darkest >= 18, "The visible disclosure must contain rendered contrasting pixels.")
    }
    private companion object {
        private fun onUi(block: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
        }
        const val PUBLIC_LINK = "https://synthetic.invalid/proxy"
        const val INTERNAL_LINK = "tg://synthetic-proxy"
        const val ALL_DISCLOSURE = "包含所有可分享代理，包括此处未显示的代理；链接可能包含代理登录信息。"
        val rows = listOf(
            SettingsProxy(101, "alpha.invalid", 1080, SettingsProxyType.SOCKS5, true),
            SettingsProxy(102, "beta.invalid", 443, SettingsProxyType.HTTP, false),
            SettingsProxy(103, "gamma.invalid", 80, SettingsProxyType.WEB, false),
        )
    }
}
