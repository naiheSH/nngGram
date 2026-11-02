package com.badnng.moe

import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatResult
import com.badnng.moe.settings.SettingsActions
import com.badnng.moe.settings.SettingsProfileExtrasActions
import com.badnng.moe.media.rememberProfileStickerAvatarArtwork
import com.badnng.moe.notifications.DeviceNotificationActions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import com.badnng.moe.diagnostics.IdleProbeConfig
import com.badnng.moe.diagnostics.IdleProbeContent
import com.badnng.moe.media.LocalNativeDialogOwner
import com.badnng.moe.media.LocalAttachmentClipboard
import com.badnng.moe.media.LocalWindowActivity
import com.badnng.moe.media.WindowActivityGate
import com.badnng.moe.media.observeWindowInactivity
import com.badnng.moe.window.WorkArea
import com.badnng.moe.window.initialWindowGeometry
import com.badnng.moe.tdlib.TdEngine
import com.badnng.moe.tdlib.ConfigurationStore
import com.badnng.moe.tdlib.TdNativeImageSmoke
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.presentation
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.TaoScreenGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.math.roundToInt

fun main(args: Array<String>) {
    handleInstallationArguments(args)?.let { kotlin.system.exitProcess(it) }
    // These explicit diagnostics never construct a configuration store or a real account engine.
    if (args.firstOrNull() == "--windows-toast-fake-smoke") {
        require(args.size == 1)
        dev.nucleusframework.graalvm.GraalVmInitializer.initialize()
        check(com.badnng.moe.notifications.WindowsToastNativeSmoke.handle(args))
        return
    }
    if (TdNativeImageSmoke.handle(args)) return
    if (PlatformNativeImageSmoke.handle(args)) return
    if (handleDesktopMainRender(args)) return
    val toastActivationLaunch = com.badnng.moe.notifications.WindowsToastLaunchArguments.isActivation(args)
    val applicationArgs = if (toastActivationLaunch) emptyArray() else args
    if (args.firstOrNull() == "--media-native-smoke") {
        require(args.size == 2)
        dev.nucleusframework.graalvm.GraalVmInitializer.initialize()
        com.badnng.moe.media.MediaNativeImageSmoke.run(java.nio.file.Path.of(args[1]))
        println("MEDIA_NATIVE_SMOKE_OK")
        return
    }
    val probe = IdleProbeConfig.parse(applicationArgs)
    val accountMenuPreview = args.firstOrNull() == "--account-menu-preview"
    val desktopMainPreview = args.firstOrNull() == "--desktop-main-preview"
    if (desktopMainPreview) require(args.size in 1..4 && args.drop(1).all {
        it == "--slim" || it == "--compact" || it == "--design-size" || it == "--dark" || it == "--unread"
    } && listOf("--slim", "--compact", "--design-size").count { it in args } <= 1) {
        "Usage: --desktop-main-preview [--slim | --compact | --design-size] [--dark] [--unread]"
    }
    nucleusApplication(applicationArgs, backend = NucleusBackend.Tao, enableSingleInstance = probe == null && !accountMenuPreview && !desktopMainPreview) {
        if (desktopMainPreview) {
            val dark = "--dark" in args
            DesktopWindow(
                "nngGram · 离线主界面预览（交互修订）", ::exitApplication,
                initialSize = when {
                    "--slim" in args -> DpSize(380.dp, 840.dp)
                    "--compact" in args -> DpSize(780.dp, 600.dp)
                    "--design-size" in args -> DpSize(1494.dp, 993.dp)
                    else -> DpSize(1160.dp, 840.dp)
                },
                initialConnection = ConnectionPhase.READY,
                forceDark = dark,
            ) { active, _ -> DesktopMainPreview(active, dark, unread = "--unread" in args) }
        } else if (accountMenuPreview) {
            DesktopWindow(
                "nngGram · 离线头像菜单预览", ::exitApplication,
                initialSize = if ("--compact" in args) DpSize(780.dp, 600.dp) else null,
            ) { active, _ ->
                AccountMenuPreview(active)
            }
        } else if (probe != null) {
            DesktopWindow("nngGram · 离线渲染探针", ::exitApplication) { active, activity ->
                IdleProbeContent(probe, active, Modifier.fillMaxSize(), ::exitApplication, windowActivity = activity)
            }
        } else {
            ConnectedDesktop(toastActivationLaunch)
        }
    }
}

@Composable
private fun NucleusApplicationScope.ConnectedDesktop(toastActivationLaunch: Boolean = false) {
    val configuration = remember { ConfigurationStore.default() }
    val notificationWindowHandle = remember { java.util.concurrent.atomic.AtomicLong() }
    val engine = remember { TdEngine(configuration, notificationWindowHandle = notificationWindowHandle::get,
        toastActivationEntryPointAvailable = true, toastActivationLaunch = toastActivationLaunch) }
    val proxyChecksHostActive = remember(engine) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val proxyChecksLoads = remember(engine) {
        java.util.concurrent.ConcurrentHashMap.newKeySet<com.badnng.moe.settings.SettingsProxyChecksLoad>()
    }
    val storageHostActive = remember(engine) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val storageHandles = remember(engine) {
        java.util.concurrent.ConcurrentHashMap.newKeySet<com.badnng.moe.settings.SettingsStorageHandle>()
    }
    DisposableEffect(engine) {
        onDispose {
            proxyChecksHostActive.set(false)
            proxyChecksLoads.forEach { it.close() }
            storageHostActive.set(false)
            storageHandles.forEach { it.close() }
        }
    }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var exitRequested by remember { mutableStateOf(false) }
    var configurationOpenError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(engine) { engine.start() }
    LaunchedEffect(engine, exitRequested) {
        // Keep listening outside the window lifecycle: a minimized window must still finish closing.
        if (exitRequested) {
            engine.state.first { it.terminated }
            engine.closeLocalLockResumeHostForExit()
            exitApplication()
        }
    }
    val requestExit: () -> Unit = {
        if (!closing) {
            proxyChecksHostActive.set(false)
            proxyChecksLoads.forEach { it.close() }
            storageHostActive.set(false)
            storageHandles.forEach { it.close() }
            exitRequested = true
            closing = true
            scope.launch {
                try {
                    if (engine.close()) {
                        engine.closeLocalLockResumeHostForExit()
                        exitApplication()
                    }
                } finally {
                    closing = false
                }
            }
        }
    }
    val titleConnection = remember(engine) { engine.state.map { it.status.connection }.distinctUntilChanged() }
    // This application-level composition has no window LifecycleOwner yet.
    val titleUnread by remember(engine) { engine.state.map { it.deviceNotifications.accountBadge() }.distinctUntilChanged() }
        .collectAsState(initial = null)
    DesktopWindow("nngGram · 聊天与媒体" + (titleUnread?.takeIf { it > 0 }?.let { " ($it)" } ?: ""), requestExit,
        connection = titleConnection, initialConnection = engine.state.value.status.connection) { windowFocused, windowActivity ->
            val actualNativeOwner = LocalNativeDialogOwner.current ?: 0L
            DisposableEffect(actualNativeOwner) {
                notificationWindowHandle.set(actualNativeOwner)
                engine.notificationWindowAvailable()
                onDispose { notificationWindowHandle.compareAndSet(actualNativeOwner, 0L) }
            }
            DisposableEffect(engine, windowActivity) {
                val observer = observeWindowInactivity(windowActivity, {
                    engine.visibleMessages(engine.state.value.chats.token, emptyList(), false)
                })
                onDispose { observer.close() }
            }
            val engineRuntime by engine.state.collectAsStateWithLifecycle()
            val localLock by engine.localLockState.collectAsStateWithLifecycle()
            val runtime = engineRuntime.copy(localLock = localLock)
            val profileEmojiArtwork by engine.profileEmojiArtwork.collectAsStateWithLifecycle()
            val profileStickerAvatarArtwork = rememberProfileStickerAvatarArtwork(engine)
            LaunchedEffect(engine, windowFocused) { if (windowFocused) engine.recheckLocalLockIdle() }
            App(RuntimeInfo(
                platform = "${System.getProperty("os.name")} / ${System.getProperty("os.arch")}",
                javaVersion = System.getProperty("java.version"),
            ), runtime, engine::start, onOpenConfig = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            configuration.ensureTemplate()
                            ProcessBuilder("notepad.exe", configuration.path.toString()).start()
                        }
                    }
                    configurationOpenError = if (result.isFailure) "无法打开编辑器，请手动打开上方路径中的配置文件。" else null
                }
            }, configurationOpenError = configurationOpenError,
                onStop = { if (!exitRequested) scope.launch { engine.stop() } },
                onRestart = { if (!exitRequested) scope.launch { engine.restart() } },
                exitRequested = exitRequested,
                onContinueExit = requestExit,
                onExit = requestExit,
                onCancelQr = { token -> if (!exitRequested) engine.cancelQrLogin(token) },
                onSubmitLogin = { token, kind, value ->
                    if (!exitRequested) engine.submitLogin(token, kind, value)
                },
                onSelectChat = { token, chatId -> if (!exitRequested) engine.selectChat(token, chatId) },
                onLoadChats = { token -> if (!exitRequested) engine.loadChats(token) },
                onLoadOlder = { token -> if (!exitRequested) engine.loadOlderMessages(token) },
                onSendText = { token, text, revision -> if (!exitRequested) engine.sendText(token, text, revision) },
                onRetryMessage = { token, messageId -> if (!exitRequested) engine.retryMessage(token, messageId) },
                onMarkRead = { token -> if (!exitRequested) engine.markChatRead(token) },
                windowFocused = windowFocused && !exitRequested,
                profileEmojiArtwork = profileEmojiArtwork,
                profileStickerAvatarArtwork = profileStickerAvatarArtwork,
                onAppActivity = engine::recordLocalLockAppActivity,
                settingsActions = SettingsActions(
                    localLock = engine.localLockActions,
                    loginEmail = com.badnng.moe.settings.LoginEmailActions(
                        open = { generation, openingId, kind, pageLease -> if (!exitRequested) engine.openSettingsLoginEmail(generation, openingId, kind, pageLease) },
                        close = { token -> engine.closeSettingsLoginEmail(token) },
                        submitEmail = { token, email, result ->
                            if (!exitRequested) engine.submitSettingsLoginEmail(token, email, result)
                            else result(com.badnng.moe.settings.LoginEmailOutcome(issue = com.badnng.moe.settings.LoginEmailIssue.NOT_AVAILABLE))
                        },
                        resend = { challenge, result ->
                            if (!exitRequested) engine.resendSettingsLoginEmail(challenge, result)
                            else result(com.badnng.moe.settings.LoginEmailOutcome(issue = com.badnng.moe.settings.LoginEmailIssue.NOT_AVAILABLE))
                        },
                        check = { challenge, code, result ->
                            if (!exitRequested) engine.checkSettingsLoginEmail(challenge, code, result)
                            else result(com.badnng.moe.settings.LoginEmailOutcome(issue = com.badnng.moe.settings.LoginEmailIssue.NOT_AVAILABLE))
                        },
                        refreshProof = { token -> if (!exitRequested) engine.refreshSettingsLoginEmailProof(token) },
                        hideOptional = { generation, epoch -> if (!exitRequested) engine.hideOptionalSettingsLoginEmail(generation, epoch) },
                    ),
                    privacyCommon = com.badnng.moe.settings.PrivacyCommonActions(
                        open = { token, pageLease -> if (!exitRequested) engine.openSettingsPrivacyCommon(token, pageLease) },
                        close = { token -> engine.closeSettingsPrivacyCommon(token) },
                        refresh = { token -> if (!exitRequested) engine.refreshSettingsPrivacyCommon(token) },
                        setTopPeers = { token, expected, desired -> if (!exitRequested) engine.saveSettingsTopPeers(token, expected, desired) },
                        setArchive = { token, expected, desired -> if (!exitRequested) engine.saveSettingsArchivePrivacy(token, expected, desired) },
                        setAccountTtl = { token, expected, desired -> if (!exitRequested) engine.saveSettingsAccountTtl(token, expected, desired) },
                    ),
                    profileExtras = SettingsProfileExtrasActions(
                        currentPhoto = com.badnng.moe.settings.SettingsCurrentProfilePhotoActions(
                            open = { token, active, admitted ->
                                if (!exitRequested) engine.openSettingsCurrentProfilePhoto(token, active, admitted)
                                else admitted(null)
                            },
                            close = { token, epoch -> engine.closeSettingsCurrentProfilePhoto(token, epoch) },
                            refresh = { token, epoch, admitted ->
                                if (!exitRequested) engine.refreshSettingsCurrentProfilePhoto(token, epoch, admitted)
                                else admitted(null)
                            },
                            acquireFile = { request, active, completed ->
                                if (!exitRequested) engine.acquireSettingsCurrentProfilePhotoFile(request, active, completed)
                                else {
                                    completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                                    object : com.badnng.moe.settings.SettingsCurrentProfilePhotoFileLease { override fun close() {} }
                                }
                            },
                        ),
                        stickerAvatar = com.badnng.moe.settings.SettingsProfileStickerAvatarActions(
                            open = { token, active, completed ->
                                if (!exitRequested) engine.openSettingsProfileStickerAvatar(token, active, completed)
                                else completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                            },
                            close = { token, picker -> engine.closeSettingsProfileStickerAvatar(token, picker) },
                            loadDefault = { token, picker, active, completed ->
                                if (!exitRequested) engine.loadSettingsProfileStickerAvatarDefault(token, picker, active, completed)
                                else completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                            },
                            loadInstalledPacks = { token, picker, kind, active, completed ->
                                if (!exitRequested) engine.loadSettingsProfileStickerAvatarPacks(token, picker, kind, active, completed)
                                else completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                            },
                            openPack = { token, picker, pack, active, completed ->
                                if (!exitRequested) engine.openSettingsProfileStickerAvatarPack(token, picker, pack, active, completed)
                                else completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                            },
                            loadWindow = { token, picker, cursor, active, completed ->
                                if (!exitRequested) engine.loadSettingsProfileStickerAvatarWindow(token, picker, cursor, active, completed)
                                else completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                            },
                            searchInstalled = { token, picker, kind, query, active, completed ->
                                if (!exitRequested) engine.searchSettingsProfileStickerAvatarInstalled(token, picker, kind, query, active, completed)
                                else completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                            },
                            select = { token, picker, candidate, active, completed ->
                                if (!exitRequested) engine.selectSettingsProfileStickerAvatar(token, picker, candidate, active, completed)
                                else completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                            },
                            save = { token, picker, selection, expected, background, active, admitted, completed ->
                                if (!exitRequested) engine.saveSettingsProfileStickerAvatar(token, picker, selection,
                                    expected, background, active, admitted, completed)
                                else admitted(null)
                            },
                        ),
                        open = { token, userId, pageLease -> if (!exitRequested) engine.openSettingsProfileExtras(token, userId, pageLease) },
                        close = { token -> engine.closeSettingsProfileExtras(token) },
                        refresh = { token -> if (!exitRequested) engine.refreshSettingsProfileExtras(token) },
                        saveBirthdate = { token, expected, desired -> if (!exitRequested) engine.saveSettingsProfileBirthdate(token, expected, desired) },
                        uploadPhoto = { token, expected, photo -> if (!exitRequested) engine.uploadSettingsProfilePhoto(token, expected, photo) },
                        uploadAnimation = { token, expected, animation, admitted ->
                            if (!exitRequested) engine.uploadSettingsProfileAnimation(token, expected, animation, admitted) else admitted(null)
                        },
                        removePhoto = { token, id -> if (!exitRequested) engine.removeSettingsProfilePhoto(token, id) },
                        openPersonalChannels = { token, selectorLease -> if (!exitRequested) engine.openSettingsProfilePersonalChannels(token, selectorLease) },
                        closePersonalChannels = { token -> engine.closeSettingsProfilePersonalChannels(token) },
                        morePersonalChannels = { token -> if (!exitRequested) engine.moreSettingsProfilePersonalChannels(token) },
                        savePersonalChannel = { token, expected, desired -> if (!exitRequested) engine.saveSettingsProfilePersonalChannel(token, expected, desired) },
                        history = com.badnng.moe.settings.SettingsProfilePhotoHistoryActions(
                            open = { token, selectorLease -> if (!exitRequested) engine.openSettingsProfilePhotoHistory(token, selectorLease) },
                            close = { engine.closeSettingsProfilePhotoHistory(it) },
                            refresh = { if (!exitRequested) engine.refreshSettingsProfilePhotoHistory(it) },
                            more = { if (!exitRequested) engine.moreSettingsProfilePhotoHistory(it) },
                            reuse = { token, epoch, target, selectionLease ->
                                if (!exitRequested) engine.reuseSettingsProfileHistoryPhoto(token, epoch, target, selectionLease)
                            },
                            remove = { token, epoch, target, selectionLease ->
                                if (!exitRequested) engine.removeSettingsProfileHistoryPhoto(token, epoch, target, selectionLease)
                            },
                            acquireFile = { request, active, completed ->
                                if (!exitRequested) engine.acquireSettingsProfileHistoryFile(request, active, completed)
                                else {
                                    completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                                    object : com.badnng.moe.settings.SettingsProfilePhotoHistoryFileLease { override fun close() {} }
                                }
                            },
                        ),
                    ),
                    storage = com.badnng.moe.settings.SettingsStorageActions(
                        open = { active -> if (!exitRequested) engine.openSettingsStorage(active) },
                        close = { token -> engine.closeSettingsStorage(token) },
                        refresh = { token -> if (!exitRequested) engine.refreshSettingsStorage(token) },
                        refreshVolume = { token -> if (!exitRequested) engine.refreshSettingsStorageVolume(token) },
                        clear = { token, revision, types -> if (!exitRequested) engine.clearSettingsStorage(token, revision, types) },
                        openOwnedObserved = { active, status ->
                            if (exitRequested || !storageHostActive.get()) null
                            else engine.openSettingsStorageOwnedObserved({ storageHostActive.get() && active() }, status)?.let { native ->
                                val handle = object : com.badnng.moe.settings.SettingsStorageHandle {
                                    override fun current(): Boolean = storageHostActive.get() && native.current()
                                    override fun close() {
                                        native.close()
                                        storageHandles.remove(this)
                                    }
                                }
                                storageHandles.add(handle)
                                if (!storageHostActive.get()) handle.close()
                                handle
                            }
                        },
                    ),
                    storagePolicy = com.badnng.moe.settings.SettingsStoragePolicyActions(
                        open = { generation, pageLease -> if (!exitRequested) engine.openSettingsStoragePolicy(generation, pageLease) },
                        close = { token -> engine.closeSettingsStoragePolicy(token) },
                        refresh = { token -> if (!exitRequested) engine.refreshSettingsStoragePolicy(token) },
                        save = { token, revision, patch, requestId, completed ->
                            if (!exitRequested) engine.saveSettingsStoragePolicy(token, revision, patch, requestId, completed)
                            else completed(com.badnng.moe.settings.SettingsStoragePolicyCompletion.rejected(token, requestId))
                        },
                    ),
                    support = com.badnng.moe.settings.SettingsSupportActions(open = { token, pageLease, completed ->
                        if (!exitRequested) engine.openSettingsSupport(token, pageLease, completed)
                        else {
                            completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                            false
                        }
                    }),
                    contactSignup = com.badnng.moe.settings.ContactSignupActions(
                        open = { token, pageLease -> if (!exitRequested) engine.openSettingsContactSignup(token, pageLease) },
                        close = { token -> engine.closeSettingsContactSignup(token) },
                        refresh = { token -> if (!exitRequested) engine.refreshSettingsContactSignup(token) },
                        save = { token, revision, expected, desired ->
                            if (!exitRequested) engine.saveSettingsContactSignup(token, revision, expected, desired)
                        },
                    ),
                    reactionNotifications = com.badnng.moe.settings.ReactionNotificationsActions(
                        open = { token, pageLease -> if (!exitRequested) engine.openSettingsReactionNotifications(token, pageLease) },
                        close = { engine.closeSettingsReactionNotifications(it) },
                        refreshFromCache = { if (!exitRequested) engine.refreshSettingsReactionNotificationsFromCache(it) },
                        save = { token, edit -> if (!exitRequested) engine.saveSettingsReactionNotifications(token, edit) },
                    ),
                    profileColors = com.badnng.moe.settings.SettingsProfileColorsActions(
                        open = { token, userId, pageLease -> if (!exitRequested) engine.openSettingsProfileColors(token, userId, pageLease) },
                        close = { engine.closeSettingsProfileColors(it) },
                        refresh = { if (!exitRequested) engine.refreshSettingsProfileColors(it) },
                        save = { token, edit -> if (!exitRequested) engine.saveSettingsProfileColors(token, edit) },
                        openBackgrounds = { if (!exitRequested) engine.openSettingsProfileColorBackgrounds(it) },
                        resolveBackgrounds = { token, ids -> if (!exitRequested) engine.resolveSettingsProfileColorBackgrounds(token, ids) },
                        closeBackgrounds = { engine.closeSettingsProfileColorBackgrounds(it) },
                    ),
                    savedNotificationSounds = com.badnng.moe.settings.SavedNotificationSoundsActions(
                        open = { token, pageLease -> if (!exitRequested) engine.openSettingsSavedNotificationSounds(token, pageLease) },
                        close = { engine.closeSettingsSavedNotificationSounds(it) },
                        refresh = { if (!exitRequested) engine.refreshSettingsSavedNotificationSounds(it) },
                        choose = { token, revision, soundId, completed ->
                            if (!exitRequested) engine.chooseSettingsSavedNotificationSound(token, revision, soundId, completed)
                            else completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                        },
                        preview = { token, soundId, volume -> if (!exitRequested) engine.previewSettingsSavedNotificationSound(token, soundId, volume) },
                        stopPreview = { engine.stopSettingsSavedNotificationSoundPreview(it) },
                        upload = { token, revision, prepared -> if (!exitRequested) engine.uploadSettingsSavedNotificationSound(token, revision, prepared) },
                        remove = { token, revision, sound -> if (!exitRequested) engine.removeSettingsSavedNotificationSound(token, revision, sound) },
                        readVolume = { token, target, completed ->
                            if (!exitRequested) engine.readSettingsNotificationSoundVolume(token, target, completed)
                            else completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                        },
                        saveVolume = { token, expected, percent, completed ->
                            if (!exitRequested) engine.saveSettingsNotificationSoundVolume(token, expected, percent, completed)
                            else completed(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                        },
                    ),
                    notificationExceptions = com.badnng.moe.settings.NotificationExceptionsActions(
                        open = { token, scope, pageLease -> if (!exitRequested) engine.openSettingsNotificationExceptions(token, scope, pageLease) },
                        close = { engine.closeSettingsNotificationExceptions(it) },
                        refresh = { if (!exitRequested) engine.refreshSettingsNotificationExceptions(it) },
                        more = { if (!exitRequested) engine.moreSettingsNotificationExceptions(it) },
                        select = { if (!exitRequested) engine.selectSettingsNotificationException(it) },
                        search = { token, query, remote -> if (!exitRequested) engine.searchSettingsNotificationExceptions(token, query, remote) },
                        edit = { token, chatId -> if (!exitRequested) engine.editSettingsNotificationException(token, chatId) },
                        back = { if (!exitRequested) engine.backSettingsNotificationExceptions(it) },
                        reloadEditor = { if (!exitRequested) engine.reloadSettingsNotificationException(it) },
                        save = { token, revision, edit -> if (!exitRequested) engine.saveSettingsNotificationException(token, revision, edit) },
                        prepareClear = { if (!exitRequested) engine.prepareClearSettingsNotificationExceptions(it) },
                        cancelClear = { if (!exitRequested) engine.cancelClearSettingsNotificationExceptions(it) },
                        clear = { token, planId -> if (!exitRequested) engine.clearSettingsNotificationExceptions(token, planId) },
                    ),
                    deviceNotifications = DeviceNotificationActions(
                        save = { generation, value -> if (!exitRequested) engine.saveDeviceNotifications(generation, value) },
                        activate = { identity -> if (!exitRequested) engine.activateDeviceNotification(identity) },
                        dismiss = { identity -> if (!exitRequested) engine.dismissDeviceNotification(identity) },
                        expire = { identity -> if (!exitRequested) engine.expireDeviceNotification(identity) },
                        context = { generation, foreground, chatId, locked ->
                            engine.deviceNotificationContext(generation, foreground && !exitRequested, chatId, locked)
                        },
                        windowsNotifications = { generation, enabled -> if (!exitRequested) engine.selectWindowsNotifications(generation, enabled) },
                        removeWindowsRegistration = { generation -> if (!exitRequested) engine.removeWindowsNotificationRegistration(generation) },
                    ),
                    load = { token, section -> if (!exitRequested) engine.loadSettings(token, section) },
                    saveProfile = { token, edit -> if (!exitRequested) engine.saveSettingsProfile(token, edit) },
                    checkUsername = { token, userId, username, active, result ->
                        if (!exitRequested) engine.checkSettingsUsernameAvailability(token, userId, username, active, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                    saveNotifications = { token, notificationScope, value ->
                        if (!exitRequested) engine.saveSettingsNotifications(token, notificationScope, value)
                    },
                    savePrivacy = { token, key, base -> if (!exitRequested) engine.saveSettingsPrivacy(token, key, base) },
                    openPrivacyEditor = { token, key, requestId, result ->
                        if (!exitRequested) engine.openSettingsPrivacyEditor(token, key, requestId, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                    cancelPrivacyEditorOpening = { token, requestId -> if (!exitRequested) engine.cancelSettingsPrivacyEditorOpening(token, requestId) },
                    closePrivacyEditor = { token, editor -> if (!exitRequested) engine.closeSettingsPrivacyEditor(token, editor) },
                    privacyPeers = { token, editor, query, result ->
                        if (!exitRequested) engine.settingsPrivacyPeers(token, editor, query, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                    savePrivacyDraft = { token, editor, draft -> if (!exitRequested) engine.saveSettingsPrivacyDraft(token, editor, draft) },
                    saveAccountTtl = { token, days -> if (!exitRequested) engine.saveSettingsAccountTtl(token, days) },
                    terminateSession = { token, id -> if (!exitRequested) engine.terminateSettingsSession(token, id) },
                    unblock = { token, sender -> if (!exitRequested) engine.unblockSettingsSender(token, sender) },
                    optimizeStorage = { token -> if (!exitRequested) engine.optimizeSettingsStorage(token) },
                    addProxy = { token, draft -> if (!exitRequested) engine.addSettingsProxy(token, draft) },
                    enableProxy = { token, id -> if (!exitRequested) engine.enableSettingsProxy(token, id) },
                    removeProxy = { token, id -> if (!exitRequested) engine.removeSettingsProxy(token, id) },
                    proxyChecks = com.badnng.moe.settings.SettingsProxyChecksActions(open = { token, active, opened ->
                        if (!exitRequested && proxyChecksHostActive.get()) {
                            val nativeLoad = engine.openSettingsProxyChecks(token,
                                { proxyChecksHostActive.get() && active() }, opened)
                            val load = object : com.badnng.moe.settings.SettingsProxyChecksLoad {
                                override fun refresh() {
                                    if (proxyChecksHostActive.get()) nativeLoad.refresh()
                                }
                                override fun share(request: com.badnng.moe.settings.SettingsProxyShareRequest,
                                    result: (com.badnng.moe.settings.SettingsResult<com.badnng.moe.settings.SettingsProxyShareContent>) -> Unit,
                                ) {
                                    if (proxyChecksHostActive.get()) nativeLoad.share(request, result)
                                    else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                                }
                                override fun close() {
                                    nativeLoad.close()
                                    proxyChecksLoads.remove(this)
                                }
                            }
                            proxyChecksLoads.add(load)
                            if (!proxyChecksHostActive.get()) load.close()
                            load
                        }
                        else {
                            opened(null)
                            com.badnng.moe.settings.SettingsProxyChecksLoad { }
                        }
                    }),
                    openSecurity = { token, section -> if (!exitRequested) engine.openSettingsSecurity(token, section) },
                    closeSecurity = { token -> engine.closeSettingsSecurity(token) },
                    refreshSecurity = { token -> if (!exitRequested) engine.refreshSettingsSecurity(token) },
                    captureDeviceNameSecurityOwner = { token, target ->
                        engine.captureDeviceNameSecurityOwner(token, target).let { nativeOwner -> { !exitRequested && nativeOwner() } }
                    },
                    password = { token, command, result ->
                        if (!exitRequested) engine.settingsPassword(token, command, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                    disconnectWebsite = { token, target, blockBot, result ->
                        if (!exitRequested) engine.disconnectSettingsWebsite(token, target, blockBot, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                    disconnectAllWebsites = { token, result ->
                        if (!exitRequested) engine.disconnectAllSettingsWebsites(token, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                    openBlockedPicker = { token -> if (!exitRequested) engine.openSettingsBlockedPicker(token) },
                    closeBlockedPicker = { token -> if (!exitRequested) engine.closeSettingsBlockedPicker(token) },
                    searchBlockedPicker = { token, query -> if (!exitRequested) engine.searchSettingsBlockedPicker(token, query) },
                    blockPickerUser = { token, userId, result ->
                        if (!exitRequested) engine.blockSettingsPickerUser(token, userId, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                    loadMoreBlocked = { token -> if (!exitRequested) engine.loadMoreSettingsBlocked(token) },
                    unblockSecurity = { token, sender, result ->
                        if (!exitRequested) engine.unblockSettingsSecuritySender(token, sender, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                    terminateSecuritySession = { token, id, result ->
                        if (!exitRequested) engine.terminateSettingsSecuritySession(token, id, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                    terminateOtherSecuritySessions = { token, expected, result ->
                        if (!exitRequested) engine.terminateOtherSettingsSecuritySessions(token, expected, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                    saveInactiveSessionTtl = { token, expected, desired, result ->
                        if (!exitRequested) engine.saveSettingsInactiveSessionTtl(token, expected, desired, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                    saveDefaultMessageAutoDeleteTime = { token, expected, desired, result ->
                        if (!exitRequested) engine.saveSettingsDefaultMessageAutoDeleteTime(token, expected, desired, result)
                        else result(com.badnng.moe.settings.SettingsResult(error = com.badnng.moe.settings.SettingsError.NOT_READY))
                    },
                ),
                features = ChatFeatureActions(
                    folders = com.badnng.moe.folders.FolderSettingsActions(
                        open = { token, pageId, active -> if (!exitRequested) engine.openFolderSettings(token, pageId, active) },
                        close = { token -> if (!exitRequested) engine.closeFolderSettings(token) },
                        refresh = { token -> if (!exitRequested) engine.refreshFolderSettings(token) },
                        stageRecommendation = { token, key -> if (!exitRequested) engine.stageFolderRecommendation(token, key) },
                        move = { token, entry, delta -> if (!exitRequested) engine.moveFolder(token, entry, delta) },
                        remove = { token, id -> if (!exitRequested) engine.stageFolderRemoval(token, id) },
                        restore = { token, id -> if (!exitRequested) engine.restoreFolder(token, id) },
                        reset = { token -> if (!exitRequested) engine.resetFolderSettings(token) },
                        apply = { token, revision -> if (!exitRequested) engine.applyFolderSettings(token, revision) },
                        toggleTags = { token, enabled -> if (!exitRequested) engine.toggleFolderTags(token, enabled) },
                        color = { token, folder, colorId -> if (!exitRequested) engine.colorFolder(token, folder, colorId) },
                    ),
                    chatListAction = { token, chatId, list, action, result ->
                        if (!exitRequested) engine.chatListAction(token, chatId, list, action, result)
                        else result(ChatResult(error = ChatError.NOT_READY))
                    },
                    addChatToFolder = { token, chatId, folderId, result ->
                        if (!exitRequested) engine.addChatToFolder(token, chatId, folderId, result)
                        else result(ChatResult(error = ChatError.NOT_READY))
                    },
                    requestMessageLink = { token, messageId, result ->
                        if (!exitRequested) engine.requestMessageLink(token, messageId, result)
                        else result(ChatResult(error = ChatError.NOT_READY))
                    },
                    selectList = { token, list -> if (!exitRequested) engine.selectChatList(token, list) },
                    automaticMedia = { token, messageId, active -> if (!active || !exitRequested) engine.automaticMedia(token, messageId, active) },
                    releaseVideoPart = { token, messageId, fileId -> engine.releaseVideoPart(token, messageId, fileId) },
                    readOwnedVideoPart = { owner, fileId, offset, count, result ->
                        if (!exitRequested) engine.readOwnedVideoPart(owner, fileId, offset, count, result)
                        else result(ChatResult(error = ChatError.NOT_READY))
                    },
                    openOwnedVideoPart = { owner, fileId -> if (!exitRequested) engine.openOwnedVideoPart(owner, fileId) },
                    releaseOwnedVideoPart = { owner, fileId -> engine.releaseOwnedVideoPart(owner, fileId) },
                    acquireOwnedMedia = { owner, fileId -> if (!exitRequested) engine.acquireOwnedMedia(owner, fileId) },
                    releaseOwnedMedia = { owner, fileId -> engine.releaseOwnedMedia(owner, fileId) },
                    saveAutoMedia = { token, expected, policy, admitted, completed ->
                        if (!exitRequested) engine.saveAutoMedia(token, expected, policy, admitted, completed) else admitted(false)
                    },
                    search = { token, query, more -> if (!exitRequested) engine.searchChats(token, query, more) },
                    searchOverview = { token -> if (!exitRequested) engine.searchOverview(token) },
                    clearRecentSearch = { token -> if (!exitRequested) engine.clearRecentSearch(token) },
                    closeSearch = { token -> if (!exitRequested) engine.closeSearch(token) },
                    selectSearchChat = { token, chatId -> if (!exitRequested) engine.selectSearchChat(token, chatId) },
                    openSearchMessage = { token, chatId, messageId -> if (!exitRequested) engine.openSearchMessage(token, chatId, messageId) },
                    loadChatDetails = { token -> if (!exitRequested) engine.loadChatDetails(token) },
                    loadMediaIndex = { token, category, more -> if (!exitRequested) engine.loadMediaIndex(token, category, more) },
                    openMediaIndexMessage = { token, messageId -> if (!exitRequested) engine.openMediaIndexMessage(token, messageId) },
                    loadPinned = { token, more -> if (!exitRequested) engine.loadPinned(token, more) },
                    openPinnedMessage = { token, messageId -> if (!exitRequested) engine.openPinnedMessage(token, messageId) },
                    returnToLatest = { token -> if (!exitRequested) engine.returnToLatest(token) },
                    cancelSendLatestNavigation = { token -> if (!exitRequested) engine.cancelSendLatestNavigation(token) },
                    completeHistoryJump = { token, revision -> if (!exitRequested) engine.completeHistoryJump(token, revision) },
                    openHistoryMessage = { token, messageId -> if (!exitRequested) engine.openHistoryMessage(token, messageId) },
                    rememberHistoryAnchor = { token, messageId -> if (!exitRequested) engine.rememberHistoryAnchor(token, messageId) },
                    loadHistoryAnchor = { token, messageId -> if (!exitRequested) engine.loadHistoryAnchor(token, messageId) },
                    openReplyMessage = { token, sourceMessageId -> if (!exitRequested) engine.openReplyMessage(token, sourceMessageId) },
                    openReplyReturn = { token, sourceChatId, anchorMessageId -> if (!exitRequested) engine.openReplyReturn(token, sourceChatId, anchorMessageId) },
                    requestPreviousEditable = { token, before, completed ->
                        if (!exitRequested) engine.requestPreviousEditable(token, before, completed)
                        else completed(ChatResult(error = ChatError.NOT_READY))
                    },
                    loadNewer = { token -> if (!exitRequested) engine.loadNewerMessages(token) },
                    visibleMessages = { token, ids, active ->
                        if (!exitRequested) {
                            val reading = active && windowActivity.value
                            engine.visibleMessages(token, if (reading) ids else emptyList(), reading)
                        }
                    },
                    markUnread = { token -> if (!exitRequested) engine.markChatUnread(token) },
                    reportSpamAndLeave = { token -> if (!exitRequested) engine.reportSpamAndLeave(token) },
                    dismissSpamBar = { token -> if (!exitRequested) engine.dismissSpamBar(token) },
                    downloadFile = { token, id -> if (!exitRequested) engine.downloadChatFile(token, id) },
                    loadStickerOutline = { token, id -> if (!exitRequested) engine.loadStickerOutline(token, id) },
                    loadStickers = { token, setId -> if (!exitRequested) engine.loadStickers(token, setId) },
                    ensureStickerSection = { token, setId -> if (!exitRequested) engine.ensureStickerSection(token, setId) },
                    sendSticker = { token, sticker -> if (!exitRequested) engine.sendSticker(token, sticker) },
                    sendPhoto = { token, path, caption -> if (!exitRequested) engine.sendPhoto(token, path, caption) },
                    sendAttachment = { token, attachment, caption -> if (!exitRequested) engine.sendAttachment(token, attachment, caption) },
                    sendOwnedPhoto = { token, path, caption, lease -> if (!exitRequested) engine.sendOwnedPhoto(token, path, caption, lease) },
                    sendVoiceNote = { token, draft -> if (!exitRequested) engine.sendVoiceNote(token, draft) },
                    submitClipboardPhoto = { token, path, caption, lease, completed ->
                        if (!exitRequested) engine.submitClipboardPhoto(token, path, caption, lease, completed)
                        else completed(com.badnng.moe.chat.ChatResult(error = com.badnng.moe.chat.ChatError.NOT_READY))
                    },
                    submitClipboardFile = { token, attachment, caption, completed ->
                        if (!exitRequested) engine.submitClipboardFile(token, attachment, caption, completed)
                        else completed(com.badnng.moe.chat.ChatResult(error = com.badnng.moe.chat.ChatError.NOT_READY))
                    },
                    setReply = { token, messageId -> if (!exitRequested) engine.setReply(token, messageId) },
                    setQuote = { token, messageId, quote -> if (!exitRequested) engine.setQuote(token, messageId, quote) },
                    loadReactions = { token, messageId -> if (!exitRequested) engine.loadReactions(token, messageId) },
                    closeReactions = { token -> if (!exitRequested) engine.closeReactions(token) },
                    setReaction = { token, messageId, emoji, add -> if (!exitRequested) engine.setReaction(token, messageId, emoji, add) },
                    setCustomReaction = { token, messageId, emojiId, add -> if (!exitRequested) engine.setCustomReaction(token, messageId, emojiId, add) },
                    loadReactionVisual = { token, key -> if (!exitRequested) engine.loadReactionVisual(token, key) },
                    loadReactionPacks = { token, setId -> if (!exitRequested) engine.loadReactionPacks(token, setId) },
                    loadReactionGroupPack = { token, sourceChatId -> if (!exitRequested) engine.loadReactionGroupPack(token, sourceChatId) },
                    readVideoPart = { token, messageId, fileId, offset, count, result -> engine.readVideoPart(token, messageId, fileId, offset, count, result) },
                    loadReadDetails = { token, messageId -> if (!exitRequested) engine.loadReadDetails(token, messageId) },
                    closeReadDetails = { token -> if (!exitRequested) engine.closeReadDetails(token) },
                    loadMessageActions = { token, messageId -> if (!exitRequested) engine.loadMessageActions(token, messageId) },
                    closeMessageActions = { token -> if (!exitRequested) engine.closeMessageActions(token) },
                    startEdit = { token, messageId -> if (!exitRequested) engine.startEdit(token, messageId) },
                    cancelEdit = { token -> if (!exitRequested) engine.cancelEdit(token) },
                    editMessage = { token, text -> if (!exitRequested) engine.editMessage(token, text) },
                    deleteMessage = { token, messageId, revoke -> if (!exitRequested) engine.deleteMessage(token, messageId, revoke) },
                    forwardMessage = { token, messageId, targetChatId -> if (!exitRequested) engine.forwardMessage(token, messageId, targetChatId) },
                    editFolder = { token, id -> if (!exitRequested) engine.editChatFolder(token, id) },
                    closeFolderEditor = { token -> if (!exitRequested) engine.closeChatFolderEditor(token) },
                    saveFolder = { token, folder -> if (!exitRequested) engine.saveChatFolder(token, folder) },
                    deleteFolder = { token, id -> if (!exitRequested) engine.deleteChatFolder(token, id) },
                ),
            )
    }
}

/** A single native Tao window owns focus, clipboard and native picker parenting. */
@Suppress("DEPRECATION")
@Composable
private fun NucleusApplicationScope.DesktopWindow(
    title: String,
    onClose: () -> Unit,
    initialSize: DpSize? = null,
    connection: Flow<ConnectionPhase>? = null,
    initialConnection: ConnectionPhase? = null,
    forceDark: Boolean? = null,
    content: @Composable (active: Boolean, activity: StateFlow<Boolean>) -> Unit,
) {
    val workArea = remember {
        val scale = TaoScreenGeometry.primaryMonitorScaleFactor().takeIf { it.isFinite() && it > 0 } ?: 1f
        val pixels = TaoScreenGeometry.primaryMonitorWorkAreaPx()
        if (pixels != null && pixels.size >= 4 && pixels[2] > 0 && pixels[3] > 0) {
            WorkArea((pixels[0] / scale).roundToInt(), (pixels[1] / scale).roundToInt(),
                (pixels[2] / scale).roundToInt().coerceAtLeast(1), (pixels[3] / scale).roundToInt().coerceAtLeast(1))
        } else WorkArea(0, 0, 1160, 840)
    }
    val geometry = remember(workArea) { initialWindowGeometry(workArea) }
    val initialWidth = initialSize?.width?.coerceIn(geometry.minimumWidth.dp, workArea.width.dp) ?: geometry.width.dp
    val initialHeight = initialSize?.height?.coerceIn(geometry.minimumHeight.dp, workArea.height.dp) ?: geometry.height.dp
    val state = rememberWindowState(
        width = initialWidth,
        height = initialHeight,
        position = if (initialSize == null) WindowPosition.Absolute(geometry.x.dp, geometry.y.dp)
        else WindowPosition.Absolute(
            (workArea.x + (workArea.width - initialWidth.value.roundToInt()) / 2).dp,
            (workArea.y + (workArea.height - initialHeight.value.roundToInt()) / 2).dp,
        ),
    )
    var appDark by remember(forceDark) { mutableStateOf<Boolean?>(null) }
    // Until App reports its effective value, retain existing preview/host behavior.
    val titleDark = appDark ?: forceDark ?: isSystemInDarkTheme()
    val themeController = remember(titleDark) { ThemeController(if (titleDark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
    val onAppTheme: (Boolean) -> Unit = remember(forceDark) { { appDark = it } }
    MiuixTheme(controller = themeController) {
        NucleusDecoratedWindowTheme(isDark = titleDark) {
            DecoratedWindow(
                onCloseRequest = onClose, title = title, state = state,
                icon = remember { androidx.compose.ui.graphics.painter.BitmapPainter(DesktopBranding.windowIcon) },
                minimumSize = DpSize(geometry.minimumWidth.dp, geometry.minimumHeight.dp),
            ) {
                val activity = remember(nucleusWindow) {
                    WindowActivityGate(nucleusWindow.focusFlow, nucleusWindow.minimizedFlow)
                }
                DisposableEffect(activity) { onDispose { activity.close() } }
                val active by activity.active.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
                val nativeOwner = nucleusWindow.unsafe.taoWindow?.nativeHandle
                val windows = com.badnng.moe.platform.DesktopPlatform.current.isWindows
                val clipboard = remember(nativeOwner, windows) {
                    if (windows) WindowsClipboard(checkNotNull(nativeOwner)) else DesktopSystemClipboard()
                }
                val retryingClipboard = remember(clipboard) { RetryingClipboard(clipboard) }
                CompositionLocalProvider(
                    LocalClipboard provides retryingClipboard,
                    LocalClipboardManager provides clipboard.manager,
                    LocalAttachmentClipboard provides clipboard,
                    LocalNativeDialogOwner provides nativeOwner,
                    LocalWindowActivity provides activity.active,
                    LocalAppThemeObserver provides onAppTheme,
                    LocalTextInputSessionHost provides { editor -> WindowsImeTextInputHost(editor) },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        val titleBarStyle = LocalTitleBarStyle.current
                        TitleBar(modifier = Modifier.height(44.dp),
                            style = titleBarStyle.copy(metrics = titleBarStyle.metrics.copy(height = 44.dp)),
                            backgroundContent = {
                                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface))
                            }) {
                            val phase = if (connection != null) {
                                val current by connection.collectAsStateWithLifecycle(initialValue = initialConnection ?: ConnectionPhase.UNKNOWN)
                                current
                            } else initialConnection
                            // The default Nucleus policy adds Start and End widths. Each item
                            // must wrap its content or a full-width Start item overflows the bar.
                            DesktopTitleBrand(Modifier.align(Alignment.Start).padding(start = 18.dp))
                            phase?.let {
                                // Nucleus declares its End controls first, so this wraps directly
                                // to their left without guessing or duplicating the controls' width.
                                DesktopTitleConnection(it, Modifier.align(Alignment.End).padding(horizontal = 16.dp),
                                    showLabel = state.size.width >= 520.dp)
                            }
                        }
                        DesktopTitleDivider()
                        Box(Modifier.weight(1f).fillMaxWidth()) { content(active, activity.active) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DesktopTitleDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.65f)))
}

/** Uses application-owned connection state only; it never reads chat content. */
@Composable
internal fun DesktopTitleContent(connection: ConnectionPhase?, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    Row(modifier.fillMaxWidth().height(44.dp).padding(start = 18.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        DesktopTitleBrand()
        Spacer(Modifier.weight(1f))
        if (connection != null) DesktopTitleConnection(connection, showLabel = showLabel)
    }
}

@Composable
private fun DesktopTitleBrand(modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    Row(modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(23.dp)) {
            val plane = Path().apply {
                moveTo(size.width * 0.05f, size.height * 0.43f)
                lineTo(size.width * 0.96f, size.height * 0.08f)
                lineTo(size.width * 0.73f, size.height * 0.94f)
                lineTo(size.width * 0.48f, size.height * 0.63f)
                lineTo(size.width * 0.27f, size.height * 0.81f)
                lineTo(size.width * 0.31f, size.height * 0.50f)
                close()
            }
            drawPath(plane, colors.primary, style = Stroke(1.4.dp.toPx()))
        }
        Spacer(Modifier.width(10.dp))
        Text("nngGram", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1)
    }
}

@Composable
private fun DesktopTitleConnection(connection: ConnectionPhase, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    val colors = MiuixTheme.colorScheme
    val label = connection.presentation().label
    Row(modifier.height(44.dp).clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(6.dp)) {
            drawCircle(if (connection == ConnectionPhase.READY) Color(0xFF3BA56C) else colors.onSurfaceVariantActions)
        }
        if (showLabel) {
            Spacer(Modifier.width(9.dp))
            Text(label, fontSize = 12.sp, color = colors.onSurfaceVariantSummary, maxLines = 1)
        }
    }
}
