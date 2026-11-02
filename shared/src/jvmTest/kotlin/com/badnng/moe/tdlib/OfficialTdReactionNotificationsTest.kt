package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Fake dispatcher only: no native client, account, network or invented Get operation. */
class OfficialTdReactionNotificationsTest {
    @Test fun unknownStateNeverGuessesDefaultsOrWrites() {
        val f = Fixture()
        assertEquals(ReactionNotificationNativeState(), f.api.state())
        f.save()
        assertEquals(SettingsError.NOT_READY, f.results.single().error)
        assertTrue(f.requests.isEmpty())
        assertNull(f.api.state().value)
    }

    @Test fun eachNativeSourceAndAllFiveFieldsDecodeWithoutChangingTheirMeaning() {
        for (source in ReactionNotificationSource.entries) for (sound in listOf(-1L, 0L, 782L)) {
            val f = Fixture()
            val value = value(message = source, story = source, poll = source, sound = sound, sender = false)
            f.update(value)
            assertEquals(ReactionNotificationNativeState(value, 1), f.api.state())
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun patchesPreserveEveryHiddenSourceAndSoundAndCreateFreshNativeObjects() {
        for (story in ReactionNotificationSource.entries) for (sound in listOf(-1L, 0L, 871L)) {
            for (patch in listOf(
                ReactionNotificationPatch(message = ReactionNotificationSource.ALL),
                ReactionNotificationPatch(poll = ReactionNotificationSource.CONTACTS),
                ReactionNotificationPatch(showSender = false),
            )) {
                val f = Fixture()
                val before = value(story = story, sound = sound)
                val native = native(before)
                f.api.accept(TdApi.UpdateReactionNotificationSettings(native))
                f.save(patch)
                val sent = assertIs<TdApi.SetReactionNotificationSettings>(f.last.command).notificationSettings
                assertNotSame(native, sent)
                assertNotSame(native.storyReactionSource, sent.storyReactionSource)
                assertEquals(story, source(sent.storyReactionSource))
                assertEquals(sound, sent.soundId)
                assertEquals(patch.applyTo(before), decode(sent))
                // Mutating either incoming or outgoing Java containers cannot rewrite the cache.
                native.soundId = 123
                native.storyReactionSource = TdApi.ReactionNotificationSourceAll()
                sent.soundId = 456
                assertEquals(before, f.api.state().value)
                f.last.reply(TdApi.Ok())
                assertTrue(f.results.single().locallyAccepted)
                assertEquals(before, f.results.single().actual.value)
                assertEquals(listOf("SetReactionNotificationSettings"), f.names())
            }
        }
    }

    @Test fun repeatedRealUpdatesAdvanceRevisionButUnrelatedObjectsNeverDo() {
        val f = Fixture(); val seen = mutableListOf<ReactionNotificationNativeState>()
        f.api.observe(seen::add)
        f.api.accept(TdApi.Ok())
        f.api.accept(TdApi.ReactionNotificationSettings())
        assertEquals(0L, f.api.state().revision)
        repeat(3) { f.update(value()) }
        assertEquals(listOf(1L, 2L, 3L), seen.map { it.revision })
        assertTrue(f.requests.isEmpty())
    }

    @Test fun malformedUpdateRetainsOldValueButAdvancesRevisionAndDisablesWritesUntilRecovery() {
        val malformed = listOf(
            TdApi.UpdateReactionNotificationSettings(),
            TdApi.UpdateReactionNotificationSettings(TdApi.ReactionNotificationSettings()),
            TdApi.UpdateReactionNotificationSettings(native(value()).also { it.messageReactionSource = null }),
            TdApi.UpdateReactionNotificationSettings(native(value()).also { it.storyReactionSource = unknownSource() }),
            TdApi.UpdateReactionNotificationSettings(native(value()).also { it.pollVoteSource = null }),
        )
        for (update in malformed) {
            val f = Fixture(); f.update(value())
            f.api.accept(update)
            assertEquals(value(), f.api.state().value)
            assertEquals(2L, f.api.state().revision)
            assertEquals(SettingsError.INVALID_VALUE, f.api.state().error)
            f.save()
            assertEquals(SettingsError.INVALID_VALUE, f.results.single().error)
            assertTrue(f.requests.isEmpty())
            f.update(value())
            assertNull(f.api.state().error)
            f.results.clear(); f.save()
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun malformedFirstUpdateStaysUnknownAndCannotEnableWrites() {
        val f = Fixture()
        f.api.accept(TdApi.UpdateReactionNotificationSettings())
        assertNull(f.api.state().value)
        assertEquals(1L, f.api.state().revision)
        assertEquals(SettingsError.INVALID_VALUE, f.api.state().error)
        f.save(); assertTrue(f.requests.isEmpty())
    }

    @Test fun revisionWholeValueAndErrorAreAllPartOfExpectedIdentity() {
        val f = Fixture(); f.update(value())
        val actual = f.api.state()
        for (expected in listOf(
            actual.copy(revision = 0),
            actual.copy(value = value(sound = 999)),
            actual.copy(value = value(story = ReactionNotificationSource.ALL)),
            actual.copy(error = SettingsError.REQUEST_FAILED),
        )) {
            f.api.save(expected, change(), { true }, f.results::add)
            assertEquals(SettingsError.CONFLICT, f.results.last().error)
        }
        assertEquals(actual, f.api.state())
        assertTrue(f.requests.isEmpty())
    }

    @Test fun identicalLaterUpdateInvalidatesAnEarlierExpectedRevision() {
        val f = Fixture(); f.update(value()); val old = f.api.state()
        f.update(value())
        f.api.save(old, change(), { true }, f.results::add)
        assertEquals(SettingsError.CONFLICT, f.results.single().error)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun emptyAndUnchangedPatchesNeverDispatchAnyRequest() {
        val f = Fixture(); f.update(value())
        f.save(ReactionNotificationPatch())
        assertEquals(SettingsError.INVALID_VALUE, f.results.single().error)
        f.results.clear()
        f.save(ReactionNotificationPatch(message = value().message, poll = value().poll, showSender = true))
        assertNull(f.results.single().error)
        assertFalse(f.results.single().locallyAccepted)
        assertEquals(value(), f.results.single().desired)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun onlyOneMutationCanBeInFlightAndARealResultReleasesItsSlot() {
        val f = Fixture(); f.update(value()); f.save()
        repeat(100) { f.save() }
        assertEquals(1, f.requests.size)
        assertEquals(100, f.results.size)
        assertTrue(f.results.all { it.error == SettingsError.NOT_READY && !it.locallyAccepted })
        f.last.reply(TdApi.Ok())
        f.save()
        assertEquals(2, f.requests.size)
        assertEquals(1, f.results.count { it.locallyAccepted })
    }

    @Test fun concurrentSaveAttemptsReserveExactlyOneMutation() {
        val requests = Collections.synchronizedList(mutableListOf<Pending>())
        val results = Collections.synchronizedList(mutableListOf<ReactionNotificationSaveResult>())
        val api = OfficialTdReactionNotifications { command, reply, fail -> requests += Pending(command, reply, fail) }
        api.accept(TdApi.UpdateReactionNotificationSettings(native(value())))
        val expected = api.state()
        val start = CountDownLatch(1); val done = CountDownLatch(16)
        repeat(16) {
            Thread {
                try {
                    start.await()
                    api.save(expected, change(), { true }, results::add)
                } finally { done.countDown() }
            }.apply { isDaemon = true; start() }
        }
        start.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, requests.size)
        assertEquals(15, results.size)
        assertTrue(results.all { it.error == SettingsError.NOT_READY })
        requests.single().reply(TdApi.Ok())
        assertEquals(16, results.size)
        assertEquals(1, results.count { it.locallyAccepted })
    }

    @Test fun okDoesNotInventANativeValueRevisionReadbackOrServerAcknowledgement() {
        val f = Fixture(); f.update(value()); val before = f.api.state()
        f.save(); f.last.reply(TdApi.Ok())
        assertTrue(f.results.single().locallyAccepted)
        assertEquals(change().applyTo(value()), f.results.single().desired)
        assertEquals(before, f.results.single().actual)
        assertEquals(before, f.api.state())
        repeat(5) { assertEquals(before, f.api.state()) }
        assertEquals(listOf("SetReactionNotificationSettings"), f.names())
    }

    @Test fun updateBeforeOkAndOkBeforeUpdateBothKeepActualNativeArrivalOrder() {
        for (updateBeforeOk in listOf(false, true)) {
            val f = Fixture(); f.update(value()); f.save(); val request = f.last
            val desired = change().applyTo(value())
            if (updateBeforeOk) f.update(desired)
            request.reply(TdApi.Ok())
            assertEquals(if (updateBeforeOk) desired else value(), f.results.single().actual.value)
            if (!updateBeforeOk) f.update(desired)
            f.update(value(sound = 55, story = ReactionNotificationSource.ALL))
            request.reply(TdApi.Ok())
            assertEquals(3L, f.api.state().revision)
            assertEquals(55L, f.api.state().value?.soundId)
            assertEquals(ReactionNotificationSource.ALL, f.api.state().value?.story)
            assertEquals(1, f.results.size)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun aConflictingOrMalformedUpdateIsNeverOverwrittenByAnOlderCallback() {
        for (malformed in listOf(false, true)) {
            val f = Fixture(); f.update(value()); f.save()
            if (malformed) f.api.accept(TdApi.UpdateReactionNotificationSettings())
            else f.update(value(sound = 900, sender = false))
            val latest = f.api.state()
            f.last.reply(TdApi.Ok())
            assertEquals(latest, f.api.state())
            assertEquals(latest, f.results.single().actual)
            assertTrue(f.results.single().locallyAccepted)
            assertEquals(if (malformed) SettingsError.INVALID_VALUE else null, f.results.single().error)
        }
    }

    @Test fun nativeErrorsAreSanitizedAndDoNotModifyTheCache() {
        for ((code, error) in listOf(
            429 to SettingsError.RATE_LIMITED, 401 to SettingsError.NOT_READY,
            403 to SettingsError.FORBIDDEN, 400 to SettingsError.INVALID_VALUE,
            500 to SettingsError.REQUEST_FAILED,
        )) {
            val f = Fixture(); f.update(value()); val before = f.api.state()
            f.save(); f.last.reply(TdApi.Error(code, "secret native detail"))
            assertEquals(error, f.results.single().error)
            assertFalse(f.results.single().locallyAccepted)
            assertFalse(f.results.single().toString().contains("secret native detail"))
            assertEquals(before, f.api.state())
            f.save(); assertEquals(2, f.requests.size)
        }
    }

    @Test fun explicitErrorsSettleOnceAndIndeterminateWritesRetainTheirOriginalLateReceipt() {
        for (mode in 0..4) {
            val requests = mutableListOf<Pending>()
            val api = OfficialTdReactionNotifications { command, reply, fail ->
                requests += Pending(command, reply, fail)
                when (mode) {
                    0 -> reply(TdApi.Error(500, "secret"))
                    1 -> fail()
                    2 -> throw IllegalStateException("secret")
                    3 -> reply(TdApi.OptionValueEmpty())
                    else -> { reply(TdApi.Ok()); throw IllegalStateException("after callback") }
                }
            }
            api.accept(TdApi.UpdateReactionNotificationSettings(native(value())))
            val results = mutableListOf<ReactionNotificationSaveResult>()
            api.save(api.state(), change(), { true }, results::add)
            assertEquals(1, results.size)
            val uncertain = mode in 1..3
            assertEquals(mode == 4, results.first().locallyAccepted)
            assertEquals(if (mode == 4) null else if (uncertain) SettingsError.NOT_READY else SettingsError.REQUEST_FAILED, results.first().error)
            requests.single().reply(TdApi.Ok()); requests.single().fail()
            assertEquals(if (uncertain) 2 else 1, results.size)
            assertEquals(mode != 0, results.last().locallyAccepted)
        }
    }

    @Test fun duplicateSuccessFailureAndOldCallbacksCannotReleaseANewerMutation() {
        val f = Fixture(); f.update(value()); f.save(); val old = f.last
        old.reply(TdApi.Ok())
        f.save(); val next = f.last
        old.fail(); old.reply(TdApi.Ok())
        f.save()
        assertEquals(2, f.requests.size)
        assertEquals(SettingsError.NOT_READY, f.results.last().error)
        next.fail()
        assertEquals(3, f.results.size)
        assertEquals(1, f.results.count { it.locallyAccepted })
    }

    @Test fun invalidOrThrowingOwnerAndRetiredSessionNeverDispatch() {
        for (mode in 0..2) {
            val f = Fixture(); f.update(value())
            if (mode == 2) f.api.retire()
            f.api.save(f.api.state(), change(), {
                if (mode == 1) error("private owner failure") else mode == 2
            }, f.results::add)
            assertEquals(SettingsError.NOT_READY, f.results.single().error)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun ownerIsRecheckedAfterReservationAndMayInvalidateOrRetireDuringTheCheck() {
        for (mode in 0..2) {
            val f = Fixture(); f.update(value()); var checks = 0
            f.api.save(f.api.state(), change(), {
                checks++
                if (checks == 2) when (mode) {
                    0 -> f.active = false
                    1 -> f.api.retire()
                    else -> f.update(value(sound = 77))
                }
                f.active
            }, f.results::add)
            assertEquals(if (mode == 2) SettingsError.CONFLICT else SettingsError.NOT_READY, f.results.single().error)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun lateOkPreservesLocalAcceptanceAfterOwnerExpiryOrRetirement() {
        for (retire in listOf(false, true)) for (ok in listOf(false, true)) {
            val f = Fixture(); f.update(value()); f.save()
            if (retire) f.api.retire() else f.active = false
            f.last.reply(if (ok) TdApi.Ok() else TdApi.Error(500, "secret"))
            assertEquals(ok, f.results.single().locallyAccepted)
            assertEquals(SettingsError.NOT_READY, f.results.single().error)
            assertEquals(1, f.requests.size)
            assertEquals(1L, f.api.state().revision)
        }
    }

    @Test fun retirementDetachesObserverAndRejectsLateNativeUpdatesAndNewObservers() {
        val f = Fixture(); val seen = mutableListOf<ReactionNotificationNativeState>()
        f.api.observe(seen::add); f.update(value())
        f.api.observe(null); f.update(value(sound = 22))
        f.api.observe(seen::add); f.api.retire()
        f.api.observe { error("retired observer") }; f.update(value(sound = 33))
        assertEquals(1, seen.size)
        assertEquals(2L, f.api.state().revision)
        assertEquals(22L, f.api.state().value?.soundId)
    }

    @Test fun foreignActiveObserverAndResultRunWithoutTheStateLock() {
        val f = Fixture()
        var observed = 0
        f.api.observe { assertSessionReadableOnAnotherThread(f.api); observed++ }
        f.update(value()); assertEquals(1, observed)
        f.api.save(f.api.state(), change(), {
            assertSessionReadableOnAnotherThread(f.api); true
        }) {
            assertSessionReadableOnAnotherThread(f.api)
            f.results += it
        }
        f.last.reply(TdApi.Ok())
        assertEquals(1, f.results.size)
    }

    @Test fun resultAndObserverCanReenterAndThrowWithoutStickingTheMutation() {
        val f = Fixture(); f.api.observe { f.api.state(); error("observer") }
        f.update(value()); assertEquals(1L, f.api.state().revision)
        f.api.save(f.api.state(), change(), { true }) {
            f.results += it
            f.save()
            error("result consumer")
        }
        // A synchronous dispatcher can propagate a consumer exception; native state still settles once.
        runCatching { f.last.reply(TdApi.Ok()) }
        assertEquals(2, f.requests.size)
        assertEquals(1, f.results.size)
        f.last.fail()
        assertEquals(2, f.results.size)
    }

    private class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val fail: () -> Unit)
    private class Fixture {
        var active = true
        val requests = mutableListOf<Pending>()
        val results = mutableListOf<ReactionNotificationSaveResult>()
        val api = OfficialTdReactionNotifications { command, reply, fail -> requests += Pending(command, reply, fail) }
        val last get() = requests.last()
        fun save(patch: ReactionNotificationPatch = change()) = api.save(api.state(), patch, { active }, results::add)
        fun update(value: ReactionNotificationValue) = api.accept(TdApi.UpdateReactionNotificationSettings(native(value)))
        fun names() = requests.map { it.command.javaClass.simpleName }
    }

    companion object {
        private fun change() = ReactionNotificationPatch(message = ReactionNotificationSource.ALL)
        private fun value(
            message: ReactionNotificationSource = ReactionNotificationSource.NONE,
            story: ReactionNotificationSource = ReactionNotificationSource.CONTACTS,
            poll: ReactionNotificationSource = ReactionNotificationSource.NONE,
            sound: Long = -1,
            sender: Boolean = true,
        ) = ReactionNotificationValue(message, story, poll, sound, sender)
        private fun native(value: ReactionNotificationValue) = TdApi.ReactionNotificationSettings(
            native(value.message), native(value.story), native(value.poll), value.soundId, value.showSender,
        )
        private fun native(source: ReactionNotificationSource): TdApi.ReactionNotificationSource = when (source) {
            ReactionNotificationSource.NONE -> TdApi.ReactionNotificationSourceNone()
            ReactionNotificationSource.CONTACTS -> TdApi.ReactionNotificationSourceContacts()
            ReactionNotificationSource.ALL -> TdApi.ReactionNotificationSourceAll()
        }
        private fun source(source: TdApi.ReactionNotificationSource): ReactionNotificationSource = when (source) {
            is TdApi.ReactionNotificationSourceNone -> ReactionNotificationSource.NONE
            is TdApi.ReactionNotificationSourceContacts -> ReactionNotificationSource.CONTACTS
            is TdApi.ReactionNotificationSourceAll -> ReactionNotificationSource.ALL
            else -> error("Unknown source")
        }
        private fun decode(value: TdApi.ReactionNotificationSettings) = ReactionNotificationValue(
            source(value.messageReactionSource), source(value.storyReactionSource), source(value.pollVoteSource),
            value.soundId, value.showPreview,
        )
        private fun unknownSource() = object : TdApi.ReactionNotificationSource() {
            override fun getConstructor() = -123
        }
        private fun assertSessionReadableOnAnotherThread(api: TdReactionNotificationsSession) {
            val done = CountDownLatch(1)
            Thread { api.state(); done.countDown() }.apply { isDaemon = true; start() }
            assertTrue(done.await(3, TimeUnit.SECONDS), "Foreign callback must not hold the native state lock")
        }
    }
}
