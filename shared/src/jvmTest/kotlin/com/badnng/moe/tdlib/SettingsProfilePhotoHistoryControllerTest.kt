package com.badnng.moe.tdlib

import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.*
import kotlin.test.*

/** Actor/fake-session evidence only: no real-account write or thumbnail/UI acceptance. */
class SettingsProfilePhotoHistoryControllerTest {
    @Test fun rawCursorAdvancesPastFilteredAndDuplicateRowsWithoutInventingTotal() {
        val f = Fixture().apply { loaded(); openHistory() }
        f.page(rawCount = 32, entries = listOf(f.entry(11, 0), f.entry(11, 1), f.entry(0, 2)))
        assertEquals(listOf(11L), f.history.entries.map { it.photoId })
        assertEquals(32, f.history.nextOffset)
        assertEquals(31, f.history.unavailableCount)
        assertNull(f.history.totalCount)
        assertTrue(f.history.hasMore)
        f.controller.moreHistory(f.token)
        assertEquals(32, f.session.pages.last().offset)
        f.page(rawCount = 2, entries = listOf(f.entry(11, 32), f.entry(-12, 33)))
        assertEquals(listOf(11L, -12L), f.history.entries.map { it.photoId })
        assertEquals(34, f.history.nextOffset)
        assertTrue(f.history.hasMore, "A short page with unknown total is not EOF.")
        assertFalse(f.history.refreshRequired)
    }
    @Test fun knownTotalShortPageKeepsMoreAndStartsAtActualRawOffset() {
        val f = Fixture().apply { loaded(); openHistory() }
        f.page(rawCount = 2, total = 6, entries = listOf(f.entry(11, 0), f.entry(12, 1)))
        assertEquals(2, f.history.nextOffset)
        assertTrue(f.history.hasMore)
        f.controller.moreHistory(f.token)
        assertEquals(2, f.session.pages.last().offset)
        f.page(rawCount = 4, total = 6, entries = (2 until 6).map { f.entry(11L + it, it) })
        assertEquals(6, f.history.nextOffset)
        assertEquals(listOf(11L, 12L, 13L, 14L, 15L, 16L), f.history.entries.map { it.photoId })
        assertFalse(f.history.hasMore)
        assertFalse(f.history.refreshRequired)
    }
    @Test fun unknownTotalShortPagesRemainReadableUntilSuccessfulEmptyPage() {
        val f = Fixture().apply { loaded(); openHistory() }
        f.page(rawCount = 1, entries = listOf(f.entry(11, 0)))
        assertTrue(f.history.hasMore)
        f.controller.moreHistory(f.token)
        assertEquals(1, f.session.pages.last().offset)
        f.page(rawCount = 2, entries = listOf(f.entry(12, 1), f.entry(13, 2)))
        assertTrue(f.history.hasMore)
        f.controller.moreHistory(f.token)
        assertEquals(3, f.session.pages.last().offset)
        f.page(rawCount = 0)
        assertEquals(3, f.history.nextOffset)
        assertEquals(listOf(11L, 12L, 13L), f.history.entries.map { it.photoId })
        assertNull(f.history.totalCount)
        assertFalse(f.history.hasMore)
        assertFalse(f.history.refreshRequired)
        assertNull(f.history.error)
    }
    @Test fun successfulEmptyIsSeparateFromFailureAndUnknownNegativeTotal() {
        val f = Fixture().apply { loaded(); openHistory() }
        f.session.pages.last().done(SettingsResult(error = SettingsError.RATE_LIMITED))
        assertEquals(SettingsError.RATE_LIMITED, f.history.error)
        f.controller.refreshHistory(f.token)
        f.page(rawCount = 0, total = -1)
        assertFalse(f.history.loading)
        assertTrue(f.history.entries.isEmpty())
        assertNull(f.history.totalCount)
        assertNull(f.history.error)
        assertFalse(f.history.hasMore)
    }
    @Test fun emptyPageClaimingRemainingRowsRequiresExplicitRefreshWithoutAnotherRequest() {
        val f = Fixture().apply { loaded(); openHistory() }
        f.page(rawCount = 0, total = 8)
        assertTrue(f.history.refreshRequired)
        assertFalse(f.history.hasMore)
        assertEquals(SettingsError.CONFLICT, f.history.error)
        f.controller.moreHistory(f.token)
        assertEquals(1, f.session.pages.size)
        val epoch = f.history.epoch
        f.controller.refreshHistory(f.token)
        assertTrue(f.history.epoch > epoch)
        assertEquals(0, f.session.pages.last().offset)
    }
    @Test fun knownTotalStopsExactlyAndImpossibleCountRequiresRefresh() {
        val f = Fixture().apply { loaded(); openHistory() }
        f.page(rawCount = 32, total = 32)
        assertFalse(f.history.hasMore)
        assertFalse(f.history.limited)
        f.controller.refreshHistory(f.token)
        f.page(rawCount = 3, total = 2)
        assertTrue(f.history.refreshRequired)
        assertFalse(f.history.hasMore)
    }
    @Test fun totalChangeDiscardsDiscontinuousListAndInvalidatesOldConfirmation() {
        val f = Fixture().apply { loaded(); openHistory() }
        f.page(rawCount = 32, total = 70, entries = listOf(f.entry(11, 0)))
        val epoch = f.history.epoch
        val target = f.target()
        f.controller.moreHistory(f.token)
        f.page(rawCount = 32, total = 71)
        assertTrue(f.history.epoch > epoch)
        assertEquals(0, f.history.nextOffset)
        assertTrue(f.history.entries.isEmpty())
        assertTrue(f.history.refreshRequired)
        f.controller.removeHistory(f.token, epoch, target) { true }
        assertTrue(f.session.writes.isEmpty())
        assertEquals(2, f.session.pages.size)
    }
    @Test fun roleChangeDiscardsOldListAndDoesNotOverwriteParentData() {
        val f = Fixture().apply { loaded(); openHistory() }
        f.page(rawCount = 32, entries = listOf(f.entry(11, 0)))
        val epoch = f.history.epoch
        f.controller.moreHistory(f.token)
        f.page(rawCount = 1, identity = f.identity.copy(publicPhotoId = 11))
        assertTrue(f.history.epoch > epoch)
        assertTrue(f.history.refreshRequired)
        assertTrue(f.history.entries.isEmpty())
        assertEquals(91L, f.snapshot.data?.photoId)
    }
    @Test fun parentReadChangingCurrentPhotoInvalidatesHistory() {
        val f = Fixture().apply { loaded(); candidate() }
        val epoch = f.history.epoch
        f.controller.refresh(f.token)
        f.session.reads.last().done(SettingsResult(f.data.copy(photoId = 92)))
        assertTrue(f.history.refreshRequired)
        assertTrue(f.history.epoch > epoch)
        assertTrue(f.history.entries.isEmpty())
        assertEquals(92L, f.snapshot.data?.photoId)
    }
    @Test fun ownerHasIndependent512RawAndDisplayBudgetAndNeverReadsPastIt() {
        val f = Fixture().apply { loaded(); openHistory() }
        repeat(16) { index ->
            val offset = index * 32
            assertEquals(offset, f.session.pages.last().offset)
            assertEquals(32, f.session.pages.last().limit)
            f.page(rawCount = 32, entries = (0 until 32).map { f.entry(1_000L + offset + it, offset + it) })
            if (index < 15) f.controller.moreHistory(f.token)
        }
        assertEquals(512, f.history.entries.size)
        assertEquals(512, f.history.nextOffset)
        assertTrue(f.history.limited)
        assertFalse(f.history.hasMore)
        f.controller.moreHistory(f.token)
        assertEquals(16, f.session.pages.size)
    }
    @Test fun duplicateOnlyPagesStillExhaustRawBudget() {
        val f = Fixture().apply { loaded(); openHistory() }
        repeat(16) { index ->
            f.page(rawCount = 32, entries = listOf(f.entry(11, index * 32)))
            if (index < 15) f.controller.moreHistory(f.token)
        }
        assertEquals(1, f.history.entries.size)
        assertEquals(511, f.history.unavailableCount)
        assertTrue(f.history.limited)
    }
    @Test fun retiredHistoryPageAndRepeatedCallbackCannotPublishNewOwner() {
        val f = Fixture().apply { loaded(); openHistory() }
        val old = f.session.pages.single()
        f.controller.closeHistory(f.token)
        f.openHistory()
        assertFalse(old.current())
        old.done(SettingsResult(f.pageValue(1, listOf(f.entry(11, 0)))))
        assertTrue(f.history.loading)
        f.page(rawCount = 1, entries = listOf(f.entry(12, 0)))
        old.done(SettingsResult(f.pageValue(1, listOf(f.entry(13, 0)))))
        assertEquals(listOf(12L), f.history.entries.map { it.photoId })
    }
    @Test fun pageAndSelectorLeasesCancelEveryNativeStageBeforeActorCleanup() {
        val f = Fixture()
        var parentVisible = true
        var historyVisible = true
        f.controller.open(43) { parentVisible }
        f.session.reads.last().done(SettingsResult(f.data))
        f.controller.openHistory(f.token) { historyVisible }
        val first = f.session.pages.last()
        historyVisible = false
        assertFalse(first.current())
        first.done(SettingsResult(f.pageValue(1, listOf(f.entry(11, 0)))))
        assertTrue(f.history.entries.isEmpty())
        historyVisible = true
        f.controller.openHistory(f.token) { historyVisible }
        parentVisible = false
        assertFalse(f.session.pages.last().current())
        f.page(rawCount = 1, entries = listOf(f.entry(12, 0)))
        assertTrue(f.history.entries.isEmpty())
    }
    @Test fun mutationChecksEpochExactRowIdentityProtectedRoleAndSelection() {
        val f = Fixture().apply { loaded(); candidate() }
        val target = f.target()
        f.controller.removeHistory(f.token, f.history.epoch - 1, target) { true }
        f.controller.removeHistory(f.token, f.history.epoch, target.copy(rawOffset = 1)) { true }
        f.controller.removeHistory(f.token, f.history.epoch, target.copy(identity = f.identity.copy(publicPhotoId = 11))) { true }
        f.controller.removeHistory(f.token, f.history.epoch, target) { false }
        assertTrue(f.session.writes.isEmpty())
        f.controller.removeHistory(f.token, f.history.epoch, target) { true }
        assertEquals("history-delete", f.session.writes.single().kind)
        assertEquals(target, f.session.writes.single().target)
        assertTrue(f.session.writes.single().current())
    }
    @Test fun currentPersonalAndPublicRowsCannotUseHistoryActions() {
        for (identity in listOf(SettingsProfilePhotoIdentity(43, 11), SettingsProfilePhotoIdentity(43, 91, personalPhotoId = 11),
            SettingsProfilePhotoIdentity(43, 91, publicPhotoId = 11))) {
            val f = Fixture().apply { loaded(); openHistory() }
            f.page(rawCount = 1, entries = listOf(f.entry(11, 0)), identity = identity)
            val target = SettingsProfilePhotoHistoryTarget(identity, 11, 0)
            f.controller.removeHistory(f.token, f.history.epoch, target) { true }
            f.controller.reuseHistory(f.token, f.history.epoch, target) { true }
            assertTrue(f.session.writes.isEmpty())
        }
    }
    @Test fun botHistoryRemainsReadableButBothHistoricalWritesAreUnsupported() {
        val f = Fixture().apply { loaded(); openHistory() }
        val identity = f.identity.copy(isBot = true)
        f.page(rawCount = 1, entries = listOf(f.entry(11, 0)), identity = identity)
        val target = SettingsProfilePhotoHistoryTarget(identity, 11, 0)
        f.controller.reuseHistory(f.token, f.history.epoch, target) { true }
        assertEquals(SettingsError.UNSUPPORTED, f.history.error)
        assertTrue(f.session.writes.isEmpty())
        f.controller.removeHistory(f.token, f.history.epoch, target) { true }
        assertEquals(SettingsError.UNSUPPORTED, f.history.error)
        assertTrue(f.session.writes.isEmpty())
        assertEquals(listOf(11L), f.history.entries.map { it.photoId })
        assertFalse(f.history.refreshRequired)
    }
    @Test fun changedSelectionCancelsWriteCurrentAndLateAcceptancePreservesNewSelector() {
        val f = Fixture().apply { loaded(); candidate() }
        var selected = true
        f.controller.reuseHistory(f.token, f.history.epoch, f.target()) { selected }
        val write = f.session.writes.single()
        selected = false
        assertFalse(write.current())
        f.controller.closeHistory(f.token)
        f.openHistory()
        f.page(rawCount = 1, entries = listOf(f.entry(12, 0)))
        val newHistory = f.history
        SettingsSpecialMutationContext.withReceipt(SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED)) {
            write.done(SettingsResult(Unit))
        }
        assertEquals(newHistory, f.history)
        assertEquals(0L, f.snapshot.acceptedRevision)
        assertEquals(1, f.profileRefreshes)
        assertTrue(f.snapshot.loading)
        f.session.reads.last().done(SettingsResult(f.data))
        assertEquals(newHistory, f.history)
    }
    @Test fun historyMutationSharesSingleBusyGateWithBirthdayAndCurrentPhoto() {
        val f = Fixture().apply { loaded(); candidate() }
        f.controller.reuseHistory(f.token, f.history.epoch, f.target()) { true }
        assertTrue(f.snapshot.busy)
        f.controller.birthdate(f.token, null, SettingsBirthdate(4, 5))
        f.controller.removePhoto(f.token, 91)
        f.controller.removeHistory(f.token, f.history.epoch, f.target()) { true }
        assertEquals(1, f.session.writes.size)
        assertEquals(SettingsProfileExtrasOperation.REUSE_PHOTO, f.snapshot.operation)
    }
    @Test fun birthdayAndUploadBlockHistoryWritesAndRejectedUploadReleasesOwnHandle() {
        val f = Fixture().apply { loaded(); candidate() }
        f.controller.birthdate(f.token, null, SettingsBirthdate(4, 5))
        f.controller.reuseHistory(f.token, f.history.epoch, f.target()) { true }
        assertEquals("birthdate", f.session.writes.single().kind)
        f.session.writes.single().done(SettingsResult(error = SettingsError.RATE_LIMITED))
        var deleted = 0
        val source = ownedAttachmentLease { deleted++ }
        val pool = AttachmentSendLeases()
        f.controller.upload(f.token, 91, SettingsPreparedProfilePhoto("synthetic.jpg", 512, 32, "00".repeat(32), source),
            assertNotNull(pool.retain(source)))
        f.controller.removeHistory(f.token, f.history.epoch, f.target()) { true }
        assertEquals(listOf("birthdate", "upload"), f.session.writes.map { it.kind })
        source.release()
        assertEquals(0, deleted)
        f.session.writes.last().done(SettingsResult(error = SettingsError.REQUEST_FAILED))
        assertEquals(1, deleted)
    }
    @Test fun acceptedWriteStartsNewEpochAndBothReadbacksButFailedReadbackNeverRewrites() {
        val f = Fixture().apply { loaded(); candidate() }
        val oldEpoch = f.history.epoch
        f.controller.reuseHistory(f.token, oldEpoch, f.target()) { true }
        val revision = f.snapshot.startedRevision
        f.session.writes.single().done(SettingsResult(Unit))
        assertEquals(revision, f.snapshot.acceptedRevision)
        assertTrue(f.history.epoch > oldEpoch)
        assertEquals(0, f.session.pages.last().offset)
        assertTrue(f.history.loading)
        f.page(rawCount = 0)
        f.session.reads.last().done(SettingsResult(error = SettingsError.REQUEST_FAILED))
        assertEquals(revision, f.snapshot.acceptedRevision)
        assertEquals(0L, f.snapshot.completedRevision)
        assertFalse(f.snapshot.busy)
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.error)
        assertEquals(1, f.session.writes.size)
        assertEquals(91L, f.snapshot.data?.photoId)
    }
    @Test fun reopenedParentRetainsBusyUntilOldNativeResultWithoutConsumingNewOwner() {
        val f = Fixture().apply { loaded(); candidate() }
        f.controller.removeHistory(f.token, f.history.epoch, f.target()) { true }
        val old = f.session.writes.single()
        f.controller.close(f.token)
        f.loaded()
        f.candidate(12)
        val history = f.history
        f.controller.removeHistory(f.token, f.history.epoch, f.target()) { true }
        assertEquals(1, f.session.writes.size)
        assertTrue(f.snapshot.busy)
        old.done(SettingsResult(Unit))
        assertEquals(history, f.history)
        assertEquals(0L, f.snapshot.acceptedRevision)
        f.session.reads.last().done(SettingsResult(f.data))
        assertFalse(f.snapshot.busy)
        assertEquals(history, f.history)
    }
    @Test fun retiredControllerAndDuplicateWriteCompletionCannotPublishOrRefresh() {
        val f = Fixture().apply { loaded(); candidate() }
        f.controller.removeHistory(f.token, f.history.epoch, f.target()) { true }
        val pending = f.snapshot
        f.controller.deactivate()
        assertFalse(f.session.writes.single().current())
        f.session.writes.single().done(SettingsResult(Unit))
        f.session.writes.single().done(SettingsResult(Unit))
        assertEquals(pending, f.snapshot)
        assertEquals(0, f.profileRefreshes)
    }
    @Test fun nativeConflictInvalidatesConfirmationAndNeverFallsBackToCurrentDelete() {
        val f = Fixture().apply { loaded(); candidate() }
        val epoch = f.history.epoch
        val target = f.target()
        f.controller.removeHistory(f.token, epoch, target) { true }
        f.session.writes.single().done(SettingsResult(error = SettingsError.CONFLICT))
        assertTrue(f.history.epoch > epoch)
        assertTrue(f.history.entries.isEmpty())
        assertTrue(f.history.refreshRequired)
        assertEquals(SettingsError.CONFLICT, f.snapshot.error)
        f.controller.removeHistory(f.token, epoch, target) { true }
        assertEquals(listOf("history-delete"), f.session.writes.map { it.kind })
    }
    @Test fun duplicateNativeAcceptanceStartsReadbacksOnceAndKeepsAcceptedRevision() {
        val f = Fixture().apply { loaded(); candidate() }
        f.controller.reuseHistory(f.token, f.history.epoch, f.target()) { true }
        val write = f.session.writes.single()
        write.done(SettingsResult(Unit))
        val epoch = f.history.epoch
        val revision = f.snapshot.acceptedRevision
        write.done(SettingsResult(Unit))
        assertEquals(2, f.session.reads.size)
        assertEquals(2, f.session.pages.size)
        assertEquals(epoch, f.history.epoch)
        assertEquals(revision, f.snapshot.acceptedRevision)
        assertEquals(1, f.profileRefreshes)
    }

    private class Fixture {
        val data = SettingsProfileExtrasData(43, 91, null, SettingsCalendarDate(25, 10, 2026))
        val identity = SettingsProfilePhotoIdentity(43, 91)
        val session = Session()
        var snapshot = SettingsProfileExtrasSnapshot()
        var profileRefreshes = 0
        val controller = SettingsProfileExtrasController(7, session, { it() }, { snapshot = it }, { profileRefreshes++ })
        val token get() = snapshot.token
        val history get() = snapshot.history
        fun loaded() { controller.open(43); session.reads.last().done(SettingsResult(data)) }
        fun openHistory() { controller.openHistory(token) { true } }
        fun entry(id: Long, offset: Int) = SettingsProfilePhotoHistoryEntry(id, 0, offset, emptyList())
        fun candidate(id: Long = 11) { openHistory(); page(1, listOf(entry(id, 0))) }
        fun target() = history.entries.first().let { SettingsProfilePhotoHistoryTarget(assertNotNull(history.identity), it.photoId, it.rawOffset) }
        fun pageValue(rawCount: Int, entries: List<SettingsProfilePhotoHistoryEntry> = emptyList(), total: Int? = null,
            identity: SettingsProfilePhotoIdentity = this.identity,
        ) = SettingsProfilePhotoHistoryPage(identity, entries, rawCount, total, session.pages.last().offset)
        fun page(rawCount: Int, entries: List<SettingsProfilePhotoHistoryEntry> = emptyList(), total: Int? = null,
            identity: SettingsProfilePhotoIdentity = this.identity,
        ) { session.pages.last().done(SettingsResult(pageValue(rawCount, entries, total, identity))) }
    }
    private class Session : TdSettingsProfileExtrasSession {
        class Read(val current: () -> Boolean, val done: (SettingsResult<SettingsProfileExtrasData>) -> Unit)
        class Page(val offset: Int, val limit: Int, val current: () -> Boolean, val done: (SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit)
        class Write(val kind: String, val current: () -> Boolean, val done: (SettingsResult<Unit>) -> Unit,
            val target: SettingsProfilePhotoHistoryTarget? = null)
        val reads = mutableListOf<Read>()
        val pages = mutableListOf<Page>()
        val writes = mutableListOf<Write>()
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) { reads += Read(isCurrent, result) }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write("birthdate", isCurrent, result) }
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write("upload", isCurrent, result) }
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write("current-delete", isCurrent, result) }
        override fun photoHistoryPage(userId: Long, offset: Int, limit: Int, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfilePhotoHistoryPage>) -> Unit) { pages += Page(offset, limit, isCurrent, result) }
        override fun reuseHistoryPhoto(userId: Long, target: SettingsProfilePhotoHistoryTarget, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write("history-reuse", isCurrent, result, target) }
        override fun deleteHistoryPhoto(userId: Long, target: SettingsProfilePhotoHistoryTarget, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write("history-delete", isCurrent, result, target) }
    }
}
