package com.badnng.moe.tdlib

import com.badnng.moe.status.*
import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import com.badnng.moe.notifications.*
import com.badnng.moe.folders.*
import com.badnng.moe.media.ProfileEmojiArtworkProvider
import com.badnng.moe.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Path

/** Owned by Engine; catalog/files are borrowed from one captured native session. */
internal class ProfileEmojiArtworkEnvironment(
    val generation: Long,
    val catalog: TdSettingsProfileColorsSession,
    val files: TdProfileEmojiFilesSession,
    val scope: CoroutineScope,
    val currentAccountUserId: () -> Long,
    val outputAllowed: () -> Boolean,
    val admit: (work: () -> Unit, discard: () -> Unit) -> Unit,
)

/** Native work captures this lease, never the Engine's actor-owned fields. */
private class ProfileStickerAvatarInputLease(
    private val stops: AtomicLong,
    private val stoppedAt: Long,
    private val runtimeRevisions: AtomicLong,
    private val runtimeAt: Long,
    private val active: () -> Boolean,
) {
    private val alive = java.util.concurrent.atomic.AtomicBoolean(true)
    fun current(): Boolean {
        if (!alive.get()) return false
        if (stops.get() != stoppedAt || runtimeRevisions.get() != runtimeAt ||
            !runCatching(active).getOrDefault(false)) {
            alive.set(false)
            return false
        }
        return alive.get() && stops.get() == stoppedAt && runtimeRevisions.get() == runtimeAt
    }
}

/** Exact actor/session captured by one proxy-page load; workers only inspect its lease. */
private class SettingsProxyChecksEnginePage(
    val parent: SettingsToken,
    val session: TdSession,
    val controller: SettingsController,
    val lease: SettingsProxyChecksPageLease,
) {
    private val shareLock = Any()
    private val shareRevision = AtomicLong()
    private var shareOwner: SettingsProxyProbeOwner? = null

    /** A replacement or refresh revokes the old synchronous commit before returning. */
    fun newShareOwner(): SettingsProxyProbeOwner? {
        val pair = synchronized(shareLock) {
            if (!lease.current()) null else {
                val revision = shareRevision.incrementAndGet()
                val next = SettingsProxyProbeOwner { shareRevision.get() == revision && lease.current() }
                val previous = shareOwner
                shareOwner = next
                previous to next
            }
        } ?: return null
        pair.first?.let { it.revoke(); lease.detach(it) }
        val owner = pair.second
        if (!lease.attach(owner) || !owner.current()) {
            releaseShareOwner(owner)
            return null
        }
        return owner
    }

    fun releaseShareOwner(owner: SettingsProxyProbeOwner) {
        synchronized(shareLock) {
            if (shareOwner === owner) { shareOwner = null; shareRevision.incrementAndGet() }
        }
        owner.revoke()
        lease.detach(owner)
    }

    fun revokeShareOwner() {
        val old = synchronized(shareLock) {
            shareRevision.incrementAndGet()
            shareOwner.also { shareOwner = null }
        }
        old?.let { it.revoke(); lease.detach(it) }
    }
}

private class SettingsStorageEnginePage(
    val generation: Long,
    val session: TdSession,
    val controller: SettingsStorageController,
    val owner: SettingsProxyProbeOwner,
    private val status: (SettingsStorageToken?) -> Unit,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
) {
    private class Status(val token: SettingsStorageToken?)
    private val lock = Any()
    private val events = Channel<Status>(capacity = 2)
    private var accepted = false
    private var finished = false

    init {
        scope.launch(dispatcher) {
            for (event in events) runCatching { status(event.token) }
        }
    }

    /** Enqueue only; external callbacks run off actor and outside all owner/page/gate locks. */
    fun accepted(token: SettingsStorageToken) {
        synchronized(lock) {
            if (!finished && !accepted) {
                accepted = true
                events.trySend(Status(token))
            }
        }
    }

    fun finish(notify: Boolean) {
        synchronized(lock) {
            if (!finished) {
                finished = true
                if (notify) events.trySend(Status(null))
                events.close()
            }
        }
    }
}

/** Captured only after successful SetTdlibParameters for the same native session and lease. */
private class AcceptedStorageVolume(
    val generation: Long,
    val session: TdSession,
    val lease: DataDirectoryLease,
)

/** One actor owns the client, parameters, directory lease and journal. Native callbacks only enqueue. */
class TdEngine(
    private val configuration: ConfigurationStore = ConfigurationStore.default(),
    private val transport: TdTransport = OfficialTdTransport(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val journal: SafeEventJournal = SafeEventJournal.besideConfiguration(configuration.path),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val notificationWindowHandle: () -> Long = { 0L },
    private val toastActivationEntryPointAvailable: Boolean = false,
    private val toastActivationLaunch: Boolean = false,
    private val saveTargetRegistry: com.badnng.moe.media.NativeSaveTargetRegistry = com.badnng.moe.media.NativeSaveTargets.registry,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val actions = Channel<LocalActionEnvelope>(Channel.UNLIMITED)
    private val deviceNotificationPreferences = FileDeviceNotificationPreferencesStore(configuration.path.resolveSibling("device-notifications.properties"))
    private val notificationSoundVolumes = FileNotificationSoundVolumeStore(configuration.path.resolveSibling("notification-sound-volumes.properties"))
    private val windowsNotifications = WindowsDeviceNotificationHost(
        WindowsToastRegistration.platform(configuration.path.resolveSibling("windows-notifications"), toastActivationEntryPointAvailable),
        focusWindow = { postAction(Action.NotificationFocus) },
    )
    private var windowsNotificationsBootstrapped = false
    private var pendingNotificationFocus = false
    private val mutableState = MutableStateFlow(RuntimeSnapshot(configPath = configuration.path.toString(),
        localLock = LocalLockSnapshot(phase = LocalLockPhase.CHECKING)))
    private val mutableLocalLock = MutableStateFlow(LocalLockSnapshot())
    private val localProjectionLock = Any()
    val localLockState: StateFlow<LocalLockSnapshot> = mutableLocalLock.asStateFlow()
    private val settingsProxyOwnership = SettingsProxyOwnership(configuration.path.resolveSibling("settings-connection.properties"))
    val state: StateFlow<RuntimeSnapshot> = mutableState.asStateFlow()
    private val mutableProfileEmojiArtwork = MutableStateFlow<ProfileEmojiArtworkProvider?>(null)
    val profileEmojiArtwork: StateFlow<ProfileEmojiArtworkProvider?> = mutableProfileEmojiArtwork.asStateFlow()
    private var profileEmojiEpoch = AtomicLong()
    private val profileEmojiAccountUserId = AtomicLong()
    private var profileEmojiSourceEpoch = -1L
    private var profileEmojiSessionStopRevision = 0L
    private var profileEmojiSource: ProfileEmojiArtworkProvider? = null
    private val mutableProfileStickerAvatarMedia = MutableStateFlow<ProfileStickerAvatarMediaEnvironment?>(null)
    internal val profileStickerAvatarMedia: StateFlow<ProfileStickerAvatarMediaEnvironment?> =
        mutableProfileStickerAvatarMedia.asStateFlow()
    private var profileStickerAvatarEpoch = AtomicLong()
    private var profileStickerAvatarSourceEpoch = -1L
    /** Internal fake seam; production uses the fixed source constructor and borrowed session facades. */
    internal var profileEmojiArtworkFactory: (ProfileEmojiArtworkEnvironment) -> ProfileEmojiArtworkProvider = { owner ->
        OfficialProfileEmojiArtworkSource(owner.generation, owner.catalog, owner.files, owner.scope,
            owner.currentAccountUserId, owner.outputAllowed, owner.admit)
    }
    @Volatile private var session: TdSession? = null
    private var nativeSaveTargets: com.badnng.moe.media.NativeSaveTargetRegistry.Registration? = null
    private var chatController: ChatController? = null
    @Volatile private var settingsController: SettingsController? = null
    /** Physical native work survives logical page cancellation and controller generations. */
    private val settingsProxyProbeBudget = SettingsProxyProbeBudget()
    private val settingsProxyChecksPages = ConcurrentHashMap<SettingsProxyChecksPageLease, SettingsProxyChecksEnginePage>()
    private val settingsStoragePages = ConcurrentHashMap<SettingsProxyProbeOwner, SettingsStorageEnginePage>()
    /** Production shares one physical worker; synthetic tests may inject a controlled one. */
    internal var settingsStorageVolumeCoordinator = SharedSettingsStorageVolumeCoordinator.instance
    internal var settingsStorageStatusDispatcher: CoroutineDispatcher = Dispatchers.Default
    private var acceptedStorageVolume: AcceptedStorageVolume? = null
    @Volatile private var settingsProxyOrdinaryWitness = AtomicBoolean(false)
    @Volatile private var settingsProxyGateRevision = AtomicLong()
    @Volatile private var settingsProxySessionStopRevision = -1L
    /** Retained across account generations; only exact, bounded callback results enter this store. */
    internal val profileMutationReceipts = SettingsProfileMutationReceipts()
    private var deviceNotificationsController: DeviceNotificationsController? = null
    private var notificationSoundSource: NotificationSoundSource? = null
    @Volatile private var storageController: SettingsStorageController? = null
    private var storagePolicyController: SettingsStoragePolicyController? = null
    private var loginEmailController: LoginEmailController? = null
    private var notificationForeground = false
    private var notificationActiveChatId: Long? = null
    private var notificationUiLocked = false
    private var chatEpoch = 0L
    private val attachmentSendLeases = AttachmentSendLeases()
    private var lease: DataDirectoryLease? = null
    private var configuredRootLease: DataRootLease? = null
    private var configuredRoot: Path? = null
    private var authenticationSelection: AuthenticationDatabaseSelectionStore? = null
    private var authenticationChoice: AuthenticationDatabaseChoice? = null
    private val stopRequestRevision = AtomicLong()
    private val startupDispatchLock = Any()
    private val rootOwnershipRevision = AtomicLong()
    @Volatile private var localCoordinator: LocalLockCoordinator? = null
    private var localStateCollection: Job? = null
    private var configuredBeforeUnlock: TdConfiguration? = null
    @Volatile private var startupNeedsKey = false
    private var startupLockEpoch = 0L
    @Volatile private var pendingStartStopRevision = -1L
    private var localStopInProgress = false
    private var nativeCreationUncertain = false
    @Volatile private var localInitializationFailed = false
    private var localIdleSeconds: Long? = null
    private val localLockDispatcher = dispatcher.limitedParallelism(1)
    // This queue is not a child Job: disposing the actor must not cancel an already
    // accepted coordinator runnable before it clears its password/proof and busy slot.
    private val localLockWorker = Executor { task ->
        localLockDispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, task)
    }
    private val localIdleScheduler = LocalLockExecutorDeadlineScheduler()
    /** Synthetic tests install this before start; production uses Windows system idle. */
    internal var localLockIdleFactory: (((LocalLockIdleDue) -> Unit, () -> Unit) -> LocalLockIdleController)? = null
    private val localIdle: LocalLockIdleController by lazy {
        val onDue: (LocalLockIdleDue) -> Unit = { postAction(Action.LocalIdleDue(it)); Unit }
        val onAvailability: () -> Unit = { postAction(Action.LocalIdleAvailability); Unit }
        localLockIdleFactory?.invoke(onDue, onAvailability)
            ?: LocalLockIdleController(desktopLocalLockIdleSource(), localIdleScheduler, onDue, onAvailability)
    }
    /** Tests install a fake before start; synthetic transports never register the system host by default. */
    internal var localLockResumeHostFactory: (() -> LocalLockWindowsResumeHost)? = null
    private val localResumeIo = Dispatchers.IO.limitedParallelism(1)
    internal var localLockResumeDispatch: (Runnable) -> Unit = { task ->
        localResumeIo.dispatch(kotlin.coroutines.EmptyCoroutineContext, task)
    }
    private val localResumeLock = Any()
    private var localResumeAttempted = false
    private var localResumeRevision = 0L
    private var localResumeDisposed = false
    private var localResumeFinalExit = false
    private var localResumeHost: LocalLockWindowsResumeHost? = null
    private var localResumeLease: LocalLockWindowsResumeLease? = null
    private var localResumeOwnsHost = false
    internal var localLockCoordinatorFactory: (LocalProtectionRoot, java.util.concurrent.Executor,
        (Long, Boolean) -> Unit, (LocalLockWitness) -> Unit) -> LocalLockCoordinator = { root, worker, start, changed ->
        LocalLockCoordinator(root, worker, start, onWitnessChanged = changed)
    }

    /** Each password array is transferred or cleared even if no live coordinator exists. */
    val localLockActions = LocalLockActions(
        create = { password, confirmation -> localCoordinator?.actions?.create?.invoke(password, confirmation)
            ?: run { password.fill('\u0000'); confirmation.fill('\u0000') } },
        unlock = { password -> localCoordinator?.actions?.unlock?.invoke(password) ?: password.fill('\u0000') },
        verifyManagement = { password -> localCoordinator?.actions?.verifyManagement?.invoke(password) ?: password.fill('\u0000') },
        change = { password, confirmation -> localCoordinator?.actions?.change?.invoke(password, confirmation)
            ?: run { password.fill('\u0000'); confirmation.fill('\u0000') } },
        remove = { localCoordinator?.actions?.remove?.invoke() },
        lockNow = { localCoordinator?.actions?.lockNow?.invoke() },
        managementActivity = { localCoordinator?.actions?.managementActivity?.invoke() },
        closeManagement = { localCoordinator?.actions?.closeManagement?.invoke() },
        setAutoLockSeconds = { localCoordinator?.actions?.setAutoLockSeconds?.invoke(it) },
        skipSetup = { localCoordinator?.actions?.skipSetup?.invoke() },
        cancel = { localCoordinator?.actions?.cancel?.invoke() },
    )

    fun recordLocalLockAppActivity() = localIdle.recordAppActivity()
    fun recheckLocalLockIdle() = localIdle.recheck()
    private val notificationLockRevision = AtomicLong()
    private var qrPhoneRestart: QrPhoneRestart? = null
    private var qrSessionTicket: LocalSessionKeyTicket? = null
    private var qrCapabilityPending = false
    private var qrCapabilitySerial = 0L
    /** Synthetic tests can block before/after this exact selection write on a temporary root. */
    internal var qrSelectionAdvance: (AuthenticationDatabaseSelectionStore, AuthenticationDatabaseChoice) -> AuthenticationDatabaseChoice =
        { store, expected -> store.beginPhoneAttempt(expected) }
    /** Injected only for synthetic barriers around these two QR parameter checks. */
    internal var qrStartupRootCurrent: (DataRootLease) -> Boolean = { it.isCurrent() }
    internal var qrStartupReadSelection: (AuthenticationDatabaseSelectionStore) -> AuthenticationDatabaseChoice = { it.read() }
    private var qrSwitchWorkRunning = false
    private var qrDeferredStop: DiagnosticKind? = null
    @Volatile private var qrStartupTicket: LocalSessionKeyTicket? = null
    @Volatile private var qrStartupInterruptedByLock = false
    private var qrStartupRestart: QrPhoneRestart? = null
    private var parameters: TdConfiguration? = null
    private var pendingProxy: ProxyConfiguration? = null
    private var usingProxy = false
    @Volatile private var generation = 0L
    private var parametersSent = false
    private var parametersHandled = false
    private var proxySent = false
    private var proxyHandled = false
    @Volatile private var closeSent = false
    private var closeRequestFailed = false
    private var disposeRequested = false
    private var loginRequestId = 0L
    private var pendingLoginId: Long? = null
    private var activeLoginKind: LoginRequestKind? = null
    @Volatile private var disposed = false
    private val waiterIds = AtomicLong()
    private val closeWaiters = mutableMapOf<Long, CompletableDeferred<Boolean>>()

    init {
        scope.launch {
            try {
                for (entry in actions) {
                    val action = entry.action
                    if (!dispatchable(entry)) { runCatching { discardLocalAction(action) }; continue }
                    // Preserve the originating admission while reconciling a terminal result.
                    // It can update its original owner but cannot authorize a new native send.
                    val contextGate = session?.interactionGate
                    val previousWitness = contextGate?.pushCallbackWitness(entry.witness)
                    val previousEmail = contextGate?.pushCallbackEmailRevision(entry.emailRevision)
                    try {
                        when (action) {
                            Action.Start -> startClient()
                            is Action.LocalStartReady -> if (localCoordinator === action.owner)
                                startNativeAfterLocalGate(action.owner, action.epoch, action.needsStartupKey)
                            is Action.LocalInitializationFailed -> if (localCoordinator === action.owner) {
                                localInitializationFailed = true
                                val unavailable = LocalLockSnapshot(phase = LocalLockPhase.RECOVERY, issue = LocalLockIssue.IO_FAILURE)
                                mutableLocalLock.value = unavailable
                                mutableState.update { it.copy(localLock = unavailable) }
                                fail(SetupFailure("本地保护状态不可用", "请停止客户端并重新检查本地保护状态。", FailureCategory.INTERNAL))
                            }
                            is Action.LocalStateChanged -> if (localCoordinator === action.owner) {
                                updateLocalIdle(action.owner)
                                requestQrCapability(action.owner)
                            }
                            is Action.LocalGateChanged -> if (localCoordinator === action.owner && action.owner.currentWitness() == action.witness) {
                                if (action.witness.blocked) {
                                    qrSessionTicket = null
                                    mutableState.update { it.copy(loginQrPhoneSwitchAvailable = false) }
                                    pendingNotificationFocus = false
                                    invalidateLogin()
                                    if (qrStartupInterruptedByLock && qrStartupTicket != null && session != null) requestClose()
                                    if (proxySent && !proxyHandled) {
                                        proxyHandled = true
                                        mutableState.value = mutableState.value.copy(proxy = ProxyStatus.FAILED,
                                            proxyFailure = EngineFailure("连接方式尚未确认", "解锁后请重启客户端，再重新确认连接方式。", FailureCategory.PROXY))
                                    }
                                }
                                applyInteractionGate()
                                if (!action.witness.blocked) {
                                    bootstrapNotificationsIfOpen()
                                    continueProxyAfterParameters()
                                    startChatsWhenReady()
                                    requestQrCapability(action.owner)
                                }
                                updateLocalIdle(action.owner)
                            }
                            is Action.LocalIdleDue -> {
                                val owner = localCoordinator
                                val witness = owner?.currentWitness()
                                if (owner != null && witness != null && witness.epoch == action.due.epoch &&
                                    owner.isCurrentAndOpen(witness) && localIdle.claimDue(action.due)) owner.actions.lockNow()
                            }
                            Action.LocalIdleAvailability -> localCoordinator?.let(::updateLocalIdle)
                            Action.LocalResumeAvailability -> localCoordinator?.let(::publishLocalSnapshot)
                            is Action.LocalPowerResume -> {
                                // Consume stale tickets too, so they cannot block later signals.
                                val consumed = action.signal.consume()
                                if (consumed && !disposed && action.generation == generation && localOpen(entry.witness)) {
                                    // A power event is not user input and never resets the inactivity interval.
                                    localIdle.recheck()
                                }
                                localCoordinator?.let(::publishLocalSnapshot)
                            }
                            is Action.QrCapabilityReady -> {
                                if (action.serial == qrCapabilitySerial) {
                                    qrCapabilityPending = false
                                    val current = localCoordinator === action.owner && session === action.session &&
                                        generation == action.generation && !closeSent && !disposeRequested &&
                                        stopRequestRevision.get() == action.stopRevision &&
                                        action.owner.isCurrentAndOpen(action.witness)
                                    if (current) {
                                        qrSessionTicket = action.ticket?.takeIf { it.epoch == action.witness.epoch }
                                        mutableState.update { it.copy(loginQrPhoneSwitchAvailable = qrSessionTicket != null) }
                                    } else localCoordinator?.let(::requestQrCapability)
                                }
                            }
                            is Action.QrPreparationReady -> finishQrPreparation(action)
                            is Action.LocalStopReady -> if (localCoordinator === action.owner) {
                                localStateCollection?.cancel()
                                localStateCollection = null
                                localCoordinator = null
                                localStopInProgress = false
                                localIdleSeconds = null
                                if (localInitializationFailed) {
                                    val unavailable = LocalLockSnapshot(phase = LocalLockPhase.RECOVERY, issue = LocalLockIssue.IO_FAILURE)
                                    mutableLocalLock.value = unavailable
                                    mutableState.update { it.copy(localLock = unavailable) }
                                } else if (!action.owner.state.value.enabled && !startupNeedsKey &&
                                    action.owner.state.value.issue == LocalLockIssue.STALE) {
                                    val stopped = LocalLockSnapshot(phase = LocalLockPhase.UNPROTECTED)
                                    mutableLocalLock.value = stopped
                                    mutableState.update { it.copy(localLock = stopped) }
                                }
                                if (releaseDirectory()) completeStopped(action.kind) else completeWaiters(false)
                            }
                            is Action.OrphanNativeClosed -> if (action.generation == generation && nativeCreationUncertain && session == null) {
                                nativeCreationUncertain = false
                                nativeSaveTargets = null
                                val restart = qrStartupRestart
                                val ticket = qrStartupTicket
                                val owner = localCoordinator
                                val locked = owner != null && qrStartupLockInterrupted(owner)
                                if (locked && restart != null && ticket != null && owner != null && !disposeRequested &&
                                    restart.stopRevision == stopRequestRevision.get() && releaseDirectory(keepConfiguredRoot = true)) {
                                    qrStartupTicket = null
                                    qrStartupRestart = null
                                    suspendQrAfterClosed(owner, restart, ticket)
                                } else beginLocalStop(DiagnosticKind.CLOSE_CONFIRMED)
                            }
                            is Action.QrStartupAbort -> if (current(action.generation) && qrStartupTicket === action.ticket &&
                                qrStartupInterruptedByLock && !closeSent) requestClose()
                            is Action.LocalParametersHandled -> if (current(action.generation) && localCoordinator === action.owner && !closeSent &&
                                action.stopRevision == stopRequestRevision.get()) {
                                if (qrStartupLockInterrupted(action.owner)) requestClose()
                                else if (action.result.code == null && !action.accepted)
                                    fail(SetupFailure("本地保护状态已变化", "请等待客户端关闭后重新检查保护状态。", FailureCategory.DIRECTORY))
                                else {
                                    if (action.result.code == null && action.accepted) {
                                        qrStartupTicket = null
                                        qrStartupRestart = null
                                        qrStartupInterruptedByLock = false
                                    }
                                    parametersResult(action.result.code)
                                }
                            }
                            Action.NotificationFocus -> {
                                pendingNotificationFocus = true
                                if (notificationWindowHandle() != 0L) {
                                    focusWindowsNotificationOwner(notificationWindowHandle())
                                    pendingNotificationFocus = false
                                }
                            }
                            Action.NotificationWindowReady -> if (pendingNotificationFocus && localCoordinator?.currentWitness()?.blocked != true &&
                                notificationWindowHandle() != 0L) {
                                focusWindowsNotificationOwner(notificationWindowHandle()); pendingNotificationFocus = false
                            }
                            is Action.Authorization -> if (current(action.generation)) authorization(action.details)
                            is Action.Connection -> if (current(action.generation) && !closeSent) connection(action.phase)
                            is Action.ParametersResult -> if (current(action.generation) && !closeSent &&
                                action.epoch == startupLockEpoch && action.needsStartupKey == startupNeedsKey &&
                                action.stopRevision == stopRequestRevision.get()) {
                                val owner = localCoordinator
                                if (owner != null && qrStartupLockInterrupted(owner)) requestClose()
                                else if (action.needsStartupKey && owner != null) {
                                    val qrTicketAtResult = qrStartupTicket
                                    val nativeAtResult = session
                                    val rootRevisionAtResult = rootOwnershipRevision.get()
                                    fun resultCurrent() = localCoordinator === owner && generation == action.generation &&
                                        session === nativeAtResult && nativeAtResult != null &&
                                        stopRequestRevision.get() == action.stopRevision &&
                                        rootOwnershipRevision.get() == rootRevisionAtResult && !disposed && !closeSent
                                    localLockWorker.execute {
                                        if (action.stopRevision == stopRequestRevision.get()) {
                                            owner.nativeStartupResult(action.epoch, action.result.code == null,
                                                action.result.parametersRejected401,
                                                if (qrTicketAtResult != null) ::resultCurrent else ({ true }))
                                            val accepted = if (qrTicketAtResult != null)
                                                owner.captureSessionKeyTicket() === qrTicketAtResult
                                            else owner.state.value.phase in setOf(LocalLockPhase.UNLOCKED,
                                                LocalLockPhase.UNPROTECTED, LocalLockPhase.HOT_LOCKED)
                                            postAction(Action.LocalParametersHandled(action.generation, owner, action.result, accepted,
                                                action.stopRevision))
                                        }
                                    }
                                } else parametersResult(action.result.code)
                            }
                            is Action.ProxyResult -> if (current(action.generation) && !closeSent) proxyResult(action.code)
                            is Action.LoginInput -> submitLoginInput(action)
                            is Action.CancelQr -> cancelQrOnActor(action.token)
                            is Action.LoginCompleted -> loginCompleted(action)
                            is Action.ChatUpdate -> if (current(action.generation) && !closeSent && !disposeRequested) {
                                chatController?.event(action.event)
                            }
                            is Action.ChatWork -> if (current(action.generation) && action.epoch == chatEpoch && !closeSent && !disposeRequested) {
                                action.work()
                            }
                            is Action.FolderTerminalWork -> if (current(action.generation) && session === action.session &&
                                localCoordinator === action.root && action.epoch == chatEpoch && !closeSent && !disposeRequested) {
                                action.work()
                            }
                            is Action.ProfileEmojiArtworkWork -> {
                                if (current(action.generation) && session === action.session &&
                                    action.epoch == profileEmojiEpoch.get() && action.stopRevision == stopRequestRevision.get() &&
                                    !closeSent && !disposeRequested && ordinaryAccountAllowed() &&
                                    profileEmojiAccountUserId.get() > 0L) action.work()
                                else action.discard()
                            }
                            is Action.ProfileStickerAvatarMediaWork -> {
                                val request = action.request
                                val bridge = action.bridge
                                val valid = current(action.generation) && session === action.session &&
                                    mutableProfileStickerAvatarMedia.value === action.environment &&
                                    action.epoch == profileStickerAvatarEpoch.get() &&
                                    action.stopRevision == stopRequestRevision.get() && !closeSent && !disposeRequested &&
                                    ordinaryAccountAllowed() && profileEmojiAccountUserId.get() == request.parent.userId
                                val captured = if (valid) settingsController?.captureProfileStickerAvatarMedia(
                                    request.parent, request.picker, request.selection) else null
                                val files = (action.session as? ProfileStickerAvatarMediaSession)?.stickerAvatarMedia
                                if (captured == null || files == null || !bridge.current()) bridge.discard()
                                else bridge.start(captured) { active, done ->
                                    if (request.selection != null) files.full(request.picker, request.selection, active, done)
                                    else files.thumbnail(request.picker, checkNotNull(request.candidate), active, done)
                                }
                            }
                            is Action.SavedProfileStickerMediaWork -> {
                                val request = action.request
                                val bridge = action.bridge
                                val valid = current(action.generation) && session === action.session &&
                                    mutableProfileStickerAvatarMedia.value === action.environment &&
                                    action.epoch == profileStickerAvatarEpoch.get() &&
                                    action.stopRevision == stopRequestRevision.get() && !closeSent && !disposeRequested &&
                                    ordinaryAccountAllowed() && profileEmojiAccountUserId.get() == request.parent.userId
                                val captured = if (valid && bridge.demandCurrent())
                                    settingsController?.captureSavedProfileSticker(request, witnessOnly = action.limited,
                                        demand = action.demand) else null
                                val grant = captured?.value
                                val captureError = captured?.error
                                val files = (action.session as? ProfileStickerAvatarMediaSession)?.stickerAvatarMedia
                                if (grant == null || captureError != null && captureError != SettingsError.RATE_LIMITED ||
                                    files == null || !bridge.demandCurrent()) {
                                    grant?.close(); bridge.reject()
                                } else if (action.limited || captureError == SettingsError.RATE_LIMITED) bridge.start(grant) { _, done ->
                                    done(SettingsResult(error = SettingsError.RATE_LIMITED))
                                    object : ProfileStickerAvatarReadLease { override fun close() {} }
                                } else bridge.start(grant) { active, done -> files.saved(grant, active, done) }
                            }
                            is Action.ChatInput -> {
                                val controller = chatController
                                if (canUseChats(action.token, action.cleanup) && controller != null) action.work(controller)
                                else action.onDiscard()
                            }
                            is Action.FolderSettingsInput -> {
                                val controller = chatController
                                if (current(action.token.generation) && !closeSent && !disposeRequested &&
                                    (action.cleanup || ordinaryAccountAllowed()) &&
                                    (if (action.cleanup) mutableState.value.chats.folderSettings.token == action.token
                                        else controller?.acceptsFolderSettings(action.token) == true)) controller?.let(action.work)
                            }
                            is Action.SettingsWork -> if (current(action.generation) && !closeSent && !disposeRequested) action.work()
                            is Action.SettingsSpecialTerminalWork -> if (current(action.generation) &&
                                session === action.session && localCoordinator === action.root && !closeSent && !disposeRequested) action.work()
                            is Action.SettingsSpecialReadbackWork -> if (current(action.generation) &&
                                session === action.session && localCoordinator === action.root && !closeSent && !disposeRequested &&
                                ordinaryAllowed() && mutableState.value.status.authorization == AuthorizationPhase.READY &&
                                action.session.authorizationReady()) {
                                // This action contains a controller-owned read, never an old input or write.
                                val gate = action.session.interactionGate
                                val readback = {
                                    if (ordinaryAccountAllowed() && action.pageCurrent()) action.work()
                                }
                                if (gate != null) gate.withoutCallbackContext(readback)
                                else SettingsStorageCallbackContext.without {
                                    FolderMutationTerminal.without { SettingsSpecialMutationContext.without(readback) }
                                }
                            }
                            is Action.SettingsMutationWork -> if (current(action.generation) && !closeSent && !disposeRequested) {
                                if (action.indeterminate) settingsController?.mutationOutcomeUnknown(action.section, action.revision)
                                else action.work()
                            }
                            is Action.DeviceNotificationInput -> if (current(action.generation) && !closeSent && !disposeRequested &&
                                (action.allowLocked || ordinaryAccountAllowed()) &&
                                mutableState.value.status.authorization == AuthorizationPhase.READY) deviceNotificationsController?.let(action.work)
                            is Action.StorageInput -> {
                                val controller = storageController
                                if (current(action.generation) && !closeSent && !disposeRequested &&
                                    (action.cleanup || ordinaryAccountAllowed()) && controller != null) action.work(controller)
                                else action.onDiscard()
                            }
                            is Action.StoragePolicyInput -> {
                                val controller = storagePolicyController
                                if (current(action.generation) && !closeSent && !disposeRequested &&
                                    (action.cleanup || ordinaryAccountAllowed()) && controller != null) action.work(controller)
                                else action.discard()
                            }
                            is Action.SettingsInput -> if (canUseSettings(action.token, action.cleanup)) {
                                settingsController?.let(action.work)
                            }
                            is Action.ProxyChecksOpen -> {
                                val page = action.page
                                if (canUseSettingsProxyChecks(page)) {
                                    try {
                                        page.controller.openProxyChecks(page.parent, page.lease) { token ->
                                            if (postAction(Action.ProxyChecksOpened(page, token, action.opened)).isFailure) {
                                                closeSettingsProxyChecksPage(page)
                                                action.opened(null)
                                            }
                                        }
                                        revokeExpiredSettingsProxyChecks()
                                    } catch (_: Exception) {
                                        closeSettingsProxyChecksPage(page)
                                        action.opened(null)
                                    }
                                } else {
                                    closeSettingsProxyChecksPage(page)
                                    action.opened(null)
                                }
                            }
                            is Action.ProxyChecksOpened -> {
                                val page = action.page
                                if (action.token?.parent == page.parent &&
                                    mutableState.value.settings.proxyChecks.token == action.token && canUseSettingsProxyChecks(page)) {
                                    action.opened(action.token)
                                } else {
                                    closeSettingsProxyChecksPage(page)
                                    action.opened(null)
                                }
                            }
                            // The exact old controller still needs cleanup after ordinary gates close.
                            is Action.ProxyChecksClose -> action.page.controller.closeProxyChecks(action.page.parent, action.page.lease)
                            is Action.ProxyChecksRefresh -> {
                                val page = action.page
                                if (canUseSettingsProxyChecks(page)) page.controller.refreshProxyChecks(page.parent, page.lease)
                                else closeSettingsProxyChecksPage(page)
                            }
                            is Action.ProxyChecksShare -> {
                                val page = action.page
                                try {
                                    if (canUseSettingsProxyChecks(page))
                                        page.controller.shareProxyChecks(page.parent, page.lease, action.request, action.owner, action.result)
                                    else action.result(SettingsResult(error = SettingsError.NOT_READY))
                                } catch (_: Exception) { action.result(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
                            }
                            is Action.UsernameInput -> {
                                try {
                                    val controller = settingsController
                                    if (canUseSettings(action.token) && controller != null) action.work(controller)
                                    else action.discard()
                                } catch (_: Exception) { action.discard() }
                            }
                            is Action.SecurityInput -> {
                                val controller = settingsController
                                if (!closeSent && !disposeRequested && (action.cleanup || ordinaryAccountAllowed()) &&
                                    (if (action.cleanup) mutableState.value.settings.security.token == action.token
                                        else controller?.acceptsSecurity(action.token) == true))
                                    controller?.let(action.work)
                                else action.discard()
                            }
                            is Action.PrivacyCommonInput -> {
                                val controller = settingsController
                                if (current(action.token.generation) && !closeSent && !disposeRequested &&
                                    (action.cleanup || ordinaryAccountAllowed()) &&
                                    (if (action.cleanup) mutableState.value.settings.privacyCommon.token == action.token
                                        else controller?.acceptsPrivacyCommon(action.token) == true)) controller?.let(action.work)
                            }
                            is Action.ContactSignupInput -> {
                                val controller = settingsController
                                if (current(action.token.generation) && !closeSent && !disposeRequested &&
                                    (action.cleanup || ordinaryAccountAllowed()) &&
                                    (if (action.cleanup) mutableState.value.settings.contactSignup.token == action.token
                                        else controller?.acceptsContactSignup(action.token) == true)) controller?.let(action.work)
                            }
                            is Action.ReactionNotificationsInput -> {
                                val controller = settingsController
                                if (current(action.token.generation) && !closeSent && !disposeRequested &&
                                    (action.cleanup || ordinaryAccountAllowed()) &&
                                    (if (action.cleanup) mutableState.value.settings.reactionNotifications.token == action.token
                                        else controller?.acceptsReactionNotifications(action.token) == true)) controller?.let(action.work)
                            }
                            is Action.NotificationExceptionsInput -> {
                                val controller = settingsController
                                if (current(action.token.generation) && !closeSent && !disposeRequested &&
                                    (action.cleanup || ordinaryAccountAllowed()) &&
                                    (if (action.cleanup) mutableState.value.settings.notificationExceptions.token == action.token
                                        else controller?.acceptsNotificationExceptions(action.token) == true)) controller?.let(action.work)
                            }
                            is Action.SavedNotificationSoundsInput -> {
                                val controller = settingsController
                                if (current(action.token.generation) && !closeSent && !disposeRequested &&
                                    (action.cleanup || ordinaryAccountAllowed()) &&
                                    (if (action.cleanup) mutableState.value.settings.savedNotificationSounds.token == action.token
                                        else controller?.acceptsSavedNotificationSounds(action.token) == true)) controller?.let(action.work)
                                else action.discard()
                            }
                            is Action.ProfileColorsInput -> {
                                val controller = settingsController
                                if (current(action.token.generation) && !closeSent && !disposeRequested &&
                                    (action.cleanup || ordinaryAccountAllowed()) &&
                                    (if (action.cleanup) mutableState.value.settings.profileColors.token == action.token
                                        else controller?.acceptsProfileColors(action.token) == true)) controller?.let(action.work)
                            }
                            is Action.ProfileExtrasInput -> {
                                val controller = settingsController
                                if (!closeSent && !disposeRequested && (action.cleanup || ordinaryAccountAllowed()) &&
                                    (if (action.cleanup) mutableState.value.settings.profileExtras.token == action.token
                                        else controller?.acceptsProfileExtras(action.token) == true))
                                    controller?.let(action.work)
                                else action.discard()
                            }
                            is Action.LoginEmailWork -> if (current(action.generation) && !closeSent && !disposeRequested) action.work()
                            is Action.LoginEmailInput -> {
                                val controller = loginEmailController
                                if (canUseLoginEmail(action.generation, action.cleanup) && controller != null)
                                    action.work(controller)
                                else action.discard()
                            }
                            is Action.InteractionGateChanged -> if (current(action.generation) && !closeSent && !disposeRequested) {
                                applyInteractionGate()
                                startChatsWhenReady()
                            }
                            is Action.CallbackError -> if (current(action.generation) && !closeSent) {
                                fail(SetupFailure("TDLib 回调发生异常", "请停止客户端后重试；诊断不会输出请求内容。", FailureCategory.INTERNAL))
                            }
                            is Action.Stop -> stopClient(action)
                            is Action.RemoveWaiter -> removeWaiter(action)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: SetupFailure) {
                        fail(error)
                    } catch (_: LinkageError) {
                        fail(SetupFailure("TDLib Java 绑定或原生文件不可用", "请重新构建匹配的原生库并重启应用。", FailureCategory.NATIVE))
                    } catch (_: Exception) {
                        fail(SetupFailure("客户端内部操作失败", "请停止客户端后重试，并检查安全事件记录。", FailureCategory.INTERNAL))
                    } finally {
                        contextGate?.pushCallbackWitness(previousWitness)
                        contextGate?.pushCallbackEmailRevision(previousEmail)
                        // A real receipt from an old epoch may be settled internally above.
                        // Only the current open context may start the follow-up readback.
                        if ((action is Action.SettingsMutationWork || action is Action.LocalGateChanged ||
                                action is Action.InteractionGateChanged) && ordinaryAccountAllowed()) {
                            try { settingsController?.resumePendingWrites() } catch (_: Exception) {
                                fail(SetupFailure("设置状态无法重新确认", "请停止客户端后重新检查设置。", FailureCategory.INTERNAL))
                            }
                        }
                    }
                }
            } finally {
                disposeLocalResumeLease()
                // An abnormal actor exit cannot authorize queued KDF work. The root and
                // keys still remain owned if a native Closed has not been observed.
                if (localCoordinator != null) {
                    stopRequestRevision.incrementAndGet()
                    runCatching { localCoordinator?.actions?.cancel?.invoke() }
                }
                revokeSettingsProxyChecks()
                revokeSettingsStorage()
                closeProfileStickerAvatarMedia()
                windowsNotifications.close()
                scope.cancel()
            }
        }
    }

    private data class LocalActionEnvelope(val action: Action, val witness: LocalLockWitness?,
        val emailRevision: Long?, val terminal: Boolean)

    /** Capture before enqueue; a later unlock never reauthorizes an older actor message. */
    private fun postAction(action: Action): kotlinx.coroutines.channels.ChannelResult<Unit> {
        val gate = session?.interactionGate
        return actions.trySend(LocalActionEnvelope(action,
            gate?.callbackWitness() ?: localCoordinator?.currentWitness(), gate?.callbackEmailRevision(),
            gate?.callbackIsTerminal() == true))
    }

    private fun localOpen(witness: LocalLockWitness?): Boolean {
        val owner = localCoordinator
        return if (owner == null) witness == null else witness != null &&
            stopRequestRevision.get() == pendingStartStopRevision && owner.isCurrentAndOpen(witness)
    }

    private fun localGateWitness(owner: LocalLockCoordinator): LocalLockWitness {
        val current = owner.currentWitness()
        return if (stopRequestRevision.get() == pendingStartStopRevision) current else current.copy(blocked = true)
    }

    private fun publishLocalSnapshot(owner: LocalLockCoordinator) = synchronized(localProjectionLock) {
        if (localCoordinator === owner) {
            val actual = owner.state.value
            val witness = owner.currentWitness()
            val visible = when {
                localInitializationFailed -> LocalLockSnapshot(phase = LocalLockPhase.RECOVERY,
                    issue = LocalLockIssue.IO_FAILURE, epoch = witness.epoch)
                stopRequestRevision.get() != pendingStartStopRevision && actual.phase != LocalLockPhase.RECOVERY ->
                    actual.copy(phase = LocalLockPhase.CHECKING, busy = true)
                witness.blocked && !actual.rootBlocked -> actual.copy(phase = LocalLockPhase.HOT_LOCKED,
                    epoch = witness.epoch, busy = true, managementAuthorized = false, canCreate = false,
                    canManage = false, canChange = false, canRemove = false, canAutoLock = false, canLockNow = false)
                else -> actual
            }
            val lease = synchronized(localResumeLock) { localResumeLease }
            val value = visible.copy(idleFallback = localIdle.availability() != LocalLockIdleAvailability.SYSTEM_SESSION,
                resumeNotificationsUnavailable = visible.enabled &&
                    lease?.availability != LocalLockWindowsResumeAvailability.AVAILABLE)
            mutableLocalLock.value = value
            mutableState.update { it.copy(localLock = value) }
        }
    }

    /** A witness closes before its Engine notification; never turn that gap into an initialization failure. */
    private fun qrStartupLockInterrupted(owner: LocalLockCoordinator): Boolean {
        val ticket = qrStartupTicket ?: return false
        if (owner.state.value.phase == LocalLockPhase.RECOVERY) return false
        val witness = owner.currentWitness()
        if (!qrStartupInterruptedByLock && !witness.blocked && witness.epoch == ticket.epoch) return false
        qrStartupInterruptedByLock = true
        return true
    }

    /** Called synchronously after coordinator witness publication and outside its lock. */
    private fun localWitnessChanged(owner: LocalLockCoordinator, witness: LocalLockWitness) {
        if (localCoordinator !== owner || owner.currentWitness() != witness) return
        if (witness.blocked) {
            if (qrStartupTicket != null && owner.state.value.phase != LocalLockPhase.RECOVERY)
                qrStartupInterruptedByLock = true
            settingsProxyOrdinaryWitness.set(false)
            settingsProxyGateRevision.incrementAndGet()
            notificationLockRevision.incrementAndGet()
            profileEmojiEpoch.incrementAndGet()
            profileStickerAvatarEpoch.incrementAndGet()
        }
        publishLocalSnapshot(owner)
        session?.interactionGate?.localWitnessChanged()
        postAction(Action.LocalGateChanged(owner, witness))
    }

    private fun updateLocalIdle(owner: LocalLockCoordinator) {
        if (localCoordinator !== owner) return
        val value = owner.state.value
        val witness = owner.currentWitness()
        if (localIdleSeconds != null && localIdleSeconds != value.autoLockSeconds) localIdle.recordAppActivity()
        localIdleSeconds = value.autoLockSeconds
        localIdle.update(LocalLockIdleConfig(enabled = value.enabled && localAllowed(),
            hasSession = session?.authorizationReady() == true && mutableState.value.status.authorization == AuthorizationPhase.READY,
            epoch = witness.epoch, timeoutSeconds = value.autoLockSeconds.coerceAtLeast(1)))
        if (value.enabled && localAllowed() && !closeSent && !disposeRequested && session?.authorizationReady() == true &&
            mutableState.value.status.authorization == AuthorizationPhase.READY) ensureLocalResumeSubscription()
        publishLocalSnapshot(owner)
    }

    /** Registration and unregistration are never performed on the actor or under the state lock. */
    private fun ensureLocalResumeSubscription() {
        val factory = localLockResumeHostFactory ?: if (transport is OfficialTdTransport)
            ({ LocalLockWindowsResumeHost.process }) else null
        val ownerRevision = synchronized(localResumeLock) {
            if (localResumeAttempted || localResumeDisposed) return
            localResumeAttempted = true
            localResumeRevision
        }
        val sourceGeneration = generation
        if (factory == null) return
        try {
            localLockResumeDispatch(Runnable {
                val host = try { factory() } catch (_: Throwable) { null }
                if (host == null) {
                    postAction(Action.LocalResumeAvailability)
                    return@Runnable
                }
                val lease = try {
                    host.acquire { signal ->
                        check(postAction(Action.LocalPowerResume(sourceGeneration, signal)).isSuccess) { "Resume owner is closed" }
                    }
                } catch (_: Throwable) { null }
                val ownsHost = lease != null && lease.availability != LocalLockWindowsResumeAvailability.IN_USE
                val keep = synchronized(localResumeLock) {
                    if (localResumeDisposed || localResumeRevision != ownerRevision) false else {
                        localResumeHost = host
                        localResumeLease = lease
                        localResumeOwnsHost = ownsHost
                        true
                    }
                }
                if (!keep) {
                    lease?.close()
                    // Final exit can race both before and after lease revocation. Keep the
                    // completed host for the later final-exit handoff, or close it now if
                    // final exit already won; never unregister before retiring the lease.
                    val closeNow = synchronized(localResumeLock) {
                        if (!ownsHost) false
                        else if (localResumeFinalExit) true
                        else {
                            localResumeHost = host
                            localResumeOwnsHost = true
                            false
                        }
                    }
                    if (closeNow) closeResumeHostAsync(host)
                    else postAction(Action.LocalResumeAvailability)
                } else if (!postAction(Action.LocalResumeAvailability).isSuccess) {
                    disposeLocalResumeLease()
                }
            })
        } catch (_: Throwable) {
            // No lease is available; the next snapshot exposes the separate capability failure.
            postAction(Action.LocalResumeAvailability)
        }
    }

    private fun disposeLocalResumeLease() {
        val lease = synchronized(localResumeLock) {
            localResumeDisposed = true
            localResumeRevision++
            localResumeLease.also { localResumeLease = null }
        }
        lease?.close()
    }

    /** A restart keeps the process registration but retires every old session ticket. */
    private fun retireLocalResumeSession() {
        val lease = synchronized(localResumeLock) {
            if (localResumeDisposed) return
            localResumeRevision++
            localResumeAttempted = false
            localResumeLease.also { localResumeLease = null }
        }
        lease?.close()
    }

    /** Main calls this only for final application exit, never for stop/restart. */
    fun closeLocalLockResumeHostForExit() {
        val host = synchronized(localResumeLock) {
            if (!localResumeDisposed) return
            localResumeFinalExit = true
            if (!localResumeOwnsHost) null else localResumeHost.also {
                localResumeOwnsHost = false
                localResumeHost = null
            }
        }
        if (host != null) closeResumeHostAsync(host)
    }

    private fun closeResumeHostAsync(host: LocalLockWindowsResumeHost) {
        try { localLockResumeDispatch(Runnable { host.close() }) }
        catch (_: Throwable) { /* The process-pinned callback remains safe until process exit. */ }
    }

    /** A capability is projected only for the current session and its current open witness. */
    private fun requestQrCapability(owner: LocalLockCoordinator) {
        if (!startupNeedsKey || localCoordinator !== owner || qrCapabilityPending || closeSent || disposeRequested) return
        val native = session ?: return
        val witness = owner.currentWitness()
        if (!owner.isCurrentAndOpen(witness)) return
        val current = generation
        val stoppedAt = stopRequestRevision.get()
        val serial = ++qrCapabilitySerial
        qrCapabilityPending = true
        try {
            localLockWorker.execute {
                val ticket = try { owner.captureSessionKeyTicket() } catch (_: Throwable) { null }
                postAction(Action.QrCapabilityReady(owner, native, current, stoppedAt, witness, serial, ticket))
            }
        } catch (_: Throwable) { qrCapabilityPending = false }
    }

    /** Old native Closed is already confirmed; retain the root while selection IO is in flight. */
    private fun beginQrPreparation(restart: QrPhoneRestart) {
        val owner = localCoordinator ?: return fail(SetupFailure("无法确认本地保护", "请停止后重新检查保护状态。", FailureCategory.INTERNAL))
        val oldTicket = restart.ticket ?: return
        val rootLease = configuredRootLease ?: return fail(SetupFailure("无法确认数据目录", "请停止后重新检查数据目录。", FailureCategory.DIRECTORY))
        val selection = authenticationSelection ?: return fail(SetupFailure("无法确认登录目录", "请保留原数据并重新检查。", FailureCategory.DIRECTORY))
        qrSwitchWorkRunning = true
        try {
            localLockWorker.execute {
                var renewed: LocalSessionKeyTicket? = null
                var suspended = false
                var failed = false
                try {
                    val leaseRevision = rootOwnershipRevision.get()
                    var expectedEngineRevision = leaseRevision
                    fun engineCurrent() =
                        stopRequestRevision.get() == restart.stopRevision && generation == restart.generation &&
                            rootOwnershipRevision.get() == expectedEngineRevision &&
                            localCoordinator === owner && session == null && !disposed
                    fun current() = owner.admitSessionPreparation(oldTicket, leaseRevision, ::engineCurrent)
                    if (current()) {
                        val rootCurrent = rootLease.isCurrent()
                        if (!rootCurrent) failed = true
                        else if (current()) {
                            val existing = selection.read()
                            if (existing != restart.choice) failed = true
                            else if (current()) {
                                val chosen = try {
                                    if (restart.sawReady) restart.choice else qrSelectionAdvance(selection, restart.choice)
                                } finally {
                                    // A selection commit can succeed even when its caller throws.
                                    expectedEngineRevision = rootOwnershipRevision.incrementAndGet()
                                }
                                if (current()) {
                                    val observed = selection.read()
                                    if (observed != chosen) failed = true
                                    else if (current()) renewed = owner.renewSessionKeyAfterClosed(oldTicket, ::engineCurrent)
                                }
                            }
                        }
                    }
                    if (!failed && renewed == null && stopRequestRevision.get() == restart.stopRevision) {
                        val ticket = owner.captureSessionKeyTicket() ?: oldTicket
                        suspended = owner.suspendSessionStartup(ticket)
                        if (!suspended && owner.state.value.phase != LocalLockPhase.RECOVERY) failed = true
                    }
                } catch (_: Throwable) {
                    // The atomic move may already have happened. Preserve all paths and
                    // require recovery rather than inventing an authoritative choice.
                    failed = true
                }
                postAction(Action.QrPreparationReady(owner, restart, renewed, suspended, failed))
            }
        } catch (_: Throwable) {
            qrSwitchWorkRunning = false
            fail(SetupFailure("无法继续切换登录目录", "请保留原数据并重新检查。", FailureCategory.DIRECTORY))
        }
    }

    private fun suspendQrAfterClosed(owner: LocalLockCoordinator, restart: QrPhoneRestart, ticket: LocalSessionKeyTicket) {
        qrSwitchWorkRunning = true
        try {
            localLockWorker.execute {
                val current = owner.captureSessionKeyTicket() ?: ticket
                val suspended = runCatching { owner.suspendSessionStartup(current) }.getOrDefault(false)
                postAction(Action.QrPreparationReady(owner, restart, null, suspended, !suspended))
            }
        } catch (_: Throwable) {
            qrSwitchWorkRunning = false
            fail(SetupFailure("无法恢复本地保护", "请停止后重新检查保护状态。", FailureCategory.INTERNAL))
        }
    }

    private fun finishQrPreparation(action: Action.QrPreparationReady) {
        qrSwitchWorkRunning = false
        if (localCoordinator !== action.owner) return
        val deferred = qrDeferredStop.also { qrDeferredStop = null }
        if (deferred != null || disposeRequested || stopRequestRevision.get() != action.restart.stopRevision) {
            beginLocalStop(deferred ?: DiagnosticKind.STOPPED_WITHOUT_CLIENT)
            return
        }
        if (action.failed || (!action.suspended && action.ticket == null)) {
            fail(SetupFailure("无法确认新的登录目录", "请保留原数据并重新检查本地保护。", FailureCategory.DIRECTORY))
            return
        }
        pendingStartStopRevision = action.restart.stopRevision
        resetNativeLifecycle()
        mutableState.value = mutableState.value.copy(clientActive = false)
        if (action.suspended) {
            qrStartupTicket = null
            qrStartupRestart = null
            qrStartupInterruptedByLock = false
            mutableState.value = mutableState.value.copy(note = "本地保护已锁定，请解锁后继续。")
            return
        }
        val ticket = checkNotNull(action.ticket)
        qrStartupRestart = action.restart
        startNativeAfterLocalGate(action.owner, ticket.epoch, needsStartupKey = true, sessionTicket = ticket)
        if (session == null && !nativeCreationUncertain) {
            if (stopRequestRevision.get() != action.restart.stopRevision || disposeRequested)
                beginLocalStop(DiagnosticKind.STOPPED_WITHOUT_CLIENT)
            else if (releaseDirectory(keepConfiguredRoot = true))
                suspendQrAfterClosed(action.owner, action.restart, ticket)
        }
    }

    private fun bootstrapNotificationsIfOpen() {
        val owner = localCoordinator ?: return
        if (windowsNotificationsBootstrapped || !localAllowed()) return
        windowsNotificationsBootstrapped = true
        val previous = runCatching { deviceNotificationPreferences.load() }.getOrDefault(DeviceNotificationPreferences())
        windowsNotifications.bootstrap(previous.useWindowsNotifications, toastActivationLaunch)
    }

    private fun dispatchable(entry: LocalActionEnvelope): Boolean {
        if (localOpen(entry.witness) && session?.interactionGate?.acceptsEmailRevision(entry.emailRevision) != false) return true
        return when (val action = entry.action) {
            Action.Start, Action.LocalIdleAvailability, Action.LocalResumeAvailability, Action.NotificationWindowReady,
            is Action.LocalPowerResume,
            is Action.LocalStartReady, is Action.LocalInitializationFailed, is Action.LocalStateChanged, is Action.LocalGateChanged,
            is Action.LocalIdleDue, is Action.LocalStopReady, is Action.LocalParametersHandled, is Action.QrCapabilityReady,
            is Action.QrPreparationReady,
            is Action.OrphanNativeClosed, is Action.QrStartupAbort, is Action.Authorization, is Action.Connection, is Action.ParametersResult,
            is Action.InteractionGateChanged, is Action.CallbackError, is Action.Stop, is Action.RemoveWaiter,
            is Action.ProxyChecksClose -> true
            is Action.ChatInput -> action.cleanup
            is Action.FolderSettingsInput -> action.cleanup
            is Action.StorageInput -> action.cleanup
            is Action.StoragePolicyInput -> action.cleanup
            is Action.SettingsInput -> action.cleanup
            is Action.SecurityInput -> action.cleanup
            is Action.ProfileExtrasInput -> action.cleanup
            is Action.PrivacyCommonInput -> action.cleanup
            is Action.ContactSignupInput -> action.cleanup
            is Action.ReactionNotificationsInput -> action.cleanup
            is Action.NotificationExceptionsInput -> action.cleanup
            is Action.SavedNotificationSoundsInput -> action.cleanup
            is Action.ProfileColorsInput -> action.cleanup
            is Action.LoginEmailInput -> action.cleanup
            is Action.DeviceNotificationInput -> action.localCleanup
            // New TDLib updates delivered during this locked epoch may reconcile in-memory
            // state and terminal attachment leases; native sends and UI composition remain gated.
            // A queued update from an earlier epoch is still discarded.
            is Action.ChatUpdate -> action.event is ChatEvent.SendSucceeded || action.event is ChatEvent.SendFailed ||
                entry.witness?.let { it.blocked && localCoordinator?.currentWitness() == it } == true
            // Only native results for explicitly admitted message mutations get this path.
            is Action.ChatWork -> entry.terminal
            // This action is created only by SettingsController.mutate's own completion.
            is Action.SettingsMutationWork -> true
            // Only original FolderSettings completions and local deadlines construct this action.
            is Action.FolderTerminalWork -> true
            is Action.SettingsSpecialTerminalWork, is Action.SettingsSpecialReadbackWork -> true
            else -> false
        }
    }

    private fun discardLocalAction(action: Action) {
        when (action) {
            is Action.LoginInput -> action.takeValue()
            is Action.ChatInput -> action.onDiscard()
            is Action.StorageInput -> action.onDiscard()
            is Action.StoragePolicyInput -> action.discard()
            is Action.UsernameInput -> action.discard()
            is Action.SecurityInput -> action.discard()
            is Action.ProfileExtrasInput -> action.discard()
            is Action.SavedNotificationSoundsInput -> action.discard()
            is Action.LoginEmailInput -> action.discard()
            is Action.ProfileEmojiArtworkWork -> action.discard()
            is Action.ProfileStickerAvatarMediaWork -> action.bridge.discard()
            is Action.SavedProfileStickerMediaWork -> action.bridge.reject()
            is Action.ProxyChecksOpen -> { closeSettingsProxyChecksPage(action.page); action.opened(null) }
            is Action.ProxyChecksOpened -> { closeSettingsProxyChecksPage(action.page); action.opened(null) }
            is Action.ProxyChecksRefresh -> closeSettingsProxyChecksPage(action.page)
            is Action.ProxyChecksShare -> action.result(SettingsResult(error = SettingsError.NOT_READY))
            else -> Unit
        }
    }

    /** Returns whether the request was accepted, not whether asynchronous initialization succeeded. */
    fun start(): Boolean = !disposed && postAction(Action.Start).isSuccess

    /** Input stays in the transient command; snapshots and diagnostic events never contain it. */
    fun submitLogin(token: LoginToken, kind: LoginRequestKind, value: String): Boolean {
        if (kind == LoginRequestKind.RESET_PHONE) return cancelQrLogin(token)
        val snapshot = state.value
        if (disposed || !localAllowed() || snapshot.loginToken != token || !snapshot.canSubmitLogin(kind)) return false
        val action = Action.LoginInput(token, kind, value)
        if (postAction(action).isSuccess) return true
        action.takeValue()
        return false
    }

    fun cancelQrLogin(token: LoginToken): Boolean = !disposed && localAllowed() && state.value.loginToken == token &&
        state.value.canSubmitLogin(LoginRequestKind.RESET_PHONE) && postAction(Action.CancelQr(token)).isSuccess

    private fun cancelQrOnActor(token: LoginToken) {
        val snapshot = mutableState.value
        if (closeSent || disposeRequested || session == null || !localAllowed() || snapshot.loginToken != token ||
            !snapshot.canSubmitLogin(LoginRequestKind.RESET_PHONE)) return
        val choice = authenticationChoice ?: return
        val ticket = if (startupNeedsKey) {
            val owner = localCoordinator ?: return
            val captured = owner.captureSessionKeyTicket() ?: return
            if (captured !== qrSessionTicket || !owner.isCurrentAndOpen(owner.currentWitness()) ||
                captured.epoch != owner.currentWitness().epoch) return
            captured
        } else null
        qrSessionTicket = null
        mutableState.update { it.copy(loginQrPhoneSwitchAvailable = false) }
        qrPhoneRestart = QrPhoneRestart(generation, stopRequestRevision.get(), choice, ticket)
        invalidateLogin()
        clearChats()
        updateEngine(EnginePhase.CLOSING)
        mutableState.value = mutableState.value.copy(note = "正在关闭二维码登录，随后切换手机号登录。")
        // Close preserves a concurrently authorized account. Never LogOut or destroy its data.
        requestClose()
    }

    fun selectChat(token: ChatToken, chatId: Long) = chatInput(token) { it.select(token, chatId) }
    fun openSettingsSupport(token: ChatToken, active: () -> Boolean,
        completed: (SettingsResult<ChatToken>) -> Unit,
    ): Boolean {
        val delivered = java.util.concurrent.atomic.AtomicBoolean()
        val finish: (SettingsResult<ChatToken>) -> Unit = {
            if (delivered.compareAndSet(false, true)) completed(it)
        }
        val rejected = { finish(SettingsResult(error = SettingsError.NOT_READY)) }
        if (!active()) { rejected(); return false }
        val admitted = chatInput(token, onDiscard = rejected) {
            if (active()) it.openSupport(token, { active() && ordinaryAccountAllowed() }, finish)
            else rejected()
        }
        if (!admitted) rejected()
        return admitted
    }
    fun openSettingsStorage(active: () -> Boolean): Boolean = openSettingsStorageOwned(active) != null

    /** A page close revokes the same owner used at the final native send boundary. */
    fun openSettingsStorageOwned(active: () -> Boolean): SettingsStorageHandle? =
        openSettingsStorageOwnedObserved(active) {}

    fun openSettingsStorageOwnedObserved(active: () -> Boolean,
        onStatus: (SettingsStorageToken?) -> Unit,
    ): SettingsStorageHandle? {
        val native = session ?: return null
        val controller = storageController ?: return null
        val generation = state.value.generation
        val stoppedAt = settingsProxySessionStopRevision
        if (disposed || stopRequestRevision.get() != stoppedAt || !ordinaryAccountAllowed() ||
            !runCatching(active).getOrDefault(false)) return null
        val gateRevision = settingsProxyGateRevision
        val lockedAt = gateRevision.get()
        val ordinary = settingsProxyOrdinaryWitness
        val owner = SettingsProxyProbeOwner {
            val value = mutableState.value
            session === native && !disposed && stopRequestRevision.get() == stoppedAt &&
                gateRevision.get() == lockedAt && ordinary.get() && native.authorizationReady() &&
                value.generation == generation && value.clientActive && value.status.authorization == AuthorizationPhase.READY &&
                runCatching(active).getOrDefault(false)
        }
        val page = SettingsStorageEnginePage(generation, native, controller, owner, onStatus,
            scope, settingsStorageStatusDispatcher)
        settingsStoragePages[owner] = page
        val handle = object : SettingsStorageHandle {
            override fun close() = closeSettingsStoragePage(page)
            override fun current(): Boolean = settingsStoragePages[page.owner] === page && page.owner.current()
        }
        // A concurrent stop/lock cannot miss a visit registered after its first scan.
        if (!canUseSettingsStorage(page) || !storageInput(generation, onDiscard = {
                closeSettingsStoragePage(page, notifyRejected = true)
            }) {
                if (it === controller && canUseSettingsStorage(page)) {
                    it.openOwned(owner)
                    revokeExpiredSettingsStorage()
                    val accepted = if (canUseSettingsStorage(page)) it.ownedToken(owner) else null
                    if (accepted == null) {
                        closeSettingsStoragePage(page, notifyRejected = true)
                    } else page.accepted(accepted)
                }
                else closeSettingsStoragePage(page, notifyRejected = true)
            }) {
            closeSettingsStoragePage(page, notifyRejected = true)
            return null
        }
        return handle
    }

    private fun canUseSettingsStorage(page: SettingsStorageEnginePage): Boolean =
        settingsStoragePages[page.owner] === page && session === page.session && storageController === page.controller &&
            stopRequestRevision.get() == settingsProxySessionStopRevision && ordinaryAccountAllowed() && page.owner.current()

    private fun closeSettingsStoragePage(page: SettingsStorageEnginePage, notifyRejected: Boolean = false) {
        page.owner.revoke()
        if (settingsStoragePages.remove(page.owner, page))
            postAction(Action.StorageInput(page.generation, { if (it === page.controller) it.closeOwned(page.owner) }, cleanup = true))
        page.finish(notifyRejected)
    }

    private fun revokeSettingsStorage(ownerGeneration: Long? = null) {
        settingsStoragePages.values.forEach { page ->
            if (ownerGeneration == null || page.generation == ownerGeneration) closeSettingsStoragePage(page, notifyRejected = true)
        }
    }

    private fun revokeExpiredSettingsStorage() {
        settingsStoragePages.values.forEach { page -> if (!page.owner.current()) closeSettingsStoragePage(page, notifyRejected = true) }
    }
    fun closeSettingsStorage(token: SettingsStorageToken): Boolean = storageInput(token.generation, cleanup = true) {
        it.close(token)
        revokeExpiredSettingsStorage()
    }
    fun refreshSettingsStorage(token: SettingsStorageToken): Boolean = storageInput(token.generation) { it.refresh(token) }
    fun refreshSettingsStorageVolume(token: SettingsStorageToken): Boolean = storageInput(token.generation) { it.refreshVolume(token) }
    fun clearSettingsStorage(token: SettingsStorageToken, revision: Long, types: Set<SettingsStorageFileType>): Boolean =
        storageInput(token.generation) { it.clear(token, revision, types) }
    private fun storageInput(tokenGeneration: Long, cleanup: Boolean = false, onDiscard: () -> Unit = {},
        work: (SettingsStorageController) -> Unit,
    ): Boolean {
        if (disposed || closeSent || disposeRequested || !current(tokenGeneration) || (!cleanup && !ordinaryAccountAllowed())) return false
        return postAction(Action.StorageInput(tokenGeneration, work, cleanup, onDiscard)).isSuccess
    }
    fun openSettingsStoragePolicy(tokenGeneration: Long, active: () -> Boolean): Boolean =
        active() && storagePolicyInput(tokenGeneration) { it.open(active) }
    fun closeSettingsStoragePolicy(token: SettingsStoragePolicyToken): Boolean =
        storagePolicyInput(token.generation, cleanup = true) { it.close(token) }
    fun refreshSettingsStoragePolicy(token: SettingsStoragePolicyToken): Boolean =
        storagePolicyInput(token.generation) { it.refresh(token) }
    fun saveSettingsStoragePolicy(token: SettingsStoragePolicyToken, revision: Long,
        draft: SettingsStoragePolicyDraft, requestId: Long, completed: (SettingsStoragePolicyCompletion) -> Unit,
    ): Boolean {
        val delivered = java.util.concurrent.atomic.AtomicBoolean()
        val finish: (SettingsStoragePolicyCompletion) -> Unit = {
            if (delivered.compareAndSet(false, true)) completed(it)
        }
        return storagePolicyInput(token.generation,
            discard = { finish(SettingsStoragePolicyCompletion.rejected(token, requestId)) }) {
            it.save(token, revision, draft, requestId, finish)
        }
    }
    private fun storagePolicyInput(tokenGeneration: Long, cleanup: Boolean = false, discard: () -> Unit = {},
        work: (SettingsStoragePolicyController) -> Unit,
    ): Boolean {
        if (disposed || closeSent || disposeRequested || !current(tokenGeneration) || (!cleanup && !ordinaryAccountAllowed())) {
            discard()
            return false
        }
        val accepted = postAction(Action.StoragePolicyInput(tokenGeneration, work, discard, cleanup)).isSuccess
        if (!accepted) discard()
        return accepted
    }
    fun saveDeviceNotifications(tokenGeneration: Long, value: DeviceNotificationPreferences) =
        postAction(Action.DeviceNotificationInput(tokenGeneration) { it.save(tokenGeneration, value) }).isSuccess
    fun activateDeviceNotification(identity: DeviceNotificationIdentity) =
        postAction(Action.DeviceNotificationInput(identity.generation, allowLocked = true) { it.activate(identity) }).isSuccess
    fun dismissDeviceNotification(identity: DeviceNotificationIdentity) =
        postAction(Action.DeviceNotificationInput(identity.generation) { it.dismiss(identity) }).isSuccess
    fun expireDeviceNotification(identity: DeviceNotificationIdentity) =
        postAction(Action.DeviceNotificationInput(identity.generation, allowLocked = true, localCleanup = true) { it.expire(identity) }).isSuccess
    fun deviceNotificationContext(tokenGeneration: Long, foreground: Boolean, activeChatId: Long?, locked: Boolean) =
        postAction(Action.DeviceNotificationInput(tokenGeneration, allowLocked = true, localCleanup = true) {
            notificationForeground = foreground
            notificationActiveChatId = activeChatId
            notificationUiLocked = locked
            it.setContext(foreground, activeChatId.takeIf { ordinaryAccountAllowed() }, locked || !ordinaryAccountAllowed())
        }).isSuccess
    fun selectWindowsNotifications(tokenGeneration: Long, enabled: Boolean) =
        postAction(Action.DeviceNotificationInput(tokenGeneration) { it.selectWindows(tokenGeneration, enabled) }).isSuccess
    fun removeWindowsNotificationRegistration(tokenGeneration: Long) =
        postAction(Action.DeviceNotificationInput(tokenGeneration) { it.removeWindowsRegistration(tokenGeneration) }).isSuccess
    fun notificationWindowAvailable() = postAction(Action.NotificationWindowReady).isSuccess
    fun openSettingsLoginEmail(tokenGeneration: Long, openingId: Long, kind: LoginEmailEntryKind, pageLease: () -> Boolean) =
        loginEmailInput(tokenGeneration) { it.open(tokenGeneration, openingId, kind, pageLease) }
    fun closeSettingsLoginEmail(token: LoginEmailSetupToken) = loginEmailInput(token.generation, cleanup = true) { it.close(token) }
    fun submitSettingsLoginEmail(token: LoginEmailSetupToken, email: String, result: (LoginEmailOutcome) -> Unit) =
        loginEmailInput(token.generation, discard = { result(LoginEmailOutcome(issue = LoginEmailIssue.NOT_AVAILABLE)) }) {
            it.submitEmail(token, email, result)
        }
    fun resendSettingsLoginEmail(challenge: LoginEmailChallengeToken, result: (LoginEmailOutcome) -> Unit) =
        loginEmailInput(challenge.owner.generation, discard = { result(LoginEmailOutcome(issue = LoginEmailIssue.NOT_AVAILABLE)) }) {
            it.resend(challenge, result)
        }
    fun checkSettingsLoginEmail(challenge: LoginEmailChallengeToken, code: String, result: (LoginEmailOutcome) -> Unit) =
        loginEmailInput(challenge.owner.generation, discard = { result(LoginEmailOutcome(issue = LoginEmailIssue.NOT_AVAILABLE)) }) {
            it.check(challenge, code, result)
        }
    fun refreshSettingsLoginEmailProof(token: LoginEmailSetupToken) = loginEmailInput(token.generation) { it.refreshProof(token) }
    fun hideOptionalSettingsLoginEmail(tokenGeneration: Long, epoch: Long) = loginEmailInput(tokenGeneration) { it.hideOptional(tokenGeneration, epoch) }
    private fun loginEmailInput(tokenGeneration: Long, cleanup: Boolean = false, discard: () -> Unit = {},
        work: (LoginEmailController) -> Unit,
    ): Boolean {
        if (disposed || !canUseLoginEmail(tokenGeneration, cleanup)) { discard(); return false }
        val accepted = postAction(Action.LoginEmailInput(tokenGeneration, work, discard, cleanup)).isSuccess
        if (!accepted) discard()
        return accepted
    }
    private fun canUseLoginEmail(tokenGeneration: Long, cleanup: Boolean): Boolean {
        val snapshot = mutableState.value
        return !closeSent && !disposeRequested && current(tokenGeneration) &&
            (cleanup || localAllowed() && snapshot.status.authorization == AuthorizationPhase.READY &&
                snapshot.status.engine == EnginePhase.INITIALIZED && snapshot.proxy in setOf(ProxyStatus.DIRECT, ProxyStatus.ENABLED))
    }
    fun loadSettings(token: SettingsToken, section: SettingsSection): Boolean {
        if (section == SettingsSection.ADVANCED) revokeSettingsProxyShares(token)
        return settingsInput(token) { it.load(token, section) }
    }
    fun openSettingsProfileColors(token: SettingsToken, userId: Long, active: () -> Boolean): Boolean {
        if (!runCatching(active).getOrDefault(false)) return false
        // The child passes only this external UI lease and captured atomics to native workers.
        // Native READY/mandatory checks stay at public/actor admission and ordinarySend.
        return settingsInput(token) { if (runCatching(active).getOrDefault(false)) it.openProfileColors(token, userId, active) }
    }
    fun closeSettingsProfileColors(token: SettingsProfileColorsToken) =
        profileColorsInput(token, cleanup = true) { it.closeProfileColors(token) }
    fun refreshSettingsProfileColors(token: SettingsProfileColorsToken) =
        profileColorsInput(token) { it.refreshProfileColors(token) }
    fun saveSettingsProfileColors(token: SettingsProfileColorsToken, edit: SettingsProfileColorEdit) =
        profileColorsInput(token) { it.saveProfileColors(token, edit) }
    fun openSettingsProfileColorBackgrounds(token: SettingsProfileColorsToken) =
        profileColorsInput(token) { it.openProfileColorBackgrounds(token) }
    fun resolveSettingsProfileColorBackgrounds(token: SettingsProfileColorsToken, ids: List<Long>): Boolean {
        if (ids.size > 200 || ids.any { it == 0L }) return false
        val requested = ids.toList()
        return profileColorsInput(token) { it.resolveProfileColorBackgrounds(token, requested) }
    }
    fun closeSettingsProfileColorBackgrounds(token: SettingsProfileColorsToken) =
        profileColorsInput(token, cleanup = true) { it.closeProfileColorBackgrounds(token) }
    private fun profileColorsInput(token: SettingsProfileColorsToken, cleanup: Boolean = false,
        work: (SettingsController) -> Unit,
    ): Boolean {
        if (disposed || closeSent || disposeRequested || !current(token.generation) || (!cleanup && !ordinaryAccountAllowed()) ||
            mutableState.value.settings.profileColors.token != token) return false
        return postAction(Action.ProfileColorsInput(token, work, cleanup)).isSuccess
    }
    fun openSettingsContactSignup(token: SettingsToken, active: () -> Boolean): Boolean {
        val guarded = { active() && current(token.generation) && !closeSent && !disposeRequested && ordinaryAccountAllowed() }
        return guarded() && settingsInput(token) { it.openContactSignup(token, guarded) }
    }
    fun openSettingsReactionNotifications(token: SettingsToken, active: () -> Boolean): Boolean {
        if (!runCatching(active).getOrDefault(false)) return false
        return settingsInput(token) {
            if (runCatching(active).getOrDefault(false)) it.openReactionNotifications(token, active)
        }
    }
    fun closeSettingsReactionNotifications(token: ReactionNotificationsToken) =
        reactionNotificationsInput(token, cleanup = true) { it.closeReactionNotifications(token) }
    fun refreshSettingsReactionNotificationsFromCache(token: ReactionNotificationsToken) =
        reactionNotificationsInput(token) { it.refreshReactionNotificationsFromCache(token) }
    fun saveSettingsReactionNotifications(token: ReactionNotificationsToken, edit: ReactionNotificationsEdit) =
        reactionNotificationsInput(token) { it.saveReactionNotifications(token, edit) }
    private fun reactionNotificationsInput(token: ReactionNotificationsToken, cleanup: Boolean = false,
        work: (SettingsController) -> Unit,
    ): Boolean {
        if (disposed || closeSent || disposeRequested || !current(token.generation) ||
            (!cleanup && !ordinaryAccountAllowed()) || mutableState.value.settings.reactionNotifications.token != token) return false
        return postAction(Action.ReactionNotificationsInput(token, work, cleanup)).isSuccess
    }
    fun openSettingsSavedNotificationSounds(token: SettingsToken, active: () -> Boolean): Boolean {
        val guarded = { active() && current(token.generation) && !closeSent && !disposeRequested && ordinaryAccountAllowed() }
        return guarded() && settingsInput(token) { it.openSavedNotificationSounds(token, guarded) }
    }
    fun closeSettingsSavedNotificationSounds(token: SavedNotificationSoundsToken) =
        savedNotificationSoundsInput(token, cleanup = true) { it.closeSavedNotificationSounds(token) }
    fun refreshSettingsSavedNotificationSounds(token: SavedNotificationSoundsToken) =
        savedNotificationSoundsInput(token) { it.refreshSavedNotificationSounds(token) }
    fun chooseSettingsSavedNotificationSound(token: SavedNotificationSoundsToken, revision: Long, soundId: Long,
        result: (SettingsResult<Long>) -> Unit,
    ): Boolean {
        val once = java.util.concurrent.atomic.AtomicBoolean()
        fun finish(value: SettingsResult<Long>) { if (once.compareAndSet(false, true)) result(value) }
        return savedNotificationSoundsInput(token, discard = { finish(SettingsResult(error = SettingsError.NOT_READY)) }) {
            it.chooseSavedNotificationSound(token, revision, soundId, ::finish)
        }
    }
    fun previewSettingsSavedNotificationSound(token: SavedNotificationSoundsToken, soundId: Long, volume: Int) =
        savedNotificationSoundsInput(token) { it.previewSavedNotificationSound(token, soundId, volume) }
    fun stopSettingsSavedNotificationSoundPreview(token: SavedNotificationSoundsToken) =
        savedNotificationSoundsInput(token, cleanup = true) { it.stopSavedNotificationSoundPreview(token) }
    fun readSettingsNotificationSoundVolume(token: SavedNotificationSoundsToken, target: NotificationSoundVolumeTarget,
        result: (SettingsResult<NotificationSoundVolumeReading>) -> Unit,
    ): Boolean {
        val once = java.util.concurrent.atomic.AtomicBoolean()
        fun finish(value: SettingsResult<NotificationSoundVolumeReading>) { if (once.compareAndSet(false, true)) result(value) }
        return savedNotificationSoundsInput(token, discard = { finish(SettingsResult(error = SettingsError.NOT_READY)) }) {
            it.readSavedNotificationSoundVolume(token, target, ::finish)
        }
    }
    fun saveSettingsNotificationSoundVolume(token: SavedNotificationSoundsToken, expected: NotificationSoundVolumeReading,
        percent: Int?, result: (SettingsResult<NotificationSoundVolumeReading>) -> Unit,
    ): Boolean {
        val once = java.util.concurrent.atomic.AtomicBoolean()
        fun finish(value: SettingsResult<NotificationSoundVolumeReading>) { if (once.compareAndSet(false, true)) result(value) }
        return savedNotificationSoundsInput(token, discard = { finish(SettingsResult(error = SettingsError.NOT_READY)) }) {
            it.saveSavedNotificationSoundVolume(token, expected, percent, ::finish)
        }
    }
    fun uploadSettingsSavedNotificationSound(token: SavedNotificationSoundsToken, revision: Long,
        prepared: SettingsPreparedNotificationSound,
    ): Boolean {
        val retained = attachmentSendLeases.retain(prepared.lease) ?: return false
        return savedNotificationSoundsInput(token, discard = retained::release) {
            it.uploadSavedNotificationSound(token, revision, prepared, retained)
        }
    }
    fun removeSettingsSavedNotificationSound(token: SavedNotificationSoundsToken, revision: Long, expected: SavedNotificationSound) =
        savedNotificationSoundsInput(token) { it.removeSavedNotificationSound(token, revision, expected) }
    private fun savedNotificationSoundsInput(token: SavedNotificationSoundsToken, discard: () -> Unit = {}, cleanup: Boolean = false,
        work: (SettingsController) -> Unit,
    ): Boolean {
        if (disposed || closeSent || disposeRequested || !current(token.generation) || (!cleanup && !ordinaryAccountAllowed()) ||
            mutableState.value.settings.savedNotificationSounds.token != token) { discard(); return false }
        val queued = postAction(Action.SavedNotificationSoundsInput(token, work, discard, cleanup)).isSuccess
        if (!queued) discard()
        return queued
    }
    fun openSettingsNotificationExceptions(token: SettingsToken, scope: NotificationScope, active: () -> Boolean): Boolean {
        val guarded = { active() && current(token.generation) && !closeSent && !disposeRequested && ordinaryAccountAllowed() }
        return guarded() && settingsInput(token) { it.openNotificationExceptions(token, scope, guarded) }
    }
    fun closeSettingsNotificationExceptions(token: NotificationExceptionsToken) =
        notificationExceptionsInput(token, cleanup = true) { it.closeNotificationExceptions(token) }
    fun refreshSettingsNotificationExceptions(token: NotificationExceptionsToken) =
        notificationExceptionsInput(token) { it.refreshNotificationExceptions(token) }
    fun moreSettingsNotificationExceptions(token: NotificationExceptionsToken) =
        notificationExceptionsInput(token) { it.moreNotificationExceptions(token) }
    fun selectSettingsNotificationException(token: NotificationExceptionsToken) =
        notificationExceptionsInput(token) { it.selectNotificationException(token) }
    fun searchSettingsNotificationExceptions(token: NotificationExceptionsToken, query: String, remote: Boolean) =
        notificationExceptionsInput(token) { it.searchNotificationExceptions(token, query, remote) }
    fun editSettingsNotificationException(token: NotificationExceptionsToken, chatId: Long) =
        notificationExceptionsInput(token) { it.editNotificationException(token, chatId) }
    fun backSettingsNotificationExceptions(token: NotificationExceptionsToken) =
        notificationExceptionsInput(token) { it.backNotificationExceptions(token) }
    fun reloadSettingsNotificationException(token: NotificationExceptionsToken) =
        notificationExceptionsInput(token) { it.reloadNotificationException(token) }
    fun saveSettingsNotificationException(token: NotificationExceptionsToken, revision: Long, edit: NotificationExceptionEdit) =
        notificationExceptionsInput(token) { it.saveNotificationException(token, revision, edit) }
    fun prepareClearSettingsNotificationExceptions(token: NotificationExceptionsToken) =
        notificationExceptionsInput(token) { it.prepareClearNotificationExceptions(token) }
    fun cancelClearSettingsNotificationExceptions(token: NotificationExceptionsToken) =
        notificationExceptionsInput(token) { it.cancelClearNotificationExceptions(token) }
    fun clearSettingsNotificationExceptions(token: NotificationExceptionsToken, planId: Long) =
        notificationExceptionsInput(token) { it.clearNotificationExceptions(token, planId) }
    private fun notificationExceptionsInput(token: NotificationExceptionsToken, cleanup: Boolean = false,
        work: (SettingsController) -> Unit,
    ): Boolean {
        if (disposed || closeSent || disposeRequested || !current(token.generation) || (!cleanup && !ordinaryAccountAllowed()) ||
            mutableState.value.settings.notificationExceptions.token != token) return false
        return postAction(Action.NotificationExceptionsInput(token, work, cleanup)).isSuccess
    }
    fun closeSettingsContactSignup(token: ContactSignupToken) = contactSignupInput(token, cleanup = true) { it.closeContactSignup(token) }
    fun refreshSettingsContactSignup(token: ContactSignupToken) = contactSignupInput(token) { it.refreshContactSignup(token) }
    fun saveSettingsContactSignup(token: ContactSignupToken, revision: Long, expected: ContactSignupValue, desired: Boolean) =
        contactSignupInput(token) { it.saveContactSignup(token, revision, expected, desired) }
    private fun contactSignupInput(token: ContactSignupToken, cleanup: Boolean = false,
        work: (SettingsController) -> Unit,
    ): Boolean {
        if (disposed || closeSent || disposeRequested || !current(token.generation) || (!cleanup && !ordinaryAccountAllowed()) ||
            mutableState.value.settings.contactSignup.token != token) return false
        return postAction(Action.ContactSignupInput(token, work, cleanup)).isSuccess
    }
    fun openSettingsPrivacyCommon(token: SettingsToken, active: () -> Boolean) =
        active() && settingsInput(token) { it.openPrivacyCommon(token, active) }
    fun closeSettingsPrivacyCommon(token: PrivacyCommonToken) = privacyCommonInput(token, cleanup = true) { it.closePrivacyCommon(token) }
    fun refreshSettingsPrivacyCommon(token: PrivacyCommonToken) = privacyCommonInput(token) { it.refreshPrivacyCommon(token) }
    fun saveSettingsTopPeers(token: PrivacyCommonToken, expected: Boolean, desired: Boolean) =
        privacyCommonInput(token) { it.setPrivacyTopPeers(token, expected, desired) }
    fun saveSettingsArchivePrivacy(token: PrivacyCommonToken, expected: PrivacyArchiveSettings, desired: PrivacyArchiveSettings) =
        privacyCommonInput(token) { it.setPrivacyArchive(token, expected, desired) }
    fun saveSettingsAccountTtl(token: PrivacyCommonToken, expected: Int, desired: Int) =
        privacyCommonInput(token) { it.setPrivacyAccountTtl(token, expected, desired) }
    private fun privacyCommonInput(token: PrivacyCommonToken, cleanup: Boolean = false, work: (SettingsController) -> Unit): Boolean {
        val snapshot = mutableState.value
        if (disposed || closeSent || disposeRequested || (!cleanup && !ordinaryAccountAllowed()) ||
            snapshot.settings.privacyCommon.token != token) return false
        return postAction(Action.PrivacyCommonInput(token, work, cleanup)).isSuccess
    }
    fun saveSettingsProfile(token: SettingsToken, edit: SettingsProfileEdit) = settingsInput(token) { it.saveProfile(token, edit) }
    fun checkSettingsUsernameAvailability(token: SettingsToken, userId: Long, username: String, active: () -> Boolean,
        result: (SettingsResult<SettingsUsernameAvailability>) -> Unit,
    ): Boolean {
        val stopAtRequest = stopRequestRevision.get()
        val stops = stopRequestRevision
        val replied = java.util.concurrent.atomic.AtomicBoolean()
        fun current() = stops.get() == stopAtRequest && runCatching(active).getOrDefault(false)
        fun finish(response: SettingsResult<SettingsUsernameAvailability>) {
            if (replied.compareAndSet(false, true)) runCatching {
                result(if (current()) response else SettingsResult(error = SettingsError.NOT_READY))
            }
        }
        fun discard() = finish(SettingsResult(error = SettingsError.NOT_READY))
        if (userId <= 0 || username.length > 32) {
            finish(SettingsResult(error = SettingsError.INVALID_VALUE)); return false
        }
        if (!current() || disposed || !canUseSettings(token)) { discard(); return false }
        val accepted = postAction(Action.UsernameInput(token, { controller ->
            if (current()) controller.checkUsername(token, userId, username, ::current, ::finish) else discard()
        }, ::discard)).isSuccess
        if (!accepted) discard()
        return accepted
    }
    fun openSettingsProfileExtras(token: SettingsToken, userId: Long, pageLease: () -> Boolean = { true }) =
        pageLease() && settingsInput(token) { it.openProfileExtras(token, userId, pageLease) }
    fun closeSettingsProfileExtras(token: SettingsProfileExtrasToken) = profileExtrasInput(token, cleanup = true) { it.closeProfileExtras(token) }
    fun refreshSettingsProfileExtras(token: SettingsProfileExtrasToken) = profileExtrasInput(token) { it.refreshProfileExtras(token) }
    fun saveSettingsProfileBirthdate(token: SettingsProfileExtrasToken, expected: SettingsBirthdate?, desired: SettingsBirthdate?) =
        profileExtrasInput(token) { it.saveProfileBirthdate(token, expected, desired) }
    fun uploadSettingsProfilePhoto(token: SettingsProfileExtrasToken, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto): Boolean {
        // The editor can leave composition immediately after this method returns.
        val retained = attachmentSendLeases.retain(photo.lease) ?: return false
        return profileExtrasInput(token, retained::release) { it.uploadProfilePhoto(token, expectedPhotoId, photo, retained) }
    }
    fun uploadSettingsProfileAnimation(token: SettingsProfileExtrasToken, expectedPhotoId: Long?, animation: SettingsPreparedProfileAnimation,
        admitted: (Long?) -> Unit = {},
    ): Boolean {
        val replied = java.util.concurrent.atomic.AtomicBoolean()
        fun reply(value: Long?) { if (replied.compareAndSet(false, true)) runCatching { admitted(value) } }
        val retained = attachmentSendLeases.retain(animation.lease) ?: run { reply(null); return false }
        val validation = animation.lease.retain() ?: run { retained.release(); reply(null); return false }
        // Confirmed native Closed may release retained while a local ffprobe still owns validation.
        return profileExtrasInput(token, discard = { retained.release(); validation.release(); reply(null) }) {
            reply(it.uploadProfileAnimation(token, expectedPhotoId, animation, retained, validation))
        }
    }
    fun removeSettingsProfilePhoto(token: SettingsProfileExtrasToken, photoId: Long) =
        profileExtrasInput(token) { it.removeProfilePhoto(token, photoId) }
    fun openSettingsCurrentProfilePhoto(token: SettingsProfileExtrasToken, view: () -> Boolean,
        admitted: (Long?) -> Unit = {},
    ): Boolean {
        val done = java.util.concurrent.atomic.AtomicBoolean()
        fun reply(epoch: Long?) { if (done.compareAndSet(false, true)) runCatching { admitted(epoch) } }
        val current = currentPhotoNativeLease(view)
        if (!current()) { reply(null); return false }
        return profileExtrasInput(token, discard = { reply(null) }) {
            reply(if (current()) runCatching { it.openCurrentProfilePhoto(token, current) }.getOrNull() else null)
        }
    }
    fun closeSettingsCurrentProfilePhoto(token: SettingsProfileExtrasToken, epoch: Long): Boolean =
        epoch >= 0 && profileExtrasInput(token, cleanup = true) { it.closeCurrentProfilePhoto(token, epoch) }
    fun refreshSettingsCurrentProfilePhoto(token: SettingsProfileExtrasToken, epoch: Long,
        admitted: (Long?) -> Unit = {},
    ): Boolean {
        val done = java.util.concurrent.atomic.AtomicBoolean()
        fun reply(next: Long?) { if (done.compareAndSet(false, true)) runCatching { admitted(next) } }
        if (epoch <= 0) { reply(null); return false }
        return profileExtrasInput(token, discard = { reply(null) }) { reply(runCatching { it.refreshCurrentProfilePhoto(token, epoch) }.getOrNull()) }
    }
    fun acquireSettingsCurrentProfilePhotoFile(request: SettingsCurrentProfilePhotoMediaRequest, active: () -> Boolean,
        result: (SettingsResult<MediaFile>) -> Unit,
    ): SettingsCurrentProfilePhotoFileLease {
        val snapshots = mutableState
        val bridge = CurrentProfilePhotoFileBridge(currentPhotoNativeLease {
            val settings = snapshots.value.settings
            val current = settings.profileExtras
            settings.enabled && current.token == request.parent && current.currentPhoto.epoch == request.epoch &&
                current.currentPhoto.status == SettingsCurrentProfilePhotoStatus.READY &&
                current.currentPhoto.photo?.photoId == request.photoId && runCatching(active).getOrDefault(false)
        }, result)
        if (!bridge.current()) {
            bridge.close(); return bridge
        }
        profileExtrasInput(request.parent, discard = bridge::close) {
            if (!bridge.current()) bridge.close()
            else bridge.bind(it.acquireCurrentProfilePhotoFile(request, bridge::sourceCurrent, bridge::finish))
        }
        return bridge
    }
    private fun currentPhotoNativeLease(view: () -> Boolean): () -> Boolean {
        val owner = session
        val stopAtRequest = stopRequestRevision.get(); val lockAtRequest = notificationLockRevision.get()
        val stops = stopRequestRevision; val locks = notificationLockRevision; val states = mutableState
        return {
            owner != null && stops.get() == stopAtRequest && locks.get() == lockAtRequest &&
                owner.authorizationReady() && (owner.interactionGate?.allowsOrdinary() ?: !states.value.loginEmail.mandatory) &&
                runCatching(view).getOrDefault(false)
        }
    }

    /** Only the actor can turn this photo selection into a private native source grant. */
    internal fun acquireSettingsSavedProfileStickerAvatarMedia(request: SettingsSavedProfileStickerAvatarRequest,
        active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit,
    ): SettingsSavedProfileStickerAvatarLease = acquireSettingsSavedProfileStickerAvatarMediaOwned(
        request, active, SettingsSavedProfileStickerAvatarDemand(), result)

    internal fun acquireSettingsSavedProfileStickerAvatarMediaOwned(request: SettingsSavedProfileStickerAvatarRequest,
        active: () -> Boolean, demand: SettingsSavedProfileStickerAvatarDemand,
        result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit,
    ): SettingsSavedProfileStickerAvatarLease {
        val environment = mutableProfileStickerAvatarMedia.value
        if (environment == null) {
            runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
            return object : SettingsSavedProfileStickerAvatarLease {
                override fun requestCurrent() = false
                override fun close() {}
            }
        }
        val snapshots = mutableState
        val controller = settingsController // volatile publication of the actor-owned controller
        val owner = session
        val epochWitness = controller?.savedStickerEpochWitness(request)
        val extrasAtRequest = snapshots.value.settings.profileExtras
        val nativeSource: SettingsSavedProfileStickerNativeSource? = if (extrasAtRequest.token != request.parent) null else when (request) {
            is SettingsSavedProfileStickerAvatarRequest.Current -> extrasAtRequest.currentPhoto.let { current ->
                current.photo?.takeIf { current.status == SettingsCurrentProfilePhotoStatus.READY &&
                    current.epoch == request.epoch && it.photoId == request.photoId }?.let {
                    SettingsSavedProfileStickerNativeSource.Current(request.parent.userId, it.authorization, it.photoId)
                }
            }
            is SettingsSavedProfileStickerAvatarRequest.History -> extrasAtRequest.history.let { history ->
                history.entries.singleOrNull { history.opened && !history.refreshRequired && history.epoch == request.epoch &&
                    it.photoId == request.photoId }?.let {
                    SettingsSavedProfileStickerNativeSource.History(request.parent.userId, it.authorization, it.photoId, it.rawOffset)
                }
            }
        }
        val ticketWitness = nativeSource?.let { controller?.savedStickerTicketWitness(it) }
        val epoch = profileStickerAvatarEpoch.get()
        val stop = stopRequestRevision.get()
        val source = currentPhotoNativeLease {
            val settings = snapshots.value.settings
            val extras = settings.profileExtras
            val selected = when (request) {
                is SettingsSavedProfileStickerAvatarRequest.Current -> extras.currentPhoto.let {
                    it.epoch == request.epoch && it.status == SettingsCurrentProfilePhotoStatus.READY &&
                        it.photo?.photoId == request.photoId
                }
                is SettingsSavedProfileStickerAvatarRequest.History -> extras.history.let {
                    it.opened && !it.refreshRequired && it.epoch == request.epoch &&
                        it.entries.any { entry -> entry.photoId == request.photoId }
                }
            }
            epochWitness?.invoke() == true && ticketWitness?.invoke() == true && environment.current() &&
                settings.enabled && extras.token == request.parent && selected
        }
        val bridge = SavedProfileStickerMediaBridge(source, active, result, demand, environment::remove)
        if (request.parent.generation != environment.generation || !bridge.demandCurrent() || owner == null) {
            bridge.reject(); return bridge
        }
        val admitted = environment.register(bridge)
        if (!admitted && !environment.current()) { bridge.reject(); return bridge }
        val action = Action.SavedProfileStickerMediaWork(environment.generation, epoch, stop, owner,
            environment, request, bridge, demand, limited = !admitted)
        if (postAction(action).isFailure) bridge.reject()
        return bridge
    }
    fun openSettingsProfilePhotoHistory(token: SettingsProfileExtrasToken, selectorLease: () -> Boolean): Boolean {
        if (!runCatching(selectorLease).getOrDefault(false)) return false
        return profileExtrasInput(token) {
            if (runCatching(selectorLease).getOrDefault(false)) it.openProfilePhotoHistory(token, selectorLease)
        }
    }
    fun closeSettingsProfilePhotoHistory(token: SettingsProfileExtrasToken) =
        profileExtrasInput(token, cleanup = true) { it.closeProfilePhotoHistory(token) }
    fun acquireSettingsProfileHistoryFile(request: SettingsProfilePhotoHistoryMediaRequest, active: () -> Boolean,
        result: (SettingsResult<MediaFile>) -> Unit,
    ): SettingsProfilePhotoHistoryFileLease {
        val stopAtRequest = stopRequestRevision.get()
        val stops = stopRequestRevision
        val bridge = ProfilePhotoHistoryFileBridge({ stops.get() == stopAtRequest && active() }, result)
        val max = 4096
        if (request.epoch <= 0 || request.photoId == 0L || request.targetWidthPx !in 1..max ||
            request.targetHeightPx !in 1..max || !bridge.current()) {
            bridge.finish(SettingsResult(error = SettingsError.INVALID_VALUE))
            return bridge
        }
        profileExtrasInput(request.parent, discard = { bridge.finish(SettingsResult(error = SettingsError.NOT_READY)) }) {
            if (!bridge.current()) bridge.finish(SettingsResult(error = SettingsError.NOT_READY))
            else bridge.bind(it.acquireProfileHistoryFile(request, bridge::current, bridge::finish))
        }
        return bridge
    }
    fun refreshSettingsProfilePhotoHistory(token: SettingsProfileExtrasToken) =
        profileExtrasInput(token) { it.refreshProfilePhotoHistory(token) }
    fun moreSettingsProfilePhotoHistory(token: SettingsProfileExtrasToken) =
        profileExtrasInput(token) { it.moreProfilePhotoHistory(token) }
    fun reuseSettingsProfileHistoryPhoto(token: SettingsProfileExtrasToken, epoch: Long,
        target: SettingsProfilePhotoHistoryTarget, selectionLease: () -> Boolean,
    ): Boolean {
        if (epoch <= 0 || target.photoId == 0L || !runCatching(selectionLease).getOrDefault(false)) return false
        return profileExtrasInput(token) {
            if (runCatching(selectionLease).getOrDefault(false)) it.reuseProfileHistoryPhoto(token, epoch, target, selectionLease)
        }
    }
    fun removeSettingsProfileHistoryPhoto(token: SettingsProfileExtrasToken, epoch: Long,
        target: SettingsProfilePhotoHistoryTarget, selectionLease: () -> Boolean,
    ): Boolean {
        if (epoch <= 0 || target.photoId == 0L || !runCatching(selectionLease).getOrDefault(false)) return false
        return profileExtrasInput(token) {
            if (runCatching(selectionLease).getOrDefault(false)) it.removeProfileHistoryPhoto(token, epoch, target, selectionLease)
        }
    }
    fun openSettingsProfilePersonalChannels(token: SettingsProfileExtrasToken, selectorLease: () -> Boolean) =
        selectorLease() && profileExtrasInput(token) { it.openProfilePersonalChannels(token, selectorLease) }
    fun closeSettingsProfilePersonalChannels(token: SettingsProfileExtrasToken) =
        profileExtrasInput(token, cleanup = true) { it.closeProfilePersonalChannels(token) }
    fun moreSettingsProfilePersonalChannels(token: SettingsProfileExtrasToken) =
        profileExtrasInput(token) { it.moreProfilePersonalChannels(token) }
    fun saveSettingsProfilePersonalChannel(token: SettingsProfileExtrasToken, expectedChatId: Long, desiredChatId: Long) =
        profileExtrasInput(token) { it.saveProfilePersonalChannel(token, expectedChatId, desiredChatId) }

    fun openSettingsProfileStickerAvatar(parent: SettingsProfileExtrasToken, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPickerTicket>) -> Unit,
    ): Boolean = profileStickerAvatarInput(parent, active, result) { controller, current, completed ->
        controller.openProfileStickerAvatar(parent, current) { response ->
            // An opening can lose its view before its ticket is delivered to that view.
            if (!current()) response.value?.let { controller.closeProfileStickerAvatar(parent, it) }
            completed(response)
        }
    }

    fun closeSettingsProfileStickerAvatar(parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket,
    ): Boolean = profileExtrasInput(parent, cleanup = true) { it.closeProfileStickerAvatar(parent, picker) }

    fun loadSettingsProfileStickerAvatarDefault(parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
    ): Boolean = profileStickerAvatarInput(parent, active, result) { controller, current, completed ->
        controller.loadProfileStickerAvatarDefault(parent, picker, current, completed)
    }

    fun loadSettingsProfileStickerAvatarPacks(parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket, kind: ProfileStickerAvatarPackKind, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
    ): Boolean = profileStickerAvatarInput(parent, active, result) { controller, current, completed ->
        controller.loadProfileStickerAvatarPacks(parent, picker, kind, current, completed)
    }

    fun openSettingsProfileStickerAvatarPack(parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket, pack: ProfileStickerAvatarPackTicket, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
    ): Boolean = profileStickerAvatarInput(parent, active, result) { controller, current, completed ->
        controller.openProfileStickerAvatarPack(parent, picker, pack, current, completed)
    }

    fun loadSettingsProfileStickerAvatarWindow(parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket, cursor: ProfileStickerAvatarCatalogCursor, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit,
    ): Boolean = profileStickerAvatarInput(parent, active, result) { controller, current, completed ->
        controller.loadProfileStickerAvatarWindow(parent, picker, cursor, current, completed)
    }

    fun searchSettingsProfileStickerAvatarInstalled(parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket, kind: ProfileStickerAvatarPackKind, query: String, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
    ): Boolean = profileStickerAvatarInput(parent, active, result) { controller, current, completed ->
        controller.searchProfileStickerAvatarInstalled(parent, picker, kind, query, current, completed)
    }

    fun selectSettingsProfileStickerAvatar(parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket, candidate: ProfileStickerAvatarCandidateTicket,
        active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarSelectionTicket>) -> Unit,
    ): Boolean = profileStickerAvatarInput(parent, active, result) { controller, current, completed ->
        controller.selectProfileStickerAvatar(parent, picker, candidate, current, completed)
    }

    fun saveSettingsProfileStickerAvatar(parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket, selection: ProfileStickerAvatarSelectionTicket,
        expectedPhotoId: Long?, background: ProfileStickerAvatarBackground, active: () -> Boolean,
        admitted: (Long?) -> Unit, result: (ProfileStickerAvatarMutationResult) -> Unit,
    ): Boolean {
        val lease = profileStickerAvatarInputLease(active)
        val admissionOnce = java.util.concurrent.atomic.AtomicBoolean()
        val resultOnce = java.util.concurrent.atomic.AtomicBoolean()
        fun admission(revision: Long?) {
            if (admissionOnce.compareAndSet(false, true)) runCatching { admitted(revision) }
        }
        fun observed(outcome: ProfileStickerAvatarMutationResult) {
            // This is the original operation's result. The UI applies its own view lease;
            // leaving/stop must never turn a real late success into a fabricated failure.
            if (resultOnce.compareAndSet(false, true)) runCatching { result(outcome) }
        }
        fun discard() = admission(null)
        if (expectedPhotoId == 0L || !lease.current()) { discard(); return false }
        return profileExtrasInput(parent, ::discard) { controller ->
            if (!lease.current()) discard()
            else try {
                controller.saveProfileStickerAvatar(parent, picker, selection, expectedPhotoId, background,
                    lease::current, ::admission, ::observed)
            } catch (_: Exception) { discard() }
        }
    }

    private fun <T> profileStickerAvatarInput(parent: SettingsProfileExtrasToken, active: () -> Boolean,
        result: (SettingsResult<T>) -> Unit,
        work: (SettingsController, () -> Boolean, (SettingsResult<T>) -> Unit) -> Unit,
    ): Boolean {
        val lease = profileStickerAvatarInputLease(active)
        val once = java.util.concurrent.atomic.AtomicBoolean()
        fun finish(response: SettingsResult<T>) {
            if (once.compareAndSet(false, true)) runCatching {
                result(if (lease.current()) response else SettingsResult(error = SettingsError.NOT_READY))
            }
        }
        fun discard() = finish(SettingsResult(error = SettingsError.NOT_READY))
        if (!lease.current()) { discard(); return false }
        return profileExtrasInput(parent, ::discard) { controller ->
            if (!lease.current()) discard()
            else try { work(controller, lease::current, ::finish) }
            catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        }
    }

    private fun profileExtrasInput(token: SettingsProfileExtrasToken, discard: () -> Unit = {}, cleanup: Boolean = false,
        work: (SettingsController) -> Unit,
    ): Boolean {
        val state = mutableState.value
        if (disposed || closeSent || disposeRequested || (!cleanup && !ordinaryAccountAllowed()) || !state.settings.enabled || state.settings.profileExtras.token != token ||
            token.userId <= 0) { discard(); return false }
        val accepted = postAction(Action.ProfileExtrasInput(token, work, discard, cleanup)).isSuccess
        if (!accepted) discard()
        return accepted
    }

    /** Native authorization/ordinary-gate loss revokes before its actor event is processed. */
    private fun profileStickerAvatarInputLease(active: () -> Boolean) = ProfileStickerAvatarInputLease(
        stopRequestRevision, stopRequestRevision.get(), notificationLockRevision, notificationLockRevision.get(), active,
    )

    fun saveSettingsNotifications(token: SettingsToken, scope: NotificationScope, value: NotificationPreferences) =
        settingsInput(token) { it.saveNotifications(token, scope, value) }
    fun saveSettingsPrivacy(token: SettingsToken, key: PrivacyKey, base: PrivacyBase) =
        settingsInput(token) { it.savePrivacy(token, key, base) }
    fun openSettingsPrivacyEditor(token: SettingsToken, key: PrivacyKey, requestId: Long, result: (SettingsResult<PrivacyEditorToken>) -> Unit): Boolean {
        if (!canUseSettings(token)) { result(SettingsResult(error = SettingsError.NOT_READY)); return false }
        return settingsInput(token) { it.openPrivacyEditor(token, key, requestId, result) }
    }
    fun cancelSettingsPrivacyEditorOpening(token: SettingsToken, requestId: Long) =
        settingsInput(token, cleanup = true) { it.cancelPrivacyEditorOpening(token, requestId) }
    fun closeSettingsPrivacyEditor(token: SettingsToken, editor: PrivacyEditorToken) =
        settingsInput(token, cleanup = true) { it.closePrivacyEditor(token, editor) }
    fun settingsPrivacyPeers(token: SettingsToken, editor: PrivacyEditorToken, query: PrivacyPeerQuery,
        result: (SettingsResult<SettingsPrivacyPeers>) -> Unit,
    ) = settingsInput(token) { it.privacyPeers(token, editor, query, result) }
    fun saveSettingsPrivacyDraft(token: SettingsToken, editor: PrivacyEditorToken, draft: PrivacyDraft) =
        settingsInput(token) { it.savePrivacyDraft(token, editor, draft) }
    fun saveSettingsAccountTtl(token: SettingsToken, days: Int) = settingsInput(token) { it.setAccountTtl(token, days) }
    fun terminateSettingsSession(token: SettingsToken, id: Long) = settingsInput(token) { it.terminateSession(token, id) }
    fun unblockSettingsSender(token: SettingsToken, sender: SettingsBlockedSender) = settingsInput(token) { it.unblock(token, sender) }
    fun optimizeSettingsStorage(token: SettingsToken): Boolean {
        revokeSettingsProxyShares(token)
        return settingsInput(token) { it.optimizeStorage(token) }
    }
    fun addSettingsProxy(token: SettingsToken, draft: SettingsProxyDraft): Boolean {
        revokeSettingsProxyShares(token)
        return settingsInput(token) { it.addProxy(token, draft) }
    }
    fun enableSettingsProxy(token: SettingsToken, id: Int?): Boolean {
        revokeSettingsProxyShares(token)
        return settingsInput(token) { it.enableProxy(token, id) }
    }
    fun removeSettingsProxy(token: SettingsToken, id: Int): Boolean {
        revokeSettingsProxyShares(token)
        return settingsInput(token) { it.removeProxy(token, id) }
    }
    /** One monotonic page visit. close() revokes synchronously, before actor cleanup. */
    fun openSettingsProxyChecks(token: SettingsToken, active: () -> Boolean,
        opened: (SettingsProxyChecksToken?) -> Unit,
    ): SettingsProxyChecksLoad {
        val completed = AtomicBoolean()
        fun finish(value: SettingsProxyChecksToken?) {
            if (completed.compareAndSet(false, true)) runCatching { opened(value) }
        }
        val owner = session
        val controller = settingsController
        val stoppedAt = settingsProxySessionStopRevision
        if (owner == null || controller == null || disposed || !canUseSettings(token) ||
            stopRequestRevision.get() != stoppedAt || !runCatching(active).getOrDefault(false)) {
            finish(null)
            return SettingsProxyChecksLoad { }
        }
        val gateRevision = settingsProxyGateRevision
        val lockedAt = gateRevision.get()
        val ordinary = settingsProxyOrdinaryWitness
        val pageLease = SettingsProxyChecksPageLease {
            val value = mutableState.value
            // Evaluated under the native owner's dispatch lock. Never take interactionGate's
            // lock here: its observer runs inside that lock and only changes this witness.
            session === owner && !disposed && stopRequestRevision.get() == stoppedAt &&
                gateRevision.get() == lockedAt && ordinary.get() && owner.authorizationReady() &&
                value.generation == token.generation && value.clientActive &&
                value.status.authorization == AuthorizationPhase.READY && value.settings.enabled &&
                value.settings.token == token && runCatching(active).getOrDefault(false)
        }
        val page = SettingsProxyChecksEnginePage(token, owner, controller, pageLease)
        settingsProxyChecksPages[pageLease] = page
        val load = object : SettingsProxyChecksLoad {
            override fun close() = closeSettingsProxyChecksPage(page)
            override fun refresh() {
                page.revokeShareOwner()
                if (!canUseSettingsProxyChecks(page) || postAction(Action.ProxyChecksRefresh(page)).isFailure) close()
            }
            override fun share(request: SettingsProxyShareRequest, result: (SettingsResult<SettingsProxyShareContent>) -> Unit) {
                if (!canUseSettingsProxyChecks(page)) {
                    runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
                    return
                }
                val frozen = try {
                    val target = when (val selected = request.target) {
                        is SettingsProxyShareTarget.One -> selected
                        is SettingsProxyShareTarget.All -> SettingsProxyShareTarget.All(selected.rows.toList())
                    }
                    request.copy(target = target)
                } catch (_: Exception) {
                    runCatching { result(SettingsResult(error = SettingsError.CONFLICT)) }
                    return
                }
                val snapshot = mutableState.value.settings
                val checks = snapshot.proxyChecks
                val targetMatches = when (val selected = frozen.target) {
                    is SettingsProxyShareTarget.One -> snapshot.proxies.getOrNull(selected.index) == selected.proxy &&
                        snapshot.proxies.count { it.id == selected.proxy.id } == 1
                    is SettingsProxyShareTarget.All -> selected.rows == snapshot.proxies &&
                        frozen.format == SettingsProxyShareFormat.PUBLIC
                }
                if (snapshot.token != page.parent || checks.token != frozen.token ||
                    frozen.token.parent != page.parent || checks.listEpoch != frozen.listEpoch ||
                    checks.rows.size != snapshot.proxies.size ||
                    checks.rows.indices.any { checks.rows[it].index != it || checks.rows[it].proxy != snapshot.proxies[it] } ||
                    !targetMatches) {
                    runCatching { result(SettingsResult(error = SettingsError.CONFLICT)) }
                    return
                }
                val owner = if (canUseSettingsProxyChecks(page)) page.newShareOwner() else null
                if (owner == null) {
                    runCatching { result(SettingsResult(error = SettingsError.NOT_READY)) }
                    return
                }
                val completed = AtomicBoolean()
                fun finish(value: SettingsResult<SettingsProxyShareContent>) {
                    if (!completed.compareAndSet(false, true)) return
                    val output = if (canUseSettingsProxyChecks(page) && owner.current()) value else {
                        value.value?.close()
                        SettingsResult(error = SettingsError.NOT_READY)
                    }
                    if (output.value == null) page.releaseShareOwner(owner)
                    runCatching { result(output) }.onFailure { output.value?.close() }
                }
                if (!canUseSettingsProxyChecks(page) ||
                    !owner.current() || postAction(Action.ProxyChecksShare(page, frozen, owner, ::finish)).isFailure) {
                    finish(SettingsResult(error = SettingsError.NOT_READY))
                }
            }
        }
        // Recheck after registry publication: a concurrent stop/lock cannot miss an
        // unregistered visit and then admit its queued open.
        if (!canUseSettingsProxyChecks(page) ||
            postAction(Action.ProxyChecksOpen(page, ::finish)).isFailure) {
            load.close()
            finish(null)
        }
        return load
    }

    /** Public/actor admission takes the native ordinary gate outside any probe dispatch lock. */
    private fun canUseSettingsProxyChecks(page: SettingsProxyChecksEnginePage): Boolean =
        settingsProxyChecksPages[page.lease] === page && session === page.session &&
            settingsController === page.controller && stopRequestRevision.get() == settingsProxySessionStopRevision &&
            canUseSettings(page.parent) && page.lease.current()

    private fun closeSettingsProxyChecksPage(page: SettingsProxyChecksEnginePage) {
        page.revokeShareOwner()
        page.lease.revoke()
        if (settingsProxyChecksPages.remove(page.lease, page)) postAction(Action.ProxyChecksClose(page))
    }

    private fun revokeSettingsProxyShares(parent: SettingsToken) {
        settingsProxyChecksPages.values.forEach { page ->
            if (page.parent == parent) page.revokeShareOwner()
        }
    }

    /** May run before the actor consumes native authorization or a public Stop request. */
    private fun revokeSettingsProxyChecks(ownerGeneration: Long? = null) {
        settingsProxyChecksPages.values.forEach { page ->
            if (ownerGeneration == null || page.parent.generation == ownerGeneration) closeSettingsProxyChecksPage(page)
        }
    }

    /** Restoring READY/ordinary cannot revive visits that observed an earlier lock revision. */
    private fun revokeExpiredSettingsProxyChecks() {
        settingsProxyChecksPages.values.forEach { page ->
            if (!page.lease.current()) closeSettingsProxyChecksPage(page)
        }
    }

    fun openSettingsSecurity(token: SettingsToken, section: SettingsSecuritySection) = settingsInput(token) { it.openSecurity(token, section) }
    fun closeSettingsSecurity(token: SettingsSecurityToken) = securityInput(token, cleanup = true) { it.closeSecurity(token) }
    fun refreshSettingsSecurity(token: SettingsSecurityToken) = securityInput(token) { it.refreshSecurity(token) }
    /** Thread-safe read witness for a synchronous local preference commit, bound to one security token. */
    fun captureDeviceNameSecurityOwner(token: SettingsSecurityToken, target: SettingsSession): () -> Boolean {
        val stop = stopRequestRevision.get()
        val lock = notificationLockRevision.get()
        val owner = session
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        return {
            val value = mutableState.value
            val current = owner != null && !disposed && stopRequestRevision.get() == stop && notificationLockRevision.get() == lock &&
                owner.authorizationReady() && (owner.interactionGate?.allowsOrdinary() ?: !value.loginEmail.mandatory) &&
                value.clientActive && value.status.authorization == AuthorizationPhase.READY && value.settings.enabled &&
                value.settings.security.token == token && value.settings.security.section == SettingsSecuritySection.SESSIONS &&
                !value.settings.security.loading && !value.settings.security.busy && !value.settings.security.sessionsRefreshRequired &&
                target.isCurrent && value.settings.security.sessions.count { it.isCurrent } == 1 &&
                value.settings.security.sessions.any { it.isCurrent && target.matchesDevice(it) &&
                    target.logInDate == it.logInDate && target.isPasswordPending == it.isPasswordPending &&
                    target.isUnconfirmed == it.isUnconfirmed }
            if (!current) alive.set(false)
            alive.get()
        }
    }
    fun settingsPassword(token: SettingsSecurityToken, command: SettingsPasswordCommand,
        result: (SettingsResult<SettingsPasswordOutcome>) -> Unit,
    ) = securityInput(token, { result(SettingsResult(error = SettingsError.NOT_READY)) }) { it.password(token, command, result) }
    fun disconnectSettingsWebsite(token: SettingsSecurityToken, target: SettingsWebsite, blockBot: Boolean,
        result: (SettingsResult<SettingsWebsiteDisconnectOutcome>) -> Unit,
    ) = securityInput(token, { result(SettingsResult(error = SettingsError.NOT_READY)) }) { it.disconnectWebsite(token, target, blockBot, result) }
    fun disconnectAllSettingsWebsites(token: SettingsSecurityToken, result: (SettingsResult<Unit>) -> Unit) =
        securityInput(token, { result(SettingsResult(error = SettingsError.NOT_READY)) }) { it.disconnectAllWebsites(token, result) }
    fun openSettingsBlockedPicker(token: SettingsSecurityToken) = securityInput(token) { it.openBlockedPicker(token) }
    fun closeSettingsBlockedPicker(token: SettingsBlockedPickerToken) =
        securityInput(token.security, cleanup = true) { it.closeBlockedPicker(token) }
    fun searchSettingsBlockedPicker(token: SettingsBlockedPickerToken, query: SettingsBlockedPeerQuery) =
        securityInput(token.security) { it.searchBlockedPicker(token, query) }
    fun blockSettingsPickerUser(token: SettingsBlockedPickerToken, userId: Long, result: (SettingsResult<Unit>) -> Unit) =
        securityInput(token.security, { result(SettingsResult(error = SettingsError.NOT_READY)) }) { it.blockPickerUser(token, userId, result) }
    fun loadMoreSettingsBlocked(token: SettingsSecurityToken) = securityInput(token) { it.moreBlocked(token) }
    fun unblockSettingsSecuritySender(token: SettingsSecurityToken, sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) =
        securityInput(token, { result(SettingsResult(error = SettingsError.NOT_READY)) }) { it.unblockSecurity(token, sender, result) }
    fun terminateSettingsSecuritySession(token: SettingsSecurityToken, id: Long, result: (SettingsResult<Unit>) -> Unit) =
        securityInput(token, { result(SettingsResult(error = SettingsError.NOT_READY)) }) { it.terminateSecuritySession(token, id, result) }
    fun terminateOtherSettingsSecuritySessions(token: SettingsSecurityToken, expected: List<SettingsSession>, result: (SettingsResult<Unit>) -> Unit) =
        securityInput(token, { result(SettingsResult(error = SettingsError.NOT_READY)) }) { it.terminateOtherSecuritySessions(token, expected, result) }
    fun saveSettingsInactiveSessionTtl(token: SettingsSecurityToken, expected: Int, desired: Int, result: (SettingsResult<Unit>) -> Unit) =
        securityInput(token, { result(SettingsResult(error = SettingsError.NOT_READY)) }) { it.saveInactiveSessionTtl(token, expected, desired, result) }
    fun saveSettingsDefaultMessageAutoDeleteTime(token: SettingsSecurityToken, expected: Int, desired: Int, result: (SettingsResult<Unit>) -> Unit) =
        securityInput(token, { result(SettingsResult(error = SettingsError.NOT_READY)) }) { it.saveDefaultMessageAutoDeleteTime(token, expected, desired, result) }

    private fun securityInput(token: SettingsSecurityToken, discard: () -> Unit = {}, cleanup: Boolean = false,
        work: (SettingsController) -> Unit,
    ): Boolean {
        val state = mutableState.value
        if (disposed || closeSent || disposeRequested || (!cleanup && !ordinaryAccountAllowed()) || !state.settings.enabled ||
            state.settings.security.token != token || state.settings.security.section == null) {
            discard(); return false
        }
        val accepted = postAction(Action.SecurityInput(token, work, discard, cleanup)).isSuccess
        if (!accepted) discard()
        return accepted
    }

    private fun settingsInput(token: SettingsToken, cleanup: Boolean = false, work: (SettingsController) -> Unit): Boolean {
        if (disposed || !canUseSettings(token, cleanup)) return false
        return postAction(Action.SettingsInput(token, work, cleanup)).isSuccess
    }

    private fun canUseSettings(token: SettingsToken, cleanup: Boolean = false) = !closeSent && !disposeRequested &&
        (cleanup || ordinaryAccountAllowed()) && current(token.generation) &&
        mutableState.value.settings.enabled && mutableState.value.settings.token == token
    fun searchChats(token: ChatToken, query: ChatSearchQuery, more: Boolean) = chatInput(token) { it.search(token, query, more) }
    fun searchOverview(token: ChatToken) = chatInput(token) { it.searchOverview(token) }
    fun clearRecentSearch(token: ChatToken) = chatInput(token) { it.clearRecentSearch(token) }
    fun closeSearch(token: ChatToken) = chatInput(token) { it.closeSearch(token) }
    fun selectSearchChat(token: ChatToken, chatId: Long) = chatInput(token) { it.selectSearchChat(token, chatId) }
    fun openSearchMessage(token: ChatToken, chatId: Long, messageId: Long) = chatInput(token) { it.openSearchMessage(token, chatId, messageId) }
    fun loadChatDetails(token: ChatToken) = chatInput(token) { it.loadChatDetails(token) }
    fun reportSpamAndLeave(token: ChatToken) = chatInput(token) { it.reportSpamAndLeave(token) }
    fun dismissSpamBar(token: ChatToken) = chatInput(token) { it.dismissSpamBar(token) }
    fun chatListAction(token: ChatToken, chatId: Long, list: ChatListRef, action: ChatListAction, result: (ChatResult<Unit>) -> Unit) {
        if (!chatInput(token) { it.chatListAction(token, chatId, list, action, result) }) result(ChatResult(error = ChatError.NOT_READY))
    }
    fun addChatToFolder(token: ChatToken, chatId: Long, folderId: Int, result: (ChatResult<Unit>) -> Unit) {
        if (!chatInput(token) { it.addChatToFolder(token, chatId, folderId, result) }) result(ChatResult(error = ChatError.NOT_READY))
    }
    fun requestMessageLink(token: ChatToken, messageId: Long, result: (ChatResult<String>) -> Unit) {
        if (!chatInput(token) { it.requestMessageLink(token, messageId, result) }) result(ChatResult(error = ChatError.NOT_READY))
    }
    fun loadPinned(token: ChatToken, more: Boolean) = chatInput(token) { it.loadPinned(token, more) }
    fun loadMediaIndex(token: ChatToken, category: ChatMediaCategory, more: Boolean) = chatInput(token) { it.loadMediaIndex(token, category, more) }
    fun openMediaIndexMessage(token: ChatToken, messageId: Long) = chatInput(token) { it.openMediaIndexMessage(token, messageId) }
    fun openPinnedMessage(token: ChatToken, messageId: Long) = chatInput(token) { it.openPinnedMessage(token, messageId) }
    fun returnToLatest(token: ChatToken) = chatInput(token) { it.returnToLatest(token) }
    fun cancelSendLatestNavigation(token: ChatToken) = chatInput(token) { it.cancelSendLatestNavigation(token) }
    fun completeHistoryJump(token: ChatToken, jumpRevision: Long) = chatInput(token) { it.completeHistoryJump(token, jumpRevision) }
    fun openHistoryMessage(token: ChatToken, messageId: Long) = chatInput(token) { it.openHistoryMessage(token, messageId) }
    fun loadChats(token: ChatToken) = chatInput(token) { it.loadChats(token) }
    fun loadOlderMessages(token: ChatToken) = chatInput(token) { it.loadOlder(token) }
    fun loadNewerMessages(token: ChatToken) = chatInput(token) { it.loadNewer(token) }
    fun sendText(token: ChatToken, text: String) = chatInput(token) { it.sendText(token, text) }
    fun sendText(token: ChatToken, text: RichMessageText, draftRevision: Long = 0) = chatInput(token) { it.sendText(token, text, draftRevision) }
    fun rememberHistoryAnchor(token: ChatToken, messageId: Long) = chatInput(token) { it.rememberHistoryAnchor(token, messageId) }
    fun loadHistoryAnchor(token: ChatToken, messageId: Long) = chatInput(token) { it.loadHistoryAnchor(token, messageId) }
    fun openReplyMessage(token: ChatToken, sourceMessageId: Long) = chatInput(token) { it.openReplyMessage(token, sourceMessageId) }
    fun openReplyReturn(token: ChatToken, sourceChatId: Long, anchorMessageId: Long) = chatInput(token) { it.openReplyReturn(token, sourceChatId, anchorMessageId) }
    fun requestPreviousEditable(token: ChatToken, beforeMessageId: Long?, completed: (ChatResult<Long>) -> Unit) =
        chatInput(token, onDiscard = { completed(ChatResult(error = ChatError.NOT_READY)) }) {
            it.requestPreviousEditable(token, beforeMessageId, completed)
        }
    fun retryMessage(token: ChatToken, messageId: Long) = chatInput(token) { it.retry(token, messageId) }
    fun markChatRead(token: ChatToken) = chatInput(token) { it.markRead(token) }
    fun selectChatList(token: ChatToken, list: ChatListRef) = chatInput(token) { it.selectList(token, list) }
    fun visibleMessages(token: ChatToken, ids: List<Long>, active: Boolean) =
        chatInput(token, cleanup = !active && ids.isEmpty()) { it.visibleMessages(token, ids, active) }
    fun markChatUnread(token: ChatToken) = chatInput(token) { it.markUnread(token) }
    fun downloadChatFile(token: ChatToken, fileId: Int) = chatInput(token) { it.downloadFile(token, fileId) }
    fun automaticMedia(token: ChatToken, messageId: Long, active: Boolean) =
        chatInput(token, cleanup = !active) { it.automaticMedia(token, messageId, active) }
    fun releaseVideoPart(token: ChatToken, messageId: Long, fileId: Int) =
        chatInput(token, cleanup = true) { it.releaseVideoPart(token, messageId, fileId) }
    fun readOwnedVideoPart(owner: MediaDownloadOwner, fileId: Int, offset: Long, count: Int, result: (ChatResult<ByteArray>) -> Unit) {
        val discarded = { result(ChatResult<ByteArray>(error = ChatError.NOT_READY)) }
        if (!chatInput(owner.token, onDiscard = discarded) { it.readOwnedVideoPart(owner, fileId, offset, count, result) }) discarded()
    }
    fun openOwnedVideoPart(owner: MediaDownloadOwner, fileId: Int) =
        chatInput(owner.token) { it.openOwnedVideoPart(owner, fileId) }
    fun releaseOwnedVideoPart(owner: MediaDownloadOwner, fileId: Int) =
        chatInput(owner.token, cleanup = true) { it.releaseOwnedVideoPart(owner, fileId) }
    fun acquireOwnedMedia(owner: MediaDownloadOwner, fileId: Int) =
        chatInput(owner.token) { it.acquireOwnedMedia(owner, fileId) }
    fun releaseOwnedMedia(owner: MediaDownloadOwner, fileId: Int) =
        chatInput(owner.token, cleanup = true) { it.releaseOwnedMedia(owner, fileId) }
    fun saveAutoMedia(token: ChatToken, expectedRevision: Long, policy: AutoMediaPolicy,
        admitted: (Boolean) -> Unit = {},
        completed: (Boolean) -> Unit = {},
    ) {
        val discarded = { admitted(false) }
        if (!chatInput(token, onDiscard = discarded) {
                admitted(it.saveAutoMedia(token, expectedRevision, policy, completed))
            }) discarded()
    }
    fun loadStickerOutline(token: ChatToken, fileId: Int) = chatInput(token) { it.loadStickerOutline(token, fileId) }
    fun loadStickers(token: ChatToken, setId: Long?) = chatInput(token) { it.loadStickers(token, setId) }
    fun ensureStickerSection(token: ChatToken, setId: Long?) = chatInput(token) { it.ensureStickerSection(token, setId) }
    fun sendSticker(token: ChatToken, sticker: StickerItem) = chatInput(token) { it.sendSticker(token, sticker) }
    fun sendPhoto(token: ChatToken, path: String, caption: String) = chatInput(token) { it.sendPhoto(token, path, caption) }
    fun sendPhoto(token: ChatToken, path: String, caption: RichMessageText) = chatInput(token) { it.sendPhoto(token, path, caption) }
    fun sendOwnedPhoto(token: ChatToken, path: String, caption: RichMessageText, lease: AttachmentLease) {
        val owned = attachmentSendLeases.retain(lease) ?: return
        if (!chatInput(token, onDiscard = owned::release) { it.sendOwnedPhoto(token, path, caption, owned) }) owned.release()
    }
    fun submitClipboardPhoto(token: ChatToken, path: String, caption: RichMessageText, lease: AttachmentLease?,
        completed: (ChatResult<Unit>) -> Unit) {
        val owned = lease?.let(attachmentSendLeases::retain)
        if (lease != null && owned == null) { completed(ChatResult(error = ChatError.NOT_READY)); return }
        val discarded: () -> Unit = { owned?.release(); completed(ChatResult(error = ChatError.NOT_READY)) }
        if (!chatInput(token, onDiscard = discarded) { it.submitClipboardPhoto(token, path, caption, owned, completed) }) discarded()
    }
    fun submitClipboardFile(token: ChatToken, attachment: LocalAttachment, caption: RichMessageText,
        completed: (ChatResult<Unit>) -> Unit) {
        val owned = attachment.lease?.let(attachmentSendLeases::retain)
        if (attachment.lease != null && owned == null) { completed(ChatResult(error = ChatError.NOT_READY)); return }
        val discarded: () -> Unit = { owned?.release(); completed(ChatResult(error = ChatError.NOT_READY)) }
        if (!chatInput(token, onDiscard = discarded) { it.submitClipboardFile(token, attachment, caption, owned, completed) }) discarded()
    }
    fun sendVoiceNote(token: ChatToken, draft: VoiceNoteDraft) {
        val owned = attachmentSendLeases.retain(draft.lease) ?: return
        if (!chatInput(token, onDiscard = owned::release) { it.sendVoiceNote(token, draft, owned) }) owned.release()
    }
    fun sendAttachment(token: ChatToken, attachment: LocalAttachment, caption: String) = sendAttachment(token, attachment, RichMessageText(caption))
    fun sendAttachment(token: ChatToken, attachment: LocalAttachment, caption: RichMessageText): Boolean {
        val owned = attachment.lease?.let(attachmentSendLeases::retain)
        if (attachment.lease != null && owned == null) return false
        val accepted = chatInput(token, onDiscard = { owned?.release() }) {
            it.sendOwnedAttachment(token, attachment, caption, owned)
        }
        if (!accepted) owned?.release()
        return accepted
    }
    fun setReply(token: ChatToken, messageId: Long?) = chatInput(token) { it.setReply(token, messageId) }
    fun setQuote(token: ChatToken, messageId: Long, quote: MessageQuote) = chatInput(token) { it.setQuote(token, messageId, quote) }
    fun loadReactions(token: ChatToken, messageId: Long) = chatInput(token) { it.loadReactions(token, messageId) }
    fun closeReactions(token: ChatToken) = chatInput(token, cleanup = true) { it.closeReactions(token) }
    fun setReaction(token: ChatToken, messageId: Long, emoji: String, add: Boolean) = chatInput(token) { it.setReaction(token, messageId, emoji, add) }
    fun setCustomReaction(token: ChatToken, messageId: Long, customEmojiId: Long, add: Boolean) = chatInput(token) { it.setCustomReaction(token, messageId, customEmojiId, add) }
    fun loadReactionVisual(token: ChatToken, key: ReactionKey) = chatInput(token) { it.loadReactionVisual(token, key) }
    fun loadReactionPacks(token: ChatToken, setId: Long?) = chatInput(token) { it.loadReactionPacks(token, setId) }
    fun loadReactionGroupPack(token: ChatToken, sourceChatId: Long?) = chatInput(token) { it.loadReactionGroupPack(token, sourceChatId) }
    fun readVideoPart(token: ChatToken, messageId: Long, fileId: Int, offset: Long, count: Int, result: (ChatResult<ByteArray>) -> Unit) {
        if (!chatInput(token) { it.readVideoPart(token, messageId, fileId, offset, count, result) }) result(ChatResult(error = ChatError.FORBIDDEN))
    }
    fun loadReadDetails(token: ChatToken, messageId: Long) = chatInput(token) { it.loadReadDetails(token, messageId) }
    fun closeReadDetails(token: ChatToken) = chatInput(token, cleanup = true) { it.closeReadDetails(token) }
    fun loadMessageActions(token: ChatToken, messageId: Long) = chatInput(token) { it.loadMessageActions(token, messageId) }
    fun closeMessageActions(token: ChatToken) = chatInput(token, cleanup = true) { it.closeMessageActions(token) }
    fun startEdit(token: ChatToken, messageId: Long) = chatInput(token) { it.startEdit(token, messageId) }
    fun cancelEdit(token: ChatToken) = chatInput(token) { it.cancelEdit(token) }
    fun editMessage(token: ChatToken, text: String) = chatInput(token) { it.editMessage(token, text) }
    fun editMessage(token: ChatToken, text: RichMessageText) = chatInput(token) { it.editMessage(token, text) }
    fun deleteMessage(token: ChatToken, messageId: Long, revoke: Boolean) = chatInput(token) { it.deleteMessage(token, messageId, revoke) }
    fun forwardMessage(token: ChatToken, messageId: Long, targetChatId: Long) = chatInput(token) { it.forwardMessage(token, messageId, targetChatId) }
    fun editChatFolder(token: ChatToken, id: Int) = chatInput(token) { it.editFolder(token, id) }
    fun openFolderSettings(token: ChatToken, pageId: Long, active: () -> Boolean) = chatInput(token) { it.openFolderSettings(token, pageId, active) }
    fun closeFolderSettings(token: FolderSettingsToken) = folderSettingsInput(token, cleanup = true) { it.closeFolderSettings(token) }
    fun refreshFolderSettings(token: FolderSettingsToken) = folderSettingsInput(token) { it.refreshFolderSettings(token) }
    fun stageFolderRecommendation(token: FolderSettingsToken, key: Long) = folderSettingsInput(token) { it.stageFolderRecommendation(token, key) }
    fun moveFolder(token: FolderSettingsToken, entry: FolderDraftEntry, delta: Int) = folderSettingsInput(token) { it.moveFolder(token, entry, delta) }
    fun stageFolderRemoval(token: FolderSettingsToken, id: Int) = folderSettingsInput(token) { it.stageFolderRemoval(token, id) }
    fun restoreFolder(token: FolderSettingsToken, id: Int) = folderSettingsInput(token) { it.restoreFolder(token, id) }
    fun resetFolderSettings(token: FolderSettingsToken) = folderSettingsInput(token) { it.resetFolderSettings(token) }
    fun applyFolderSettings(token: FolderSettingsToken, revision: Long) = folderSettingsInput(token) { it.applyFolderSettings(token, revision) }
    fun toggleFolderTags(token: FolderSettingsToken, enabled: Boolean) = folderSettingsInput(token) { it.toggleFolderTags(token, enabled) }
    fun colorFolder(token: FolderSettingsToken, folder: ChatFolderInfo, colorId: Int) = folderSettingsInput(token) { it.colorFolder(token, folder, colorId) }
    private fun folderSettingsInput(token: FolderSettingsToken, cleanup: Boolean = false, work: (ChatController) -> Unit): Boolean {
        val state = mutableState.value
        if (disposed || closeSent || disposeRequested || (!cleanup && !ordinaryAccountAllowed()) || state.chats.folderSettings.token != token ||
            !state.chats.folderSettings.opened) return false
        return postAction(Action.FolderSettingsInput(token, work, cleanup)).isSuccess
    }
    fun closeChatFolderEditor(token: ChatToken) = chatInput(token, cleanup = true) { it.closeFolderEditor(token) }
    fun saveChatFolder(token: ChatToken, folder: ChatFolder) = chatInput(token) { it.saveFolder(token, folder) }
    fun deleteChatFolder(token: ChatToken, id: Int) = chatInput(token) { it.deleteFolder(token, id) }

    private fun chatInput(token: ChatToken, onDiscard: () -> Unit = {}, cleanup: Boolean = false,
        work: (ChatController) -> Unit,
    ): Boolean {
        val snapshot = state.value
        if (disposed || (!cleanup && !ordinaryAccountAllowed()) || !snapshot.chats.enabled || snapshot.chats.token != token) return false
        return postAction(Action.ChatInput(token, work, onDiscard, cleanup)).isSuccess
    }

    private fun localAllowed(): Boolean = localCoordinator?.let {
        stopRequestRevision.get() == pendingStartStopRevision && it.isCurrentAndOpen(it.currentWitness())
    } ?: false
    private fun ordinaryAllowed(): Boolean = localAllowed() &&
        (session?.interactionGate?.allowsOrdinary() ?: !mutableState.value.loginEmail.mandatory)
    private fun ordinaryAccountAllowed(): Boolean = ordinaryAllowed() &&
        session?.interactionGate?.callbackContextIsCurrent() != false &&
        mutableState.value.status.authorization == AuthorizationPhase.READY && session?.authorizationReady() != false

    private fun canUseChats(token: ChatToken, cleanup: Boolean = false): Boolean = !closeSent && !disposeRequested &&
        (cleanup || ordinaryAccountAllowed()) &&
        current(token.generation) && state.value.chats.enabled && state.value.chats.token == token &&
        (cleanup || state.value.proxy in setOf(ProxyStatus.DIRECT, ProxyStatus.ENABLED))

    /** Stops the current generation; successful completion permits a later start on this engine. */
    suspend fun stop(timeoutMillis: Long = 10_000): Boolean = awaitStop(dispose = false, timeoutMillis)

    /** Only starts a new generation after the previous client and directory lease have been released. */
    suspend fun restart(timeoutMillis: Long = 10_000): Boolean = stop(timeoutMillis) && start()

    /** Terminal, idempotent disposal. A timeout keeps the actor alive to receive a late Closed event. */
    suspend fun close(timeoutMillis: Long = 10_000): Boolean = awaitStop(dispose = true, timeoutMillis)

    private suspend fun awaitStop(dispose: Boolean, timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        synchronized(startupDispatchLock) { stopRequestRevision.incrementAndGet() }
        retireLocalResumeSession()
        revokeSettingsProxyChecks()
        revokeSettingsStorage()
        localCoordinator?.let {
            publishLocalSnapshot(it)
            session?.interactionGate?.localWitnessChanged()
        }
        if (disposed) return true
        val id = waiterIds.incrementAndGet()
        val result = CompletableDeferred<Boolean>()
        if (postAction(Action.Stop(id, dispose, result)).isFailure) return disposed
        val completed = try {
            withTimeoutOrNull(timeoutMillis) { result.await() }
        } catch (error: CancellationException) {
            postAction(Action.RemoveWaiter(id, timedOut = false))
            throw error
        }
        if (completed == null) postAction(Action.RemoveWaiter(id, timedOut = true))
        return completed == true
    }

    private fun current(callbackGeneration: Long) = callbackGeneration == generation && session != null && !disposed

    private fun startClient() {
        if (disposed || disposeRequested || session != null || lease != null || localCoordinator != null || localStopInProgress) return
        startupNeedsKey = false
        pendingStartStopRevision = stopRequestRevision.get()
        resetNativeLifecycle()
        val checking = LocalLockSnapshot(phase = LocalLockPhase.CHECKING)
        mutableLocalLock.value = checking
        mutableState.update { it.copy(localLock = checking) }
        val configured = configuration.read()
        if (configured == null) {
            val unprotected = LocalLockSnapshot(phase = LocalLockPhase.UNPROTECTED)
            mutableLocalLock.value = unprotected
            updateEngine(EnginePhase.WAITING_CONFIGURATION)
            mutableState.value = mutableState.value.copy(localLock = unprotected,
                note = "此版本尚未配置完成，请使用已配置的发行版本，或按构建文档重新构建。")
            record(DiagnosticKind.CONFIGURATION_REQUIRED)
            return
        }
        val root = configured.dataDirectory.toAbsolutePath().normalize()
        configuredBeforeUnlock = configured
        val rootLease = DataRootLease.acquire(root)
        configuredRootLease = rootLease
        configuredRoot = root
        rootOwnershipRevision.incrementAndGet()
        val protectedRoot = LocalProtectionRoot(root, rootLease::identity, rootLease::isCurrent, rootLease::freshEligible,
            revision = rootOwnershipRevision::get)
        lateinit var owner: LocalLockCoordinator
        owner = localLockCoordinatorFactory(protectedRoot, localLockWorker,
            { epoch, needsStartupKey -> postAction(Action.LocalStartReady(owner, epoch, needsStartupKey)) },
            { witness -> localWitnessChanged(owner, witness) })
        localCoordinator = owner
        localStateCollection = scope.launch {
            owner.state.collect {
                if (localCoordinator === owner) {
                    publishLocalSnapshot(owner)
                    postAction(Action.LocalStateChanged(owner))
                }
            }
        }
        localInitializationFailed = false
        try {
            localLockWorker.execute {
                try { owner.initialize() } catch (_: Throwable) { postAction(Action.LocalInitializationFailed(owner)) }
            }
        } catch (_: Throwable) { postAction(Action.LocalInitializationFailed(owner)) }
    }

    private fun resetNativeLifecycle() {
        generation++
        parametersSent = false
        parametersHandled = false
        proxySent = false
        proxyHandled = false
        closeSent = false
        closeRequestFailed = false
        pendingProxy = null
        pendingLoginId = null
        activeLoginKind = null
        clearChats()
        journal.useStartupDirectory()
        mutableState.value = mutableState.value.copy(
            status = ClientStatus(engine = EnginePhase.STARTING), native = null, dataPath = null,
            note = "正在校验本地保护和数据目录。", generation = generation,
            closeTimedOut = false, proxy = ProxyStatus.NOT_CONFIGURED, proxyFailure = null,
            login = LoginState(),
        )
        record(DiagnosticKind.START_REQUESTED)
    }

    private fun startNativeAfterLocalGate(owner: LocalLockCoordinator, epoch: Long, needsStartupKey: Boolean,
        sessionTicket: LocalSessionKeyTicket? = null,
    ) {
        if (disposed || disposeRequested || localStopInProgress || localCoordinator !== owner || session != null || lease != null ||
            stopRequestRevision.get() != pendingStartStopRevision) return
        val witness = owner.currentWitness()
        val admission = if (sessionTicket != null) needsStartupKey && sessionTicket.epoch == epoch &&
            owner.isCurrentAndOpen(witness) else if (needsStartupKey) witness.blocked else owner.isCurrentAndOpen(witness)
        if (witness.epoch != epoch || !admission) return
        val configured = configuredBeforeUnlock ?: return
        val root = configuredRoot ?: return
        val rootLease = configuredRootLease ?: return
        val preparationRootRevision = rootOwnershipRevision.get()
        val preparationStopRevision = pendingStartStopRevision
        val preparationGeneration = generation
        fun engineCurrent() = stopRequestRevision.get() == preparationStopRevision &&
            rootOwnershipRevision.get() == preparationRootRevision && localCoordinator === owner &&
            generation == preparationGeneration && session == null && !disposed && !disposeRequested && !closeSent
        fun preparationCurrent() = sessionTicket == null ||
            owner.admitSessionPreparation(sessionTicket, preparationRootRevision, ::engineCurrent)
        if (!preparationCurrent()) return
        if (!rootLease.isCurrent()) throw SetupFailure("无法确认数据目录身份", "请保留数据目录并检查本地保护记录。", FailureCategory.DIRECTORY)
        if (!preparationCurrent()) return
        startupLockEpoch = epoch
        startupNeedsKey = needsStartupKey
        qrStartupTicket = sessionTicket
        qrStartupInterruptedByLock = false
        if (sessionTicket != null && owner.currentWitness().blocked) {
            qrStartupInterruptedByLock = true
            return
        }
        val proxySessionStartedAt = stopRequestRevision.get()
        // The previous session already owns notification setup; do not start it
        // as part of a replacement preparation after the ticket may be revoked.
        if (sessionTicket == null) bootstrapNotificationsIfOpen()
        if (!preparationCurrent()) return
        val nativeInfo = transport.load()
        if (!preparationCurrent()) return
        mutableState.value = mutableState.value.copy(native = nativeInfo)
        record(DiagnosticKind.NATIVE_LOADED)
        if (!preparationCurrent()) return
        val selection = AuthenticationDatabaseSelectionStore(root)
        val choice = selection.read()
        if (!preparationCurrent()) return
        val selectedDirectory = selection.directory(choice)
        if (!preparationCurrent()) return
        authenticationSelection = selection
        authenticationChoice = choice
        try {
            lease = if (selectedDirectory == root) rootLease.prepareDirectories(::preparationCurrent)
                else DataDirectoryLease.acquire(selectedDirectory, ::preparationCurrent)
        } catch (error: Exception) {
            if (sessionTicket != null && !preparationCurrent()) return
            throw error
        }
        if (!preparationCurrent()) return
        val config = TdConfiguration(configured.apiId, configured.apiHash, selectedDirectory, configured.useTestDc, configured.proxy)
        mutableState.value = mutableState.value.copy(loginInitialPhoneMode = choice.phoneMode,
            loginQrPhoneSwitchAvailable = !needsStartupKey)
        if (!preparationCurrent()) return
        journal.useDirectory(config.dataDirectory.resolve("logs"))
        if (!preparationCurrent()) return
        parameters = config
        pendingProxy = config.proxy
        usingProxy = config.proxy != null
        val current = generation
        // Native callbacks retain this session's epoch; late callbacks never invalidate a later generation.
        val artworkEpoch = AtomicLong()
        val stickerMediaEpoch = AtomicLong()
        val proxyOrdinaryWitness = AtomicBoolean(false)
        settingsProxyOrdinaryWitness = proxyOrdinaryWitness
        val proxyGateRevision = AtomicLong()
        settingsProxyGateRevision = proxyGateRevision
        profileEmojiEpoch = artworkEpoch
        profileStickerAvatarEpoch = stickerMediaEpoch
        profileEmojiSessionStopRevision = stopRequestRevision.get()
        // Bind this session to its starting revision; a later public Stop cannot
        // authorize a new page while the actor still exposes its old READY state.
        settingsProxySessionStopRevision = proxySessionStartedAt
        // Even an offline/preview UI must respect this actual background client's roots.
        // A create which throws may already have entered native code; keep its Unknown fence.
        if (sessionTicket != null && !owner.grantSessionCreate(sessionTicket, preparationRootRevision, ::engineCurrent)) return
        val createRegistration = if (transport.hasNativeFileScanRoots)
            (if (transport is OfficialTdTransport) com.badnng.moe.media.NativeSaveTargets.registry else saveTargetRegistry).registerUnknown(current)
        else null
        nativeSaveTargets = createRegistration
        nativeCreationUncertain = transport.hasNativeFileScanRoots
        session = transport.create(
            { if (it.phase == AuthorizationPhase.CLOSED) {
                    createRegistration?.confirmedClosed()
                    postAction(Action.OrphanNativeClosed(current))
                }
                if (it.phase != AuthorizationPhase.READY) {
                    notificationLockRevision.incrementAndGet()
                    artworkEpoch.incrementAndGet()
                    stickerMediaEpoch.incrementAndGet()
                    proxyGateRevision.incrementAndGet()
                    revokeSettingsProxyChecks(current)
                    revokeSettingsStorage(current)
                }
                postAction(Action.Authorization(current, it)) },
            { postAction(Action.Connection(current, it)) },
            { postAction(Action.CallbackError(current)) },
            { postAction(Action.ChatUpdate(current, it)) },
        )
        nativeCreationUncertain = false
        session?.interactionGate?.bindLocalWitness { localGateWitness(owner) }
        fun closeInterruptedQrCreate(): Boolean {
            if (sessionTicket == null) return false
            val stopped = stopRequestRevision.get() != preparationStopRevision || disposeRequested || disposed
            val latest = owner.currentWitness()
            val revoked = qrStartupInterruptedByLock || latest.epoch != sessionTicket.epoch || !owner.isCurrentAndOpen(latest)
            if (!stopped && !revoked) return false
            if (revoked && owner.state.value.phase != LocalLockPhase.RECOVERY) qrStartupInterruptedByLock = true
            mutableState.value = mutableState.value.copy(clientActive = true, dataPath = config.dataDirectory.toString())
            requestClose()
            return true
        }
        // A granted native create can finish after lock/stop. Do not initialize
        // account controllers or accept parameters for the returned client.
        if (closeInterruptedQrCreate()) return
        session?.chats?.let { chatSession ->
            val epoch = ++chatEpoch
            val folderNative = session
            val folderGate = folderNative?.interactionGate
            chatController = ChatController(
                generation = current,
                session = chatSession,
                enqueue = { postAction(Action.ChatWork(current, epoch, it)) },
                publish = {
                    // ChatController assigns account only after its correlated GetMe result; UpdateUser cannot select self.
                    val actualAccountUserId = it.account?.userId ?: 0L
                    if (profileEmojiAccountUserId.getAndSet(actualAccountUserId) != actualAccountUserId) {
                        profileEmojiEpoch.incrementAndGet()
                        profileStickerAvatarEpoch.incrementAndGet()
                    }
                    mutableState.value = mutableState.value.copy(chats = it)
                    refreshProfileEmojiArtwork()
                    refreshProfileStickerAvatarMedia()
                },
                nowMillis = nowMillis,
                schedule = { delayMillis, work ->
                    val witness = owner.currentWitness()
                    scope.launch {
                        delay(delayMillis)
                        if (localCoordinator === owner && owner.isCurrentAndOpen(witness)) work()
                    }
                },
                autoMediaAccess = com.badnng.moe.media.AutoMediaPreferencesStore(config.dataDirectory),
                isAccountAllowed = { ordinaryAccountAllowed() },
                enqueueFolderTerminal = { work -> if (folderNative != null)
                    postAction(Action.FolderTerminalWork(current, epoch, folderNative, owner, work)) },
                scheduleFolderTerminal = { millis, work -> scope.launch {
                    delay(millis)
                    if (folderNative != null) postAction(Action.FolderTerminalWork(current, epoch, folderNative, owner, work))
                } },
                captureFolderAuthority = {
                    val valid = folderGate?.captureFolderAuthority() ?: { false }
                    val authority: () -> Boolean = { localCoordinator === owner && session === folderNative &&
                        this@TdEngine.current(current) && epoch == chatEpoch &&
                        !closeSent && !disposeRequested && ordinaryAccountAllowed() && valid() }
                    authority
                },
            )
        }
        session?.settings?.let { settingsSession ->
            val specialNative = checkNotNull(session)
            val specialRoot = localCoordinator
            val specialTerminal = SettingsSpecialMutationBoundary(
                post = { postAction(Action.SettingsSpecialTerminalWork(current, specialNative, specialRoot, it)) },
                authority = { specialNative.interactionGate?.captureSpecialAuthority() ?: { true } },
                postReadback = { page, work ->
                    postAction(Action.SettingsSpecialReadbackWork(current, specialNative, specialRoot, page, work))
                },
            )
            settingsSession.storage?.let { storageSession ->
                val storageNative = session
                storageController = SettingsStorageController(current, storageSession,
                    enqueue = { postAction(Action.SettingsWork(current, it)) },
                    publish = { mutableState.value = mutableState.value.copy(storageSettings = it) },
                    volumeSource = {
                        val accepted = acceptedStorageVolume
                        if (accepted != null && accepted.generation == current && accepted.session === storageNative &&
                            session === accepted.session && lease === accepted.lease && !closeSent && !disposeRequested &&
                            stopRequestRevision.get() == settingsProxySessionStopRevision && settingsProxyOrdinaryWitness.get() &&
                            accepted.session.authorizationReady() && mutableState.value.status.authorization == AuthorizationPhase.READY)
                            accepted.lease.files else null
                    },
                    volumeCoordinator = settingsStorageVolumeCoordinator,
                    volumeCommit = { commit ->
                        val native = storageNative
                        if (native == null) false else {
                            val gate = native.interactionGate
                            val guarded = {
                                if (session !== native || !native.authorizationReady() ||
                                    !settingsProxyOrdinaryWitness.get() || stopRequestRevision.get() != settingsProxySessionStopRevision) false
                                else { commit(); true }
                            }
                            if (gate != null) gate.dispatchStorage(guarded) else guarded()
                        }
                    })
            }
            settingsSession.storagePolicy?.let { policySession ->
                storagePolicyController = SettingsStoragePolicyController(current, policySession,
                    enqueue = { postAction(Action.SettingsWork(current, it)) },
                    publish = { mutableState.value = mutableState.value.copy(storagePolicySettings = it) },
                    terminal = specialTerminal, isAllowed = ::ordinaryAccountAllowed)
            }
            val catalog = settingsSession.savedNotificationSounds
            val soundFiles = session?.notificationSoundFiles
            val sounds = if (catalog != null && soundFiles != null) OfficialNotificationSoundSource(catalog, soundFiles, scope) else null
            notificationSoundSource = sounds
            val preview = sounds?.let { NotificationSoundPreviewPlayer(it, desktopNotificationSignals { 0L }) }
            settingsController = SettingsController(current, settingsSession,
                enqueue = { postAction(Action.SettingsWork(current, it)) },
                publish = { settings ->
                    val confirmedConnection = SettingsSection.ADVANCED in settings.loaded &&
                        SettingsSection.ADVANCED !in settings.loading && SettingsSection.ADVANCED !in settings.saving &&
                        settings.errors[SettingsSection.ADVANCED] == null
                    if (confirmedConnection) usingProxy = settings.proxies.any { it.enabled }
                    mutableState.value = mutableState.value.copy(settings = settings,
                        proxy = if (confirmedConnection) {
                            if (usingProxy) ProxyStatus.ENABLED else ProxyStatus.DIRECT
                        } else mutableState.value.proxy)
                }, proxyAccepted = settingsProxyOwnership::markNativeSelection, ordinaryAllowed = ::ordinaryAccountAllowed,
                soundSource = sounds, soundPreview = preview,
                soundCatalogChanged = { deviceNotificationsController?.soundCatalogChanged() },
                soundVolumes = notificationSoundVolumes,
                soundVolumeChanged = { deviceNotificationsController?.soundVolumeChanged() },
                profileMutationReceipts = profileMutationReceipts,
                proxyProbeBudget = settingsProxyProbeBudget,
                enqueueMutation = { section, revision, work ->
                    val uncertain = session?.interactionGate?.callbackWriteIndeterminate() == true
                    postAction(Action.SettingsMutationWork(current, section, revision, uncertain, work))
                },
                withMutationContext = { work ->
                    val gate = session?.interactionGate
                    if (gate == null) work() else gate.withSettingsMutation(work)
                },
                readbackRevision = { proxyGateRevision.get() }, specialTerminal = specialTerminal)
        }
        session?.deviceNotifications?.let { notifications ->
            val notificationOwner = session
            deviceNotificationsController = DeviceNotificationsController(current, notifications,
                deviceNotificationPreferences,
                desktopNotificationSignals(notificationWindowHandle),
                post = { postAction(Action.SettingsWork(current, it)) },
                publish = { mutableState.value = mutableState.value.copy(deviceNotifications = it) },
                navigate = { chatId, messageId ->
                    if (ordinaryAccountAllowed()) chatController?.openNotificationTarget(mutableState.value.chats.token, chatId, messageId)
                    postAction(Action.NotificationFocus)
                }, focusLocked = { postAction(Action.NotificationFocus) }, windowsHost = windowsNotifications,
                externallyLocked = { !ordinaryAccountAllowed() }, externalLockRevision = notificationLockRevision::get,
                soundSource = notificationSoundSource,
                soundAccountUserId = { mutableState.value.chats.account?.userId ?: 0L },
                soundOutputAllowed = { notificationOwner?.authorizationReady() == true &&
                    notificationOwner.interactionGate?.allowsOrdinary() != false },
                soundVolumes = notificationSoundVolumes)
        }
        session?.loginEmail?.let { emailSession ->
            loginEmailController = LoginEmailController(current, emailSession,
                enqueue = { postAction(Action.LoginEmailWork(current, it)) },
                publish = { value ->
                    if (this@TdEngine.current(current) && !closeSent && !disposeRequested) {
                        mutableState.value = mutableState.value.copy(loginEmail = value)
                        applyInteractionGate()
                    }
                })
            // Initial cached suggestions are read synchronously; no network query is made here.
            loginEmailController?.start()
        }
        proxyOrdinaryWitness.set(session?.interactionGate?.allowsOrdinary() ?: !mutableState.value.loginEmail.mandatory)
        session?.interactionGate?.observe { allowed ->
            // observe runs with the native gate locked. Do not revoke a dispatch owner
            // here, because a dispatch holds its owner lock before entering that gate.
            proxyOrdinaryWitness.set(allowed)
            if (!allowed) {
                proxyGateRevision.incrementAndGet()
                notificationLockRevision.incrementAndGet()
                artworkEpoch.incrementAndGet()
                stickerMediaEpoch.incrementAndGet()
            }
            postAction(Action.InteractionGateChanged(current))
        }
        mutableState.value = mutableState.value.copy(clientActive = true, dataPath = config.dataDirectory.toString(), note = "客户端已创建，正在等待 TDLib 请求初始化参数。")
        record(DiagnosticKind.CLIENT_CREATED)
        closeInterruptedQrCreate()
    }

    private fun authorization(details: AuthorizationDetails) {
        val phase = details.phase
        if (phase != AuthorizationPhase.READY && mutableState.value.status.authorization == AuthorizationPhase.READY)
            retireLocalResumeSession()
        if (phase != AuthorizationPhase.READY) {
            revokeExpiredSettingsProxyChecks()
            val extras = mutableState.value.settings.profileExtras
            if (extras.currentPhoto.status != SettingsCurrentProfilePhotoStatus.CLOSED && extras.currentPhoto.epoch > 0)
                settingsController?.closeCurrentProfilePhoto(extras.token, extras.currentPhoto.epoch)
        }
        if (phase == AuthorizationPhase.READY) qrPhoneRestart?.takeIf { it.generation == generation }?.sawReady = true
        if (closeSent && phase != AuthorizationPhase.CLOSING && phase != AuthorizationPhase.CLOSED) return
        // A replacement number can produce identical delivery metadata. Its native
        // update still starts a new challenge and retires the earlier form.
        val replacedPhoneChallenge = activeLoginKind == LoginRequestKind.PHONE && phase in setOf(
            AuthorizationPhase.WAITING_CODE, AuthorizationPhase.WAITING_PASSWORD, AuthorizationPhase.OTHER_STEP,
        )
        if (mutableState.value.login.challenge != details || replacedPhoneChallenge) {
            pendingLoginId = null
            activeLoginKind = null
            mutableState.value = mutableState.value.copy(
                status = mutableState.value.status.reduce(StatusEvent.AuthorizationChanged(phase)),
                login = LoginState(challenge = details, revision = mutableState.value.login.revision + 1),
            )
            record(DiagnosticKind.AUTHORIZATION_CHANGED)
        }
        if (phase == AuthorizationPhase.CLOSED) {
            nativeSaveTargets = null
            val restart = qrPhoneRestart?.takeIf { it.generation == generation &&
                it.stopRevision == stopRequestRevision.get() && !disposeRequested }
            val owner = localCoordinator
            val locked = owner != null && qrStartupLockInterrupted(owner)
            val interruptedRestart = qrStartupRestart?.takeIf { locked &&
                it.stopRevision == stopRequestRevision.get() && !disposeRequested }
            val interruptedTicket = qrStartupTicket
            qrPhoneRestart = null
            clearChats()
            attachmentSendLeases.releaseAll()
            session = null
            mutableState.value = mutableState.value.copy(clientActive = false)
            closeSent = true
            parameters = null
            pendingProxy = null
            if (interruptedRestart != null && interruptedTicket != null && owner != null && releaseDirectory(keepConfiguredRoot = true)) {
                qrStartupTicket = null
                qrStartupRestart = null
                suspendQrAfterClosed(owner, interruptedRestart, interruptedTicket)
            } else if (restart != null && releaseDirectory(keepConfiguredRoot = true)) {
                if (restart.ticket != null) beginQrPreparation(restart)
                else try {
                    if (!restart.sawReady) authenticationSelection?.beginPhoneAttempt(restart.choice)
                        ?: throw AuthenticationDatabaseSelectionException()
                    rootOwnershipRevision.incrementAndGet()
                    mutableState.value = mutableState.value.copy(clientActive = false)
                    pendingStartStopRevision = stopRequestRevision.get()
                    resetNativeLifecycle()
                    val owner = checkNotNull(localCoordinator)
                    startNativeAfterLocalGate(owner, owner.currentWitness().epoch, needsStartupKey = false)
                } catch (_: AuthenticationDatabaseSelectionException) {
                    fail(SetupFailure("无法切换登录数据目录", "原有账号数据已保留，请停止后重新尝试。", FailureCategory.DIRECTORY))
                }
            } else if (restart == null) beginLocalStop(DiagnosticKind.CLOSE_CONFIRMED)
            else completeWaiters(false)
        } else if (phase == AuthorizationPhase.CLOSING) {
            clearChats()
            closeSent = true
            parameters = null
            pendingProxy = null
            if (mutableState.value.status.engine != EnginePhase.FAILED) updateEngine(EnginePhase.CLOSING)
        } else if (!closeSent && phase == AuthorizationPhase.WAITING_PARAMETERS && !parametersSent) {
            parametersSent = true
            val config = checkNotNull(parameters)
            val current = generation
            val native = checkNotNull(session)
            val directories = checkNotNull(lease)
            val owner = checkNotNull(localCoordinator)
            val rootLease = checkNotNull(configuredRootLease)
            val selected = checkNotNull(authenticationSelection)
            val choice = checkNotNull(authenticationChoice)
            val epoch = startupLockEpoch
            val needsKey = startupNeedsKey
            val sessionTicket = qrStartupTicket
            val stoppedAt = pendingStartStopRevision
            val startupRootRevision = rootOwnershipRevision.get()
            fun result(value: TdParametersResult) { postAction(Action.ParametersResult(current, epoch, needsKey, value, stoppedAt)) }
            localLockWorker.execute {
                var submitted = false
                try {
                    fun engineCurrent() = generation == current && localCoordinator === owner && session === native &&
                        stopRequestRevision.get() == stoppedAt && rootOwnershipRevision.get() == startupRootRevision &&
                        !disposed && !closeSent
                    fun qrReadAllowed() = sessionTicket != null &&
                        owner.admitSessionParameterValidation(sessionTicket, startupRootRevision, ::engineCurrent)
                    val verified = if (sessionTicket == null) rootLease.isCurrent() && selected.read() == choice
                        else qrReadAllowed() && qrStartupRootCurrent(rootLease) && qrReadAllowed() &&
                            qrStartupReadSelection(selected) == choice && qrReadAllowed()
                    if (verified) {
                        fun send(key: ByteArray) = synchronized(startupDispatchLock) {
                            if (session === native && stopRequestRevision.get() == stoppedAt && !disposed && !closeSent) {
                                native.setParameters(config, directories, key, ::result)
                                submitted = true
                            }
                        }
                        if (needsKey && sessionTicket != null) owner.borrowSessionKey(sessionTicket, ::engineCurrent, ::send)
                        else if (needsKey) owner.borrowStartupKey(epoch, ::send)
                        else if (owner.isCurrentAndOpen(owner.currentWitness()) && owner.currentWitness().epoch == epoch)
                            send(ByteArray(0))
                    }
                } catch (_: Exception) { }
                if (!submitted) {
                    if (sessionTicket != null && qrStartupTicket === sessionTicket && localCoordinator === owner &&
                        qrStartupLockInterrupted(owner))
                        postAction(Action.QrStartupAbort(current, sessionTicket))
                    else result(TdParametersResult(-1))
                }
            }
            parameters = null
            mutableState.value = mutableState.value.copy(note = "正在校验本地保护并提交初始化参数。")
            record(DiagnosticKind.PARAMETERS_SENT)
        } else if (!closeSent && phase !in setOf(AuthorizationPhase.UNKNOWN, AuthorizationPhase.WAITING_PARAMETERS)) {
            updateEngine(EnginePhase.INITIALIZED)
            mutableState.value = mutableState.value.copy(note = when (phase) {
                AuthorizationPhase.WAITING_LOGIN -> "TDLib 初始化完成，请在下方输入手机号登录。"
                AuthorizationPhase.WAITING_CODE -> "请查看验证码发送方式，输入收到的验证码。"
                AuthorizationPhase.WAITING_PASSWORD -> "账号已启用二步验证，请输入登录密码。"
                AuthorizationPhase.READY -> "TDLib 已确认账号登录。账号授权与网络连接状态独立显示。"
                else -> "TDLib 请求其他验证步骤，请查看下方登录说明。"
            })
        }
        startChatsWhenReady()
        localCoordinator?.let(::updateLocalIdle)
    }

    /** Actor only. Reopening a native gate creates a fresh provider, never revives an old lease. */
    private fun refreshProfileEmojiArtwork() {
        val owner = session
        val allowed = owner != null && !closeSent && !disposeRequested && ordinaryAccountAllowed() &&
            stopRequestRevision.get() == profileEmojiSessionStopRevision &&
            mutableState.value.proxy in setOf(ProxyStatus.DIRECT, ProxyStatus.ENABLED) &&
            profileEmojiAccountUserId.get() > 0L
        if (!allowed || (profileEmojiSource != null && profileEmojiSourceEpoch != profileEmojiEpoch.get()))
            closeProfileEmojiArtwork()
        if (!allowed || profileEmojiSource != null) return
        val nativeOwner = owner ?: return
        val nativeGate = nativeOwner.interactionGate ?: return
        val catalog = nativeOwner.settings?.profileColors ?: return
        val files = nativeOwner.profileEmojiFiles ?: return
        val sourceGeneration = generation
        val epochs = profileEmojiEpoch
        val accounts = profileEmojiAccountUserId
        val stops = stopRequestRevision
        val sourceEpoch = epochs.get()
        val stopRevision = stops.get()
        // These suppliers may run on a worker or in the download broker's lock. No actor maps/state are read.
        val outputAllowed = {
            epochs.get() == sourceEpoch && stops.get() == stopRevision && accounts.get() > 0L &&
                nativeOwner.authorizationReady() && nativeGate.allowsOrdinary()
        }
        val source = profileEmojiArtworkFactory(ProfileEmojiArtworkEnvironment(
            sourceGeneration, catalog, files, scope, accounts::get, outputAllowed,
            admit = { work, discard ->
                if (!outputAllowed()) discard()
                else {
                    val action = Action.ProfileEmojiArtworkWork(sourceGeneration, sourceEpoch, stopRevision, nativeOwner, work, discard)
                    if (postAction(action).isFailure) discard()
                }
            },
        ))
        profileEmojiSourceEpoch = sourceEpoch
        profileEmojiSource = source
        mutableProfileEmojiArtwork.value = source
    }

    /** Before borrowed files/catalog and the session's single global download broker retire. */
    private fun closeProfileEmojiArtwork() {
        profileEmojiEpoch.incrementAndGet()
        mutableProfileEmojiArtwork.value = null
        val old = profileEmojiSource
        profileEmojiSource = null
        profileEmojiSourceEpoch = -1L
        old?.close()
    }

    /** Actor only. The source is tied to one correlated GetMe account and one native session. */
    private fun refreshProfileStickerAvatarMedia() {
        val owner = session
        val accountId = profileEmojiAccountUserId.get()
        val allowed = owner != null && !closeSent && !disposeRequested && ordinaryAccountAllowed() &&
            stopRequestRevision.get() == profileEmojiSessionStopRevision &&
            mutableState.value.proxy in setOf(ProxyStatus.DIRECT, ProxyStatus.ENABLED) && accountId > 0L
        if (!allowed || (mutableProfileStickerAvatarMedia.value != null &&
                profileStickerAvatarSourceEpoch != profileStickerAvatarEpoch.get())) closeProfileStickerAvatarMedia()
        if (!allowed || mutableProfileStickerAvatarMedia.value != null) return
        val nativeOwner = owner ?: return
        val nativeGate = nativeOwner.interactionGate ?: return
        if (nativeOwner !is ProfileStickerAvatarMediaSession) return
        val sourceGeneration = generation
        val epochs = profileStickerAvatarEpoch
        val accounts = profileEmojiAccountUserId
        val stops = stopRequestRevision
        val snapshots = mutableState
        val sourceEpoch = epochs.get()
        val stopRevision = stops.get()
        val outputAllowed = {
            epochs.get() == sourceEpoch && stops.get() == stopRevision && accounts.get() == accountId &&
                snapshots.value.status.authorization == AuthorizationPhase.READY &&
                snapshots.value.proxy in setOf(ProxyStatus.DIRECT, ProxyStatus.ENABLED) &&
                nativeOwner.authorizationReady() && nativeGate.allowsOrdinary()
        }
        lateinit var environment: ProfileStickerAvatarMediaEnvironment
        environment = ProfileStickerAvatarMediaEnvironment(sourceGeneration, outputAllowed,
            savedRead = { request, active, demand, result ->
                acquireSettingsSavedProfileStickerAvatarMediaOwned(request, active, demand, result)
            }) { request, bridge ->
            if (!outputAllowed()) bridge.discard()
            else {
                val action = Action.ProfileStickerAvatarMediaWork(sourceGeneration, sourceEpoch, stopRevision,
                    nativeOwner, environment, request, bridge)
                if (postAction(action).isFailure) bridge.discard()
            }
        }
        profileStickerAvatarSourceEpoch = sourceEpoch
        mutableProfileStickerAvatarMedia.value = environment
    }

    private fun closeProfileStickerAvatarMedia() {
        profileStickerAvatarEpoch.incrementAndGet()
        val old = mutableProfileStickerAvatarMedia.value
        mutableProfileStickerAvatarMedia.value = null
        profileStickerAvatarSourceEpoch = -1L
        old?.close()
    }

    private fun startChatsWhenReady() {
        refreshProfileEmojiArtwork()
        refreshProfileStickerAvatarMedia()
        val snapshot = state.value
        if (!closeSent && !disposeRequested && snapshot.status.authorization == AuthorizationPhase.READY &&
            snapshot.proxy in setOf(ProxyStatus.DIRECT, ProxyStatus.ENABLED) && ordinaryAccountAllowed()) {
            chatController?.start()
            settingsController?.start()
            deviceNotificationsController?.start()
        }
    }

    private fun applyInteractionGate() {
        refreshProfileEmojiArtwork()
        refreshProfileStickerAvatarMedia()
        val locked = !ordinaryAllowed()
        val emailBlocked = session?.interactionGate?.emailRequiredBlocked() ?: snapshotEmailBlocked()
        settingsProxyOrdinaryWitness.set(!locked)
        revokeExpiredSettingsProxyChecks()
        revokeExpiredSettingsStorage()
        val snapshot = mutableState.value
        if (snapshot.loginEmail.mandatory != emailBlocked) mutableState.value = snapshot.copy(
            loginEmail = snapshot.loginEmail.copy(generation = generation, mandatory = emailBlocked))
        if (locked) {
            revokeSettingsProxyChecks()
            revokeSettingsStorage()
            settingsController?.revokeProxyChecks()
            chatController?.cancelSupportOpening()
            chatController?.visibleMessages(snapshot.chats.token, emptyList(), false)
            snapshot.settings.security.token?.let { settingsController?.closeSecurity(it) }
            snapshot.settings.profileExtras.token?.let { settingsController?.closeProfileExtras(it) }
            if (snapshot.settings.privacyCommon.opened) settingsController?.closePrivacyCommon(snapshot.settings.privacyCommon.token)
            if (snapshot.settings.contactSignup.opened) settingsController?.closeContactSignup(snapshot.settings.contactSignup.token)
            if (snapshot.settings.notificationExceptions.opened)
                settingsController?.closeNotificationExceptions(snapshot.settings.notificationExceptions.token)
            if (snapshot.settings.savedNotificationSounds.opened)
                settingsController?.closeSavedNotificationSounds(snapshot.settings.savedNotificationSounds.token)
            if (snapshot.settings.profileColors.opened)
                settingsController?.closeProfileColors(snapshot.settings.profileColors.token)
            if (snapshot.settings.reactionNotifications.opened)
                settingsController?.closeReactionNotifications(snapshot.settings.reactionNotifications.token)
            storageController?.close(snapshot.storageSettings.token)
            storagePolicyController?.close(snapshot.storagePolicySettings.token)
            if (snapshot.chats.folderSettings.opened) snapshot.chats.folderSettings.token?.let { chatController?.closeFolderSettings(it) }
        }
        deviceNotificationsController?.setContext(notificationForeground,
            notificationActiveChatId.takeUnless { locked }, notificationUiLocked || locked)
    }

    private fun snapshotEmailBlocked() = mutableState.value.loginEmail.mandatory

    private fun clearChats() {
        acceptedStorageVolume = null
        revokeSettingsProxyChecks()
        revokeSettingsStorage()
        settingsProxyOrdinaryWitness.set(false)
        closeProfileEmojiArtwork()
        closeProfileStickerAvatarMedia()
        profileEmojiAccountUserId.set(0L)
        session?.interactionGate?.observe(null)
        loginEmailController?.deactivate()
        loginEmailController = null
        notificationForeground = false
        notificationActiveChatId = null
        notificationUiLocked = false
        chatEpoch++
        chatController?.deactivate()
        chatController = null
        settingsController?.deactivate()
        settingsController = null
        deviceNotificationsController?.close()
        deviceNotificationsController = null
        notificationSoundSource?.close()
        notificationSoundSource = null
        storageController?.deactivate()
        storageController = null
        storagePolicyController?.deactivate()
        storagePolicyController = null
        session?.retireDownloads()
        mutableState.value = mutableState.value.copy(chats = ChatSnapshot(token = ChatToken(generation, 0, null)),
            settings = SettingsSnapshot(token = SettingsToken(generation)),
            deviceNotifications = DeviceNotificationsSnapshot(generation = generation),
            storageSettings = SettingsStorageSnapshot(token = SettingsStorageToken(generation)),
            storagePolicySettings = SettingsStoragePolicySnapshot(token = SettingsStoragePolicyToken(generation)),
            loginEmail = LoginEmailSnapshot(generation = generation))
    }

    private fun connection(phase: ConnectionPhase) {
        if (mutableState.value.status.connection == phase) return
        mutableState.value = mutableState.value.copy(status = mutableState.value.status.reduce(StatusEvent.ConnectionChanged(phase)))
        record(DiagnosticKind.CONNECTION_CHANGED)
    }

    private fun submitLoginInput(action: Action.LoginInput) {
        // Clear the command even when it became stale while queued.
        val supplied = action.takeValue() ?: return
        val snapshot = state.value
        if (closeSent || disposeRequested || session == null || !localAllowed() || snapshot.loginToken != action.token ||
            !snapshot.canSubmitLogin(action.kind)) return
        val value = when (action.kind) {
            LoginRequestKind.PHONE -> supplied.filterNot { it.isWhitespace() || it in "-()" }
            LoginRequestKind.PASSWORD -> supplied
            else -> supplied.trim()
        }
        val invalid = when {
            action.kind.requiresInput && value.isEmpty() -> LoginError.EMPTY_INPUT
            action.kind == LoginRequestKind.PHONE && !value.matches(Regex("\\+[1-9][0-9]{4,14}")) -> LoginError.INVALID_PHONE
            else -> null
        }
        if (invalid != null) {
            mutableState.value = snapshot.copy(login = snapshot.login.copy(error = invalid, revision = snapshot.login.revision + 1))
            record(DiagnosticKind.LOGIN_REJECTED, FailureCategory.AUTHENTICATION)
            return
        }
        val requestId = ++loginRequestId
        val token = action.token
        pendingLoginId = requestId
        activeLoginKind = action.kind
        mutableState.value = snapshot.copy(login = snapshot.login.copy(pending = true, awaitingAuthorization = false, error = null))
        record(DiagnosticKind.LOGIN_SUBMITTED)
        try {
            session?.submitLogin(action.kind, value) { result ->
                postAction(Action.LoginCompleted(token, requestId, result))
            }
        } catch (_: Exception) {
            loginCompleted(Action.LoginCompleted(token, requestId, LoginResult(LoginError.REQUEST_FAILED, -1)))
        }
    }

    private fun loginCompleted(action: Action.LoginCompleted) {
        val snapshot = state.value
        if (closeSent || disposeRequested || !current(action.token.generation) ||
            snapshot.loginToken != action.token || pendingLoginId != action.requestId) return
        pendingLoginId = null
        val accepted = action.result.error == null
        val awaitsAuthorization = accepted && activeLoginKind?.awaitsAuthorization == true
        if (!awaitsAuthorization) activeLoginKind = null
        mutableState.value = snapshot.copy(login = snapshot.login.copy(
            pending = false, awaitingAuthorization = awaitsAuthorization, error = action.result.error,
            revision = snapshot.login.revision + if (accepted && !awaitsAuthorization) 1 else 0,
        ))
        if (accepted) record(DiagnosticKind.LOGIN_ACCEPTED)
        else record(DiagnosticKind.LOGIN_REJECTED, FailureCategory.AUTHENTICATION, action.result.code)
    }

    private fun invalidateLogin() {
        pendingLoginId = null
        activeLoginKind = null
        val login = mutableState.value.login
        mutableState.value = mutableState.value.copy(login = LoginState(login.challenge, login.revision + 1))
    }

    private fun parametersResult(code: Int?) {
        if (!parametersSent || parametersHandled) return
        parametersHandled = true
        if (code != null) {
            fail(SetupFailure("TDLib 拒绝初始化参数", "请核对本地应用凭据和数据目录，再重新启动。", FailureCategory.TDLIB_REQUEST), code)
            return
        }
        // Synthetic transports default to false and never imply a native directory.
        acceptedStorageVolume = if (transport.hasNativeFileScanRoots) {
            val native = session
            val actualLease = lease
            if (native != null && actualLease != null) AcceptedStorageVolume(generation, native, actualLease) else null
        } else null
        storageController?.acceptedVolumeAvailable()
        lease?.let { nativeSaveTargets?.accepted(it.database, it.files) }
        updateEngine(EnginePhase.INITIALIZED)
        record(DiagnosticKind.PARAMETERS_ACCEPTED)
        continueProxyAfterParameters()
    }

    private fun continueProxyAfterParameters() {
        if (!parametersHandled || proxySent || closeSent || disposeRequested || session == null || !localAllowed()) return
        proxySent = true
        mutableState.value = mutableState.value.copy(proxy = ProxyStatus.CONFIGURING)
        record(DiagnosticKind.PROXY_CONFIGURING)
        val current = generation
        if (settingsProxyOwnership.usesNativeSelection()) {
            session?.resumeConfiguredProxy { postAction(Action.ProxyResult(current, it)) }
        } else session?.configureProxy(pendingProxy) { postAction(Action.ProxyResult(current, it)) }
        pendingProxy = null
    }

    private fun proxyResult(code: Int?) {
        if (!proxySent || proxyHandled) return
        proxyHandled = true
        if (code == null) {
            session?.configuredProxyEnabled?.let { usingProxy = it }
            mutableState.value = mutableState.value.copy(proxy = if (usingProxy) ProxyStatus.ENABLED else ProxyStatus.DIRECT)
            record(DiagnosticKind.PROXY_APPLIED)
            startChatsWhenReady()
        } else {
            mutableState.value = mutableState.value.copy(
                proxy = ProxyStatus.FAILED,
                proxyFailure = EngineFailure("无法应用连接方式", "请在本地修正代理配置并重启客户端。", FailureCategory.PROXY),
                note = "初始化已完成，但连接方式应用失败。修正配置后可停止并重启客户端。",
            )
            record(DiagnosticKind.PROXY_FAILED, FailureCategory.PROXY, code)
        }
    }

    private fun fail(failure: SetupFailure, code: Int? = null) {
        invalidateLogin()
        clearChats()
        parameters = null
        pendingProxy = null
        mutableState.value = mutableState.value.copy(
            status = mutableState.value.status.reduce(StatusEvent.EngineChanged(EnginePhase.FAILED, EngineFailure(failure.summary, failure.recovery, failure.category))),
            note = failure.summary,
        )
        record(DiagnosticKind.FAILURE, failure.category, code)
        if (localCoordinator == null && session == null && !nativeCreationUncertain) {
            val safe = if (configuredBeforeUnlock == null) LocalLockSnapshot(phase = LocalLockPhase.UNPROTECTED)
                else LocalLockSnapshot(phase = LocalLockPhase.RECOVERY, issue = LocalLockIssue.IO_FAILURE)
            mutableLocalLock.value = safe
            mutableState.update { it.copy(localLock = safe) }
        }
        if (session != null) requestClose()
        else if (!nativeCreationUncertain) beginLocalStop(DiagnosticKind.STOPPED_WITHOUT_CLIENT)
        else mutableState.value = mutableState.value.copy(clientActive = true,
            note = "客户端创建结果尚未确认；数据目录和保护状态仍保留，等待真实关闭。")
    }

    private fun stopClient(action: Action.Stop) {
        qrPhoneRestart = null
        qrStartupTicket = null
        qrStartupRestart = null
        qrStartupInterruptedByLock = false
        if (disposed) {
            action.result.complete(true)
            return
        }
        disposeRequested = disposeRequested || action.dispose
        invalidateLogin()
        clearChats()
        closeWaiters[action.id] = action.result
        parameters = null
        pendingProxy = null
        if (session == null) {
            if (!nativeCreationUncertain) beginLocalStop(DiagnosticKind.STOPPED_WITHOUT_CLIENT)
            else mutableState.value = mutableState.value.copy(clientActive = true,
                note = "客户端创建结果尚未确认；完成前保留数据目录锁。")
        } else {
            updateEngine(EnginePhase.CLOSING)
            mutableState.value = mutableState.value.copy(closeTimedOut = false, note = "正在等待 TDLib 确认关闭；完成前保留客户端和数据目录锁。")
            requestClose()
        }
    }

    private fun requestClose() {
        val active = session ?: return
        revokeSettingsProxyChecks()
        revokeSettingsStorage()
        if (closeSent && !closeRequestFailed) return
        closeSent = true
        closeRequestFailed = false
        record(DiagnosticKind.CLOSE_REQUESTED)
        try {
            synchronized(startupDispatchLock) { active.close() }
        } catch (_: Exception) {
            closeRequestFailed = true
            mutableState.value = mutableState.value.copy(
                status = mutableState.value.status.reduce(StatusEvent.EngineChanged(EnginePhase.FAILED,
                    EngineFailure("无法提交关闭请求", "请继续等待或再次请求停止，完成前不要复用数据目录。", FailureCategory.LIFECYCLE))),
                note = "关闭请求未成功提交，客户端与目录锁仍保留。",
            )
            record(DiagnosticKind.FAILURE, FailureCategory.LIFECYCLE)
        }
    }

    private fun beginLocalStop(kind: DiagnosticKind) {
        if (qrSwitchWorkRunning) {
            qrDeferredStop = kind
            return
        }
        if (localStopInProgress) return
        val owner = localCoordinator
        if (owner == null) {
            if (releaseDirectory()) completeStopped(kind) else completeWaiters(false)
            return
        }
        localStopInProgress = true
        localLockWorker.execute { owner.stop { postAction(Action.LocalStopReady(owner, kind)) } }
    }

    private fun releaseDirectory(keepConfiguredRoot: Boolean = false): Boolean = try {
        lease?.close()
        lease = null
        if (!keepConfiguredRoot) {
            configuredRootLease?.close()
            configuredRootLease = null
            configuredRoot = null
            configuredBeforeUnlock = null
            rootOwnershipRevision.incrementAndGet()
            authenticationSelection = null
            authenticationChoice = null
        }
        true
    } catch (_: Exception) {
        mutableState.value = mutableState.value.copy(
            status = mutableState.value.status.reduce(StatusEvent.EngineChanged(EnginePhase.FAILED,
                EngineFailure("无法确认数据目录句柄已释放", "请再次停止客户端；释放完成前不要复用数据目录。", FailureCategory.DIRECTORY))),
            clientActive = true, note = "TDLib 已停止，但数据目录句柄释放未完成。",
        )
        record(DiagnosticKind.FAILURE, FailureCategory.DIRECTORY)
        false
    }

    private fun completeStopped(kind: DiagnosticKind) {
        val failed = mutableState.value.status.engine == EnginePhase.FAILED
        if (!failed) updateEngine(EnginePhase.CLOSED)
        mutableState.value = mutableState.value.copy(
            status = mutableState.value.status.reduce(StatusEvent.ConnectionChanged(ConnectionPhase.UNKNOWN)),
            clientActive = false, closeTimedOut = false, proxy = ProxyStatus.NOT_CONFIGURED,
            note = if (failed) "客户端已释放，可修复问题后重试。" else "TDLib 客户端已停止，数据目录已释放。",
        )
        record(kind)
        if (disposeRequested) {
            disposed = true
            disposeLocalResumeLease()
            localIdle.close()
            localIdleScheduler.close()
            actions.close()
            // The UI can exit the JVM as soon as this is observed; persist the final event first.
            mutableState.value = mutableState.value.copy(terminated = true)
        }
        completeWaiters(true)
    }

    private fun completeWaiters(success: Boolean) {
        closeWaiters.values.forEach { it.complete(success) }
        closeWaiters.clear()
    }

    private fun removeWaiter(action: Action.RemoveWaiter) {
        if (closeWaiters.remove(action.id) == null || !action.timedOut ||
            (session == null && !nativeCreationUncertain && !localStopInProgress && !qrSwitchWorkRunning)) return
        mutableState.value = mutableState.value.copy(closeTimedOut = true, note = "客户端或本地保护工作尚未确认结束；目录锁仍保留，可继续等待。")
        record(DiagnosticKind.CLOSE_TIMED_OUT, FailureCategory.LIFECYCLE)
    }

    private fun updateEngine(phase: EnginePhase) {
        mutableState.value = mutableState.value.copy(status = mutableState.value.status.reduce(StatusEvent.EngineChanged(phase)))
    }

    private fun record(kind: DiagnosticKind, category: FailureCategory? = null, code: Int? = null) {
        val snapshot = mutableState.value
        journal.record(DiagnosticEvent(nowMillis(), generation, kind, snapshot.status.engine,
            snapshot.status.authorization, snapshot.status.connection, category, code,
            snapshot.native?.version, snapshot.native?.commit))
        mutableState.value = snapshot.copy(events = journal.snapshot(), eventLogPath = journal.path.toString(), eventLogAvailable = journal.available)
    }

    private sealed interface Action {
        data object Start : Action
        data class LocalStartReady(val owner: LocalLockCoordinator, val epoch: Long, val needsStartupKey: Boolean) : Action
        data class QrCapabilityReady(val owner: LocalLockCoordinator, val session: TdSession,
            val generation: Long, val stopRevision: Long, val witness: LocalLockWitness,
            val serial: Long, val ticket: LocalSessionKeyTicket?) : Action
        data class QrPreparationReady(val owner: LocalLockCoordinator, val restart: QrPhoneRestart,
            val ticket: LocalSessionKeyTicket?, val suspended: Boolean, val failed: Boolean) : Action
        data class QrStartupAbort(val generation: Long, val ticket: LocalSessionKeyTicket) : Action
        data class LocalInitializationFailed(val owner: LocalLockCoordinator) : Action
        data class LocalStateChanged(val owner: LocalLockCoordinator) : Action
        data class LocalGateChanged(val owner: LocalLockCoordinator, val witness: LocalLockWitness) : Action
        data class LocalIdleDue(val due: LocalLockIdleDue) : Action
        data object LocalIdleAvailability : Action
        data object LocalResumeAvailability : Action
        data class LocalPowerResume(val generation: Long, val signal: LocalLockWindowsResumeSignal) : Action
        data class LocalStopReady(val owner: LocalLockCoordinator, val kind: DiagnosticKind) : Action
        data class OrphanNativeClosed(val generation: Long) : Action
        data class LocalParametersHandled(val generation: Long, val owner: LocalLockCoordinator,
            val result: TdParametersResult, val accepted: Boolean, val stopRevision: Long) : Action
        data object NotificationFocus : Action
        data object NotificationWindowReady : Action
        data class Authorization(val generation: Long, val details: AuthorizationDetails) : Action
        class LoginInput(val token: LoginToken, val kind: LoginRequestKind, private var value: String?) : Action {
            fun takeValue(): String? = value.also { value = null }
            override fun toString() = "LoginInput(<redacted>)"
        }
        data class CancelQr(val token: LoginToken) : Action
        data class LoginCompleted(val token: LoginToken, val requestId: Long, val result: LoginResult) : Action
        class ChatUpdate(val generation: Long, val event: ChatEvent) : Action {
            override fun toString() = "ChatUpdate(<redacted>)"
        }
        class ChatWork(val generation: Long, val epoch: Long, val work: () -> Unit) : Action
        class FolderTerminalWork(val generation: Long, val epoch: Long, val session: TdSession,
            val root: LocalLockCoordinator, val work: () -> Unit) : Action {
            override fun toString() = "FolderTerminalWork(<redacted>)"
        }
        class ProfileEmojiArtworkWork(val generation: Long, val epoch: Long, val stopRevision: Long,
            val session: TdSession, val work: () -> Unit, val discard: () -> Unit,
        ) : Action { override fun toString() = "ProfileEmojiArtworkWork(<redacted>)" }
        class ProfileStickerAvatarMediaWork(val generation: Long, val epoch: Long, val stopRevision: Long,
            val session: TdSession, val environment: ProfileStickerAvatarMediaEnvironment,
            val request: ProfileStickerAvatarMediaRequest, val bridge: ProfileStickerAvatarMediaBridge,
        ) : Action { override fun toString() = "ProfileStickerAvatarMediaWork(<redacted>)" }
        class SavedProfileStickerMediaWork(val generation: Long, val epoch: Long, val stopRevision: Long,
            val session: TdSession, val environment: ProfileStickerAvatarMediaEnvironment,
            val request: SettingsSavedProfileStickerAvatarRequest, val bridge: SavedProfileStickerMediaBridge,
            val demand: SettingsSavedProfileStickerAvatarDemand,
            val limited: Boolean,
        ) : Action { override fun toString() = "SavedProfileStickerMediaWork(<redacted>)" }
        class ChatInput(val token: ChatToken, val work: (ChatController) -> Unit, val onDiscard: () -> Unit = {},
            val cleanup: Boolean = false,
        ) : Action {
            override fun toString() = "ChatInput(<redacted>)"
        }
        class SettingsWork(val generation: Long, val work: () -> Unit) : Action
        class SettingsSpecialTerminalWork(val generation: Long, val session: TdSession,
            val root: LocalLockCoordinator?, val work: () -> Unit) : Action
        class SettingsSpecialReadbackWork(val generation: Long, val session: TdSession,
            val root: LocalLockCoordinator?, val pageCurrent: () -> Boolean, val work: () -> Unit) : Action
        class SettingsMutationWork(val generation: Long, val section: SettingsSection, val revision: Long,
            val indeterminate: Boolean, val work: () -> Unit) : Action
        class DeviceNotificationInput(val generation: Long, val allowLocked: Boolean = false, val localCleanup: Boolean = false,
            val work: (DeviceNotificationsController) -> Unit,
        ) : Action
        class StorageInput(val generation: Long, val work: (SettingsStorageController) -> Unit, val cleanup: Boolean = false,
            val onDiscard: () -> Unit = {},
        ) : Action
        class StoragePolicyInput(val generation: Long, val work: (SettingsStoragePolicyController) -> Unit,
            val discard: () -> Unit, val cleanup: Boolean = false,
        ) : Action { override fun toString() = "StoragePolicyInput(<redacted>)" }
        class SettingsInput(val token: SettingsToken, val work: (SettingsController) -> Unit, val cleanup: Boolean = false) : Action {
            override fun toString() = "SettingsInput(<redacted>)"
        }
        class ProxyChecksOpen(val page: SettingsProxyChecksEnginePage, val opened: (SettingsProxyChecksToken?) -> Unit) : Action
        class ProxyChecksOpened(val page: SettingsProxyChecksEnginePage, val token: SettingsProxyChecksToken?,
            val opened: (SettingsProxyChecksToken?) -> Unit) : Action
        class ProxyChecksClose(val page: SettingsProxyChecksEnginePage) : Action
        class ProxyChecksRefresh(val page: SettingsProxyChecksEnginePage) : Action
        class ProxyChecksShare(val page: SettingsProxyChecksEnginePage, val request: SettingsProxyShareRequest,
            val owner: SettingsProxyProbeOwner,
            val result: (SettingsResult<SettingsProxyShareContent>) -> Unit) : Action {
            override fun toString() = "ProxyChecksShare(<redacted>)"
        }
        class UsernameInput(val token: SettingsToken, val work: (SettingsController) -> Unit, val discard: () -> Unit) : Action {
            override fun toString() = "UsernameInput(<redacted>)"
        }
        class SecurityInput(val token: SettingsSecurityToken, val work: (SettingsController) -> Unit, val discard: () -> Unit,
            val cleanup: Boolean = false,
        ) : Action {
            override fun toString() = "SecurityInput(<redacted>)"
        }
        class FolderSettingsInput(val token: FolderSettingsToken, val work: (ChatController) -> Unit, val cleanup: Boolean = false) : Action {
            override fun toString() = "FolderSettingsInput(<redacted>)"
        }
        class ProfileExtrasInput(val token: SettingsProfileExtrasToken, val work: (SettingsController) -> Unit, val discard: () -> Unit,
            val cleanup: Boolean = false,
        ) : Action {
            override fun toString() = "ProfileExtrasInput(<redacted>)"
        }
        class PrivacyCommonInput(val token: PrivacyCommonToken, val work: (SettingsController) -> Unit, val cleanup: Boolean) : Action {
            override fun toString() = "PrivacyCommonInput(<redacted>)"
        }
        class ContactSignupInput(val token: ContactSignupToken, val work: (SettingsController) -> Unit, val cleanup: Boolean) : Action {
            override fun toString() = "ContactSignupInput(<redacted>)"
        }
        class ReactionNotificationsInput(val token: ReactionNotificationsToken, val work: (SettingsController) -> Unit,
            val cleanup: Boolean,
        ) : Action { override fun toString() = "ReactionNotificationsInput(<redacted>)" }
        class NotificationExceptionsInput(val token: NotificationExceptionsToken, val work: (SettingsController) -> Unit,
            val cleanup: Boolean,
        ) : Action {
            override fun toString() = "NotificationExceptionsInput(<redacted>)"
        }
        class SavedNotificationSoundsInput(val token: SavedNotificationSoundsToken, val work: (SettingsController) -> Unit,
            val discard: () -> Unit, val cleanup: Boolean,
        ) : Action {
            override fun toString() = "SavedNotificationSoundsInput(<redacted>)"
        }
        class ProfileColorsInput(val token: SettingsProfileColorsToken, val work: (SettingsController) -> Unit,
            val cleanup: Boolean,
        ) : Action {
            override fun toString() = "ProfileColorsInput(<redacted>)"
        }
        class LoginEmailWork(val generation: Long, val work: () -> Unit) : Action
        class LoginEmailInput(val generation: Long, val work: (LoginEmailController) -> Unit, val discard: () -> Unit,
            val cleanup: Boolean = false,
        ) : Action { override fun toString() = "LoginEmailInput(<redacted>)" }
        class InteractionGateChanged(val generation: Long) : Action
        data class Connection(val generation: Long, val phase: ConnectionPhase) : Action
        data class ParametersResult(val generation: Long, val epoch: Long, val needsStartupKey: Boolean,
            val result: TdParametersResult, val stopRevision: Long) : Action
        data class ProxyResult(val generation: Long, val code: Int?) : Action
        data class CallbackError(val generation: Long) : Action
        data class Stop(val id: Long, val dispose: Boolean, val result: CompletableDeferred<Boolean>) : Action
        data class RemoveWaiter(val id: Long, val timedOut: Boolean) : Action
    }

    private class QrPhoneRestart(val generation: Long, val stopRevision: Long, val choice: AuthenticationDatabaseChoice,
        val ticket: LocalSessionKeyTicket?,
        var sawReady: Boolean = false,
    )
}
