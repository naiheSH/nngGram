package com.badnng.moe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import com.badnng.moe.status.ConnectionPhase
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults

/** Optional offline observer of the actual callbacks passed to the send-key rows. */
internal class SettingsSendKeyCallbacks(val enter: () -> Unit, val ctrlEnter: () -> Unit)
internal val LocalSettingsSendKeyCallbacks = staticCompositionLocalOf<((SettingsSendKeyCallbacks) -> Unit)?> { null }

@Composable
internal fun SettingsChatPage(preferences: SettingsPreferences, onPreferences: (SettingsPreferences) -> Unit,
    onSaveFont: (SettingsChatFont) -> Boolean = { false },
    fontEditor: SettingsFontEditorSession = remember { SettingsFontEditorSession() },
    themeCoordinator: SettingsThemeCoordinator,
    themeOwner: () -> Boolean,
    onSaveTheme: (SettingsThemePreferences) -> Boolean,
) {
    val enter: () -> Unit = { onPreferences(preferences.copy(sendKey = SettingsSendKey.ENTER)) }
    val ctrlEnter: () -> Unit = { onPreferences(preferences.copy(sendKey = SettingsSendKey.CTRL_ENTER)) }
    val sendKeyObserver = LocalSettingsSendKeyCallbacks.current
    SideEffect { sendKeyObserver?.invoke(SettingsSendKeyCallbacks(enter, ctrlEnter)) }
    SettingsSystemThemeControls(themeCoordinator, themeOwner, onSaveTheme)
    SettingsFontControl(preferences, onSaveFont, enabled = LocalSettingsPageActive.current, session = fontEditor)
    SettingsAccentColorControls(preferences, onPreferences)
    SettingsGroup("聊天壁纸") {
        listOf("跟随主题" to SettingsWallpaper.AUTOMATIC, "白天" to SettingsWallpaper.DAY, "夜间" to SettingsWallpaper.NIGHT).forEach { (label, wallpaper) ->
            SettingsRow(label, if (preferences.wallpaper == wallpaper) "已选中" else null) { onPreferences(preferences.copy(wallpaper = wallpaper)) }
        }
    }
    SettingsLocalWallpaperControls(themeOwner)
    SettingsGroup("贴纸和表情符号") {
        SettingsToggle("循环播放动态贴纸", preferences.stickerLoop) { onPreferences(preferences.copy(stickerLoop = it)) }
    }
    SettingsGroup("消息") {
        SettingsRow("按 Enter 键发送消息", if (preferences.sendKey == SettingsSendKey.ENTER) "已选中" else null, onClick = enter)
        SettingsRow("按 Ctrl+Enter 键发送消息", if (preferences.sendKey == SettingsSendKey.CTRL_ENTER) "已选中" else null, onClick = ctrlEnter)
        SettingsRow("换行", summary = "Enter 发送时使用 Shift+Enter 换行；Ctrl+Enter 发送时可直接按 Enter 换行。")
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SettingsAdvancedPage(snapshot: SettingsSnapshot, actions: SettingsActions, onOpenConfig: () -> Unit,
    connection: ConnectionPhase = ConnectionPhase.UNKNOWN,
    proxyChecksOwner: SettingsProxyChecksPageOwner = remember { SettingsProxyChecksPageOwner() },
    storageOwner: SettingsStoragePageOwner = remember { SettingsStoragePageOwner() },
    proxyNavigationRevision: Long = 0L,
    automaticMediaContent: @Composable () -> Unit = {},
) {
    val pageActive = LocalSettingsPageActive.current
    val proxyChecksToken = rememberSettingsProxyChecksToken(snapshot, actions.proxyChecks, proxyChecksOwner,
        pageActive, proxyNavigationRevision)
    val proxyCheckRows = remember(snapshot.proxyChecks, snapshot.proxies, snapshot.token, proxyChecksToken) {
        settingsProxyCheckRows(snapshot, proxyChecksToken)
    }
    val loaded = SettingsSection.ADVANCED in snapshot.loaded
    val available = pageActive && snapshot.enabled && loaded && SettingsSection.ADVANCED !in snapshot.loading && SettingsSection.ADVANCED !in snapshot.saving
    // Capture the concrete visit that produced these callbacks. A retired button or
    // dialog cannot submit on behalf of a later visit with the same settings token.
    val proxyPage = proxyChecksOwner.current
    val ownsProxyPage = { proxyChecksOwner.owns(proxyPage) }
    val canWriteProxy = { available && ownsProxyPage() }
    var confirmRemoval by remember(snapshot.token.generation) { mutableStateOf<SettingsProxy?>(null) }
    var addingProxy by remember(snapshot.token.generation) { mutableStateOf(false) }
    var server by remember(snapshot.token.generation) { mutableStateOf(TextFieldValue()) }
    var port by remember(snapshot.token.generation) { mutableStateOf(TextFieldValue()) }
    var username by remember(snapshot.token.generation) { mutableStateOf(TextFieldValue()) }
    var password by remember(snapshot.token.generation) { mutableStateOf(TextFieldValue()) }
    var secret by remember(snapshot.token.generation) { mutableStateOf(TextFieldValue()) }
    var type by remember(snapshot.token.generation) { mutableStateOf(SettingsProxyType.SOCKS5) }
    var proxySaveBaseline by remember(snapshot.token.generation) { mutableStateOf<Long?>(null) }
    LaunchedEffect(snapshot.completedSaveRevision, snapshot.failedSaveRevision) {
        proxySaveBaseline?.let { baseline ->
            if ((snapshot.completedSaveRevision[SettingsSection.ADVANCED] ?: 0L) > baseline) {
                addingProxy = false
                username = TextFieldValue()
                password = TextFieldValue()
                secret = TextFieldValue()
                proxySaveBaseline = null
            } else if ((snapshot.failedSaveRevision[SettingsSection.ADVANCED] ?: 0L) > baseline) {
                proxySaveBaseline = null
            }
        }
    }
    SettingsProxySharingHost(snapshot, proxyPage, proxyChecksToken, available, ownsProxyPage) { sharing ->
        val shortcutFocus = remember(proxyPage) { FocusRequester() }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SettingsGroup("数据与存储") {
                snapshot.storage?.let { storage ->
                    SettingsRow("本地媒体缓存", formatSettingsBytes(storage.totalSize), "近似统计 · ${storage.totalCount} 个文件")
                } ?: SettingsRow("缓存管理", summary = "打开此分类时读取实际缓存统计。")
            }
            SettingsStorageControls(LocalSettingsStorageSnapshot.current, LocalSettingsStorageActions.current,
                storageOwner, proxyNavigationRevision)
            SettingsDownloadControls(snapshot.token.generation, LocalDownloadAccountDataPath.current)
            automaticMediaContent()
            Box(Modifier.fillMaxWidth().focusRequester(shortcutFocus)
                .onPointerEvent(PointerEventType.Release, pass = PointerEventPass.Final) {
                    if (!addingProxy && canWriteProxy()) shortcutFocus.requestFocus()
                }.onPreviewKeyEvent { event ->
                // The inline add form owns ordinary copy shortcuts while it is being edited.
                if (!addingProxy && sharing.canShareList && event.key == Key.C && (event.isCtrlPressed || event.isMetaPressed) &&
                    !event.isAltPressed && !event.isShiftPressed) {
                    if (event.type == KeyEventType.KeyUp) sharing.shareList()
                    true
                } else false
            }.focusable(enabled = pageActive)) {
                SettingsGroup("连接类型") {
                    SettingsRow("不使用代理", if (!loaded) "尚未读取" else if (snapshot.proxies.none { it.enabled }) "当前连接" else null, enabled = available) {
                        if (canWriteProxy()) actions.enableProxy(snapshot.token, null)
                    }
                    settingsProxyDisplayRows(snapshot.proxies, proxyCheckRows).forEach { row ->
                        key(row.index, row.proxy.id) {
                            SettingsProxyRow(row, connection, available, canWriteProxy,
                                onEnable = { if (canWriteProxy() && snapshot.proxies.getOrNull(row.index) == row.proxy)
                                    actions.enableProxy(snapshot.token, row.proxy.id) },
                                onRemove = { if (canWriteProxy() && snapshot.proxies.getOrNull(row.index) == row.proxy)
                                    confirmRemoval = row.proxy }, share = sharing)
                        }
                    }
                    SettingsAction("分享代理列表", sharing.canShareList) { sharing.shareList() }
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        SettingsNote("包含所有可分享代理，包括此处未显示的代理；链接可能包含代理登录信息。")
                    }
                    SettingsAction(if (addingProxy) "取消添加" else "添加代理", available) {
                        if (canWriteProxy()) addingProxy = !addingProxy
                    }
                    if (addingProxy) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsProxyType.entries.filter { it != SettingsProxyType.WEB }.forEach { choice ->
                            SettingsRow(choice.name, if (choice == type) "已选中" else null, enabled = available) { type = choice }
                        }
                        TextField(server, { server = it }, label = "服务器", modifier = Modifier.fillMaxWidth(), enabled = available, singleLine = true)
                        TextField(port, { port = it }, label = "端口", modifier = Modifier.fillMaxWidth(), enabled = available, singleLine = true)
                        if (type == SettingsProxyType.MTPROTO) {
                            TextField(secret, { secret = it }, label = "密钥", modifier = Modifier.fillMaxWidth(), enabled = available,
                                visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        } else {
                            TextField(username, { username = it }, label = "用户名（可选）", modifier = Modifier.fillMaxWidth(), enabled = available, singleLine = true)
                            TextField(password, { password = it }, label = "密码（可选）", modifier = Modifier.fillMaxWidth(), enabled = available,
                                visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        }
                        ChatButton("保存代理", enabled = available && server.text.isNotBlank() && port.text.toIntOrNull() in 1..65535 &&
                            (type != SettingsProxyType.MTPROTO || secret.text.isNotBlank()), primary = true) {
                            val actualPort = port.text.toIntOrNull()
                            if (canWriteProxy() && server.text.isNotBlank() && actualPort in 1..65535 &&
                                (type != SettingsProxyType.MTPROTO || secret.text.isNotBlank())) {
                                proxySaveBaseline = snapshot.startedSaveRevision[SettingsSection.ADVANCED] ?: 0L
                                actions.addProxy(snapshot.token, SettingsProxyDraft(server.text.trim(), actualPort!!, type,
                                    username.text, password.text, secret.text))
                            }
                        }
                    }
                }
            }
            SettingsGroup("本地配置") {
                SettingsAction("打开配置文件", onClick = onOpenConfig)
            }
            LocalSettingsSecurityConfirmationHost.current.Content(pageActive && confirmRemoval != null,
                title = "删除代理", summary = null, maxWidth = DialogDefaults.MaxWidth,
                onDismissRequest = { if (ownsProxyPage()) confirmRemoval = null }) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingsNote("删除选中的代理配置？")
                    ChatButton("取消", onClick = { if (ownsProxyPage()) confirmRemoval = null })
                    ChatButton("删除", enabled = available, onClick = {
                        if (canWriteProxy()) {
                            val target = confirmRemoval
                            confirmRemoval = null
                            if (target != null && snapshot.proxies.any { it == target }) actions.removeProxy(snapshot.token, target.id)
                        }
                    })
                }
            }
        }
    }
}

internal fun formatSettingsBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "${bytes / 1_073_741_824L}.${bytes % 1_073_741_824L * 10 / 1_073_741_824L} GB"
    bytes >= 1_048_576L -> "${bytes / 1_048_576L}.${bytes % 1_048_576L * 10 / 1_048_576L} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
