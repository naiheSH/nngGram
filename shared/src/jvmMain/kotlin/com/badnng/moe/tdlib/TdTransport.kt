package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.AuthorizationDetails
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.LoginCodeDelivery
import com.badnng.moe.status.LoginError
import com.badnng.moe.status.LoginOtherStep
import com.badnng.moe.status.LoginRequestKind
import com.badnng.moe.status.LoginResult
import com.badnng.moe.status.LoginPresentation
import com.badnng.moe.status.NativeInfo
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Carries only a safe code and the request origin; native error text is never exposed. */
data class TdParametersResult(val code: Int?, val parametersRejected401: Boolean = false)

interface TdSession {
    /** Synchronous native authorization boundary; legacy synthetic sessions remain compatible. */
    fun authorizationReady(): Boolean = true
    val loginEmail: TdLoginEmailSession? get() = null
    val interactionGate: TdUserInteractionGate? get() = null
    val chats: TdChatSession? get() = null
    val settings: TdSettingsSession? get() = null
    val deviceNotifications: TdDeviceNotificationSession? get() = null
    val notificationSoundFiles: TdNotificationSoundFilesSession? get() = null
    val profileEmojiFiles: TdProfileEmojiFilesSession? get() = null
    /** Final generation closure, after every chat/settings/sound owner has been deactivated. */
    fun retireDownloads() {}
    val configuredProxyEnabled: Boolean? get() = null
    fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit)
    /** The caller owns key and clears it immediately after this synchronous submission returns. */
    fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, key: ByteArray, result: (TdParametersResult) -> Unit) {
        if (key.isEmpty()) setParameters(config, directories) { result(TdParametersResult(it)) }
        else result(TdParametersResult(-1))
    }
    fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit)
    fun resumeConfiguredProxy(result: (Int?) -> Unit) = configureProxy(null, result)
    fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit)
    fun close()
}

interface TdTransport {
    /** A transport which creates a real TDLib client must opt in; synthetic tools create none. */
    val hasNativeFileScanRoots: Boolean get() = false
    fun load(): NativeInfo
    fun create(
        onAuthorization: (AuthorizationDetails) -> Unit,
        onConnection: (ConnectionPhase) -> Unit,
        onError: () -> Unit,
        onChatEvent: (ChatEvent) -> Unit = {},
    ): TdSession
}

class OfficialTdTransport(private val offline: Boolean = false) : TdTransport {
    override val hasNativeFileScanRoots: Boolean = true
    override fun load() = NativeBundle.load()

    override fun create(
        onAuthorization: (AuthorizationDetails) -> Unit,
        onConnection: (ConnectionPhase) -> Unit,
        onError: () -> Unit,
        onChatEvent: (ChatEvent) -> Unit,
    ): TdSession {
        val chatUpdates = TdChatUpdateMapper(onChatEvent)
        val settingsUpdates = java.util.concurrent.atomic.AtomicReference<OfficialTdSettingsSession?>()
        val folderUpdates = java.util.concurrent.atomic.AtomicReference<OfficialTdFolderSettings?>()
        val notificationUpdates = java.util.concurrent.atomic.AtomicReference<OfficialTdDeviceNotificationSession?>()
        val downloadUpdates = java.util.concurrent.atomic.AtomicReference<OfficialTdSession?>()
        val interactionGate = TdUserInteractionGate()
        val loginEmailSuggestions = TdLoginEmailSuggestions(interactionGate)
        val authorizationReady = AtomicBoolean(false)
        val profileColorUpdates = TdProfileColorsEarlyUpdates()
        val reactionNotificationUpdates = TdReactionNotificationsEarlyUpdates()
        val client = Client.create({ update ->
            if (update is TdApi.UpdateAuthorizationState)
                interactionGate.updateAuthorizationReady {
                    authorizationReady.set(update.authorizationState is TdApi.AuthorizationStateReady)
                }
            loginEmailSuggestions.accept(update)
            downloadUpdates.get()?.acceptDownloadUpdate(update)
            if (update is TdApi.UpdateAccentColors || update is TdApi.UpdateProfileAccentColors)
                profileColorUpdates.accept(update)
            else if (update is TdApi.UpdateReactionNotificationSettings) reactionNotificationUpdates.accept(update)
            else settingsUpdates.get()?.accept(update)
            if (update is TdApi.UpdateChatFolders) folderUpdates.get()?.accept(update)
            notificationUpdates.get()?.accept(update)
            when (update) {
                is TdApi.UpdateAuthorizationState -> onAuthorization(mapAuthorizationDetails(update.authorizationState))
                is TdApi.UpdateConnectionState -> onConnection(mapConnectionState(update.state))
                is TdApi.UpdateAccentColors, is TdApi.UpdateProfileAccentColors -> Unit
                is TdApi.UpdateReactionNotificationSettings -> Unit
                else -> chatUpdates.accept(update)
            }
        }, { onError() }, { onError() })
        val session = OfficialTdSession(offline, interactionGate, loginEmailSuggestions, authorizationReady::get,
            retireProfileColorsUpdates = { profileColorUpdates.retire(); reactionNotificationUpdates.retire() },
            profileStickerReceiverOrder = ProfileStickerAvatarOrderedReceiver) { request, result, error ->
            client.send(request, { result(it) }, { error() })
        }
        settingsUpdates.set(session.settings as? OfficialTdSettingsSession)
        profileColorUpdates.bind { update ->
            (session.settings as? OfficialTdSettingsSession)?.accept(update)
            session.settings.profileColors?.catalog()?.let(chatUpdates::acceptProfileColorsCatalog)
        }
        reactionNotificationUpdates.bind { update -> (session.settings as? OfficialTdSettingsSession)?.accept(update) }
        downloadUpdates.set(session)
        folderUpdates.set(session.chats?.folderSettings as? OfficialTdFolderSettings)
        (session.deviceNotifications as? OfficialTdDeviceNotificationSession)?.let { notifications ->
            notifications.onResolvedChat = { chat -> chatUpdates.accept(TdApi.UpdateNewChat(chat)) }
            notificationUpdates.set(notifications)
        }
        return session
    }
}

internal fun mapAuthorizationState(state: TdApi.AuthorizationState?): AuthorizationPhase = when (state) {
    is TdApi.AuthorizationStateWaitTdlibParameters -> AuthorizationPhase.WAITING_PARAMETERS
    is TdApi.AuthorizationStateWaitPhoneNumber -> AuthorizationPhase.WAITING_LOGIN
    is TdApi.AuthorizationStateWaitCode -> AuthorizationPhase.WAITING_CODE
    is TdApi.AuthorizationStateWaitPassword -> AuthorizationPhase.WAITING_PASSWORD
    is TdApi.AuthorizationStateReady -> AuthorizationPhase.READY
    is TdApi.AuthorizationStateClosing, is TdApi.AuthorizationStateLoggingOut -> AuthorizationPhase.CLOSING
    is TdApi.AuthorizationStateClosed -> AuthorizationPhase.CLOSED
    else -> AuthorizationPhase.OTHER_STEP
}

internal fun mapAuthorizationDetails(state: TdApi.AuthorizationState?): AuthorizationDetails {
    val codeType = (state as? TdApi.AuthorizationStateWaitCode)?.codeInfo?.type
    val codeDelivery = when (codeType) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> LoginCodeDelivery.TELEGRAM
        is TdApi.AuthenticationCodeTypeSms -> LoginCodeDelivery.SMS
        is TdApi.AuthenticationCodeTypeCall -> LoginCodeDelivery.CALL
        is TdApi.AuthenticationCodeTypeFlashCall -> LoginCodeDelivery.FLASH_CALL
        is TdApi.AuthenticationCodeTypeMissedCall -> LoginCodeDelivery.MISSED_CALL
        is TdApi.AuthenticationCodeTypeFragment -> LoginCodeDelivery.FRAGMENT
        is TdApi.AuthenticationCodeTypeSmsWord -> LoginCodeDelivery.SMS_WORD
        is TdApi.AuthenticationCodeTypeSmsPhrase -> LoginCodeDelivery.SMS_PHRASE
        is TdApi.AuthenticationCodeTypeFirebaseAndroid, is TdApi.AuthenticationCodeTypeFirebaseIos -> LoginCodeDelivery.FIREBASE
        else -> LoginCodeDelivery.UNKNOWN
    }
    val codeLength = when (codeType) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> codeType.length
        is TdApi.AuthenticationCodeTypeSms -> codeType.length
        is TdApi.AuthenticationCodeTypeCall -> codeType.length
        is TdApi.AuthenticationCodeTypeMissedCall -> codeType.length
        is TdApi.AuthenticationCodeTypeFragment -> codeType.length
        is TdApi.AuthenticationCodeTypeFirebaseAndroid -> codeType.length
        is TdApi.AuthenticationCodeTypeFirebaseIos -> codeType.length
        else -> null
    }?.takeIf { it in 1..128 }
    val otherStep = when (state) {
        is TdApi.AuthorizationStateWaitEmailAddress -> LoginOtherStep.EMAIL_ADDRESS
        is TdApi.AuthorizationStateWaitEmailCode -> LoginOtherStep.EMAIL_CODE
        is TdApi.AuthorizationStateWaitRegistration -> LoginOtherStep.REGISTRATION
        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> LoginOtherStep.DEVICE_CONFIRMATION
        is TdApi.AuthorizationStateWaitPremiumPurchase -> LoginOtherStep.PREMIUM_PURCHASE
        else -> LoginOtherStep.UNKNOWN
    }
    val next = (state as? TdApi.AuthorizationStateWaitCode)?.codeInfo?.nextType
    val nextDelivery = when (next) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> LoginCodeDelivery.TELEGRAM
        is TdApi.AuthenticationCodeTypeSms -> LoginCodeDelivery.SMS
        is TdApi.AuthenticationCodeTypeCall -> LoginCodeDelivery.CALL
        is TdApi.AuthenticationCodeTypeFlashCall -> LoginCodeDelivery.FLASH_CALL
        is TdApi.AuthenticationCodeTypeMissedCall -> LoginCodeDelivery.MISSED_CALL
        is TdApi.AuthenticationCodeTypeFragment -> LoginCodeDelivery.FRAGMENT
        is TdApi.AuthenticationCodeTypeSmsWord -> LoginCodeDelivery.SMS_WORD
        is TdApi.AuthenticationCodeTypeSmsPhrase -> LoginCodeDelivery.SMS_PHRASE
        else -> LoginCodeDelivery.UNKNOWN
    }
    val password = state as? TdApi.AuthorizationStateWaitPassword
    val emailCode = (state as? TdApi.AuthorizationStateWaitEmailCode)?.codeInfo
    return AuthorizationDetails(
        mapAuthorizationState(state), codeDelivery,
        codeLength ?: emailCode?.length?.takeIf { it in 1..128 }, otherStep,
        nextDelivery, (state as? TdApi.AuthorizationStateWaitCode)?.codeInfo?.timeout?.coerceIn(0, 86400) ?: 0,
        password?.hasRecoveryEmailAddress == true,
        LoginPresentation(
            qrLink = (state as? TdApi.AuthorizationStateWaitOtherDeviceConfirmation)?.link
                ?.takeIf { it.length <= 2048 && it.matches(Regex("tg://login\\?token=[A-Za-z0-9_=-]+")) },
            phoneNumber = (state as? TdApi.AuthorizationStateWaitCode)?.codeInfo?.phoneNumber?.take(64),
            passwordHint = password?.passwordHint?.take(256),
            recoveryEmailPattern = password?.recoveryEmailAddressPattern?.take(320),
            emailPattern = emailCode?.emailAddressPattern?.take(320),
        ),
    )
}

internal fun mapConnectionState(state: TdApi.ConnectionState?): ConnectionPhase = when (state) {
    is TdApi.ConnectionStateWaitingForNetwork -> ConnectionPhase.WAITING_NETWORK
    is TdApi.ConnectionStateConnectingToProxy -> ConnectionPhase.CONNECTING_PROXY
    is TdApi.ConnectionStateConnecting -> ConnectionPhase.CONNECTING
    is TdApi.ConnectionStateUpdating -> ConnectionPhase.UPDATING
    is TdApi.ConnectionStateReady -> ConnectionPhase.READY
    else -> ConnectionPhase.UNKNOWN
}

/** The request boundary is injectable so proxy sequencing can be tested without opening a native client. */
internal class OfficialTdSession(
    private val offline: Boolean,
    override val interactionGate: TdUserInteractionGate = TdUserInteractionGate(),
    private val loginEmailSuggestions: TdLoginEmailSuggestions = TdLoginEmailSuggestions(interactionGate),
    private val isAuthorizationReady: () -> Boolean = { true },
    private val retireProfileColorsUpdates: () -> Unit = {},
    private val profileStickerReceiverOrder: ProfileStickerAvatarOrderedReceiver? = null,
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdSession, ProfileStickerAvatarMediaSession {
    override fun authorizationReady(): Boolean = isAuthorizationReady()
    private val localSend = interactionGate.guardLocal(send)
    override val loginEmail: TdLoginEmailSession = OfficialTdLoginEmail(localSend, loginEmailSuggestions, interactionGate, isAuthorizationReady)
    private val ordinarySend = interactionGate.guard { request, result, failed ->
        // Every callback-chain step checks actual authorization immediately before native send.
        // Already owned resource relinquishment remains available during an authorization change.
        if (request is TdApi.CloseChat || request is TdApi.CancelDownloadFile || authorizationReady())
            send(request, result, failed)
        else {
            val scope = SettingsSpecialMutationContext.scopeFor(request)
            if (scope != null) SettingsSpecialMutationContext.withDispatchFailure(scope,
                SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.NOT_DISPATCHED, com.badnng.moe.settings.SettingsError.NOT_READY), failed)
            else failed()
        }
    }
    private val storageSend: SettingsStorageNativeSend = { request, result, failed ->
        interactionGate.dispatchStorageRequest(request, ::authorizationReady, send, result, failed)
    }
    private val chatNotifications = NativeChatNotificationMutations(ordinarySend)
    private val downloads = TdDownloadBroker(OfficialTdDownloadNative(ordinarySend), DownloadBrokerScheduler::schedule)
    private val closeRequested = AtomicBoolean()
    override val chats: TdChatSession = OfficialTdChatSession(sharedChatNotifications = chatNotifications,
        sharedDownloads = downloads, send = ordinarySend)
    private val nativeSettings = OfficialTdSettingsSession(sharedLoginEmail = loginEmail as OfficialTdLoginEmail,
        sharedChatNotifications = chatNotifications, storageSend = storageSend,
        profileStickerReceiverOrder = profileStickerReceiverOrder, send = ordinarySend).also {
            it.bindProfileHistoryDownloads(downloads)
            it.bindStickerAvatarDownloads(downloads)
        }
    override val settings: TdSettingsSession get() = nativeSettings
    override val stickerAvatarMedia: ProfileStickerAvatarMediaFiles = nativeSettings.stickerAvatarMedia()
    override val notificationSoundFiles: TdNotificationSoundFilesSession = OfficialTdNotificationSoundFiles(
        checkNotNull(settings.savedNotificationSounds), downloads)
    override val profileEmojiFiles: TdProfileEmojiFilesSession = OfficialTdProfileEmojiFiles(
        checkNotNull(settings.profileColors), downloads, ordinarySend)
    override val deviceNotifications: TdDeviceNotificationSession = OfficialTdDeviceNotificationSession(ordinarySend)
    @Volatile override var configuredProxyEnabled: Boolean? = null
        private set

    override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) {
        setParameters(config, directories, ByteArray(0)) { result(it.code) }
    }

    override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, key: ByteArray, result: (TdParametersResult) -> Unit) {
        val parameters = TdApi.SetTdlibParameters().apply {
            useTestDc = config.useTestDc
            databaseDirectory = directories.database.toString()
            filesDirectory = directories.files.toString()
            databaseEncryptionKey = key
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = config.apiId
            apiHash = config.apiHash
            systemLanguageCode = Locale.getDefault().language.ifBlank { "en" }
            deviceModel = com.badnng.moe.settingsEffectiveDeviceName(config.deviceModel)
            systemVersion = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
            applicationVersion = "nngGram 0.1.0"
        }
        val remaining = AtomicInteger(2)
        val completed = AtomicBoolean()
        fun complete(code: Int?, fromParameters: Boolean) {
            // Invalid parameters can leave SetNetworkType queued forever. Fail
            // immediately on either error; require both acknowledgements only
            // for success, and ignore cancellation replies after a failure.
            if ((code != null || remaining.decrementAndGet() == 0) && completed.compareAndSet(false, true))
                result(TdParametersResult(code, fromParameters && code == 401))
        }
        // This is queued until init_options_and_network, before ConfigManager
        // starts. Do not wait before submitting parameters. Keep
        // networking gated until the configured proxy (or direct mode) is applied.
        sendStartupOk(TdApi.SetNetworkType(TdApi.NetworkTypeNone())) { complete(it, false) }
        try { if (!completed.get()) sendStartupOk(parameters) { complete(it, true) } }
        finally { parameters.databaseEncryptionKey = ByteArray(0) }
    }

    override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) {
        val request = if (proxy == null) TdApi.DisableProxy() else {
            val type = when (proxy.type) {
                ProxyType.SOCKS5 -> TdApi.ProxyTypeSocks5(proxy.username, proxy.password)
                ProxyType.HTTP -> TdApi.ProxyTypeHttp(proxy.username, proxy.password, false)
            }
            TdApi.AddProxy(TdApi.Proxy(proxy.host, proxy.port, type), true, "")
        }
        sendExpecting(request, { if (proxy == null) it is TdApi.Ok else it is TdApi.AddedProxy }) { code ->
            fun complete(value: Int?) {
                if (value == null) configuredProxyEnabled = proxy != null
                result(value)
            }
            if (code != null || offline) complete(code)
            else sendOk(TdApi.SetNetworkType(TdApi.NetworkTypeOther()), ::complete)
        }
    }

    override fun resumeConfiguredProxy(result: (Int?) -> Unit) {
        val completed = AtomicBoolean()
        val selectionRead = AtomicBoolean()
        fun complete(code: Int?) {
            if (completed.compareAndSet(false, true)) result(code)
        }
        fun applySelection(response: TdApi.Object) {
            if (!selectionRead.compareAndSet(false, true)) return
            if (response is TdApi.Error) {
                complete(response.code)
                return
            }
            if (response !is TdApi.AddedProxies || response.proxies == null) {
                complete(-1)
                return
            }
            // Unlike a bounded UI list, startup must inspect every native entry to find the enabled identity.
            val enabled = response.proxies.firstOrNull { it.isEnabled }
            if (enabled != null && enabled.id <= 0) {
                complete(-1)
                return
            }
            val request = if (enabled == null) TdApi.DisableProxy() else TdApi.EnableProxy(enabled.id)
            sendOk(request) { code ->
                fun confirmed(value: Int?) {
                    if (value == null) configuredProxyEnabled = enabled != null
                    complete(value)
                }
                if (code != null || offline) confirmed(code)
                else sendOk(TdApi.SetNetworkType(TdApi.NetworkTypeOther()), ::confirmed)
            }
        }
        try {
            localSend(TdApi.GetProxies(), { response ->
                try { applySelection(response) } catch (_: Exception) { complete(-1) }
            }, { if (selectionRead.compareAndSet(false, true)) complete(-1) })
        } catch (_: Exception) {
            if (selectionRead.compareAndSet(false, true)) complete(-1)
        }
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        retireProfileColorsUpdates()
        settings.profileColors?.retire()
        settings.reactionNotifications?.retire()
        settings.profileExtras?.retireHistoryFiles()
        settings.profileExtras?.retireProfileAnimations()
        settings.profileExtras?.stickerAvatar?.retire()
        retireDownloads()
        interactionGate.retire()
        loginEmail.retireAll()
        send(TdApi.Close(), {}, {})
    }

    fun acceptDownloadUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateFile) update.file?.let { downloads.observe(mapMediaFile(it)) }
    }

    override fun retireDownloads() {
        stickerAvatarMedia.retire()
        profileEmojiFiles.retire()
        notificationSoundFiles.retire()
        downloads.close()
    }

    override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) {
        val request = when (kind) {
            LoginRequestKind.PHONE -> TdApi.SetAuthenticationPhoneNumber(value,
                TdApi.PhoneNumberAuthenticationSettings(false, false, false, false, false, null, emptyArray()))
            LoginRequestKind.CODE -> TdApi.CheckAuthenticationCode(value)
            LoginRequestKind.PASSWORD -> TdApi.CheckAuthenticationPassword(value)
            LoginRequestKind.QR_CODE -> TdApi.RequestQrCodeAuthentication(longArrayOf())
            // Never LogOut here: a scan may have authorized the account before this query runs.
            // Closing preserves that account. QR auth_state itself persists for five minutes;
            // the root host must own any separate unauthorised-session directory transition.
            LoginRequestKind.RESET_PHONE -> TdApi.Close()
            LoginRequestKind.RESEND_CODE -> TdApi.ResendAuthenticationCode(null)
            LoginRequestKind.EMAIL_ADDRESS -> TdApi.SetAuthenticationEmailAddress(value)
            LoginRequestKind.EMAIL_CODE -> TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(value))
            LoginRequestKind.PASSWORD_RECOVERY_REQUEST -> TdApi.RequestAuthenticationPasswordRecovery()
            LoginRequestKind.PASSWORD_RECOVERY_CODE -> TdApi.RecoverAuthenticationPassword(value, "", "")
        }
        val completed = AtomicBoolean()
        fun complete(outcome: LoginResult) {
            if (completed.compareAndSet(false, true)) result(outcome)
        }
        try {
            localSend(request, { response ->
                complete(when (response) {
                    is TdApi.Ok -> LoginResult()
                    is TdApi.Error -> LoginResult(classifyLoginError(response), response.code)
                    else -> LoginResult(LoginError.REQUEST_FAILED, -1)
                })
            }, { complete(LoginResult(LoginError.REQUEST_FAILED, -1)) })
        } catch (_: Exception) {
            complete(LoginResult(LoginError.REQUEST_FAILED, -1))
        }
    }

    private fun sendOk(request: TdApi.Function<*>, result: (Int?) -> Unit) =
        sendExpecting(request, { it is TdApi.Ok }, result = result)

    private fun sendStartupOk(request: TdApi.Function<*>, result: (Int?) -> Unit) =
        sendExpecting(request, { it is TdApi.Ok }, startup = true, result = result)

    private fun sendExpecting(request: TdApi.Function<*>, expected: (TdApi.Object) -> Boolean,
        startup: Boolean = false, result: (Int?) -> Unit) {
        val completed = AtomicBoolean()
        fun complete(code: Int?) {
            if (completed.compareAndSet(false, true)) result(code)
        }
        try {
            val submit = if (startup) send else localSend
            submit(request, { response ->
                // Never forward native error text or request objects: either can
                // contain API credentials, proxy passwords or account information.
                complete(when {
                    response is TdApi.Error -> response.code
                    expected(response) -> null
                    else -> -1
                })
            }, { complete(-1) })
        } catch (_: Exception) {
            complete(-1)
        }
    }
}

/** Only exact known tokens are inspected; native text never crosses the transport boundary. */
private fun classifyLoginError(error: TdApi.Error): LoginError = when {
    error.code == 429 || error.message?.matches(Regex("FLOOD_(?:PREMIUM_)?WAIT_[0-9]+")) == true -> LoginError.TOO_MANY_ATTEMPTS
    else -> when (error.message) {
        "PHONE_NUMBER_INVALID" -> LoginError.INVALID_PHONE
        "PHONE_CODE_INVALID", "PHONE_CODE_EMPTY" -> LoginError.INVALID_CODE
        "PHONE_CODE_EXPIRED" -> LoginError.EXPIRED_CODE
        "PASSWORD_HASH_INVALID" -> LoginError.INVALID_PASSWORD
        "PHONE_NUMBER_BANNED" -> LoginError.PHONE_BANNED
        "EMAIL_INVALID" -> LoginError.INVALID_EMAIL
        "EMAIL_NOT_ALLOWED" -> LoginError.EMAIL_NOT_ALLOWED
        "EMAIL_CODE_INVALID", "CODE_INVALID" -> LoginError.INVALID_CODE
        "EMAIL_CODE_EXPIRED", "PASSWORD_RECOVERY_EXPIRED" -> LoginError.EXPIRED_CODE
        "PASSWORD_RECOVERY_NA" -> LoginError.RECOVERY_UNAVAILABLE
        else -> LoginError.REQUEST_FAILED
    }
}
