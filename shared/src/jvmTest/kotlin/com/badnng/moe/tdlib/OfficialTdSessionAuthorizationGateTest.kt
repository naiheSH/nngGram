package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.notifications.DeviceNotificationIdentity
import com.badnng.moe.settings.*
import com.badnng.moe.status.LoginRequestKind
import com.badnng.moe.status.LoginResult
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Real session facades with a synthetic native boundary; no JNI, account, files or OS actions. */
class OfficialTdSessionAuthorizationGateTest {
    @Test fun notReadyRejectsOrdinaryReadsWritesDownloadsAndNotificationRequestsWithoutCreatingAnEmailLock() {
        val f = Fixture(ready = false)
        val profiles = mutableListOf<SettingsResult<SettingsProfile>>()
        val writes = mutableListOf<SettingsResult<Unit>>()
        val accounts = mutableListOf<ChatResult<AccountProfile>>()
        val downloads = mutableListOf<ChatResult<MediaFile>>()
        val notifications = mutableListOf<Boolean>()
        f.settings.profile(profiles::add)
        f.settings.saveProfile(SettingsProfileEdit(ProfileField.NAME, "New first", "New last"), writes::add)
        f.chats.getMe(accounts::add)
        f.chats.download(7, downloads::add)
        f.notifications.listen {}
        f.notifications.enable(true, notifications::add)
        assertFalse(f.session.authorizationReady())
        assertTrue(f.gate.allowsOrdinary(), "Authorization readiness must not become a mandatory-email suggestion.")
        assertTrue(f.calls.isEmpty())
        assertNotNull(profiles.single().error)
        assertNotNull(writes.single().error)
        assertNotNull(accounts.single().error)
        assertNotNull(downloads.single().error)
        assertEquals(listOf(false), notifications)
    }

    @Test fun eachProfileReadContinuationRechecksReadyAtItsActualNativeSend() {
        val beforeFullInfo = Fixture()
        val failed = mutableListOf<SettingsResult<SettingsProfile>>()
        beforeFullInfo.settings.profile(failed::add)
        val me = beforeFullInfo.last
        assertIs<TdApi.GetMe>(me.command)
        beforeFullInfo.ready = false
        me.reply(TdApi.User().apply { id = 43 })
        assertEquals(1, beforeFullInfo.calls.size)
        assertNotNull(failed.single().error)

        val beforeOption = Fixture()
        val partial = mutableListOf<SettingsResult<SettingsProfile>>()
        beforeOption.settings.profile(partial::add)
        beforeOption.last.reply(TdApi.User().apply { id = 43 })
        assertIs<TdApi.GetUserFullInfo>(beforeOption.last.command)
        beforeOption.ready = false
        beforeOption.last.reply(TdApi.UserFullInfo())
        assertEquals(2, beforeOption.calls.size)
        assertNull(partial.single().value?.bioLengthMax, "A blocked optional limit read cannot authorize bio writes.")
        val writes = mutableListOf<SettingsResult<Unit>>()
        beforeOption.settings.saveProfile(SettingsProfileEdit(ProfileField.BIO, "Changed bio"), writes::add)
        assertEquals(SettingsError.INVALID_VALUE, writes.single().error)
        assertEquals(2, beforeOption.calls.size)
    }

    @Test fun ordinarySecurityPreflightCannotWriteAfterReadyIsLostAndItsReadyControlActuallyWrites() {
        for (loseReady in listOf(false, true)) {
            val f = Fixture()
            val results = mutableListOf<SettingsResult<Unit>>()
            f.settings.setDefaultMessageAutoDeleteTime(0, 86400, { true }, results::add)
            val preflight = f.last
            assertIs<TdApi.GetDefaultMessageAutoDeleteTime>(preflight.command)
            if (loseReady) f.ready = false
            preflight.reply(TdApi.MessageAutoDeleteTime(0))
            if (loseReady) {
                assertEquals(1, f.calls.size)
                assertNotNull(results.single().error)
            } else {
                assertEquals(86400, assertIs<TdApi.SetDefaultMessageAutoDeleteTime>(f.last.command).messageAutoDeleteTime.time)
                f.last.reply(TdApi.Ok())
                assertEquals(Unit, results.single().value)
                assertEquals(2, f.calls.size)
            }
        }
    }

    @Test fun profileExtrasFreshIdentityCannotAuthorizePhotoDeletionAfterReadyIsLost() {
        for (loseReady in listOf(false, true)) {
            val f = Fixture()
            val results = mutableListOf<SettingsResult<Unit>>()
            assertNotNull(f.settings.profileExtras).removePhoto(43, 91, { true }, results::add)
            assertIs<TdApi.GetMe>(f.last.command)
            if (loseReady) f.ready = false
            f.last.reply(TdApi.User().apply { id = 43; profilePhoto = TdApi.ProfilePhoto().apply { id = 91 } })
            if (loseReady) {
                assertEquals(1, f.calls.size)
                assertEquals(SettingsError.NOT_READY, results.single().error)
            } else {
                assertEquals(91L, assertIs<TdApi.DeleteProfilePhoto>(f.last.command).profilePhotoId)
                f.last.reply(TdApi.Ok())
                assertEquals(Unit, results.single().value)
            }
        }
    }

    @Test fun notificationResolveCannotStartItsMessageReadAfterReadyIsLost() {
        for (loseReady in listOf(false, true)) {
            val f = Fixture()
            val published = mutableListOf<Long>()
            val results = mutableListOf<Boolean>()
            f.notifications.listen {}
            (f.notifications as OfficialTdDeviceNotificationSession).onResolvedChat = { published += it.id }
            f.notifications.resolveTarget(DeviceNotificationIdentity(1, 6, 8), 10, 108, true, results::add)
            assertEquals(10L, assertIs<TdApi.GetChat>(f.last.command).chatId)
            if (loseReady) f.ready = false
            f.last.reply(TdApi.Chat().apply { id = 10 })
            if (loseReady) {
                assertEquals(1, f.calls.size)
                assertEquals(listOf(false), results)
                assertTrue(published.isEmpty())
            } else {
                assertEquals(108L, assertIs<TdApi.GetMessage>(f.last.command).messageId)
                f.last.reply(TdApi.Message().apply { id = 108; chatId = 10 })
                assertEquals(listOf(true), results)
                assertEquals(listOf(10L), published)
            }
        }
    }

    @Test fun anAcceptedNativeWriteStillDeliversItsOriginalAcknowledgementAndReleasesItsLease() {
        val f = Fixture()
        var cleaned = 0
        val pageLease = ownedAttachmentLease { cleaned++ }
        val nativeLease = assertNotNull(pageLease.retain())
        val results = mutableListOf<SettingsResult<Unit>>()
        f.gate.withSettingsMutation { f.settings.saveProfile(SettingsProfileEdit(ProfileField.NAME, "New first")) {
            nativeLease.release()
            results += it
        } }
        val sent = f.last
        assertIs<TdApi.SetName>(sent.command)
        pageLease.release()
        f.ready = false
        f.required()
        assertEquals(0, cleaned)
        sent.reply(TdApi.Ok())
        sent.fail()
        assertEquals(1, cleaned)
        assertEquals(Unit, results.single().value)
        assertEquals(1, f.calls.size)
    }

    @Test fun ownedDownloadCancellationWaitsForItsAcceptanceThenCrossesReadyAndMandatoryLocks() {
        val f = Fixture()
        val owner = MediaDownloadOwner(ChatToken(1, 1, 10), 108, MediaDownloadPurpose.USER, 9)
        // Deliver inside the native boundary so the real broker's timer cannot race this causal sequence.
        f.onRequest = { call -> when (call.command) {
            is TdApi.GetFileDownloadedPrefixSize -> call.reply(TdApi.FileDownloadedPrefixSize(0))
            is TdApi.DownloadFile -> {
                f.ready = false
                f.required()
                f.chats.releaseDownload(owner, 7)
                assertEquals(2, f.calls.size, "The original false-acceptance barrier must finish before cancellation.")
                call.reply(TdApi.File().apply { id = 7; size = 1024; local = TdApi.LocalFile().apply { canBeDownloaded = true; isDownloadingActive = true } })
            }
            is TdApi.CancelDownloadFile -> call.reply(TdApi.Ok())
            else -> Unit
        } }
        f.chats.acquireDownload(owner, MediaFile(7, size = 1024, canDownload = true))
        f.chats.retireDownloads()
        assertEquals(3, f.calls.size)
        val cancel = assertIs<TdApi.CancelDownloadFile>(f.last.command)
        assertEquals(7, cancel.fileId)
        assertFalse(cancel.onlyIfPending)
        f.chats.close(10)
        assertEquals(10L, assertIs<TdApi.CloseChat>(f.last.command).chatId)
        val denied = mutableListOf<ChatResult<AccountProfile>>()
        val before = f.calls.size
        f.chats.open(10)
        f.chats.getMe(denied::add)
        assertEquals(before, f.calls.size)
        assertNotNull(denied.single().error)
        f.session.close()
        assertIs<TdApi.Close>(f.last.command)
        val retired = f.calls.size
        f.chats.close(10)
        assertEquals(retired, f.calls.size, "Session retirement rejects even cleanup; only its control Close remains independent.")
    }

    @Test fun loginProxyAndCloseControlPathsRemainAvailableBeforeReady() {
        val f = Fixture(ready = false, offline = true)
        f.required()
        val proxy = mutableListOf<Int?>()
        f.session.configureProxy(null, proxy::add)
        assertIs<TdApi.DisableProxy>(f.last.command)
        f.last.reply(TdApi.Ok())
        assertEquals(listOf<Int?>(null), proxy)
        val login = mutableListOf<LoginResult>()
        f.session.submitLogin(LoginRequestKind.PHONE, "15550000000", login::add)
        assertIs<TdApi.SetAuthenticationPhoneNumber>(f.last.command)
        f.last.reply(TdApi.Ok())
        assertNull(login.single().error)
        f.session.close()
        assertIs<TdApi.Close>(f.last.command)
        assertEquals(3, f.calls.size)
    }

    @Test fun legacySyntheticSessionsDefaultToReadyWithoutImplementingANewNativeState() {
        val legacy = object : TdSession {
            override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) {}
            override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) {}
            override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) {}
            override fun close() {}
        }
        assertTrue(legacy.authorizationReady())
    }

    @Test fun profileColorsLoadAndEmojiMetadataRespectNotReadyAndMandatorySendGatesExactlyOnce() {
        for (mandatory in listOf(false, true)) {
            val f = Fixture(ready = mandatory)
            if (mandatory) f.required()
            assertEquals(mandatory, f.session.authorizationReady())
            assertEquals(!mandatory, f.gate.allowsOrdinary(), "Exercise one independent gate at a time.")
            val colors = assertNotNull(f.settings.profileColors)
            val loads = mutableListOf<SettingsResult<SettingsProfileColorsData>>()
            val backgrounds = mutableListOf<SettingsResult<List<SettingsBackgroundEmoji>>>()
            val resolved = mutableListOf<SettingsResult<List<SettingsBackgroundEmoji>>>()

            colors.load(43, { true }, loads::add)
            colors.backgroundEmojis({ true }, backgrounds::add)
            colors.resolveEmojis(listOf(-701L), { true }, resolved::add)

            assertTrue(f.calls.isEmpty(), "Native READY and mandatory gates must reject before the send ledger.")
            assertEquals(listOf(SettingsError.NOT_READY), loads.map { it.error })
            assertEquals(listOf(SettingsError.NOT_READY), backgrounds.map { it.error })
            assertEquals(listOf(SettingsError.NOT_READY), resolved.map { it.error })
            assertEquals(1, loads.size)
            assertEquals(1, backgrounds.size)
            assertEquals(1, resolved.size)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun profileColorSaveContinuationsRecheckReadyBeforeSetAndAfterAcceptedSet() {
        val beforeSet = Fixture()
        val beforeSetColors = assertNotNull(beforeSet.settings.profileColors)
        val beforeSetLoaded = mutableListOf<SettingsResult<SettingsProfileColorsData>>()
        beforeSetColors.load(43, { true }, beforeSetLoaded::add)
        assertIs<TdApi.GetMe>(beforeSet.last.command)
        beforeSet.last.reply(profileColorsUser())
        val beforeSetExpected = assertNotNull(beforeSetLoaded.single().value)
        val rejected = mutableListOf<SettingsProfileColorMutationResult>()
        beforeSetColors.save(beforeSetExpected, SettingsProfileColorPart.PROFILE,
            SettingsProfileColorValue(-1), false, { true }, rejected::add)
        val preflight = beforeSet.last
        assertIs<TdApi.GetMe>(preflight.command)
        beforeSet.ready = false
        preflight.reply(profileColorsUser())
        assertEquals(2, beforeSet.calls.size)
        assertTrue(beforeSet.calls.none { it.command is TdApi.SetAccentColor || it.command is TdApi.SetProfileAccentColor })
        assertEquals(1, rejected.size)
        assertFalse(rejected.single().locallyAccepted)
        assertEquals(SettingsError.NOT_READY, rejected.single().error)

        val afterSet = Fixture()
        val afterSetColors = assertNotNull(afterSet.settings.profileColors)
        val afterSetLoaded = mutableListOf<SettingsResult<SettingsProfileColorsData>>()
        afterSetColors.load(43, { true }, afterSetLoaded::add)
        assertIs<TdApi.GetMe>(afterSet.last.command)
        afterSet.last.reply(profileColorsUser())
        val afterSetExpected = assertNotNull(afterSetLoaded.single().value)
        val accepted = mutableListOf<SettingsProfileColorMutationResult>()
        afterSetColors.save(afterSetExpected, SettingsProfileColorPart.PROFILE,
            SettingsProfileColorValue(-1), false, { true }, accepted::add)
        val freshSelf = afterSet.last
        assertIs<TdApi.GetMe>(freshSelf.command)
        freshSelf.reply(profileColorsUser())
        val set = afterSet.last
        assertIs<TdApi.SetProfileAccentColor>(set.command)
        afterSet.ready = false
        set.reply(TdApi.Ok())
        assertEquals(3, afterSet.calls.size, "A READY loss after Ok must block the follow-up GetMe readback.")
        assertEquals(1, accepted.size)
        assertTrue(accepted.single().locallyAccepted, "The original native Ok remains accepted after navigation/readback loss.")
        assertNull(accepted.single().actual)
        assertEquals(SettingsError.NOT_READY, accepted.single().error)
    }

    @Test fun sessionCloseRetiresProfileColorUpdatesAndLateMetadataCannotReviveHelperCaches() {
        val f = Fixture()
        val colors = assertNotNull(f.settings.profileColors)
        val officialSettings = f.settings as OfficialTdSettingsSession
        officialSettings.accept(profileColorsAccentUpdate(7))
        assertEquals(listOf(7), colors.catalog().availableNameColorIds)

        val metadata = mutableListOf<SettingsResult<List<SettingsBackgroundEmoji>>>()
        colors.backgroundEmojis({ true }, metadata::add)
        val pending = f.last
        assertIs<TdApi.GetDefaultBackgroundCustomEmojiStickers>(pending.command)

        f.session.close()
        assertEquals(1, f.profileColorUpdatesRetired)
        assertEquals(1, f.calls.count { it.command is TdApi.Close })
        assertNull(colors.catalog().availableNameColorIds, "Retirement clears the helper's cached directory.")
        assertEquals(SettingsError.NOT_READY, metadata.single().error)

        pending.reply(TdApi.Stickers(arrayOf(profileColorsSticker())))
        officialSettings.accept(profileColorsAccentUpdate(8))
        assertEquals(1, metadata.size)
        assertNull(colors.catalog().availableNameColorIds, "Late metadata and native updates cannot revive retired cache state.")
        val afterClose = f.calls.size
        val resolveAfterClose = mutableListOf<SettingsResult<List<SettingsBackgroundEmoji>>>()
        colors.resolveEmojis(listOf(-701L), { true }, resolveAfterClose::add)
        assertEquals(SettingsError.NOT_READY, resolveAfterClose.single().error)
        assertEquals(afterClose, f.calls.size)
        f.session.close()
        assertEquals(1, f.profileColorUpdatesRetired)
        assertEquals(1, f.calls.count { it.command is TdApi.Close })
    }

    private fun profileColorsUser() = TdApi.User().apply {
        id = 43
        type = TdApi.UserTypeRegular()
        isPremium = true
        haveAccess = true
    }

    private fun profileColorsAccentUpdate(id: Int) = TdApi.UpdateAccentColors(
        arrayOf(TdApi.AccentColor(id, 0, intArrayOf(0x112233, 0x223344), intArrayOf(0x334455), 0)), intArrayOf(id),
    )

    private fun profileColorsSticker() = TdApi.Sticker().apply {
        id = 700; setId = 701; width = 512; height = 512; emoji = "✨"
        format = TdApi.StickerFormatWebp()
        fullType = TdApi.StickerFullTypeCustomEmoji(-701, false)
        sticker = TdApi.File().apply {
            id = 702; size = 256; expectedSize = 256
            remote = TdApi.RemoteFile().apply { uniqueId = "profile-colors-test-file" }
        }
    }

    private data class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val fail: () -> Unit)
    private class Fixture(ready: Boolean = true, offline: Boolean = false) {
        var ready = ready
        var profileColorUpdatesRetired = 0
        val gate = TdUserInteractionGate()
        val suggestions = TdLoginEmailSuggestions(gate)
        val calls = mutableListOf<Pending>()
        var onRequest: ((Pending) -> Unit)? = null
        val session = OfficialTdSession(
            offline = offline,
            interactionGate = gate,
            loginEmailSuggestions = suggestions,
            isAuthorizationReady = { this.ready },
            retireProfileColorsUpdates = { profileColorUpdatesRetired++ },
            send = { command, reply, fail ->
                val call = Pending(command, reply, fail)
                calls += call
                onRequest?.invoke(call)
            },
        )
        val settings get() = session.settings
        val chats get() = session.chats
        val notifications get() = session.deviceNotifications
        val last get() = calls.last()
        fun required() = suggestions.accept(TdApi.UpdateSuggestedActions(arrayOf(TdApi.SuggestedActionSetLoginEmailAddress(false)), emptyArray()))
    }
}
