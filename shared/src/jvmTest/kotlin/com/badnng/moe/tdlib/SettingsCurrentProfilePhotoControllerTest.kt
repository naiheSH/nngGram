package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Actor/fake native evidence only; the fake deliberately performs no authorization. */
class SettingsCurrentProfilePhotoControllerTest {
    @Test fun readOnlyOpeningDoesNotRequireTheParentSummaryToHaveLoaded() {
        val session = Session().apply { deferLoad = true }
        val queue = ArrayDeque<() -> Unit>()
        var snapshot = SettingsProfileExtrasSnapshot()
        val controller = SettingsProfileExtrasController(7, session, { queue.addLast(it) }, { snapshot = it })
        controller.open(43)
        assertTrue(snapshot.loading); assertNull(snapshot.data)
        val epoch = controller.openCurrentPhoto(snapshot.token) { true }
        assertNotNull(epoch); assertEquals(SettingsCurrentProfilePhotoStatus.LOADING, snapshot.currentPhoto.status)
        assertEquals(1, session.reads.size)
        session.reads.last().result(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY,
            SettingsCurrentProfilePhoto(90, 0, emptyList(), authorization = 71))))
        while (queue.isNotEmpty()) queue.removeFirst()()
        session.loads.single()(SettingsResult(SettingsProfileExtrasData(43, 11, null, SettingsCalendarDate(27, 10, 2026))))
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(SettingsCurrentProfilePhotoStatus.READY, snapshot.currentPhoto.status)
        assertEquals(90L, snapshot.currentPhoto.photo?.photoId)
    }

    @Test fun freshCurrentIsNotRejectedByAStaleParentSummaryAndSnapshotIsRedacted() {
        val f = Fixture(); val epoch = assertNotNull(f.controller.openCurrentPhoto(f.token) { true })
        assertEquals(SettingsCurrentProfilePhotoStatus.LOADING, f.snapshot.currentPhoto.status)
        f.session.reads.last().result(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY, f.photo())))
        f.drain()
        assertEquals(11L, f.snapshot.data?.photoId)
        val current = f.snapshot.currentPhoto
        assertEquals(epoch, current.epoch); assertEquals(SettingsCurrentProfilePhotoStatus.READY, current.status)
        assertEquals(90L, current.photo?.photoId)
        assertNull(current.photo?.variants?.single()?.file?.localPath)
        assertNull(current.photo?.animation?.file?.localPath)
        assertFalse(assertNotNull(current.photo?.animation).file.downloaded)
        assertFalse(f.snapshot.history.opened)
    }

    @Test fun openingAndRefreshReturnExactEpochAndStaleCloseCannotAffectNewOpening() {
        val f = Fixture(); val firstView = AtomicBoolean(true)
        val first = assertNotNull(f.controller.openCurrentPhoto(f.token, firstView::get))
        val old = f.session.reads.last(); firstView.set(false)
        val second = assertNotNull(f.controller.openCurrentPhoto(f.token) { true })
        assertNotEquals(first, second)
        f.controller.closeCurrentPhoto(f.token, first)
        old.result(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.EMPTY))); f.drain()
        assertEquals(second, f.snapshot.currentPhoto.epoch); assertEquals(SettingsCurrentProfilePhotoStatus.LOADING, f.snapshot.currentPhoto.status)
        f.session.reads.last().result(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY, f.photo())))
        f.drain()
        val third = assertNotNull(f.controller.refreshCurrentPhoto(f.token, second))
        assertNotEquals(second, third); assertNull(f.controller.refreshCurrentPhoto(f.token, second))
        f.controller.closeCurrentPhoto(f.token, second)
        assertEquals(third, f.snapshot.currentPhoto.epoch); assertEquals(SettingsCurrentProfilePhotoStatus.LOADING, f.snapshot.currentPhoto.status)
    }

    @Test fun zeroCloseOnlyCleansAnAlreadyInactiveOpeningAndLateReadCannotReviveIt() {
        val f = Fixture(); val view = AtomicBoolean(true)
        val epoch = assertNotNull(f.controller.openCurrentPhoto(f.token, view::get))
        f.controller.closeCurrentPhoto(f.token, 0)
        assertEquals(epoch, f.snapshot.currentPhoto.epoch)
        val pending = f.session.reads.last(); view.set(false)
        f.controller.closeCurrentPhoto(f.token, 0)
        assertEquals(SettingsCurrentProfilePhotoStatus.CLOSED, f.snapshot.currentPhoto.status)
        pending.result(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY, f.photo()))); f.drain()
        assertEquals(SettingsCurrentProfilePhotoStatus.CLOSED, f.snapshot.currentPhoto.status)
    }

    @Test fun emptyUnknownAndRoleConflictArePublishedWithoutSyntheticPhoto() {
        for (status in listOf(SettingsCurrentProfilePhotoStatus.EMPTY, SettingsCurrentProfilePhotoStatus.UNKNOWN,
            SettingsCurrentProfilePhotoStatus.ROLE_CONFLICT, SettingsCurrentProfilePhotoStatus.CONFLICT)) {
            val f = Fixture(); f.controller.openCurrentPhoto(f.token) { true }
            f.session.reads.last().result(SettingsResult(SettingsCurrentProfilePhotoRead(status))); f.drain()
            assertEquals(status, f.snapshot.currentPhoto.status); assertNull(f.snapshot.currentPhoto.photo)
        }
    }

    @Test fun typedCurrentFileIsDerivedFromCurrentTicketAndRejectsWrongParentEpochOrPhoto() {
        val f = Fixture(); val epoch = f.ready()
        val request = f.request(epoch)
        listOf(request.copy(parent = request.parent.copy(owner = request.parent.owner + 1)),
            request.copy(epoch = epoch + 1), request.copy(photoId = 91)).forEach { f.acquire(it) }
        assertTrue(f.results.all { it.error == SettingsError.CONFLICT }); assertTrue(f.session.files.isEmpty())
        f.acquire(request)
        val pending = f.session.files.single()
        assertEquals(SettingsCurrentProfilePhotoAuthorizedFileRequest(43, 71, 90,
            SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW, 200, 200), pending.request)
        pending.result(SettingsResult(staticReady)); f.drain()
        assertEquals(staticReady, f.results.last().value); assertEquals(0, pending.closes)
    }

    @Test fun currentFileConflictRevokesOnlyCurrentOwnerAndOtherHistoryStateSurvives() {
        val f = Fixture(); val epoch = f.ready()
        f.controller.openHistory(f.token) { true }
        f.session.pages.last()(SettingsResult(SettingsProfilePhotoHistoryPage(SettingsProfilePhotoIdentity(43, 11),
            listOf(SettingsProfilePhotoHistoryEntry(12, 0, 0, emptyList(), authorization = 77)), 1, 1, 0)))
        f.drain(); assertTrue(f.snapshot.history.opened)
        f.acquire(f.request(epoch))
        val file = f.session.files.single(); file.result(SettingsResult(error = SettingsError.CONFLICT)); f.drain()
        assertEquals(SettingsCurrentProfilePhotoStatus.CONFLICT, f.snapshot.currentPhoto.status)
        assertEquals(epoch, f.snapshot.currentPhoto.epoch)
        assertEquals(1, file.closes); assertTrue(f.snapshot.history.opened); assertFalse(f.snapshot.history.refreshRequired)
        assertNotNull(f.controller.refreshCurrentPhoto(f.token, epoch))
    }

    @Test fun actualPhotoMutationRevokesReadyAndPendingFilesWithinTheAdmittedEpoch() {
        val f = Fixture(); val epoch = f.ready()
        f.controller.openHistory(f.token) { true }
        f.session.pages.last()(SettingsResult(SettingsProfilePhotoHistoryPage(SettingsProfilePhotoIdentity(43, 11),
            listOf(SettingsProfilePhotoHistoryEntry(12, 0, 0, emptyList(), authorization = 77)), 1, 1, 0))); f.drain()
        val readyLease = f.acquire(f.request(epoch)); f.session.files[0].result(SettingsResult(staticReady)); f.drain()
        f.acquire(f.request(epoch)); val pending = f.session.files[1]
        f.controller.removePhoto(f.token, 11)
        SettingsSpecialMutationContext.withReceipt(SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED)) {
            f.session.removals.single()(SettingsResult(Unit))
        }
        assertFalse(readyLease.active()); f.drain()
        assertEquals(epoch, f.snapshot.currentPhoto.epoch)
        assertEquals(SettingsCurrentProfilePhotoStatus.CONFLICT, f.snapshot.currentPhoto.status)
        assertEquals(1, f.session.files[0].closes); assertEquals(1, pending.closes); assertFalse(pending.active())
        pending.result(SettingsResult(staticReady)); f.drain(); assertEquals(1, f.results.size)
        assertTrue(f.snapshot.history.opened); assertFalse(f.snapshot.history.refreshRequired)
        val next = assertNotNull(f.controller.refreshCurrentPhoto(f.token, epoch)); assertNotEquals(epoch, next)
        f.session.reads.last().result(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY,
            f.photo().copy(photoId = 92)))); f.drain()
        assertEquals(92L, f.snapshot.currentPhoto.photo?.photoId)
    }

    @Test fun parentRefreshStartedAfterCurrentCanInvalidateOnANewerGetMePhoto() {
        val f = Fixture(); val epoch = f.ready(); f.acquire(f.request(epoch))
        val pending = f.session.files.single(); f.session.deferLoad = true
        f.controller.refresh(f.token)
        f.session.loads.single()(SettingsResult(SettingsProfileExtrasData(43, 91, null, SettingsCalendarDate(27, 10, 2026))))
        f.drain()
        assertEquals(epoch, f.snapshot.currentPhoto.epoch)
        assertEquals(SettingsCurrentProfilePhotoStatus.CONFLICT, f.snapshot.currentPhoto.status)
        assertEquals(1, pending.closes); assertFalse(pending.active())
    }

    @Test fun nativeUpdateInvalidatesOnlyItsOpeningAndClosesReadyAndPendingFiles() {
        val f = Fixture(); val epoch = f.ready(); val oldRead = f.session.reads.last()
        val ready = f.acquire(f.request(epoch)); f.session.files[0].result(SettingsResult(staticReady)); f.drain()
        f.acquire(f.request(epoch)); val pending = f.session.files[1]
        var notifications = 0
        val cancel = ready.onInvalidated { assertFalse(ready.active()); notifications++ }
        assertTrue(ready.active())
        oldRead.invalidated(); assertFalse(ready.active()); assertEquals(0, notifications)
        f.drain(); assertEquals(1, notifications); cancel(); cancel()
        assertEquals(epoch, f.snapshot.currentPhoto.epoch)
        assertEquals(SettingsCurrentProfilePhotoStatus.CONFLICT, f.snapshot.currentPhoto.status)
        assertEquals(1, f.session.files[0].closes); assertEquals(1, pending.closes)
        pending.result(SettingsResult(staticReady)); f.drain(); assertEquals(1, f.results.size)
        val next = assertNotNull(f.controller.refreshCurrentPhoto(f.token, epoch))
        f.session.reads.last().result(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY,
            f.photo().copy(photoId = 92)))); f.drain()
        oldRead.invalidated(); f.drain()
        assertEquals(next, f.snapshot.currentPhoto.epoch); assertEquals(SettingsCurrentProfilePhotoStatus.READY, f.snapshot.currentPhoto.status)
        assertEquals(92L, f.snapshot.currentPhoto.photo?.photoId)
    }

    @Test fun leaseNotificationCanBeCancelledAndLateRegistrationSeesClosedGate() {
        val f = Fixture(); val epoch = f.ready(); val lease = f.acquire(f.request(epoch))
        var cancelled = 0; var late = 0
        val cancel = lease.onInvalidated { cancelled++ }; cancel(); cancel()
        f.controller.closeCurrentPhoto(f.token, epoch); f.drain()
        lease.onInvalidated { assertFalse(lease.active()); late++ }
        assertEquals(0, cancelled); assertEquals(1, late)
    }

    @Test fun conflictingFileClosesSiblingOutputEvenIfTheFaultingReadIsCancelledBeforeActorDrain() {
        val f = Fixture(); val epoch = f.ready()
        val ready = f.acquire(f.request(epoch)); f.session.files[0].result(SettingsResult(staticReady)); f.drain()
        val faulting = f.acquire(f.request(epoch)); val pending = f.session.files[1]
        var invalidations = 0
        ready.onInvalidated { assertFalse(ready.active()); invalidations++ }
        pending.result(SettingsResult(error = SettingsError.CONFLICT))
        assertFalse(ready.active()); faulting.close(); f.drain()
        assertEquals(1, invalidations); assertEquals(epoch, f.snapshot.currentPhoto.epoch)
        assertEquals(SettingsCurrentProfilePhotoStatus.CONFLICT, f.snapshot.currentPhoto.status)
        assertEquals(1, f.session.files[0].closes)
    }

    @Test fun duplicateFileCallbackCannotInvalidateASuccessfulCurrentSource() {
        val f = Fixture(); val epoch = f.ready(); val lease = f.acquire(f.request(epoch))
        val file = f.session.files.single()
        file.result(SettingsResult(staticReady)); file.result(SettingsResult(error = SettingsError.CONFLICT)); f.drain()
        assertTrue(lease.active()); assertEquals(epoch, f.snapshot.currentPhoto.epoch)
        assertEquals(SettingsCurrentProfilePhotoStatus.READY, f.snapshot.currentPhoto.status)
        assertEquals(staticReady, f.results.single().value)
    }

    @Test fun ordinaryFileErrorPreservesRequestWitnessWithoutRevocationNotification() {
        val f = Fixture(); val epoch = f.ready(); val lease = f.acquire(f.request(epoch))
        var invalidations = 0
        lease.onInvalidated { invalidations++ }
        f.session.files.single().result(SettingsResult(error = SettingsError.REQUEST_FAILED)); f.drain()
        assertEquals(SettingsError.REQUEST_FAILED, f.results.single().error)
        assertFalse(lease.active()); assertTrue(lease.requestCurrent()); assertEquals(0, invalidations)
        lease.onInvalidated { invalidations++ }; assertEquals(0, invalidations)
        f.session.reads.last().invalidated()
        assertFalse(lease.requestCurrent()); f.drain()
        assertEquals(SettingsCurrentProfilePhotoStatus.CONFLICT, f.snapshot.currentPhoto.status)
    }

    @Test fun immediateInvalidSizeRetainsOnlyAnAuthorizedFailureWitness() {
        val f = Fixture(); val epoch = f.ready()
        val lease = f.acquire(f.request(epoch).copy(targetWidthPx = 0))
        assertEquals(SettingsError.INVALID_VALUE, f.results.single().error)
        assertFalse(lease.active()); assertTrue(lease.requestCurrent()); assertTrue(f.session.files.isEmpty())
        lease.close(); assertFalse(lease.requestCurrent())
    }

    @Test fun individuallyCancelledReadCannotPublishALateOrdinaryError() {
        val f = Fixture(); val epoch = f.ready(); val lease = f.acquire(f.request(epoch))
        val pending = f.session.files.single(); lease.close()
        pending.result(SettingsResult(error = SettingsError.REQUEST_FAILED)); f.drain()
        assertTrue(f.results.isEmpty()); assertFalse(lease.requestCurrent())
        assertEquals(SettingsCurrentProfilePhotoStatus.READY, f.snapshot.currentPhoto.status)
    }

    @Test fun stoppedViewOrClosedParentReleasesOnlyItsOwnFileAndDropsLateCompletion() {
        for (closeParent in listOf(false, true)) {
            val f = Fixture(); val active = AtomicBoolean(true); val epoch = f.ready(active::get)
            f.acquire(f.request(epoch), active::get); val pending = f.session.files.single()
            if (closeParent) f.controller.close(f.token) else { active.set(false); f.controller.closeCurrentPhoto(f.token, epoch) }
            assertFalse(pending.active()); assertEquals(1, pending.closes)
            pending.result(SettingsResult(staticReady)); f.drain(); assertTrue(f.results.isEmpty())
        }
    }

    private class Fixture {
        val session = Session(); private val queue = ArrayDeque<() -> Unit>()
        var snapshot = SettingsProfileExtrasSnapshot(); val results = mutableListOf<SettingsResult<MediaFile>>()
        val controller = SettingsProfileExtrasController(7, session, { queue.addLast(it) }, { snapshot = it })
        val token get() = snapshot.token
        init { controller.open(43); drain() }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
        fun photo() = SettingsCurrentProfilePhoto(90, 123,
            listOf(SettingsProfilePhotoVariant(staticReady, 160, 160)), hasAnimation = true,
            animation = SettingsProfilePhotoAnimation(animationReady, 640, 1.25), authorization = 71)
        fun ready(active: () -> Boolean = { true }): Long {
            val epoch = assertNotNull(controller.openCurrentPhoto(token, active))
            session.reads.last().result(SettingsResult(SettingsCurrentProfilePhotoRead(SettingsCurrentProfilePhotoStatus.READY, photo())))
            drain(); return epoch
        }
        fun request(epoch: Long) = SettingsCurrentProfilePhotoMediaRequest(token, epoch, 90,
            SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW, 200, 200)
        fun acquire(request: SettingsCurrentProfilePhotoMediaRequest, active: () -> Boolean = { true }) =
            controller.acquireCurrentPhotoFile(request, active, results::add)
    }
    private class Session : TdSettingsProfileExtrasSession {
        class Read(val active: () -> Boolean, val invalidated: () -> Unit,
            val result: (SettingsResult<SettingsCurrentProfilePhotoRead>) -> Unit)
        class File(val request: SettingsCurrentProfilePhotoAuthorizedFileRequest, val active: () -> Boolean,
            val result: (SettingsResult<MediaFile>) -> Unit) : SettingsCurrentProfilePhotoFileLease {
            var closes = 0; override fun close() { closes++ }
        }
        val reads = mutableListOf<Read>(); val files = mutableListOf<File>(); var deferLoad = false
        val loads = mutableListOf<(SettingsResult<SettingsProfileExtrasData>) -> Unit>()
        val removals = mutableListOf<(SettingsResult<Unit>) -> Unit>()
        val pages = mutableListOf<(SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit>()
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) {
            if (deferLoad) loads += result else result(SettingsResult(SettingsProfileExtrasData(userId, 11, null, SettingsCalendarDate(27, 10, 2026))))
        }
        override fun currentProfilePhoto(userId: Long, active: () -> Boolean, invalidated: () -> Unit,
            result: (SettingsResult<SettingsCurrentProfilePhotoRead>) -> Unit) {
            reads += Read(active, invalidated, result)
        }
        override fun acquireCurrentProfilePhotoFile(request: SettingsCurrentProfilePhotoAuthorizedFileRequest, active: () -> Boolean,
            result: (SettingsResult<MediaFile>) -> Unit): SettingsCurrentProfilePhotoFileLease =
            File(request, active, result).also { files += it }
        override fun photoHistoryPage(userId: Long, offset: Int, limit: Int, isCurrent: () -> Boolean,
            result: (SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit) { pages += result }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) = Unit
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) = Unit
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { removals += result }
    }
    private companion object {
        val staticReady = MediaFile(8, 100, localPath = "synthetic-current.jpg", downloaded = true, uniqueId = "native-8")
        val animationReady = MediaFile(9, 100, localPath = "synthetic-current.mp4", downloaded = true, uniqueId = "native-9")
    }
}
