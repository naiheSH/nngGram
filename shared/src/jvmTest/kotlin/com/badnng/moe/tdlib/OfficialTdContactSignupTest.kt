package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Fake public API only; a successful GetOption never proves its hidden server query succeeded. */
class OfficialTdContactSignupTest {
    @Test fun booleanOptionIsInvertedAndOnlySuccessfulEmptyHasTheDefaultValue() {
        for ((native, expected) in listOf(
            TdApi.OptionValueBoolean(true) to value(false), TdApi.OptionValueBoolean(false) to value(true),
            TdApi.OptionValueEmpty() to value(true, ContactSignupValueSource.DEFAULT))) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<ContactSignupValue>>()
            f.api.load(f::current, results::add)
            assertEquals(OPTION, assertIs<TdApi.GetOption>(f.last.command).name)
            f.last.reply(native)
            assertEquals(expected, results.single().value)
        }
    }

    @Test fun unknownTypesAndNativeFailuresNeverGuessADefault() {
        for (native in listOf<TdApi.Object>(TdApi.OptionValueInteger(0), TdApi.OptionValueString("false"),
            TdApi.Ok(), TdApi.Error(500, "private error"))) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<ContactSignupValue>>()
            f.api.load(f::current, results::add); f.last.reply(native)
            assertNull(results.single().value); assertNotNull(results.single().error)
            assertFalse(results.single().toString().contains("private error"))
        }
    }

    @Test fun saveUsesFreshEnabledComparisonAndWritesOnlyTheNegativeBooleanOption() {
        val f = Fixture(); val results = mutableListOf<ContactSignupSaveResult>()
        f.api.save(value(true, ContactSignupValueSource.DEFAULT), false, f::current, results::add)
        f.last.reply(TdApi.OptionValueBoolean(false))
        val write = assertIs<TdApi.SetOption>(f.last.command)
        assertEquals(OPTION, write.name); assertTrue(assertIs<TdApi.OptionValueBoolean>(write.value).value)
        assertTrue(results.isEmpty())
        f.last.reply(TdApi.Ok())
        assertIs<TdApi.GetOption>(f.last.command); assertTrue(results.isEmpty())
        f.last.reply(TdApi.OptionValueBoolean(true))
        assertEquals(ContactSignupSaveResult(false, true, value(false)), results.single())
        assertEquals(listOf("GetOption", "SetOption", "GetOption"), f.names())
    }

    @Test fun freshConflictAndUnchangedDesiredNeverWrite() {
        for (conflict in listOf(false, true)) {
            val f = Fixture(); val results = mutableListOf<ContactSignupSaveResult>()
            f.api.save(value(true), true, f::current, results::add)
            f.last.reply(TdApi.OptionValueBoolean(conflict))
            assertEquals(1, f.requests.size); assertFalse(results.single().locallyAccepted)
            assertEquals(if (conflict) SettingsError.CONFLICT else null, results.single().error)
            assertEquals(value(!conflict), results.single().actual)
        }
    }

    @Test fun acceptanceAndReadbackMismatchOrFailureStayDistinctWithoutRepeatedWrites() {
        for (read in listOf<TdApi.Object>(TdApi.OptionValueBoolean(false), TdApi.Error(500, "private"), TdApi.Ok())) {
            val f = Fixture(); val results = mutableListOf<ContactSignupSaveResult>()
            f.api.save(value(true), false, f::current, results::add)
            f.last.reply(TdApi.OptionValueBoolean(false)); f.last.reply(TdApi.Ok()); f.last.reply(read)
            assertTrue(results.single().locallyAccepted); assertFalse(results.single().desiredEnabled)
            if (read is TdApi.OptionValueBoolean) assertEquals(value(true), results.single().actual)
            else { assertNull(results.single().actual); assertNotNull(results.single().error) }
            assertEquals(1, f.requests.count { it.command is TdApi.SetOption })
        }
    }

    @Test fun everySaveStepHandlesNativeErrorsDiscardExceptionsAndDuplicateCallbacksOnce() {
        for (stage in 0..2) for (failure in 0..2) {
            val requests = mutableListOf<Pending>()
            val api = OfficialTdContactSignup { command, reply, fail ->
                val current = requests.size; requests += Pending(command, reply, fail)
                if (current == stage) when (failure) {
                    0 -> reply(TdApi.Error(500, "private"))
                    1 -> fail()
                    else -> throw IllegalStateException("private")
                }
            }
            val results = mutableListOf<ContactSignupSaveResult>()
            api.save(value(true), false, { true }, results::add)
            if (stage >= 1) requests[0].reply(TdApi.OptionValueBoolean(false))
            if (stage >= 2) requests[1].reply(TdApi.Ok())
            assertEquals(stage + 1, requests.size); assertNotNull(results.single().error)
            assertEquals(stage == 2, results.single().locallyAccepted)
            val failed = requests[stage]
            failed.fail(); failed.reply(TdApi.Ok())
            if (stage == 1 && failure != 0) {
                // An exception/disconnect after dispatch is not a rejection. Its late
                // real Ok settles the same write and permits one current readback.
                assertEquals(3, requests.size)
                requests[2].reply(TdApi.OptionValueBoolean(true))
                assertEquals(2, results.size)
                assertTrue(results.last().locallyAccepted); assertNull(results.last().error)
            } else { assertEquals(stage + 1, requests.size); assertEquals(1, results.size) }
        }
        val f = Fixture(); val results = mutableListOf<ContactSignupSaveResult>()
        f.api.save(value(true), false, f::current, results::add)
        val fresh = f.last; fresh.reply(TdApi.OptionValueBoolean(false)); fresh.reply(TdApi.OptionValueBoolean(false))
        val write = f.last; write.reply(TdApi.Ok()); write.reply(TdApi.Ok()); write.fail()
        val read = f.last; read.reply(TdApi.OptionValueBoolean(true)); read.reply(TdApi.OptionValueEmpty())
        assertEquals(3, f.requests.size); assertTrue(results.single().locallyAccepted)
    }

    @Test fun eachStageChecksLeaseAndRetirementWhileLateSetOkPreservesItsFact() {
        for (retire in listOf(false, true)) for (stage in 0..2) {
            val f = Fixture(); val results = mutableListOf<ContactSignupSaveResult>()
            f.api.save(value(true), false, f::current, results::add)
            if (stage >= 1) f.last.reply(TdApi.OptionValueBoolean(false))
            if (stage >= 2) f.last.reply(TdApi.Ok())
            if (retire) f.api.retire() else f.active = false
            f.last.reply(if (stage == 1) TdApi.Ok() else TdApi.OptionValueBoolean(false))
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertEquals(stage > 0, results.single().locallyAccepted)
            assertEquals(stage + 1, f.requests.size)
        }
        val f = Fixture(); f.active = false
        val read = mutableListOf<SettingsResult<ContactSignupValue>>()
        f.api.load(f::current, read::add)
        assertEquals(SettingsError.NOT_READY, read.single().error); assertTrue(f.requests.isEmpty())
    }

    @Test fun nativeUpdateOnlyNotifiesInvalidationForThisOptionAndRetirementDetaches() {
        val f = Fixture(); var updates = 0
        f.api.observe { updates++ }
        f.api.accept(TdApi.UpdateOption("unrelated", TdApi.OptionValueBoolean(true)))
        f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueBoolean(true)))
        assertEquals(1, updates); assertTrue(f.requests.isEmpty())
        f.api.observe(null); f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueEmpty()))
        assertEquals(1, updates)
        f.api.observe { updates++ }; f.api.retire()
        f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueEmpty()))
        assertEquals(1, updates)
    }

    @Test fun successfulLoadRecordsTheValueSoItsLateSameUpdateDoesNotInvalidateAgain() {
        val f = Fixture(); var invalidations = 0
        f.api.observe { invalidations++ }
        val results = mutableListOf<SettingsResult<ContactSignupValue>>()
        f.api.load(f::current, results::add); f.last.reply(TdApi.OptionValueBoolean(false))
        repeat(3) { f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueBoolean(false))) }
        assertEquals(value(true), results.single().value); assertEquals(0, invalidations)
        f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueBoolean(true)))
        assertEquals(1, invalidations)
        f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueBoolean(true)))
        assertEquals(1, invalidations)
    }

    @Test fun initialSelfUpdateDuringLoadMayInvalidateOnceButDoesNotRejectTheMatchingRead() {
        for (native in listOf<TdApi.OptionValue>(TdApi.OptionValueBoolean(false), TdApi.OptionValueEmpty())) {
            val f = Fixture(); var invalidations = 0
            f.api.observe { invalidations++ }
            val results = mutableListOf<SettingsResult<ContactSignupValue>>()
            f.api.load(f::current, results::add)
            f.api.accept(TdApi.UpdateOption(OPTION, native))
            f.last.reply(native)
            assertTrue(assertNotNull(results.single().value).enabled); assertNull(results.single().error)
            f.api.accept(TdApi.UpdateOption(OPTION, native))
            assertEquals(1, invalidations)
        }
    }

    @Test fun genuinelyDifferentUpdateDuringReadRejectsStaleResponseWithoutOverwritingLatest() {
        val f = Fixture(); var invalidations = 0
        f.api.observe { invalidations++ }
        val first = mutableListOf<SettingsResult<ContactSignupValue>>()
        f.api.load(f::current, first::add); f.last.reply(TdApi.OptionValueBoolean(false))
        val stale = mutableListOf<SettingsResult<ContactSignupValue>>()
        f.api.load(f::current, stale::add)
        val pending = f.last
        f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueBoolean(true)))
        pending.reply(TdApi.OptionValueBoolean(false)); pending.reply(TdApi.OptionValueBoolean(true))
        assertEquals(SettingsError.CONFLICT, stale.single().error); assertNull(stale.single().value)
        f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueBoolean(true)))
        assertEquals(1, invalidations, "Rejecting the old response must preserve the newer decoded value.")
        val refreshed = mutableListOf<SettingsResult<ContactSignupValue>>()
        f.api.load(f::current, refreshed::add); f.last.reply(TdApi.OptionValueBoolean(true))
        assertEquals(value(false), refreshed.single().value)
    }

    @Test fun unknownUpdatesInvalidateAndCannotLetAnOlderKnownReadRestoreADefault() {
        val f = Fixture(); var invalidations = 0
        f.api.observe { invalidations++ }
        val results = mutableListOf<SettingsResult<ContactSignupValue>>()
        f.api.load(f::current, results::add)
        f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueString("unknown")))
        f.last.reply(TdApi.OptionValueEmpty())
        assertEquals(SettingsError.CONFLICT, results.single().error); assertNull(results.single().value)
        assertEquals(1, invalidations)
        val unknown = mutableListOf<SettingsResult<ContactSignupValue>>()
        f.api.load(f::current, unknown::add); f.last.reply(TdApi.OptionValueInteger(0))
        assertNull(unknown.single().value); assertNotNull(unknown.single().error)
        f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueInteger(0)))
        assertEquals(2, invalidations)
        val fresh = mutableListOf<SettingsResult<ContactSignupValue>>()
        f.api.load(f::current, fresh::add); f.last.reply(TdApi.OptionValueEmpty())
        assertEquals(value(true, ContactSignupValueSource.DEFAULT), fresh.single().value)
    }

    @Test fun lateSameNativeUpdatesAfterActorReadCompletionCannotKeepThePagePermanentlyUneditable() {
        val f = Fixture(); val queue = ArrayDeque<() -> Unit>(); var snapshot = ContactSignupSnapshot()
        val controller = ContactSignupController(3, f.api, { queue.addLast(it) }, { snapshot = it })
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        controller.open { true }
        f.last.reply(TdApi.OptionValueBoolean(false)); drain()
        repeat(3) {
            f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueBoolean(false))); drain()
            assertFalse(snapshot.refreshRequired)
            controller.refresh(snapshot.token)
            f.last.reply(TdApi.OptionValueBoolean(false)); drain()
            f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueBoolean(false))); drain()
            assertFalse(snapshot.loading); assertFalse(snapshot.refreshRequired)
        }
        controller.save(snapshot.token, snapshot.revision, assertNotNull(snapshot.value), false)
        assertTrue(snapshot.saving, "The current public value must remain editable after identical late updates.")
        f.last.reply(TdApi.OptionValueBoolean(false))
        assertIs<TdApi.SetOption>(f.last.command)
        f.last.reply(TdApi.Ok()); f.last.reply(TdApi.OptionValueBoolean(true)); drain()
        f.api.accept(TdApi.UpdateOption(OPTION, TdApi.OptionValueBoolean(true))); drain()
        assertFalse(snapshot.readbackPending); assertFalse(snapshot.refreshRequired)
        assertEquals(value(false), snapshot.value)
        assertEquals(1, f.requests.count { it.command is TdApi.SetOption })
    }

    private class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val fail: () -> Unit)
    private class Fixture {
        var active = true
        val requests = mutableListOf<Pending>()
        val api = OfficialTdContactSignup { command, reply, fail -> requests += Pending(command, reply, fail) }
        val last get() = requests.last()
        fun current() = active
        fun names() = requests.map { it.command.javaClass.simpleName }
    }
    private fun value(enabled: Boolean, source: ContactSignupValueSource = ContactSignupValueSource.EXPLICIT) = ContactSignupValue(enabled, source)
    private companion object { const val OPTION = "disable_contact_registered_notifications" }
}
