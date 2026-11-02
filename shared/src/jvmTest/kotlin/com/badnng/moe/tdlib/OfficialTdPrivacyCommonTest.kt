package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Real provider with synthetic native callbacks only; does not create a client or mutate an account. */
class OfficialTdPrivacyCommonTest {
    @Test
    fun loadStartsFourIndependentReadsAndJoinsOutOfOrderResponsesExactlyOnce() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<PrivacyCommonRead>>()
        f.provider.load(f.lease, results::add)
        assertEquals(4, f.calls.size)
        assertEquals(setOf("disable_top_chats", "can_archive_and_mute_new_chats_from_unknown_users"),
            f.calls.mapNotNull { (it.command as? TdApi.GetOption)?.name }.toSet())
        assertEquals(1, f.calls.count { it.command is TdApi.GetArchiveChatListSettings })
        assertEquals(1, f.calls.count { it.command is TdApi.GetAccountTtl })
        f.accountTtl.respond(TdApi.AccountTtl(730))
        f.capability.respond(TdApi.OptionValueBoolean(true))
        f.archive.respond(nativeArchive(false, true, false))
        assertTrue(results.isEmpty())
        f.topPeers.respond(TdApi.OptionValueBoolean(true))
        f.topPeers.respond(TdApi.OptionValueEmpty())
        f.topPeers.fail()
        val read = assertNotNull(results.single().value)
        assertEquals(PrivacyCommonData(false, archive(false, true, false), PrivacyArchiveCapability.ALLOWED, 730), read.data)
        assertTrue(read.errors.isEmpty())
        assertNull(results.single().error)
        assertEquals(4, f.calls.size)
    }

    @Test
    fun partialLoadErrorsDoNotDiscardSuccessfulFieldsOrInventDefaults() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<PrivacyCommonRead>>()
        f.provider.load(f.lease, results::add)
        f.topPeers.respond(TdApi.Error(403, "private-native-text"))
        f.archive.respond(nativeArchive(true, false, true))
        f.capability.respond(TdApi.OptionValueString("true"))
        f.accountTtl.respond(TdApi.AccountTtl(548))
        val read = assertNotNull(results.single().value)
        assertNull(read.data.topPeersEnabled)
        assertEquals(archive(true, false, true), read.data.archive)
        assertEquals(PrivacyArchiveCapability.UNKNOWN, read.data.archiveCapability)
        assertEquals(548, read.data.accountTtlDays)
        assertEquals(mapOf(PrivacyCommonField.TOP_PEERS to SettingsError.FORBIDDEN,
            PrivacyCommonField.ARCHIVE_CAPABILITY to SettingsError.REQUEST_FAILED), read.errors)
        assertFalse(results.toString().contains("private"))
    }

    @Test
    fun topPeersBooleanAndOnlyThisKnownOptionsEmptyMapToActualEnabledState() {
        listOf(TdApi.OptionValueBoolean(true) to false, TdApi.OptionValueBoolean(false) to true,
            TdApi.OptionValueEmpty() to true).forEach { (native, enabled) ->
            val f = Fixture()
            val read = f.load(topPeers = native)
            assertEquals(enabled, read.data.topPeersEnabled)
            assertFalse(PrivacyCommonField.TOP_PEERS in read.errors)
        }
        listOf(TdApi.OptionValueInteger(0), TdApi.OptionValueString("false"), TdApi.Ok()).forEach { native ->
            val read = Fixture().load(topPeers = native)
            assertNull(read.data.topPeersEnabled)
            assertEquals(SettingsError.REQUEST_FAILED, read.errors[PrivacyCommonField.TOP_PEERS])
        }
    }

    @Test
    fun capabilityEmptyIsUnknownRatherThanPermissionAndDoesNotBorrowTopPeersDefault() {
        listOf(TdApi.OptionValueBoolean(true) to PrivacyArchiveCapability.ALLOWED,
            TdApi.OptionValueBoolean(false) to PrivacyArchiveCapability.DENIED,
            TdApi.OptionValueEmpty() to PrivacyArchiveCapability.UNKNOWN).forEach { (native, expected) ->
            val read = Fixture().load(capability = native)
            assertEquals(expected, read.data.archiveCapability)
            assertFalse(PrivacyCommonField.ARCHIVE_CAPABILITY in read.errors)
        }
    }

    @Test
    fun topPeersWritesTheInverseDisableOptionOnlyAfterMatchingFreshRead() {
        listOf(true to false, false to true).forEach { (expected, desired) ->
            val f = Fixture()
            val results = mutableListOf<SettingsResult<Unit>>()
            f.provider.setTopPeers(expected, desired, f.lease, results::add)
            assertEquals("disable_top_chats", assertIs<TdApi.GetOption>(f.last.command).name)
            assertTrue(results.isEmpty())
            f.last.respond(TdApi.OptionValueBoolean(!expected))
            val set = assertIs<TdApi.SetOption>(f.last.command)
            assertEquals("disable_top_chats", set.name)
            assertEquals(!desired, assertIs<TdApi.OptionValueBoolean>(set.value).value)
            assertTrue(results.isEmpty())
            f.last.respond(TdApi.Ok())
            assertEquals(SettingsResult(Unit), results.single())
            // Provider reports native acceptance only; actor owns readback and server sync is not exposed.
            assertEquals(2, f.calls.size)
        }
    }

    @Test
    fun topPeersConflictAndUnreadableFreshStateNeverIssueSetOption() {
        listOf(TdApi.OptionValueBoolean(true) to SettingsError.CONFLICT,
            TdApi.OptionValueInteger(0) to SettingsError.REQUEST_FAILED,
            TdApi.Error(403, "private-native-text") to SettingsError.FORBIDDEN).forEach { (native, error) ->
            val f = Fixture()
            val results = mutableListOf<SettingsResult<Unit>>()
            f.provider.setTopPeers(true, false, f.lease, results::add)
            f.last.respond(native)
            assertEquals(error, results.single().error)
            assertNull(results.single().value)
            assertEquals(1, f.calls.size)
        }
    }

    @Test
    fun topPeersNoopStillReadsFreshAndMutationErrorNeverLooksAccepted() {
        val noop = Fixture()
        val noops = mutableListOf<SettingsResult<Unit>>()
        noop.provider.setTopPeers(true, true, noop.lease, noops::add)
        noop.last.respond(TdApi.OptionValueEmpty())
        assertEquals(SettingsResult(Unit), noops.single())
        assertEquals(1, noop.calls.size)
        val failed = Fixture()
        val failures = mutableListOf<SettingsResult<Unit>>()
        failed.provider.setTopPeers(true, false, failed.lease, failures::add)
        failed.last.respond(TdApi.OptionValueEmpty())
        failed.last.respond(TdApi.Error(403, "private-native-text"))
        assertEquals(SettingsError.FORBIDDEN, failures.single().error)
        assertNull(failures.single().value)
    }

    @Test
    fun archiveSaveMergesOnlyTheChangedFieldAndPreservesTwoFreshRemoteFields() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        f.provider.setArchive(archive(false, false, false), archive(true, false, false), f.lease, results::add)
        assertIs<TdApi.GetArchiveChatListSettings>(f.last.command)
        f.last.respond(nativeArchive(false, true, true))
        assertEquals("can_archive_and_mute_new_chats_from_unknown_users", assertIs<TdApi.GetOption>(f.last.command).name)
        f.last.respond(TdApi.OptionValueBoolean(true))
        val saved = assertIs<TdApi.SetArchiveChatListSettings>(f.last.command).settings
        assertTrue(saved.archiveAndMuteNewChatsFromUnknownUsers)
        assertTrue(saved.keepUnmutedChatsArchived)
        assertTrue(saved.keepChatsFromFoldersArchived)
        f.last.respond(TdApi.Ok())
        assertEquals(SettingsResult(Unit), results.single())
        assertEquals(3, f.calls.size)
    }

    @Test
    fun changesToAnyEditedArchiveFieldConflictBeforeCapabilityOrMutation() {
        listOf(archive(true, false, false), archive(false, true, false), archive(false, false, true)).forEach { desired ->
            val f = Fixture()
            val results = mutableListOf<SettingsResult<Unit>>()
            f.provider.setArchive(archive(false, false, false), desired, f.lease, results::add)
            f.last.respond(nativeArchive(desired.archiveAndMuteNewChats,
                desired.keepUnmutedChatsArchived, desired.keepChatsFromFoldersArchived))
            assertEquals(SettingsError.CONFLICT, results.single().error)
            assertEquals(1, f.calls.size)
        }
    }

    @Test
    fun enablingArchiveRequiresFreshCapabilityTrueAndUnknownNeverAuthorizesIt() {
        listOf(TdApi.OptionValueBoolean(false) to SettingsError.FORBIDDEN,
            TdApi.OptionValueEmpty() to SettingsError.NOT_READY,
            TdApi.OptionValueInteger(1) to SettingsError.REQUEST_FAILED,
            TdApi.Error(429, "private-native-text") to SettingsError.RATE_LIMITED).forEach { (native, error) ->
            val f = Fixture()
            val results = mutableListOf<SettingsResult<Unit>>()
            f.provider.setArchive(archive(false, false, false), archive(true, false, false), f.lease, results::add)
            f.last.respond(nativeArchive(false, false, false))
            f.last.respond(native)
            assertEquals(error, results.single().error)
            assertNull(results.single().value)
            assertEquals(2, f.calls.size)
            assertTrue(f.calls.none { it.command is TdApi.SetArchiveChatListSettings })
        }
    }

    @Test
    fun revokingArchiveCapabilityStillAllowsDisablingAnAlreadyEnabledValue() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        f.provider.accept(TdApi.UpdateOption("can_archive_and_mute_new_chats_from_unknown_users", TdApi.OptionValueBoolean(false)))
        f.provider.setArchive(archive(true, false, false), archive(false, false, false), f.lease, results::add)
        f.last.respond(nativeArchive(true, true, true))
        val saved = assertIs<TdApi.SetArchiveChatListSettings>(f.last.command).settings
        assertFalse(saved.archiveAndMuteNewChatsFromUnknownUsers)
        assertTrue(saved.keepUnmutedChatsArchived)
        assertTrue(saved.keepChatsFromFoldersArchived)
        assertTrue(f.calls.none { it.command is TdApi.GetOption })
        f.last.respond(TdApi.Ok())
        assertEquals(SettingsResult(Unit), results.single())
    }

    @Test
    fun changingOnlyKeepArchiveFieldDoesNotReauthorizeAnUnchangedEnabledValue() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        f.provider.setArchive(archive(false, false, false), archive(false, true, false), f.lease, results::add)
        f.last.respond(nativeArchive(true, false, true))
        val saved = assertIs<TdApi.SetArchiveChatListSettings>(f.last.command).settings
        assertTrue(saved.archiveAndMuteNewChatsFromUnknownUsers)
        assertTrue(saved.keepUnmutedChatsArchived)
        assertTrue(saved.keepChatsFromFoldersArchived)
        assertTrue(f.calls.none { it.command is TdApi.GetOption })
        f.last.respond(TdApi.Ok())
        assertEquals(SettingsResult(Unit), results.single())
    }

    @Test
    fun archiveNoopPreservesFreshFieldsAndReadOrSetErrorsStayTyped() {
        val noop = Fixture()
        val noops = mutableListOf<SettingsResult<Unit>>()
        noop.provider.setArchive(archive(false, false, false), archive(false, false, false), noop.lease, noops::add)
        noop.last.respond(nativeArchive(true, true, true))
        assertEquals(SettingsResult(Unit), noops.single())
        assertEquals(1, noop.calls.size)
        val failed = Fixture()
        val failures = mutableListOf<SettingsResult<Unit>>()
        failed.provider.setArchive(archive(false, false, false), archive(false, true, false), failed.lease, failures::add)
        failed.last.respond(TdApi.Error(403, "private-native-text"))
        assertEquals(SettingsError.FORBIDDEN, failures.single().error)
        assertEquals(1, failed.calls.size)
        val setFailed = Fixture()
        val setFailures = mutableListOf<SettingsResult<Unit>>()
        setFailed.provider.setArchive(archive(false, false, false), archive(false, true, false), setFailed.lease, setFailures::add)
        setFailed.last.respond(nativeArchive(false, false, false))
        setFailed.last.respond(TdApi.Error(403, "private-native-text"))
        assertEquals(SettingsError.FORBIDDEN, setFailures.single().error)
        assertNull(setFailures.single().value)
    }

    @Test
    fun accountTtlPreservesNativeBoundaryAndCustomDayCountsUsingDedicatedSet() {
        listOf(30, 548, 730).forEach { desired ->
            val f = Fixture()
            val results = mutableListOf<SettingsResult<Unit>>()
            f.provider.setAccountTtl(180, desired, f.lease, results::add)
            assertIs<TdApi.GetAccountTtl>(f.last.command)
            f.last.respond(TdApi.AccountTtl(180))
            assertEquals(desired, assertIs<TdApi.SetAccountTtl>(f.last.command).ttl.days)
            f.last.respond(TdApi.Ok())
            assertEquals(SettingsResult(Unit), results.single())
            assertEquals(2, f.calls.size)
            assertTrue(f.calls.none { it.command is TdApi.DeleteAccount })
        }
    }

    @Test
    fun invalidAccountTtlArgumentsFailBeforeAnyNativeRequest() {
        listOf(29 to 30, 731 to 30, 30 to 0, 30 to 29, 30 to 731).forEach { (expected, desired) ->
            val f = Fixture()
            val results = mutableListOf<SettingsResult<Unit>>()
            f.provider.setAccountTtl(expected, desired, f.lease, results::add)
            assertEquals(SettingsError.INVALID_VALUE, results.single().error)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test
    fun accountTtlConflictMalformedNativeValuesAndReadErrorsNeverWrite() {
        listOf(TdApi.AccountTtl(730) to SettingsError.CONFLICT,
            TdApi.AccountTtl(29) to SettingsError.REQUEST_FAILED,
            TdApi.AccountTtl(731) to SettingsError.REQUEST_FAILED,
            TdApi.Error(403, "private-native-text") to SettingsError.FORBIDDEN).forEach { (native, error) ->
            val f = Fixture()
            val results = mutableListOf<SettingsResult<Unit>>()
            f.provider.setAccountTtl(180, 30, f.lease, results::add)
            f.last.respond(native)
            assertEquals(error, results.single().error)
            assertEquals(1, f.calls.size)
        }
        val read = Fixture().load(ttl = TdApi.AccountTtl(731))
        assertNull(read.data.accountTtlDays)
        assertEquals(SettingsError.REQUEST_FAILED, read.errors[PrivacyCommonField.ACCOUNT_TTL])
    }

    @Test
    fun accountTtlNoopStillFreshChecksAndSetFailureIsNotAcceptance() {
        val noop = Fixture()
        val noops = mutableListOf<SettingsResult<Unit>>()
        noop.provider.setAccountTtl(730, 730, noop.lease, noops::add)
        noop.last.respond(TdApi.AccountTtl(730))
        assertEquals(SettingsResult(Unit), noops.single())
        assertEquals(1, noop.calls.size)
        val failed = Fixture()
        val failures = mutableListOf<SettingsResult<Unit>>()
        failed.provider.setAccountTtl(730, 30, failed.lease, failures::add)
        failed.last.respond(TdApi.AccountTtl(730))
        failed.last.respond(TdApi.Error(403, "private-native-text"))
        assertEquals(SettingsError.FORBIDDEN, failures.single().error)
        assertNull(failures.single().value)
    }

    @Test
    fun closedLeaseOrRetiredProviderRejectsAllEntryPointsWithoutSending() {
        repeat(2) { mode ->
            val f = Fixture()
            if (mode == 0) f.current = false else f.provider.retire()
            val loads = mutableListOf<SettingsResult<PrivacyCommonRead>>()
            val writes = mutableListOf<SettingsResult<Unit>>()
            f.provider.load(f.lease, loads::add)
            f.provider.setTopPeers(true, false, f.lease, writes::add)
            f.provider.setArchive(archive(false, false, false), archive(true, false, false), f.lease, writes::add)
            f.provider.setAccountTtl(180, 30, f.lease, writes::add)
            assertTrue(f.calls.isEmpty())
            assertEquals(SettingsError.NOT_READY, loads.single().error)
            assertNull(loads.single().value)
            assertEquals(3, writes.size)
            assertTrue(writes.all { it.error == SettingsError.NOT_READY && it.value == null })
        }
    }

    @Test
    fun closingOrRetiringDuringParallelLoadCannotPublishAnOldReadEvenWithDuplicates() {
        repeat(2) { mode ->
            val f = Fixture()
            val results = mutableListOf<SettingsResult<PrivacyCommonRead>>()
            f.provider.load(f.lease, results::add)
            f.topPeers.respond(TdApi.OptionValueEmpty())
            f.topPeers.respond(TdApi.OptionValueBoolean(true))
            assertTrue(results.isEmpty())
            if (mode == 0) f.current = false else f.provider.retire()
            f.archive.respond(nativeArchive(false, true, false))
            f.capability.respond(TdApi.OptionValueBoolean(true))
            f.accountTtl.respond(TdApi.AccountTtl(180))
            f.accountTtl.fail()
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertNull(results.single().value)
            assertEquals(4, f.calls.size)
        }
    }

    @Test
    fun pageLeaseAndRetirementGateEveryPreflightStepBeforeAMutation() {
        repeat(2) { mode ->
            // Top peers and account TTL each have one preflight; archive has fresh-data and capability steps.
            repeat(4) { step ->
                val f = Fixture()
                val results = mutableListOf<SettingsResult<Unit>>()
                when (step) {
                    0 -> f.provider.setTopPeers(true, false, f.lease, results::add)
                    1 -> f.provider.setAccountTtl(180, 30, f.lease, results::add)
                    else -> f.provider.setArchive(archive(false, false, false), archive(true, false, false), f.lease, results::add)
                }
                if (step == 3) f.last.respond(nativeArchive(false, false, false))
                val pending = f.last
                val count = f.calls.size
                if (mode == 0) f.current = false else f.provider.retire()
                pending.respond(when (step) {
                    0 -> TdApi.OptionValueEmpty()
                    1 -> TdApi.AccountTtl(180)
                    2 -> nativeArchive(false, false, false)
                    else -> TdApi.OptionValueBoolean(true)
                })
                pending.fail()
                assertEquals(SettingsError.NOT_READY, results.single().error)
                assertNull(results.single().value)
                assertEquals(count, f.calls.size)
                assertTrue(f.calls.none { it.command is TdApi.SetOption || it.command is TdApi.SetAccountTtl ||
                    it.command is TdApi.SetArchiveChatListSettings })
            }
        }
    }

    @Test
    fun closingOrRetiringAfterNativeSubmissionKeepsTheRealReceiptWithoutStartingReadback() {
        repeat(2) { mode ->
            repeat(3) { operation ->
                val f = Fixture()
                val results = mutableListOf<SettingsResult<Unit>>()
                when (operation) {
                    0 -> {
                        f.provider.setTopPeers(true, false, f.lease, results::add)
                        f.last.respond(TdApi.OptionValueEmpty())
                    }
                    1 -> {
                        f.provider.setAccountTtl(180, 30, f.lease, results::add)
                        f.last.respond(TdApi.AccountTtl(180))
                    }
                    else -> {
                        f.provider.setArchive(archive(false, false, false), archive(true, false, false), f.lease, results::add)
                        f.last.respond(nativeArchive(false, false, false))
                        f.last.respond(TdApi.OptionValueBoolean(true))
                    }
                }
                val pending = f.last
                val count = f.calls.size
                if (mode == 0) f.current = false else f.provider.retire()
                pending.respond(TdApi.Ok())
                pending.respond(TdApi.Ok())
                pending.fail()
                assertNull(results.single().error)
                assertEquals(Unit, results.single().value)
                assertEquals(count, f.calls.size)
            }
        }
    }

    @Test
    fun duplicatePreflightAndMutationCallbacksCannotIssueAdditionalNativeWrites() {
        repeat(3) { operation ->
            val f = Fixture()
            val results = mutableListOf<SettingsResult<Unit>>()
            when (operation) {
                0 -> f.provider.setTopPeers(true, false, f.lease, results::add)
                1 -> f.provider.setAccountTtl(180, 30, f.lease, results::add)
                else -> f.provider.setArchive(archive(false, false, false), archive(true, false, false), f.lease, results::add)
            }
            val preflight = f.last
            val native = when (operation) {
                0 -> TdApi.OptionValueEmpty()
                1 -> TdApi.AccountTtl(180)
                else -> nativeArchive(false, false, false)
            }
            preflight.respond(native)
            val after = f.calls.size
            preflight.respond(native)
            preflight.fail()
            assertEquals(after, f.calls.size)
            if (operation == 2) {
                val capability = f.last
                capability.respond(TdApi.OptionValueBoolean(true))
                val afterCapability = f.calls.size
                capability.respond(TdApi.OptionValueBoolean(true))
                capability.fail()
                assertEquals(afterCapability, f.calls.size)
            }
            val mutation = f.last
            mutation.respond(TdApi.Ok())
            mutation.respond(TdApi.Error(403, "private-native-text"))
            mutation.fail()
            assertEquals(SettingsResult(Unit), results.single())
            assertEquals(1, f.calls.count { it.command is TdApi.SetOption || it.command is TdApi.SetAccountTtl ||
                it.command is TdApi.SetArchiveChatListSettings })
        }
    }

    @Test
    fun nativeUpdatesUseExactNamesPreserveUnknownAndStopAfterUnobserveOrRetire() {
        val f = Fixture()
        val updates = mutableListOf<PrivacyCommonUpdate>()
        f.provider.observe(updates::add)
        f.provider.accept(TdApi.UpdateOption("other_option", TdApi.OptionValueBoolean(true)))
        f.provider.accept(TdApi.Ok())
        assertTrue(updates.isEmpty())
        listOf(TdApi.OptionValueBoolean(true), TdApi.OptionValueBoolean(false), TdApi.OptionValueEmpty(),
            TdApi.OptionValueInteger(0)).forEach { value -> f.provider.accept(TdApi.UpdateOption("disable_top_chats", value)) }
        listOf(TdApi.OptionValueBoolean(true), TdApi.OptionValueBoolean(false), TdApi.OptionValueEmpty())
            .forEach { value -> f.provider.accept(TdApi.UpdateOption("can_archive_and_mute_new_chats_from_unknown_users", value)) }
        assertEquals(listOf(false, true, true, null), updates.take(4).map(PrivacyCommonUpdate::booleanValue))
        assertTrue(updates.take(4).all { it.field == PrivacyCommonField.TOP_PEERS })
        assertEquals(listOf(true, false, null), updates.drop(4).map(PrivacyCommonUpdate::booleanValue))
        assertTrue(updates.drop(4).all { it.field == PrivacyCommonField.ARCHIVE_CAPABILITY })
        f.provider.observe(null)
        f.provider.accept(TdApi.UpdateOption("disable_top_chats", TdApi.OptionValueEmpty()))
        assertEquals(7, updates.size)
        f.provider.observe(updates::add)
        f.provider.retire()
        f.provider.accept(TdApi.UpdateOption("disable_top_chats", TdApi.OptionValueEmpty()))
        assertEquals(7, updates.size)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun thrownTransportAndErrorCallbacksStayTypedAndNeverCreateASecondMutation() {
        val throws = OfficialTdPrivacyCommon { _, _, _ -> throw IllegalStateException("private-native-text") }
        val thrown = mutableListOf<SettingsResult<Unit>>()
        throws.setTopPeers(true, false, { true }, thrown::add)
        assertEquals(SettingsError.REQUEST_FAILED, thrown.single().error)
        assertFalse(thrown.toString().contains("private"))
        val f = Fixture()
        val failed = mutableListOf<SettingsResult<Unit>>()
        f.provider.setAccountTtl(180, 30, f.lease, failed::add)
        val read = f.last
        read.fail()
        read.respond(TdApi.AccountTtl(180))
        assertEquals(SettingsError.NOT_READY, failed.single().error)
        assertEquals(1, f.calls.size)
    }

    @Test
    fun capabilityRevocationDuringNativeReadCannotBeOverwrittenByAnOlderAllowedCallback() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        val before = archive(false, true, false)
        f.provider.setArchive(before, before.copy(archiveAndMuteNewChats = true), f.lease, results::add)
        f.last.respond(nativeArchive(false, true, false))
        val pending = f.last
        assertEquals("can_archive_and_mute_new_chats_from_unknown_users", assertIs<TdApi.GetOption>(pending.command).name)
        f.provider.accept(TdApi.UpdateOption("can_archive_and_mute_new_chats_from_unknown_users", TdApi.OptionValueBoolean(false)))
        pending.respond(TdApi.OptionValueBoolean(true))
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertTrue(f.calls.none { it.command is TdApi.SetArchiveChatListSettings })
    }

    @Test
    fun aNewTopPeersUpdateInvalidatesAnEarlierExpectedValueReadBeforeItCanWrite() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        f.provider.setTopPeers(true, false, f.lease, results::add)
        val pending = f.last
        f.provider.accept(TdApi.UpdateOption("disable_top_chats", TdApi.OptionValueBoolean(true)))
        pending.respond(TdApi.OptionValueEmpty())
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertEquals(1, f.calls.size)
        assertTrue(f.calls.none { it.command is TdApi.SetOption })
    }

    private class Fixture {
        var current = true
        val lease: () -> Boolean = { current }
        val calls = mutableListOf<Call>()
        val provider = OfficialTdPrivacyCommon { command, response, error -> calls += Call(command, response, error) }
        val last get() = calls.last()
        val topPeers get() = calls.single { (it.command as? TdApi.GetOption)?.name == "disable_top_chats" }
        val capability get() = calls.single { (it.command as? TdApi.GetOption)?.name == "can_archive_and_mute_new_chats_from_unknown_users" }
        val archive get() = calls.single { it.command is TdApi.GetArchiveChatListSettings }
        val accountTtl get() = calls.single { it.command is TdApi.GetAccountTtl }
        fun load(topPeers: TdApi.Object = TdApi.OptionValueEmpty(), capability: TdApi.Object = TdApi.OptionValueBoolean(true),
            ttl: TdApi.Object = TdApi.AccountTtl(180)): PrivacyCommonRead {
            val results = mutableListOf<SettingsResult<PrivacyCommonRead>>()
            provider.load(lease, results::add)
            this.topPeers.respond(topPeers)
            archive.respond(nativeArchive(false, false, false))
            this.capability.respond(capability)
            accountTtl.respond(ttl)
            return assertNotNull(results.single().value)
        }
    }

    private class Call(val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit, val fail: () -> Unit)

    private companion object {
        fun archive(automatic: Boolean, keepUnmuted: Boolean, keepFolders: Boolean) =
            PrivacyArchiveSettings(automatic, keepUnmuted, keepFolders)
        fun nativeArchive(automatic: Boolean, keepUnmuted: Boolean, keepFolders: Boolean) =
            TdApi.ArchiveChatListSettings(automatic, keepUnmuted, keepFolders)
    }
}
