package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actor-owned page state. Uploads retain their handles; visible history reads own cancellable child leases. */
internal class SettingsProfileExtrasController(
    private val generation: Long, private val session: TdSettingsProfileExtrasSession,
    private val enqueue: (() -> Unit) -> Unit, private val publish: (SettingsProfileExtrasSnapshot) -> Unit,
    private val profileChanged: () -> Unit = {},
    private val profileMutationReceipts: SettingsProfileMutationReceipts? = null,
    private val terminal: SettingsSpecialMutationBoundary = SettingsSpecialMutationBoundary(enqueue),
) {
    /** Captured by a future submitter alongside this controller's token and write revision. */
    internal val mutationReceiptScope = profileMutationReceipts?.issueScope()
    private var snapshot = SettingsProfileExtrasSnapshot()
    private var nextOwner = 0L
    private var request = 0L
    private var revision = 0L
    private var active = AtomicBoolean(false)
    @Volatile private var retired = false
    private val mutationLifetime = AtomicBoolean(true)
    private var mutationInFlight = false
    private var pageActive: () -> Boolean = { false }
    private var channelOwner = AtomicBoolean(false)
    private var channelPageActive: () -> Boolean = { false }
    private var channelRequest = 0L
    private var channelIds = emptyList<Long>()
    private var channelOffset = 0
    private var historyOwner = AtomicBoolean(false)
    private var historySelectorLease: () -> Boolean = { false }
    private var historyRequest = 0L
    private var historyEpoch = 0L
    private var historyFileEpoch = AtomicBoolean(false)
    private val historyFiles = mutableSetOf<HistoryFileLease>()
    private val historySavedStickerReads = mutableSetOf<SavedProfileStickerActorGrant>()
    private var currentPhotoOwner = AtomicBoolean(false)
    private var currentPhotoView: () -> Boolean = { false }
    private var currentPhotoEpoch = 0L
    private var currentPhotoRequest = 0L
    private var currentPhotoFileEpoch = AtomicBoolean(false)
    private val currentPhotoFiles = mutableSetOf<CurrentPhotoFileLease>()
    private val currentSavedStickerReads = mutableSetOf<SavedProfileStickerActorGrant>()
    private class CurrentPhotoScope(val token: SettingsProfileExtrasToken, val epoch: Long, val gate: AtomicBoolean) {
        val userId: Long get() = token.userId
    }
    private val currentPhotoScope = AtomicReference<CurrentPhotoScope?>()
    private class HistoryPhotoScope(val token: SettingsProfileExtrasToken, val epoch: Long, val gate: AtomicBoolean)
    private val historyPhotoScope = AtomicReference<HistoryPhotoScope?>()
    // Keep evidence for duplicates that display deduplication would otherwise discard.
    // Distinct raw offsets and the 512-item history limit bound this list per epoch.
    private val historyEvidence = mutableListOf<SettingsProfilePhotoHistoryEntry>()
    private val stickerAvatar = session.stickerAvatar?.let { native ->
        SettingsProfileStickerAvatarController(
            session = native,
            enqueue = enqueue,
            allows = { token -> accepts(token) && pageActive() && !snapshot.loading && !snapshot.busy && !mutationInFlight && snapshot.data?.userId == token.userId },
            captureParent = { token ->
                if (!accepts(token) || !pageActive()) null else {
                    val parentOwner = active
                    val parentPage = pageActive
                    val current: () -> Boolean = {
                        parentOwner.get() && runCatching(parentPage).getOrDefault(false) && parentOwner.get()
                    }
                    current
                }
            },
            allowsSave = { token, expected ->
                expected != 0L && canSave(token) && snapshot.data?.photoId == expected
            },
            mutate = ProfileStickerAvatarMutationGate { token, expected, owns, observed, work ->
                if (expected == 0L || !canSave(token) || snapshot.data?.photoId != expected) null
                else mutate(SettingsProfileExtrasOperation.UPLOAD_STICKER_AVATAR,
                    ownsMutation = owns, observed = observed, work = work)
            },
        )
    }

    fun accepts(token: SettingsProfileExtrasToken) = !retired && active.get() && snapshot.token == token
    fun open(userId: Long, pageLease: () -> Boolean = { true }) {
        if (retired || userId <= 0 || !pageLease()) return
        if (active.get() && snapshot.token.userId == userId && pageActive()) return
        val previousParent = snapshot.token
        active.set(false)
        channelOwner.set(false)
        historyOwner.set(false); historyRequest++
        closeHistoryFiles()
        currentPhotoOwner.set(false); currentPhotoRequest++; closeCurrentPhotoFiles()
        active = AtomicBoolean(true)
        pageActive = pageLease
        snapshot = SettingsProfileExtrasSnapshot(SettingsProfileExtrasToken(generation, ++nextOwner, userId), loading = true,
            busy = mutationInFlight)
        val openedParent = snapshot.token
        stickerAvatar?.closeParent(previousParent)
        if (!accepts(openedParent) || !pageActive()) return
        emit(); read()
    }
    fun close(token: SettingsProfileExtrasToken) {
        if (!accepts(token)) return
        active.set(false); request++
        channelOwner.set(false); channelRequest++
        channelIds = emptyList(); channelOffset = 0
        historyOwner.set(false); historyRequest++
        closeHistoryFiles()
        currentPhotoOwner.set(false); currentPhotoRequest++; closeCurrentPhotoFiles()
        snapshot = SettingsProfileExtrasSnapshot(SettingsProfileExtrasToken(generation, ++nextOwner))
        val closedParent = snapshot.token
        stickerAvatar?.closeParent(token)
        if (snapshot.token == closedParent) emit()
    }
    fun deactivate() {
        mutationLifetime.set(false)
        retired = true; active.set(false); request++
        stickerAvatar?.deactivate()
        channelOwner.set(false); channelRequest++
        channelIds = emptyList(); channelOffset = 0
        historyOwner.set(false); historyRequest++
        closeHistoryFiles()
        currentPhotoOwner.set(false); currentPhotoRequest++; closeCurrentPhotoFiles()
        snapshot = SettingsProfileExtrasSnapshot(SettingsProfileExtrasToken(generation, ++nextOwner))
    }
    fun refresh(token: SettingsProfileExtrasToken) {
        if (!accepts(token) || !pageActive() || snapshot.busy || snapshot.loading) return
        snapshot = snapshot.copy(loading = true, error = null); emit(); read()
    }
    fun birthdate(token: SettingsProfileExtrasToken, expected: SettingsBirthdate?, desired: SettingsBirthdate?) {
        if (!canSave(token) || snapshot.data?.birthdate != expected) return
        val data = checkNotNull(snapshot.data)
        if (desired != null && !desired.isValidProfileBirthdate(data.today, expected)) {
            snapshot = snapshot.copy(error = SettingsError.INVALID_VALUE); emit(); return
        }
        mutate(SettingsProfileExtrasOperation.BIRTHDATE) { current, done ->
            session.setBirthdate(token.userId, expected, desired, current, done)
        }
    }
    /** The engine has already retained this handle synchronously before enqueueing. */
    fun upload(token: SettingsProfileExtrasToken, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto,
        retained: PendingAttachmentLease,
    ) {
        if (!canSave(token) || snapshot.data?.photoId != expectedPhotoId) { retained.release(); return }
        mutate(SettingsProfileExtrasOperation.UPLOAD_PHOTO, retained::release) { current, done ->
            session.uploadPhoto(token.userId, expectedPhotoId, photo, current, done)
        }
    }
    /** Native closure and local probing have independent lifetimes after admission. */
    fun uploadAnimation(token: SettingsProfileExtrasToken, expectedPhotoId: Long?, animation: SettingsPreparedProfileAnimation,
        retained: PendingAttachmentLease, validationLease: com.badnng.moe.chat.AttachmentLease,
    ): Long? {
        if (!canSave(token) || snapshot.data?.photoId != expectedPhotoId) {
            retained.release(); validationLease.release(); return null
        }
        return mutate(SettingsProfileExtrasOperation.UPLOAD_ANIMATION, retained::release) { current, done ->
            session.uploadAnimation(token.userId, expectedPhotoId, animation, validationLease, current, done)
        }
    }
    fun removePhoto(token: SettingsProfileExtrasToken, photoId: Long) {
        if (!canSave(token) || photoId == 0L || snapshot.data?.photoId != photoId) return
        mutate(SettingsProfileExtrasOperation.REMOVE_PHOTO) { current, done -> session.removePhoto(token.userId, photoId, current, done) }
    }
    fun openPersonalChannels(token: SettingsProfileExtrasToken, selectorLease: () -> Boolean) {
        if (!accepts(token) || !pageActive() || snapshot.loading || !selectorLease()) return
        channelOwner.set(false)
        channelOwner = AtomicBoolean(true)
        channelPageActive = selectorLease
        channelIds = emptyList(); channelOffset = 0
        val owner = channelOwner
        val parent = active
        val parentLease = pageActive
        val id = ++channelRequest
        val current = { !retired && parent.get() && parentLease() && owner.get() && selectorLease() }
        snapshot = snapshot.copy(channelsOpened = true, channelsLoading = true, channels = emptyList(),
            channelsTotal = null, channelsHasMore = false, channelsLimited = false, channelsUnavailable = 0, channelsError = null)
        emit()
        val once = AtomicBoolean()
        fun finish(response: SettingsResult<SettingsPersonalChannelCatalog>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (!accepts(token) || !current() || channelRequest != id) return@enqueue
                val catalog = response.value
                if (catalog == null) {
                    snapshot = snapshot.copy(channelsLoading = false, channelsError = response.error ?: SettingsError.REQUEST_FAILED); emit()
                } else {
                    channelIds = catalog.ids.take(512)
                    snapshot = snapshot.copy(channelsTotal = catalog.totalCount, channelsLimited = catalog.limited || catalog.ids.size > 512)
                    if (channelIds.isEmpty()) { snapshot = snapshot.copy(channelsLoading = false); emit() }
                    else readPersonalChannelsPage()
                }
            }
        }
        try { session.personalChannelCatalog(token.userId, current, ::finish) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
    fun closePersonalChannels(token: SettingsProfileExtrasToken) {
        if (!accepts(token)) return
        channelOwner.set(false); channelRequest++
        channelIds = emptyList(); channelOffset = 0
        snapshot = snapshot.copy(channelsOpened = false, channelsLoading = false, channels = emptyList(), channelsHasMore = false,
            channelsTotal = null, channelsLimited = false, channelsUnavailable = 0, channelsError = null)
        emit()
    }
    fun morePersonalChannels(token: SettingsProfileExtrasToken) {
        if (!accepts(token) || !pageActive() || !channelOwner.get() || !channelPageActive() ||
            !snapshot.channelsOpened || snapshot.channelsLoading || snapshot.busy || channelOffset >= channelIds.size) return
        snapshot = snapshot.copy(channelsLoading = true, channelsError = null); emit(); readPersonalChannelsPage()
    }
    fun savePersonalChannel(token: SettingsProfileExtrasToken, expectedChatId: Long, desiredChatId: Long) {
        if (!canSave(token) || !snapshot.channelsOpened ||
            snapshot.channelsLoading || !channelOwner.get() || !channelPageActive() ||
            desiredChatId != 0L && snapshot.channels.none { it.chatId == desiredChatId }) return
        if (snapshot.data?.personalChatId != expectedChatId) {
            snapshot = snapshot.copy(error = SettingsError.CONFLICT); emit(); return
        }
        val owner = channelOwner
        val selectorLease = channelPageActive
        mutate(SettingsProfileExtrasOperation.PERSONAL_CHANNEL, ownsMutation = { owner.get() && selectorLease() }) { current, done ->
            session.setPersonalChannel(token.userId, expectedChatId, desiredChatId, current, done)
        }
    }
    private fun readPersonalChannelsPage() {
        val token = snapshot.token
        val parent = active
        val parentLease = pageActive
        val owner = channelOwner
        val selectorLease = channelPageActive
        val offset = channelOffset
        val ids = channelIds.drop(offset).take(32)
        val id = ++channelRequest
        val current = { !retired && parent.get() && parentLease() && owner.get() && selectorLease() }
        val once = AtomicBoolean()
        fun finish(response: SettingsResult<SettingsPersonalChannelsPage>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (!accepts(token) || !current() || channelRequest != id || channelOffset != offset) return@enqueue
                val page = response.value
                if (page == null) snapshot = snapshot.copy(channelsLoading = false, channelsError = response.error ?: SettingsError.REQUEST_FAILED)
                else {
                    // Advance by the actual requested native slice, independently of failed rows and deduplication.
                    channelOffset += ids.size
                    val valid = page.entries.filter { it.chatId in ids && it.title.isNotBlank() && it.username.isNotBlank() }
                    val entries = (snapshot.channels + valid).distinctBy { it.chatId }.take(512)
                    val missing = ids.size - valid.distinctBy { it.chatId }.size
                    snapshot = snapshot.copy(channelsLoading = false, channels = entries,
                        channelsUnavailable = snapshot.channelsUnavailable + maxOf(missing, page.unavailableCount.coerceAtLeast(0)),
                        channelsHasMore = channelOffset < channelIds.size, channelsError = null)
                }
                emit()
            }
        }
        try { session.personalChannelsPage(token.userId, ids, current, ::finish) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
    private fun canSave(token: SettingsProfileExtrasToken) = accepts(token) && pageActive() && !mutationInFlight && !snapshot.busy && !snapshot.loading && snapshot.data != null

    fun captureStickerAvatarMedia(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        selection: ProfileStickerAvatarSelectionTicket?): (() -> Boolean)? =
        stickerAvatar?.captureMedia(parent, picker, selection)

    fun openStickerAvatar(parent: SettingsProfileExtrasToken, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPickerTicket>) -> Unit,
    ) {
        val child = stickerAvatar ?: return avatarUnavailable(result)
        child.open(parent, active, result)
    }
    fun closeStickerAvatar(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket) {
        stickerAvatar?.close(parent, picker)
    }
    fun loadStickerAvatarDefault(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
    ) {
        val child = stickerAvatar ?: return avatarUnavailable(result)
        child.loadDefault(parent, picker, active, result)
    }
    fun loadStickerAvatarPacks(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        kind: ProfileStickerAvatarPackKind, active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
    ) {
        val child = stickerAvatar ?: return avatarUnavailable(result)
        child.loadInstalledPacks(parent, picker, kind, active, result)
    }
    fun openStickerAvatarPack(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        pack: ProfileStickerAvatarPackTicket, active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
    ) {
        val child = stickerAvatar ?: return avatarUnavailable(result)
        child.openPack(parent, picker, pack, active, result)
    }
    fun loadStickerAvatarWindow(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        cursor: ProfileStickerAvatarCatalogCursor, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit,
    ) {
        val child = stickerAvatar ?: return avatarUnavailable(result)
        child.loadWindow(parent, picker, cursor, active, result)
    }
    fun searchStickerAvatarInstalled(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        kind: ProfileStickerAvatarPackKind, query: String, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
    ) {
        val child = stickerAvatar ?: return avatarUnavailable(result)
        child.searchInstalled(parent, picker, kind, query, active, result)
    }
    fun selectStickerAvatar(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        candidate: ProfileStickerAvatarCandidateTicket, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarSelectionTicket>) -> Unit,
    ) {
        val child = stickerAvatar ?: return avatarUnavailable(result)
        child.select(parent, picker, candidate, active, result)
    }
    fun saveStickerAvatar(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        selection: ProfileStickerAvatarSelectionTicket, expectedPhotoId: Long?, background: ProfileStickerAvatarBackground,
        active: () -> Boolean, admitted: (Long?) -> Unit, result: (ProfileStickerAvatarMutationResult) -> Unit,
    ) {
        val child = stickerAvatar
        if (child == null) { runCatching { admitted(null) }; return }
        child.save(parent, picker, selection, expectedPhotoId, background, active, admitted, result)
    }
    private fun <T> avatarUnavailable(result: (SettingsResult<T>) -> Unit) {
        runCatching { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    }

    fun openCurrentPhoto(token: SettingsProfileExtrasToken, view: () -> Boolean): Long? {
        if (!accepts(token) || !pageActive() || !safeCurrentPhoto(view)) return null
        currentPhotoOwner.set(false)
        currentPhotoOwner = AtomicBoolean(true)
        currentPhotoView = view
        resetCurrentPhoto(SettingsCurrentProfilePhotoStatus.LOADING)
        val admitted = snapshot.currentPhoto.epoch
        readCurrentPhoto()
        return admitted
    }

    fun closeCurrentPhoto(token: SettingsProfileExtrasToken, epoch: Long) {
        if (!accepts(token)) return
        if (epoch == 0L) {
            if (snapshot.currentPhoto.status != SettingsCurrentProfilePhotoStatus.CLOSED && !safeCurrentPhoto(currentPhotoView)) {
                currentPhotoOwner.set(false); resetCurrentPhoto(SettingsCurrentProfilePhotoStatus.CLOSED)
            }
            return
        }
        if (snapshot.currentPhoto.epoch != epoch) return
        currentPhotoOwner.set(false)
        resetCurrentPhoto(SettingsCurrentProfilePhotoStatus.CLOSED)
    }

    fun refreshCurrentPhoto(token: SettingsProfileExtrasToken, epoch: Long): Long? {
        if (!currentPhotoReady(token) || snapshot.currentPhoto.epoch != epoch || snapshot.currentPhoto.status == SettingsCurrentProfilePhotoStatus.LOADING) return null
        resetCurrentPhoto(SettingsCurrentProfilePhotoStatus.LOADING)
        val admitted = snapshot.currentPhoto.epoch
        readCurrentPhoto()
        return admitted
    }

    private fun currentPhotoReady(token: SettingsProfileExtrasToken) = accepts(token) && pageActive() &&
        currentPhotoOwner.get() && safeCurrentPhoto(currentPhotoView) && snapshot.currentPhoto.status != SettingsCurrentProfilePhotoStatus.CLOSED

    private fun resetCurrentPhoto(status: SettingsCurrentProfilePhotoStatus) {
        closeCurrentPhotoFiles()
        currentPhotoFileEpoch = AtomicBoolean(status != SettingsCurrentProfilePhotoStatus.CLOSED)
        currentPhotoRequest++
        snapshot = snapshot.copy(currentPhoto = SettingsCurrentProfilePhotoSnapshot(epoch = ++currentPhotoEpoch, status = status))
        currentPhotoScope.set(if (status == SettingsCurrentProfilePhotoStatus.CLOSED) null else
            CurrentPhotoScope(snapshot.token, snapshot.currentPhoto.epoch, currentPhotoFileEpoch))
        runCatching { emit() }
    }

    /** Preserve the admitted epoch so the owning view can observe the conflict and refresh. */
    private fun conflictCurrentPhoto() {
        if (snapshot.currentPhoto.status == SettingsCurrentProfilePhotoStatus.CLOSED) return
        closeCurrentPhotoFiles()
        currentPhotoScope.set(null)
        currentPhotoRequest++
        snapshot = snapshot.copy(currentPhoto = SettingsCurrentProfilePhotoSnapshot(
            epoch = snapshot.currentPhoto.epoch, status = SettingsCurrentProfilePhotoStatus.CONFLICT))
        runCatching { emit() }
    }

    private fun readCurrentPhoto() {
        val token = snapshot.token
        val parent = active; val parentView = pageActive; val owner = currentPhotoOwner
        val view = currentPhotoView; val epochLease = currentPhotoFileEpoch
        val epoch = snapshot.currentPhoto.epoch; val id = ++currentPhotoRequest
        val current = { !retired && parent.get() && safeCurrentPhoto(parentView) && owner.get() &&
            safeCurrentPhoto(view) && epochLease.get() }
        val once = AtomicBoolean()
        val invalidated = {
            // The receiver closes output immediately; the actor handles release and publication.
            if (epochLease.compareAndSet(true, false)) enqueue {
                if (!retired && parent.get() && safeCurrentPhoto(parentView) && owner.get() && safeCurrentPhoto(view) &&
                    accepts(token) && snapshot.currentPhoto.epoch == epoch &&
                    currentPhotoOwner === owner && currentPhotoRequest == id) conflictCurrentPhoto()
            }
        }
        fun finish(response: SettingsResult<SettingsCurrentProfilePhotoRead>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (!current()) {
                    if (accepts(token) && snapshot.currentPhoto.epoch == epoch && currentPhotoOwner === owner && !safeCurrentPhoto(view)) {
                        owner.set(false); resetCurrentPhoto(SettingsCurrentProfilePhotoStatus.CLOSED)
                    }
                    return@enqueue
                }
                if (!currentPhotoReady(token) || snapshot.currentPhoto.epoch != epoch || currentPhotoRequest != id) return@enqueue
                val read = response.value
                val nativePhoto = read?.photo
                val valid = when (read?.status) {
                    SettingsCurrentProfilePhotoStatus.READY -> nativePhoto != null && nativePhoto.photoId != 0L && nativePhoto.authorization > 0
                    SettingsCurrentProfilePhotoStatus.EMPTY, SettingsCurrentProfilePhotoStatus.UNKNOWN,
                    SettingsCurrentProfilePhotoStatus.ROLE_CONFLICT, SettingsCurrentProfilePhotoStatus.CONFLICT -> nativePhoto == null
                    else -> false
                }
                val next = if (response.error == null && valid && read != null) {
                    fun clean(file: MediaFile) = file.copy(localPath = null, downloaded = false, downloading = false,
                        downloadedSize = 0, uploading = false, uploadedSize = 0)
                    val photo = nativePhoto?.let { value -> value.copy(
                        variants = java.util.Collections.unmodifiableList(value.variants.map { it.copy(file = clean(it.file)) }),
                        animation = value.animation?.let { it.copy(file = clean(it.file)) },
                        smallAnimation = value.smallAnimation?.let { it.copy(file = clean(it.file)) }) }
                    SettingsCurrentProfilePhotoSnapshot(epoch, read.status, photo)
                } else if (response.error == SettingsError.CONFLICT) {
                    SettingsCurrentProfilePhotoSnapshot(epoch, SettingsCurrentProfilePhotoStatus.CONFLICT)
                } else SettingsCurrentProfilePhotoSnapshot(epoch, SettingsCurrentProfilePhotoStatus.ERROR,
                    error = response.error ?: SettingsError.REQUEST_FAILED)
                snapshot = snapshot.copy(currentPhoto = next); emit()
            }
        }
        try { session.currentProfilePhoto(token.userId, current, invalidated, ::finish) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    fun acquireCurrentPhotoFile(request: SettingsCurrentProfilePhotoMediaRequest, active: () -> Boolean,
        result: (SettingsResult<MediaFile>) -> Unit,
    ): SettingsCurrentProfilePhotoFileLease {
        val lease = CurrentPhotoFileLease { closed -> enqueue { currentPhotoFiles.remove(closed) } }
        val parent = this.active; val parentView = pageActive; val owner = currentPhotoOwner
        val visible = currentPhotoView; val epochLease = currentPhotoFileEpoch; val sourceRequest = currentPhotoRequest
        val admitted = AtomicBoolean()
        val source = { !retired && admitted.get() && parent.get() && safeCurrentPhoto(parentView) && owner.get() &&
            safeCurrentPhoto(visible) && epochLease.get() && safeCurrentPhoto(active) }
        lease.setGate(source)
        fun reject(error: SettingsError): SettingsCurrentProfilePhotoFileLease {
            if (source()) lease.fail() else lease.close()
            if (safeCurrentPhoto(active)) result(SettingsResult(error = error)); return lease
        }
        if (!safeCurrentPhoto(active)) return reject(SettingsError.NOT_READY)
        val view = snapshot.currentPhoto
        val photo = view.photo
        if (!currentPhotoReady(request.parent) || view.status != SettingsCurrentProfilePhotoStatus.READY ||
            view.epoch != request.epoch || photo == null || photo.photoId != request.photoId || photo.authorization <= 0)
            return reject(SettingsError.CONFLICT)
        admitted.set(true)
        val authorized = SettingsCurrentProfilePhotoAuthorizedFileRequest(request.parent.userId, photo.authorization,
            photo.photoId, request.use, request.targetWidthPx, request.targetHeightPx)
        val expected = when (request.use) {
            SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW -> {
                val selected = selectCurrentProfilePhotoVariant(photo.variants, authorized)
                selected.value?.file ?: return reject(selected.error ?: SettingsError.UNSUPPORTED)
            }
            SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW -> {
                val selected = selectCurrentProfilePhotoAnimation(photo, authorized)
                selected.value?.file ?: return reject(selected.error ?: SettingsError.UNSUPPORTED)
            }
        }
        currentPhotoFiles.removeAll { it.isClosed() }
        if (currentPhotoFiles.size >= 64) return reject(SettingsError.RATE_LIMITED)
        currentPhotoFiles += lease
        val current = { lease.active() }
        val once = AtomicBoolean()
        fun finish(response: SettingsResult<MediaFile>) {
            if (!once.compareAndSet(false, true)) return
            if (response.error == SettingsError.CONFLICT && epochLease.compareAndSet(true, false)) enqueue {
                if (!retired && parent.get() && safeCurrentPhoto(parentView) && owner.get() && safeCurrentPhoto(visible) &&
                    accepts(request.parent) && snapshot.currentPhoto.epoch == request.epoch &&
                    currentPhotoOwner === owner && currentPhotoRequest == sourceRequest) conflictCurrentPhoto()
            }
            enqueue {
                val latest = snapshot.currentPhoto
                if (!current() || !currentPhotoReady(request.parent) || latest.epoch != request.epoch ||
                    latest.status != SettingsCurrentProfilePhotoStatus.READY || latest.photo != photo) {
                    lease.close(); return@enqueue
                }
                if (response.error == SettingsError.CONFLICT) { conflictCurrentPhoto(); return@enqueue }
                val file = response.value
                val valid = file != null && response.error == null && file.id == expected.id && file.uniqueId.isNotBlank() &&
                    (expected.uniqueId.isBlank() || expected.uniqueId == file.uniqueId) &&
                    file.size in 1..(24L * 1024 * 1024) && (expected.size == 0L || expected.size == file.size) &&
                    file.downloadedSize in 0..(24L * 1024 * 1024) && file.downloaded && !file.localPath.isNullOrBlank()
                if (!valid) lease.fail()
                result(if (valid) response else SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED))
            }
        }
        try { lease.assign(session.acquireCurrentProfilePhotoFile(authorized, current, ::finish)) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        return lease
    }

    /** Thread-safe, non-authorizing pre-admission epoch witness. The actor still captures the native ticket. */
    fun savedStickerEpochWitness(request: SettingsSavedProfileStickerAvatarRequest): (() -> Boolean)? = when (request) {
        is SettingsSavedProfileStickerAvatarRequest.Current -> currentPhotoScope.get()?.takeIf {
            it.token == request.parent && it.epoch == request.epoch && it.gate.get()
        }?.let { scope -> { currentPhotoScope.get() === scope && scope.gate.get() } }
        is SettingsSavedProfileStickerAvatarRequest.History -> historyPhotoScope.get()?.takeIf {
            it.token == request.parent && it.epoch == request.epoch && it.gate.get()
        }?.let { scope -> { historyPhotoScope.get() === scope && scope.gate.get() } }
    }
    /** Thread-safe lookup of an existing native gate only; never authorizes a file request. */
    fun savedStickerTicketWitness(source: SettingsSavedProfileStickerNativeSource): (() -> Boolean)? =
        (session as? TdSavedProfileStickerSession)?.savedStickerTicketWitness(source)

    /** Actor derives both the native ticket and the original history offset; UI supplies neither. */
    fun captureSavedProfileSticker(request: SettingsSavedProfileStickerAvatarRequest,
        witnessOnly: Boolean = false,
        demand: SettingsSavedProfileStickerAvatarDemand = SettingsSavedProfileStickerAvatarDemand(),
    ): SettingsResult<SavedProfileStickerActorGrant> {
        if (!accepts(request.parent) || !safeCurrentPhoto(pageActive) || request.epoch <= 0 || request.photoId == 0L)
            return SettingsResult(error = SettingsError.NOT_READY)
        val native = session as? TdSavedProfileStickerSession ?: return SettingsResult(error = SettingsError.UNSUPPORTED)
        val parent = active; val parentPage = pageActive
        val owner: AtomicBoolean; val epochGate: AtomicBoolean; val view: () -> Boolean
        val nativeSource: SettingsSavedProfileStickerNativeSource
        val expected: SettingsProfilePhotoSticker
        var limited = false
        val currentRequest = currentPhotoRequest
        when (request) {
            is SettingsSavedProfileStickerAvatarRequest.Current -> {
                val state = snapshot.currentPhoto; val photo = state.photo
                if (!currentPhotoReady(request.parent) || state.status != SettingsCurrentProfilePhotoStatus.READY ||
                    state.epoch != request.epoch || photo == null || photo.photoId != request.photoId || photo.authorization <= 0)
                    return SettingsResult(error = SettingsError.CONFLICT)
                if (photo.hasAnimation || photo.invalidAnimation || photo.invalidSmallAnimation) return SettingsResult(error = SettingsError.UNSUPPORTED)
                if (photo.invalidSticker) return SettingsResult(error = SettingsError.INVALID_VALUE)
                expected = photo.sticker ?: return SettingsResult(error = SettingsError.UNSUPPORTED)
                owner = currentPhotoOwner; epochGate = currentPhotoFileEpoch; view = currentPhotoView
                nativeSource = SettingsSavedProfileStickerNativeSource.Current(request.parent.userId, photo.authorization, photo.photoId)
                limited = !witnessOnly && currentSavedStickerReads.size >= 64
            }
            is SettingsSavedProfileStickerAvatarRequest.History -> {
                val history = snapshot.history
                if (!historyReady(request.parent) || history.refreshRequired || history.epoch != request.epoch)
                    return SettingsResult(error = SettingsError.CONFLICT)
                val entry = history.entries.singleOrNull { it.photoId == request.photoId && it.authorization > 0 }
                    ?: return SettingsResult(error = SettingsError.CONFLICT)
                if (entry.hasAnimation || entry.invalidAnimation || entry.invalidSmallAnimation) return SettingsResult(error = SettingsError.UNSUPPORTED)
                if (entry.invalidSticker) return SettingsResult(error = SettingsError.INVALID_VALUE)
                expected = entry.sticker ?: return SettingsResult(error = SettingsError.UNSUPPORTED)
                owner = historyOwner; epochGate = historyFileEpoch; view = historySelectorLease
                nativeSource = SettingsSavedProfileStickerNativeSource.History(request.parent.userId, entry.authorization,
                    entry.photoId, entry.rawOffset)
                limited = !witnessOnly && historySavedStickerReads.size >= 64
            }
        }
        val invalidate = {
            if (epochGate.compareAndSet(true, false)) enqueue {
                if (!retired && parent.get() && owner.get() && accepts(request.parent)) when (request) {
                    is SettingsSavedProfileStickerAvatarRequest.Current ->
                        if (currentPhotoOwner === owner && snapshot.currentPhoto.epoch == request.epoch && currentPhotoRequest == currentRequest)
                            conflictCurrentPhoto()
                    is SettingsSavedProfileStickerAvatarRequest.History ->
                        if (historyOwner === owner && snapshot.history.epoch == request.epoch)
                            resetHistory(refreshRequired = true)
                }
            }
        }
        val capturedResult = native.captureSavedSticker(nativeSource, invalidate)
        val captured = capturedResult.value ?: return SettingsResult(error = capturedResult.error ?: SettingsError.CONFLICT)
        if (captured.sticker != expected) { captured.invalidate(); invalidate(); return SettingsResult(error = SettingsError.CONFLICT) }
        val atomic = { parent.get() && owner.get() && epochGate.get() && captured.atomicCurrent() }
        val current = { atomic() && safeCurrentPhoto(parentPage) && safeCurrentPhoto(view) &&
            runCatching(captured.sourceCurrent).getOrDefault(false) && atomic() }
        if (!current()) return SettingsResult(error = SettingsError.NOT_READY)
        val set = if (request is SettingsSavedProfileStickerAvatarRequest.Current) currentSavedStickerReads else historySavedStickerReads
        val grant = SavedProfileStickerActorGrant(captured, atomic, current, demand) { removed -> enqueue { set.remove(removed) } }
        if (!witnessOnly && !limited) set += grant
        // A rejected resource slot still carries a real source witness, but starts no Files work.
        return SettingsResult(value = grant, error = if (limited) SettingsError.RATE_LIMITED else null)
    }

    private fun closeCurrentPhotoFiles() {
        currentPhotoFileEpoch.set(false)
        currentPhotoScope.set(null)
        val closing = currentPhotoFiles.toList(); currentPhotoFiles.clear(); closing.forEach { it.close() }
        val stickers = currentSavedStickerReads.toList(); currentSavedStickerReads.clear(); stickers.forEach { it.close() }
    }
    private fun safeCurrentPhoto(value: () -> Boolean) = runCatching(value).getOrDefault(false)
    private enum class CurrentPhotoLeaseState { ACTIVE, FAILED, CLOSED }
    private class CurrentPhotoFileLease(private val onClosed: (CurrentPhotoFileLease) -> Unit) : SettingsCurrentProfilePhotoFileLease {
        private val state = AtomicReference(CurrentPhotoLeaseState.ACTIVE)
        private val released = AtomicBoolean()
        private val native = AtomicReference<SettingsCurrentProfilePhotoFileLease?>()
        private val gate = AtomicReference<(() -> Boolean)?>(null)
        private val observer = AtomicReference<CurrentPhotoInvalidationObserver?>()
        fun setGate(value: () -> Boolean) { gate.set(value) }
        fun isClosed() = state.get() != CurrentPhotoLeaseState.ACTIVE
        override fun requestCurrent(): Boolean = state.get() != CurrentPhotoLeaseState.CLOSED &&
            runCatching { gate.get()?.invoke() == true }.getOrDefault(false) && state.get() != CurrentPhotoLeaseState.CLOSED
        override fun active(): Boolean = state.get() == CurrentPhotoLeaseState.ACTIVE && requestCurrent() &&
            state.get() == CurrentPhotoLeaseState.ACTIVE
        override fun onInvalidated(callback: () -> Unit): () -> Unit {
            val next = CurrentPhotoInvalidationObserver(callback)
            observer.getAndSet(next)?.cancel()
            if (!active()) {
                if (state.get() == CurrentPhotoLeaseState.FAILED && requestCurrent()) {
                    if (observer.compareAndSet(next, null)) next.cancel()
                } else { close(); observer.getAndSet(null)?.fire() }
            }
            return { next.cancel(); observer.compareAndSet(next, null); Unit }
        }
        fun assign(value: SettingsCurrentProfilePhotoFileLease) {
            native.set(value); if (isClosed()) native.getAndSet(null)?.close()
        }
        fun fail() {
            if (!state.compareAndSet(CurrentPhotoLeaseState.ACTIVE, CurrentPhotoLeaseState.FAILED)) return
            observer.getAndSet(null)?.cancel(); release()
        }
        private fun release() {
            runCatching { native.getAndSet(null)?.close() }
            if (released.compareAndSet(false, true)) runCatching { onClosed(this) }
        }
        override fun close() {
            val old = state.getAndSet(CurrentPhotoLeaseState.CLOSED)
            if (old == CurrentPhotoLeaseState.CLOSED) return
            try { release() } finally {
                val listener = observer.getAndSet(null)
                if (old == CurrentPhotoLeaseState.ACTIVE) listener?.fire() else listener?.cancel()
            }
        }
    }

    fun openHistory(token: SettingsProfileExtrasToken, selectorLease: () -> Boolean) {
        if (!accepts(token) || !pageActive() || snapshot.loading || snapshot.data == null || !selectorLease()) return
        historyOwner.set(false)
        historyOwner = AtomicBoolean(true)
        historySelectorLease = selectorLease
        resetHistory(loading = true)
        readHistoryPage()
    }
    fun closeHistory(token: SettingsProfileExtrasToken) {
        if (!accepts(token)) return
        historyOwner.set(false); historyRequest++
        closeHistoryFiles()
        snapshot = snapshot.copy(history = SettingsProfilePhotoHistorySnapshot(epoch = ++historyEpoch))
        emit()
    }
    fun refreshHistory(token: SettingsProfileExtrasToken) {
        if (!historyReady(token) || snapshot.busy || snapshot.loading || snapshot.history.loading) return
        resetHistory(loading = true)
        readHistoryPage()
    }
    fun moreHistory(token: SettingsProfileExtrasToken) {
        if (!historyReady(token) || snapshot.busy || snapshot.loading || snapshot.history.loading ||
            !snapshot.history.hasMore || snapshot.history.refreshRequired || snapshot.history.limited) return
        snapshot = snapshot.copy(history = snapshot.history.copy(loading = true, error = null))
        emit(); readHistoryPage()
    }
    fun reuseHistory(token: SettingsProfileExtrasToken, epoch: Long, target: SettingsProfilePhotoHistoryTarget,
        selectionLease: () -> Boolean,
    ) = mutateHistory(token, epoch, target, selectionLease, reuse = true)
    fun removeHistory(token: SettingsProfileExtrasToken, epoch: Long, target: SettingsProfilePhotoHistoryTarget,
        selectionLease: () -> Boolean,
    ) = mutateHistory(token, epoch, target, selectionLease, reuse = false)

    /** Actor admission only. Native current predicates capture no actor-owned state. */
    fun acquireHistoryFile(request: SettingsProfilePhotoHistoryMediaRequest, active: () -> Boolean,
        result: (SettingsResult<MediaFile>) -> Unit,
    ): SettingsProfilePhotoHistoryFileLease {
        val lease = HistoryFileLease { closed -> enqueue { historyFiles.remove(closed) } }
        fun reject(error: SettingsError): SettingsProfilePhotoHistoryFileLease {
            lease.close()
            if (active()) result(SettingsResult(error = error))
            return lease
        }
        if (!active()) return reject(SettingsError.NOT_READY)
        val history = snapshot.history
        if (!historyReady(request.parent) || history.refreshRequired || history.epoch != request.epoch ||
            request.photoId == 0L) return reject(SettingsError.CONFLICT)
        if (request.targetWidthPx !in 1..4096 || request.targetHeightPx !in 1..4096 ||
            request.targetWidthPx.toLong() * request.targetHeightPx > 24_000_000L) return reject(SettingsError.INVALID_VALUE)
        val entry = history.entries.singleOrNull { it.photoId == request.photoId && it.authorization > 0 }
            ?: return reject(SettingsError.CONFLICT)
        val expectedAnimation = if (request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION) {
            if (entry.invalidAnimation || entry.animation == null && entry.invalidSmallAnimation)
                return reject(SettingsError.INVALID_VALUE)
            val value = entry.preferredAnimation() ?: return reject(SettingsError.UNSUPPORTED)
            if (value.length !in 1..1280 || entry.animation == null && value.length != 160 ||
                value.mainFrameTimestampMillis == null || value.file.id <= 0 ||
                value.file.uniqueId.length > 1024 || value.file.size !in 0..(24L * 1024 * 1024) ||
                value.file.expectedSize < 0) return reject(SettingsError.INVALID_VALUE)
            value
        } else null
        // Completed files remain owned until UI close, so bound all live handles, not only downloads.
        historyFiles.removeAll { it.closed.get() }
        if (historyFiles.size >= 64) return reject(SettingsError.RATE_LIMITED)
        historyFiles += lease
        val parent = this.active
        val parentLease = pageActive
        val owner = historyOwner
        val selectorLease = historySelectorLease
        val epochLease = historyFileEpoch
        val current = { !lease.closed.get() && parent.get() && parentLease() && owner.get() &&
            selectorLease() && epochLease.get() && active() }
        val once = AtomicBoolean()
        fun finish(response: SettingsResult<MediaFile>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                val latest = snapshot.history
                if (!current() || !historyReady(request.parent) || latest.refreshRequired || latest.epoch != request.epoch ||
                    latest.entries.none { it.photoId == entry.photoId && it.rawOffset == entry.rawOffset &&
                        it.authorization == entry.authorization && (expectedAnimation == null ||
                            it.preferredAnimation() == expectedAnimation) }) {
                    lease.close(); return@enqueue
                }
                if (response.error == SettingsError.CONFLICT) {
                    resetHistory(refreshRequired = true); return@enqueue
                }
                val file = response.value
                val wrongAnimation = expectedAnimation != null && file != null &&
                    (response.error != null || file.id != expectedAnimation.file.id ||
                        expectedAnimation.file.uniqueId.isNotBlank() && file.uniqueId != expectedAnimation.file.uniqueId ||
                        file.size !in 1..(24L * 1024 * 1024) ||
                        expectedAnimation.file.size != 0L && file.size != expectedAnimation.file.size ||
                        file.downloadedSize !in 0..(24L * 1024 * 1024))
                if (file != null && (file.id <= 0 || file.uniqueId.isBlank() || !file.downloaded ||
                        file.localPath.isNullOrBlank() || wrongAnimation)) {
                    lease.close()
                    result(SettingsResult(error = SettingsError.REQUEST_FAILED))
                } else {
                    if (file == null) lease.close()
                    else if (response.error == null && file.size >= 0) {
                        // Only the authorized native result can strengthen this epoch's
                        // metadata; keep the displayed entry/ticket and selection stable.
                        historyEvidence.indices.filter { index ->
                            val observed = historyEvidence[index]
                            observed.photoId == entry.photoId && observed.rawOffset == entry.rawOffset &&
                                observed.authorization == entry.authorization
                        }.forEach { index -> historyEvidence[index] = historyPhotoWithObservedFile(historyEvidence[index], file) }
                    }
                    result(if (file == null) SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED) else response)
                }
            }
        }
        val authorized = SettingsProfilePhotoHistoryAuthorizedFileRequest(request.parent.userId, entry.authorization,
            entry.photoId, entry.rawOffset, request.use, request.targetWidthPx, request.targetHeightPx)
        try { lease.assign(session.acquireHistoryFile(authorized, current, ::finish)) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
        return lease
    }

    private fun closeHistoryFiles() {
        historyFileEpoch.set(false)
        historyPhotoScope.set(null)
        historyEvidence.clear()
        val closing = historyFiles.toList()
        historyFiles.clear()
        closing.forEach { it.close() }
        val stickers = historySavedStickerReads.toList(); historySavedStickerReads.clear(); stickers.forEach { it.close() }
    }

    /** Close may run on UI/native threads, including before acquire returns its native handle. */
    private class HistoryFileLease(private val onClosed: (HistoryFileLease) -> Unit) : SettingsProfilePhotoHistoryFileLease {
        val closed = AtomicBoolean()
        private val native = AtomicReference<SettingsProfilePhotoHistoryFileLease?>()
        fun assign(value: SettingsProfilePhotoHistoryFileLease) {
            native.set(value)
            if (closed.get()) native.getAndSet(null)?.close()
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            native.getAndSet(null)?.close()
            onClosed(this)
        }
    }

    private fun historyReady(token: SettingsProfileExtrasToken) = accepts(token) && pageActive() &&
        historyOwner.get() && historySelectorLease() && snapshot.history.opened
    private fun resetHistory(loading: Boolean = false, refreshRequired: Boolean = false) {
        closeHistoryFiles()
        historyFileEpoch = AtomicBoolean(!refreshRequired)
        historyRequest++
        snapshot = snapshot.copy(history = SettingsProfilePhotoHistorySnapshot(opened = true, epoch = ++historyEpoch,
            loading = loading, refreshRequired = refreshRequired,
            error = if (refreshRequired) SettingsError.CONFLICT else null))
        historyPhotoScope.set(if (refreshRequired) null else HistoryPhotoScope(snapshot.token, snapshot.history.epoch, historyFileEpoch))
        emit()
    }
    private fun readHistoryPage() {
        val token = snapshot.token
        val parent = active
        val parentLease = pageActive
        val owner = historyOwner
        val selectorLease = historySelectorLease
        val epochLease = historyFileEpoch
        val epoch = snapshot.history.epoch
        val offset = snapshot.history.nextOffset
        val limit = minOf(32, 512 - offset)
        if (limit <= 0) return
        val id = ++historyRequest
        val current = { !retired && parent.get() && parentLease() && owner.get() && selectorLease() && epochLease.get() }
        val once = AtomicBoolean()
        fun finish(response: SettingsResult<SettingsProfilePhotoHistoryPage>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (!accepts(token) || !current() || historyRequest != id || snapshot.history.epoch != epoch) return@enqueue
                val page = response.value
                val old = snapshot.history
                if (page == null) {
                    snapshot = snapshot.copy(history = old.copy(loading = false, error = response.error ?: SettingsError.REQUEST_FAILED))
                    emit(); return@enqueue
                }
                val total = page.totalCount?.takeIf { it >= 0 }
                if (page.metadataConflict || page.identity.userId != token.userId || page.offset != offset || page.rawCount !in 0..limit ||
                    old.identity != null && old.identity != page.identity ||
                    old.totalCount != null && total != null && old.totalCount != total) {
                    resetHistory(refreshRequired = true); return@enqueue
                }
                val next = offset + page.rawCount
                val candidates = page.entries.filter { it.photoId != 0L && it.rawOffset >= offset && it.rawOffset < next }
                    .take(limit).distinctBy { it.rawOffset }
                    .map { entry ->
                        fun publicAnimation(value: SettingsProfilePhotoAnimation?) = value?.copy(file = value.file.copy(
                            localPath = null, downloaded = false, downloading = false, downloadedSize = 0,
                            uploading = false, uploadedSize = 0))
                        entry.copy(variants = entry.variants.map { variant ->
                            variant.copy(file = variant.file.copy(localPath = null))
                        }, animation = publicAnimation(entry.animation), smallAnimation = publicAnimation(entry.smallAnimation))
                    }
                val conflictWithOld = candidates.any { later -> historyEvidence.any { earlier -> earlier.photoId == later.photoId &&
                    !sameHistoryPhotoMetadata(earlier, later) } }
                val conflictInPage = candidates.indices.any { index ->
                    candidates.subList(index + 1, candidates.size).any { later ->
                        candidates[index].photoId == later.photoId && !sameHistoryPhotoMetadata(candidates[index], later)
                    }
                }
                if (conflictWithOld || conflictInPage) {
                    resetHistory(refreshRequired = true); return@enqueue
                }
                historyEvidence.addAll(candidates)
                val valid = candidates.distinctBy { it.photoId }
                val entries = (old.entries + valid).distinctBy { it.photoId }.take(512)
                val inconsistent = total != null && (next > total || page.rawCount == 0 && next < total)
                if (inconsistent) closeHistoryFiles()
                val limited = next >= 512 && (total == null || next < total)
                snapshot = snapshot.copy(history = old.copy(identity = page.identity, loading = false,
                    entries = entries, totalCount = total, nextOffset = next,
                    hasMore = page.rawCount > 0 && !inconsistent && !limited && (total == null || next < total), limited = limited,
                    unavailableCount = next - entries.size,
                    error = if (inconsistent) SettingsError.CONFLICT else null, refreshRequired = inconsistent))
                emit()
            }
        }
        try { session.photoHistoryPage(token.userId, offset, limit, current, ::finish) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
    private fun mutateHistory(token: SettingsProfileExtrasToken, epoch: Long, target: SettingsProfilePhotoHistoryTarget,
        selectionLease: () -> Boolean, reuse: Boolean,
    ) {
        if (!canSave(token) || !historyReady(token) || !selectionLease()) return
        val history = snapshot.history
        if (history.loading || history.refreshRequired || history.epoch != epoch || history.identity != target.identity ||
            target.identity.userId != token.userId || target.photoId == 0L || target.identity.isProtected(target.photoId) ||
            snapshot.data?.photoId != target.identity.currentPhotoId ||
            history.entries.none { it.photoId == target.photoId && it.rawOffset == target.rawOffset }) {
            snapshot = snapshot.copy(history = history.copy(error = SettingsError.CONFLICT)); emit(); return
        }
        // Pinned TDLib bot deletion rewrites the id to its current photo; historical writes are read-only.
        if (target.identity.isBot) {
            snapshot = snapshot.copy(history = history.copy(error = SettingsError.UNSUPPORTED)); emit(); return
        }
        val owner = historyOwner
        val selectorLease = historySelectorLease
        val owns = { owner.get() && selectorLease() && selectionLease() }
        mutate(if (reuse) SettingsProfileExtrasOperation.REUSE_PHOTO else SettingsProfileExtrasOperation.REMOVE_HISTORY_PHOTO,
            ownsMutation = owns, accepted = {
                resetHistory(loading = true)
                readHistoryPage()
            }, failed = { error ->
                if (error == SettingsError.CONFLICT) resetHistory(refreshRequired = true)
                else snapshot = snapshot.copy(history = snapshot.history.copy(error = error))
            }) { current, done ->
            if (reuse) session.reuseHistoryPhoto(token.userId, target, current, done)
            else session.deleteHistoryPhoto(token.userId, target, current, done)
        }
    }
    private fun mutate(operation: SettingsProfileExtrasOperation, release: () -> Unit = {}, ownsMutation: () -> Boolean = { true },
        accepted: () -> Unit = {},
        failed: (SettingsError) -> Unit = {},
        observed: (Long, SettingsResult<Unit>) -> Unit = { _, _ -> },
        work: (() -> Boolean, (SettingsResult<Unit>) -> Unit) -> Unit,
    ): Long {
        val token = snapshot.token
        val lease = active
        val externalLease = pageActive
        val id = ++request
        val write = ++revision
        val authority = terminal.captureAuthority()
        mutationInFlight = true
        snapshot = snapshot.copy(busy = true, error = null, operation = operation, startedRevision = write)
        // A throwing snapshot observer must not hide an allocated revision or interrupt its submission.
        runCatching { emit() }
        val resultLock = Any()
        var receivedActual = false
        var settledUi = false
        val receiptScope = mutationReceiptScope
        val lifetime = mutationLifetime
        val current = { authority() && lifetime.get() && lease.get() && externalLease() && ownsMutation() && lifetime.get() && lease.get() }
        fun finish(response: SettingsResult<Unit>, actualCallback: Boolean) {
            // Callback invocation alone is not proof that a native write even entered send.
            val outcome = if (actualCallback) SettingsSpecialMutationContext.receipt()?.outcome else null
            if (!actualCallback || outcome == SettingsSpecialWriteOutcome.INDETERMINATE) return
            val nativeTerminal = outcome == SettingsSpecialWriteOutcome.ACCEPTED || outcome == SettingsSpecialWriteOutcome.REJECTED
            var notifyActual = false
            val settle = synchronized(resultLock) {
                if (nativeTerminal) {
                    if (receivedActual) return
                    receivedActual = true
                    if (receiptScope != null) profileMutationReceipts?.record(receiptScope, token, write, operation, response)
                    notifyActual = true
                }
                if (settledUi) false else { settledUi = true; true }
            }
            if (notifyActual && response.value != null && operation in setOf(
                    SettingsProfileExtrasOperation.UPLOAD_PHOTO, SettingsProfileExtrasOperation.REMOVE_PHOTO,
                    SettingsProfileExtrasOperation.REUSE_PHOTO, SettingsProfileExtrasOperation.UPLOAD_ANIMATION,
                    SettingsProfileExtrasOperation.UPLOAD_STICKER_AVATAR)) {
                // A real accepted mutation can outlive its original page. Invalidate
                // the matching atomic output gate before actor publication catches up.
                val affected = currentPhotoScope.get()?.takeIf { it.userId == token.userId }
                if (affected != null && affected.gate.compareAndSet(true, false)) runCatching {
                    terminal.readback({ !retired && active.get() && pageActive() && currentPhotoScope.get() === affected &&
                        snapshot.token.userId == token.userId && snapshot.currentPhoto.epoch == affected.epoch &&
                        (affected.token != token || authority()) }) { conflictCurrentPhoto() }
                }
            }
            if (settle) {
                // Finish ownership and enqueue before foreign observation, even if it blocks or throws.
                runCatching { release() }
                runCatching {
                    terminal.enqueue {
                        mutationInFlight = false
                        // Receipt delivery is an internal capability, never page authority.
                        if (retired) return@enqueue
                        // A proved accepted mutation changes the account even if its old
                        // picker was closed. Notify the account owner before refreshing only
                        // the new page; never publish the retired selector's state.
                        if (response.value != null && (notifyActual || current())) profileChanged()
                        if (!current() || !accepts(token) || request != id) {
                            val fresh = snapshot.token
                            terminal.readback({ !retired && accepts(fresh) && pageActive() && !mutationInFlight &&
                                (fresh != token || authority()) }) {
                                snapshot = snapshot.copy(busy = false, loading = true)
                                emit(); read()
                            }
                            return@enqueue
                        }
                        if (response.value != null) {
                            snapshot = snapshot.copy(acceptedRevision = write)
                            emit()
                            accepted()
                            read(write)
                        } else {
                            snapshot = snapshot.copy(busy = false, error = response.error ?: SettingsError.REQUEST_FAILED,
                                failedRevision = write)
                            failed(response.error ?: SettingsError.REQUEST_FAILED)
                            emit()
                        }
                    }
                }
            }
            // Only the original avatar operation's content-free terminal can outlive
            // its picker. It carries no page authority, profile content or read continuation.
            val originalAvatarTerminal = operation == SettingsProfileExtrasOperation.UPLOAD_STICKER_AVATAR && notifyActual
            if (originalAvatarTerminal ||
                current() && (notifyActual || outcome == null || outcome == SettingsSpecialWriteOutcome.NOT_DISPATCHED))
                runCatching { observed(write, response) }
        }
        try { work(current) { finish(it, actualCallback = true) } }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED), actualCallback = false) }
        return write
    }
    private fun read(accepted: Long? = null) {
        val token = snapshot.token
        val lease = active
        val externalLease = pageActive
        val id = ++request
        val observedCurrent = snapshot.currentPhoto.takeIf {
            it.status == SettingsCurrentProfilePhotoStatus.READY || it.status == SettingsCurrentProfilePhotoStatus.EMPTY
        }
        val once = AtomicBoolean()
        fun finish(response: SettingsResult<SettingsProfileExtrasData>) {
            if (!once.compareAndSet(false, true)) return
            enqueue {
                if (!accepts(token) || !externalLease() || request != id) return@enqueue
                val history = snapshot.history
                if (response.value != null && history.opened && history.identity != null &&
                    history.identity.currentPhotoId != response.value.photoId) resetHistory(refreshRequired = true)
                // Only a parent GetMe started after a settled current result is newer evidence;
                // an already pending parent read cannot overrule fresh current GetMe/FullInfo.
                val currentPhoto = snapshot.currentPhoto
                if (response.value != null && observedCurrent != null &&
                    currentPhoto.epoch == observedCurrent.epoch && currentPhoto.status == observedCurrent.status &&
                    currentPhoto.photo?.photoId != response.value.photoId) conflictCurrentPhoto()
                snapshot = snapshot.copy(loading = false, busy = mutationInFlight, data = response.value ?: snapshot.data,
                    error = response.error, completedRevision = if (response.value != null && accepted != null) accepted else snapshot.completedRevision)
                emit()
            }
        }
        try { session.load(token.userId, { !retired && lease.get() && externalLease() }, ::finish) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
    private fun emit() = publish(snapshot)
}
