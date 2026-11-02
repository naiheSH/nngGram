package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.notifications.FileNotificationSoundVolumeStore
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

/** The fake facades deliberately do not gate requests: these cases exercise the actual Engine actor. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrdinaryAccountReadyEngineTest {
    @Test fun profileColorsQueuedOpenRechecksTheExternalPageLeaseBeforeAnyNativeLoad() = runTest {
        withEngine { engine, f ->
            val account = engine.state.value.settings.token
            val visible = java.util.concurrent.atomic.AtomicBoolean(true)
            assertTrue(f.ready)
            assertTrue(f.gate.allowsOrdinary())
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertFalse(engine.openSettingsProfileColors(account.copy(generation = account.generation - 1), 43, visible::get))
            assertFalse(engine.openSettingsProfileColors(account, 43) { false })
            assertTrue(engine.openSettingsProfileColors(account, 43, visible::get))
            visible.set(false)
            runCurrent()
            assertTrue(f.settings.colors.loads.isEmpty(), "The external lease expired while the actual Engine open was queued.")
            assertFalse(engine.state.value.settings.profileColors.opened)
            visible.set(true)
            val page = openProfileColorPage(engine, f, visible::get)
            assertTrue(page.opened)
            assertEquals(profileColorsData, page.data)
            assertEquals(43L, f.settings.colors.loads.single().userId)
            assertTrue(f.settings.colors.loads.single().active())
        }
    }

    @Test fun profileColorsQueuedSaveRechecksSynchronousReadyAndRecoveryUsesANewOwnerAndActualSave() = runTest {
        withEngine { engine, f ->
            val page = openProfileColorPage(engine, f)
            val edit = profileColorEdit(91)
            assertTrue(engine.saveSettingsProfileColors(page.token, edit))
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertFalse(f.ready, "Native READY must already be false before the queued save reaches the actor.")
            assertTrue(f.gate.allowsOrdinary(), "This case must fail on READY, independently of mandatory email.")
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertFalse(engine.saveSettingsProfileColors(page.token, edit))
            assertFalse(engine.refreshSettingsProfileColors(page.token))
            runCurrent()
            assertTrue(f.settings.colors.saves.isEmpty(), "The deliberately ungated facade must not see the queued save.")
            assertNull(engine.state.value.settings.profileColors.activeSave)
            assertTrue(engine.state.value.settings.profileColors.outcomes.isEmpty())
            assertEquals(AuthorizationPhase.OTHER_STEP, engine.state.value.status.authorization)
            assertTrue(engine.closeSettingsProfileColorBackgrounds(page.token), "Exact picker cleanup bypasses ordinary admission.")
            assertTrue(engine.closeSettingsProfileColors(page.token), "Exact owner cleanup remains callable after READY loss.")
            runCurrent()
            assertFalse(engine.state.value.settings.profileColors.opened)
            f.ready = true
            assertFalse(engine.openSettingsProfileColors(engine.state.value.settings.token, 43) { true },
                "A recovered supplier cannot bypass the actor's older non-READY snapshot.")
            f.auth(AuthorizationPhase.READY); runCurrent()
            val fresh = openProfileColorPage(engine, f)
            assertNotEquals(page.token, fresh.token)
            assertFalse(engine.saveSettingsProfileColors(page.token, edit))
            assertTrue(engine.saveSettingsProfileColors(fresh.token, edit)); runCurrent()
            val save = f.settings.colors.saves.single()
            assertTrue(save.active())
            assertEquals(profileColorsData, save.expected)
            assertEquals(edit.part, save.part)
            assertEquals(edit.desired, save.desired)
            assertFalse(save.replaceGift)
            val actual = profileColorsData.copy(values = profileColorsData.values.copy(name = edit.desired))
            save.result(SettingsProfileColorMutationResult(locallyAccepted = true, actual = actual)); runCurrent()
            val outcome = engine.state.value.settings.profileColors.outcomes.getValue(edit.part)
            assertTrue(outcome.locallyAccepted)
            assertEquals(91L, outcome.attempt.edit.contentRevision)
            assertEquals(actual, engine.state.value.settings.profileColors.data)
            assertEquals(1, f.settings.colors.saves.size)
        }
    }

    @Test fun profileColorsMandatoryGateRejectsQueuedWritesAllowsCleanupAndNeverReplaysRetiredOwnerWrites() = runTest {
        withEngine { engine, f ->
            val page = openProfileColorPage(engine, f)
            val edit = profileColorEdit(92)
            assertTrue(engine.saveSettingsProfileColors(page.token, edit))
            f.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            assertTrue(f.ready, "Mandatory gating must be tested with native READY still current.")
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertFalse(f.gate.allowsOrdinary())
            assertFalse(engine.openSettingsProfileColors(engine.state.value.settings.token, 43) { true })
            assertFalse(engine.saveSettingsProfileColors(page.token, edit))
            assertFalse(engine.refreshSettingsProfileColors(page.token))
            assertFalse(engine.openSettingsProfileColorBackgrounds(page.token))
            assertFalse(engine.resolveSettingsProfileColorBackgrounds(page.token, listOf(-71)))
            assertTrue(engine.closeSettingsProfileColorBackgrounds(page.token))
            assertTrue(engine.closeSettingsProfileColors(page.token))
            runCurrent()
            assertTrue(engine.state.value.loginEmail.mandatory)
            assertFalse(engine.state.value.settings.profileColors.opened)
            assertTrue(f.settings.colors.saves.isEmpty())
            f.gate.update(LoginEmailSuggestions(2)); runCurrent()
            val second = openProfileColorPage(engine, f)
            assertNotEquals(page.token, second.token)
            assertTrue(engine.saveSettingsProfileColors(second.token, edit)); runCurrent()
            val write = f.settings.colors.saves.single()
            assertTrue(write.active(), "The recovered gate must allow an actual controller-to-facade write.")
            f.gate.update(LoginEmailSuggestions(3, requiredPresent = true, requiredEpoch = 2)); runCurrent()
            assertFalse(write.active(), "Mandatory processing retires the old page's captured atomic owner.")
            assertFalse(engine.state.value.settings.profileColors.opened)
            f.gate.update(LoginEmailSuggestions(4)); runCurrent()
            assertTrue(f.ready)
            assertTrue(f.gate.allowsOrdinary())
            assertFalse(engine.state.value.loginEmail.mandatory)
            val readsBefore = f.settings.colors.loads.size
            assertTrue(engine.openSettingsProfileColors(engine.state.value.settings.token, 43) { true }); runCurrent()
            val waiting = engine.state.value.settings.profileColors
            assertNotEquals(second.token, waiting.token)
            assertTrue(waiting.opened && waiting.busy)
            assertNull(waiting.data)
            assertEquals(readsBefore, f.settings.colors.loads.size, "A new page waits for the original write's terminal result.")
            val accepted = SettingsProfileColorMutationResult(true,
                profileColorsData.copy(values = profileColorsData.values.copy(name = edit.desired)))
            write.result(accepted); write.result(accepted); runCurrent()
            val reading = engine.state.value.settings.profileColors
            assertEquals(waiting.token, reading.token)
            assertTrue(reading.loading)
            assertNull(reading.data, "The retired owner's result cannot populate a newer page.")
            assertTrue(reading.outcomes.isEmpty())
            assertEquals(readsBefore + 1, f.settings.colors.loads.size)
            val freshRead = f.settings.colors.loads.last()
            assertTrue(freshRead.active())
            freshRead.result(SettingsResult(profileColorsData)); runCurrent()
            assertEquals(profileColorsData, engine.state.value.settings.profileColors.data)
            assertTrue(engine.state.value.settings.profileColors.outcomes.isEmpty())
            assertEquals(1, f.settings.colors.saves.size, "Neither queued rejection nor old acceptance may replay a Set request.")
        }
    }

    @Test fun profileColorMetadataUsesCurrentOwnerAndBoundedNonzeroIdsAndRejectsInactivePageWork() = runTest {
        withEngine { engine, f ->
            val visible = java.util.concurrent.atomic.AtomicBoolean(true)
            val page = openProfileColorPage(engine, f, visible::get)
            for (old in listOf(page.token.copy(generation = page.token.generation - 1), page.token.copy(owner = page.token.owner - 1))) {
                assertFalse(engine.openSettingsProfileColorBackgrounds(old))
                assertFalse(engine.resolveSettingsProfileColorBackgrounds(old, listOf(-71)))
                assertFalse(engine.refreshSettingsProfileColors(old))
                assertFalse(engine.saveSettingsProfileColors(old, profileColorEdit(93)))
                assertFalse(engine.closeSettingsProfileColorBackgrounds(old))
                assertFalse(engine.closeSettingsProfileColors(old))
            }
            assertTrue(engine.openSettingsProfileColorBackgrounds(page.token))
            visible.set(false); runCurrent()
            assertTrue(f.settings.colors.backgrounds.isEmpty())
            visible.set(true)
            assertTrue(f.ready)
            assertTrue(f.gate.allowsOrdinary())
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertTrue(engine.openSettingsProfileColorBackgrounds(page.token)); runCurrent()
            val metadata = f.settings.colors.backgrounds.single()
            assertTrue(metadata.active())
            metadata.result(SettingsResult(emptyList())); runCurrent()
            assertTrue(engine.state.value.settings.profileColors.backgroundsOpened)
            assertFalse(engine.resolveSettingsProfileColorBackgrounds(page.token, List(201) { -71L }))
            assertFalse(engine.resolveSettingsProfileColorBackgrounds(page.token, listOf(0)))
            assertTrue(f.settings.colors.resolutions.isEmpty())
            val requested = MutableList(200) { -71L }
            assertTrue(engine.resolveSettingsProfileColorBackgrounds(page.token, requested))
            requested[0] = 0L // The public boundary must already own a bounded copy.
            runCurrent()
            val resolution = f.settings.colors.resolutions.single()
            assertEquals(listOf(-71L), resolution.ids, "Signed nonzero native IDs survive; the child may deduplicate the accepted 200-item request.")
            assertTrue(resolution.active())
            resolution.result(SettingsResult(emptyList())); runCurrent()
            assertTrue(engine.resolveSettingsProfileColorBackgrounds(page.token, listOf(-71)))
            assertTrue(engine.refreshSettingsProfileColors(page.token))
            visible.set(false); runCurrent()
            assertEquals(1, f.settings.colors.resolutions.size)
            assertEquals(1, f.settings.colors.loads.size)
            assertTrue(engine.closeSettingsProfileColorBackgrounds(page.token)); runCurrent()
            assertFalse(metadata.active())
            assertFalse(engine.state.value.settings.profileColors.backgroundsOpened)
            assertTrue(engine.closeSettingsProfileColors(page.token)); runCurrent()
            visible.set(true)
            val fresh = openProfileColorPage(engine, f, visible::get)
            assertNotEquals(page.token, fresh.token)
            assertFalse(engine.openSettingsProfileColorBackgrounds(page.token))
            assertFalse(engine.resolveSettingsProfileColorBackgrounds(page.token, listOf(-71)))
            assertTrue(engine.openSettingsProfileColorBackgrounds(fresh.token)); runCurrent()
            assertEquals(2, f.settings.colors.backgrounds.size, "A live replacement owner must actually reach the metadata facade.")
            f.settings.colors.backgrounds.last().result(SettingsResult(emptyList())); runCurrent()
            assertTrue(engine.state.value.settings.profileColors.backgroundsOpened)
            assertEquals(1, f.settings.colors.resolutions.size)
            assertTrue(f.settings.colors.saves.isEmpty())
        }
    }

    @Test fun queuedSecurityInputLosesPermissionBeforeItsAuthorizationSnapshotIsProcessed() = runTest {
        withEngine { engine, f ->
            engine.openSettingsSecurity(engine.state.value.settings.token, SettingsSecuritySection.PASSWORD)
            runCurrent()
            val owner = engine.state.value.settings.security.token
            assertNotNull(engine.state.value.settings.security.password)
            val queued = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
            assertTrue(engine.settingsPassword(owner, SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RECOVERY), queued::add))
            f.auth(AuthorizationPhase.OTHER_STEP) // Native ready flips now; Authorization is behind the already queued input.
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertTrue(f.gate.allowsOrdinary(), "The account did not acquire a mandatory-email suggestion.")
            val immediate = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
            assertFalse(engine.settingsPassword(owner, SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RECOVERY), immediate::add))
            assertEquals(SettingsError.NOT_READY, immediate.single().error)
            runCurrent()
            assertTrue(f.settings.passwordCommands.isEmpty(), "The actor must check the synchronous native READY state before calling the facade.")
            assertEquals(SettingsError.NOT_READY, queued.single().error)
            assertEquals(AuthorizationPhase.OTHER_STEP, engine.state.value.status.authorization)
            assertFalse(engine.state.value.loginEmail.mandatory)
            assertTrue(engine.closeSettingsSecurity(owner), "Exact owner retirement remains available after non-closing authorization loss.")
            runCurrent()
            assertNull(engine.state.value.settings.security.section)
            f.auth(AuthorizationPhase.READY); runCurrent()
            engine.openSettingsSecurity(engine.state.value.settings.token, SettingsSecuritySection.PASSWORD); runCurrent()
            val normal = mutableListOf<SettingsResult<SettingsPasswordOutcome>>()
            assertTrue(engine.settingsPassword(engine.state.value.settings.security.token,
                SettingsPasswordCommand(SettingsPasswordOperation.REQUEST_RECOVERY), normal::add))
            runCurrent()
            assertEquals(listOf(SettingsPasswordOperation.REQUEST_RECOVERY), f.settings.passwordCommands)
            assertNull(normal.single().error)
        }
    }

    @Test fun queuedProfileUploadReleasesItsBorrowedHandleWithoutCallingTheNativeFacadeAfterReadyLoss() = runTest {
        withEngine { engine, f ->
            engine.openSettingsProfileExtras(engine.state.value.settings.token, 43); runCurrent()
            val owner = engine.state.value.settings.profileExtras.token
            assertEquals(43L, owner.userId)
            var cleaned = 0
            val queuedPhoto = photo { cleaned++ }
            assertTrue(engine.uploadSettingsProfilePhoto(owner, 91, queuedPhoto))
            queuedPhoto.lease.release()
            assertEquals(0, cleaned, "The queued Engine command already retained its own handle.")
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            runCurrent()
            assertTrue(f.settings.extras.uploads.isEmpty())
            assertEquals(1, cleaned)
            assertEquals(0L, engine.state.value.settings.profileExtras.acceptedRevision)
            assertFalse(engine.state.value.loginEmail.mandatory)
            assertTrue(engine.closeSettingsProfileExtras(owner)); runCurrent()
            assertEquals(0L, engine.state.value.settings.profileExtras.token.userId)
            f.auth(AuthorizationPhase.READY); runCurrent()
            engine.openSettingsProfileExtras(engine.state.value.settings.token, 43); runCurrent()
            var normalCleaned = 0
            val normal = photo { normalCleaned++ }
            assertTrue(engine.uploadSettingsProfilePhoto(engine.state.value.settings.profileExtras.token, 91, normal))
            normal.lease.release()
            runCurrent()
            assertEquals(1, f.settings.extras.uploads.size, "The same actual controller must still submit when READY remains current.")
            assertEquals(0, normalCleaned)
            f.settings.extras.uploads.single()(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
            assertEquals(1, normalCleaned)
        }
    }

    @Test fun ordinarySettingsInputRechecksNativeReadyAndBothSnapshotAndPublicBoundariesRecoverNormally() = runTest {
        withEngine { engine, f ->
            val owner = engine.state.value.settings.token
            val edit = SettingsProfileEdit(ProfileField.NAME, "Changed synthetic name")
            assertTrue(engine.saveSettingsProfile(owner, edit))
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertFalse(engine.saveSettingsProfile(owner, edit))
            runCurrent()
            assertTrue(f.settings.profileWrites.isEmpty())
            assertEquals(owner, engine.state.value.settings.token)
            assertTrue(engine.state.value.settings.enabled)
            assertFalse(engine.state.value.loginEmail.mandatory)
            // Even if the native supplier is already READY, the actor's older non-READY snapshot also gates input.
            f.ready = true
            assertFalse(engine.saveSettingsProfile(owner, edit))
            f.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.saveSettingsProfile(owner, edit)); runCurrent()
            assertEquals(listOf(edit), f.settings.profileWrites)
        }
    }

    @Test fun rejectedAutoMediaAdmissionCompletesOnceForQueuedReadyLossAndImmediateReadyOrMandatoryDenial() = runTest {
        withEngine { engine, f ->
            val token = engine.state.value.chats.token
            assertTrue(engine.state.value.chats.enabled)
            assertFalse(engine.state.value.chats.autoMediaSettings.loaded, "This rejection fixture needs no account identity or media preference store.")
            val queued = mutableListOf<Boolean>()
            val completions = mutableListOf<Boolean>()
            engine.saveAutoMedia(token, 0, AutoMediaPolicy(), queued::add, completions::add)
            assertTrue(queued.isEmpty(), "Admission is pending until the actual actor consumes the request.")
            f.auth(AuthorizationPhase.OTHER_STEP)
            val immediate = mutableListOf<Boolean>()
            engine.saveAutoMedia(token, 0, AutoMediaPolicy(), immediate::add, completions::add)
            assertEquals(listOf(false), immediate)
            runCurrent()
            assertEquals(listOf(false), queued)
            runCurrent()
            assertEquals(listOf(false), queued, "Actor discard must not leave the editor pending or deliver twice.")
            f.auth(AuthorizationPhase.READY); runCurrent()
            f.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            val mandatory = mutableListOf<Boolean>()
            engine.saveAutoMedia(token, 0, AutoMediaPolicy(), mandatory::add, completions::add)
            assertEquals(listOf(false), mandatory)
            assertTrue(completions.isEmpty(), "Rejected admission never invents the result of a write that did not start.")
            runCurrent()
            assertEquals(listOf(false), mandatory)
        }
    }

    @Test fun queuedStoragePolicySaveRechecksNativeReadyBeforeTheFacadeAndKeepsExactCleanupAvailable() = runTest {
        withEngine { engine, f ->
            assertTrue(engine.openSettingsStoragePolicy(engine.state.value.generation) { true })
            runCurrent()
            val state = engine.state.value.storagePolicySettings
            assertTrue(state.opened && state.values.known && !state.loading)
            val results = mutableListOf<SettingsStoragePolicyCompletion>()
            assertTrue(engine.saveSettingsStoragePolicy(state.token, state.revision,
                SettingsStoragePolicyDraft(sizeKiB = 262_144), 81, results::add))
            assertTrue(results.isEmpty(), "The public boundary only queued this actual Engine request.")
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization,
                "The authorization event is behind the already queued save.")
            assertTrue(f.gate.allowsOrdinary(), "Native READY loss is independent of the mandatory-email gate.")
            runCurrent()
            assertTrue(f.settings.policy.saves.isEmpty(), "The deliberately ungated facade must never receive the rejected actor command.")
            assertEquals(SettingsStoragePolicyCompletion.rejected(state.token, 81), results.single())
            runCurrent()
            assertEquals(1, results.size)
            assertEquals(AuthorizationPhase.OTHER_STEP, engine.state.value.status.authorization)
            assertTrue(engine.closeSettingsStoragePolicy(state.token), "Exact owner cleanup is permitted after non-closing READY loss.")
            runCurrent()
            assertFalse(engine.state.value.storagePolicySettings.opened)
            assertNotEquals(state.token, engine.state.value.storagePolicySettings.token)
            assertTrue(f.settings.policy.saves.isEmpty())
        }
    }

    @Test fun storagePolicyPublicBoundaryRejectsOldGenerationAndMandatoryCommandsOnceWithoutSaving() = runTest {
        withEngine { engine, f ->
            assertTrue(engine.openSettingsStoragePolicy(engine.state.value.generation) { true })
            runCurrent()
            val state = engine.state.value.storagePolicySettings
            val old = state.token.copy(generation = state.token.generation - 1)
            val oldResults = mutableListOf<SettingsStoragePolicyCompletion>()
            assertFalse(engine.saveSettingsStoragePolicy(old, state.revision,
                SettingsStoragePolicyDraft(unusedSeconds = 172_800), 82, oldResults::add))
            assertEquals(SettingsStoragePolicyCompletion.rejected(old, 82), oldResults.single())
            assertFalse(engine.closeSettingsStoragePolicy(old), "A different generation cannot retire this page's owner.")
            assertEquals(state.token, engine.state.value.storagePolicySettings.token)
            f.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            val mandatoryResults = mutableListOf<SettingsStoragePolicyCompletion>()
            assertFalse(engine.saveSettingsStoragePolicy(state.token, state.revision,
                SettingsStoragePolicyDraft(sizeKiB = 262_144), 83, mandatoryResults::add))
            assertEquals(SettingsStoragePolicyCompletion.rejected(state.token, 83), mandatoryResults.single())
            assertTrue(f.settings.policy.saves.isEmpty())
            runCurrent()
            assertTrue(engine.state.value.loginEmail.mandatory)
            assertFalse(engine.state.value.storagePolicySettings.opened, "The mandatory transition retires the actual policy page owner.")
            runCurrent()
            assertEquals(1, oldResults.size)
            assertEquals(1, mandatoryResults.size)
            assertTrue(f.settings.policy.saves.isEmpty())
        }
    }

    @Test fun storagePolicyRecoveryRequiresBothReadyBoundariesAndActuallySubmitsTheKnownFiveValues() = runTest {
        withEngine { engine, f ->
            assertEquals(0, f.settings.policy.reads, "The policy provider remains lazy during ordinary Engine startup.")
            f.auth(AuthorizationPhase.OTHER_STEP); runCurrent()
            assertFalse(engine.openSettingsStoragePolicy(engine.state.value.generation) { true })
            f.ready = true
            assertFalse(engine.openSettingsStoragePolicy(engine.state.value.generation) { true },
                "A recovered native supplier alone cannot bypass the actor's non-READY snapshot.")
            assertEquals(0, f.settings.policy.reads)
            f.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.openSettingsStoragePolicy(engine.state.value.generation) { true })
            runCurrent()
            assertEquals(1, f.settings.policy.reads)
            val state = engine.state.value.storagePolicySettings
            assertEquals(f.settings.policy.values, state.values)
            val draft = SettingsStoragePolicyDraft(sizeKiB = 262_144)
            val results = mutableListOf<SettingsStoragePolicyCompletion>()
            assertTrue(engine.saveSettingsStoragePolicy(state.token, state.revision, draft, 84, results::add))
            runCurrent()
            val write = f.settings.policy.saves.single()
            assertEquals(f.settings.policy.values, write.expected)
            assertEquals(draft, write.draft)
            assertTrue(write.active())
            assertTrue(results.isEmpty(), "Admission cannot invent a terminal native-save result.")
            val actual = SettingsStoragePolicyRead(state.values.copy(sizeKiB = policyNumber(262_144)))
            val outcome = SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.CONFIRMED,
                steps = listOf(SettingsStoragePolicyStepResult(SettingsStoragePolicyStep.SIZE, accepted = true, confirmed = true)),
                actual = actual)
            write.completed(outcome)
            write.completed(outcome)
            runCurrent()
            val completed = results.single()
            assertEquals(state.token, completed.token)
            assertEquals(84L, completed.requestId)
            assertEquals(SettingsStoragePolicyStatus.CONFIRMED, completed.status)
            assertEquals(actual, completed.actual)
            assertEquals(actual.values, engine.state.value.storagePolicySettings.values)
            assertFalse(engine.state.value.storagePolicySettings.saving)
            assertEquals(1, f.settings.policy.saves.size)
            assertTrue(engine.closeSettingsStoragePolicy(state.token)); runCurrent()
            assertFalse(engine.state.value.storagePolicySettings.opened)
        }
    }

    @Test fun queuedSupportRechecksNativeReadyAndRecoveryCompletesOnlyAfterAnActualOpenOnce() = runTest {
        withEngine { engine, f ->
            val owner = engine.state.value.chats.token
            val queued = mutableListOf<SettingsResult<ChatToken>>()
            assertTrue(engine.openSettingsSupport(owner, { true }, queued::add))
            assertTrue(queued.isEmpty())
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            val immediate = mutableListOf<SettingsResult<ChatToken>>()
            assertFalse(engine.openSettingsSupport(owner, { true }, immediate::add))
            assertEquals(SettingsError.NOT_READY, immediate.single().error)
            runCurrent()
            assertEquals(SettingsError.NOT_READY, queued.single().error)
            assertTrue(f.chats.support.resolutions.isEmpty())
            f.ready = true
            val snapshotDenied = mutableListOf<SettingsResult<ChatToken>>()
            assertFalse(engine.openSettingsSupport(owner, { true }, snapshotDenied::add),
                "Recovering the native supplier cannot bypass the non-READY actor snapshot.")
            assertEquals(SettingsError.NOT_READY, snapshotDenied.single().error)
            f.auth(AuthorizationPhase.READY); runCurrent()
            val recovered = mutableListOf<SettingsResult<ChatToken>>()
            assertTrue(engine.openSettingsSupport(owner, { true }, recovered::add)); runCurrent()
            val resolve = f.chats.support.resolutions.single()
            assertTrue(resolve.active())
            resolve.result(ChatResult(supportTarget)); resolve.result(ChatResult(supportTarget)); runCurrent()
            assertEquals(owner, engine.state.value.chats.token)
            assertTrue(recovered.isEmpty(), "Accepted admission and resolution cannot invent a native OpenChat result.")
            val open = f.chats.support.opens.single()
            assertTrue(open.active())
            open.result(ChatResult(Unit)); open.result(ChatResult(Unit)); runCurrent()
            assertEquals(engine.state.value.chats.token, recovered.single().value)
            assertEquals(supportTarget.chat.id, recovered.single().value?.chatId)
            assertNull(recovered.single().error)
            assertEquals(listOf(supportTarget.chat.id), f.chats.histories)
            assertTrue(engine.state.value.chats.items.isEmpty(), "Support resolution must not invent a main-list position.")
            assertEquals(1, queued.size)
            assertEquals(1, immediate.size)
            assertEquals(1, snapshotDenied.size)
        }
    }

    @Test fun supportPublicAndActorBoundariesRejectOldSelectionGenerationAndInactivePageExactlyOnce() = runTest {
        withEngine { engine, f ->
            val owner = engine.state.value.chats.token
            for (invalid in listOf(owner.copy(generation = owner.generation - 1), owner.copy(selection = owner.selection - 1))) {
                val result = mutableListOf<SettingsResult<ChatToken>>()
                assertFalse(engine.openSettingsSupport(invalid, { true }, result::add))
                assertEquals(SettingsError.NOT_READY, result.single().error)
                runCurrent()
                assertEquals(1, result.size)
            }
            val inactive = mutableListOf<SettingsResult<ChatToken>>()
            assertFalse(engine.openSettingsSupport(owner, { false }, inactive::add))
            assertEquals(SettingsError.NOT_READY, inactive.single().error)
            var active = true
            val queuedInactive = mutableListOf<SettingsResult<ChatToken>>()
            assertTrue(engine.openSettingsSupport(owner, { active }, queuedInactive::add))
            active = false
            runCurrent()
            assertEquals(SettingsError.NOT_READY, queuedInactive.single().error)
            f.event(ChatEvent.NewChat(ChatItem(44, "Synthetic selection", 100))); runCurrent()
            engine.selectChat(owner, 44)
            val queuedOldSelection = mutableListOf<SettingsResult<ChatToken>>()
            assertTrue(engine.openSettingsSupport(owner, { true }, queuedOldSelection::add),
                "The selection command is queued before support but has not changed the public snapshot yet.")
            runCurrent()
            assertEquals(SettingsError.NOT_READY, queuedOldSelection.single().error)
            assertEquals(44L, engine.state.value.chats.token.chatId)
            assertTrue(f.chats.support.resolutions.isEmpty())
            runCurrent()
            assertEquals(1, inactive.size)
            assertEquals(1, queuedInactive.size)
            assertEquals(1, queuedOldSelection.size)
        }
    }

    @Test fun supportMandatoryGateCancelsInFlightResolutionAndPublicAdmissionWithoutNavigation() = runTest {
        withEngine { engine, f ->
            val owner = engine.state.value.chats.token
            val pending = mutableListOf<SettingsResult<ChatToken>>()
            assertTrue(engine.openSettingsSupport(owner, { true }, pending::add)); runCurrent()
            val resolve = f.chats.support.resolutions.single()
            f.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            assertFalse(resolve.active(), "The real native-stage lease sees mandatory gating synchronously.")
            val denied = mutableListOf<SettingsResult<ChatToken>>()
            assertFalse(engine.openSettingsSupport(owner, { true }, denied::add))
            assertEquals(SettingsError.NOT_READY, denied.single().error)
            resolve.result(ChatResult(supportTarget)); resolve.result(ChatResult(supportTarget)); runCurrent()
            assertEquals(SettingsError.NOT_READY, pending.single().error)
            assertTrue(engine.state.value.loginEmail.mandatory)
            assertEquals(owner, engine.state.value.chats.token)
            assertTrue(f.chats.support.opens.isEmpty())
            assertTrue(f.chats.histories.isEmpty())
            runCurrent()
            assertEquals(1, pending.size)
            assertEquals(1, denied.size)
        }
    }

    @Test fun nativeReadyLossAfterSupportOpenDispatchCleansUpTheAcceptedOpenWithoutChangingSelection() = runTest {
        withEngine { engine, f ->
            val owner = engine.state.value.chats.token
            val result = mutableListOf<SettingsResult<ChatToken>>()
            assertTrue(engine.openSettingsSupport(owner, { true }, result::add)); runCurrent()
            f.chats.support.resolutions.single().result(ChatResult(supportTarget)); runCurrent()
            val open = f.chats.support.opens.single()
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertFalse(open.active(), "The controller's actual native-stage lease includes synchronous READY state.")
            open.result(ChatResult(Unit)); open.result(ChatResult(Unit)); runCurrent()
            assertEquals(SettingsError.NOT_READY, result.single().error)
            assertEquals(owner, engine.state.value.chats.token)
            assertEquals(listOf(supportTarget.chat.id), f.chats.closed)
            assertTrue(f.chats.histories.isEmpty())
            runCurrent()
            assertEquals(1, result.size)
        }
    }

    @Test fun contactSignupQueuedOpenAndSaveRecheckNativeReadyBeforeCallingTheUngatedFacade() = runTest {
        withEngine { engine, f ->
            val account = engine.state.value.settings.token
            assertTrue(engine.openSettingsContactSignup(account) { true })
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertFalse(engine.openSettingsContactSignup(account) { true })
            runCurrent()
            assertTrue(f.settings.signup.loads.isEmpty())
            assertFalse(engine.state.value.settings.contactSignup.opened)
            f.ready = true
            assertFalse(engine.openSettingsContactSignup(account) { true },
                "The recovered native supplier cannot bypass the non-READY Engine snapshot.")
            f.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.openSettingsContactSignup(account) { true }); runCurrent()
            val load = f.settings.signup.loads.single()
            assertTrue(load.active())
            load.result(SettingsResult(signupEnabled)); runCurrent()
            val page = engine.state.value.settings.contactSignup
            assertEquals(signupEnabled, page.value)
            assertTrue(engine.saveSettingsContactSignup(page.token, page.revision, signupEnabled, false))
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertFalse(load.active(), "The actual page/native lease sees READY loss synchronously.")
            assertFalse(engine.saveSettingsContactSignup(page.token, page.revision, signupEnabled, false))
            runCurrent()
            assertTrue(f.settings.signup.saves.isEmpty())
            assertFalse(engine.state.value.settings.contactSignup.saving)
            assertEquals(signupEnabled, engine.state.value.settings.contactSignup.value)
            assertTrue(engine.closeSettingsContactSignup(page.token), "Exact owner cleanup remains available after READY loss.")
            runCurrent()
            assertFalse(engine.state.value.settings.contactSignup.opened)
            assertNotEquals(page.token, engine.state.value.settings.contactSignup.token)
        }
    }

    @Test fun contactSignupMandatoryRetiresThePageAndOldAcceptedWriteRequiresANewOwnersFreshRead() = runTest {
        withEngine { engine, f ->
            val account = engine.state.value.settings.token
            assertTrue(engine.openSettingsContactSignup(account) { true }); runCurrent()
            f.settings.signup.loads.single().result(SettingsResult(signupEnabled)); runCurrent()
            val first = engine.state.value.settings.contactSignup
            assertTrue(engine.saveSettingsContactSignup(first.token, first.revision, signupEnabled, false)); runCurrent()
            val write = f.settings.signup.saves.single()
            assertEquals(signupEnabled, write.expected)
            assertFalse(write.desired)
            assertTrue(write.active())
            f.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            assertFalse(write.active())
            assertFalse(engine.openSettingsContactSignup(account) { true })
            assertFalse(engine.saveSettingsContactSignup(first.token, first.revision, signupEnabled, false))
            runCurrent()
            assertTrue(engine.state.value.loginEmail.mandatory)
            assertFalse(engine.state.value.settings.contactSignup.opened)
            assertNotEquals(first.token, engine.state.value.settings.contactSignup.token)
            f.gate.update(LoginEmailSuggestions(2)); runCurrent()
            assertFalse(engine.state.value.loginEmail.mandatory)
            assertTrue(engine.openSettingsContactSignup(engine.state.value.settings.token) { true }); runCurrent()
            val reopened = engine.state.value.settings.contactSignup
            assertNotEquals(first.token, reopened.token)
            assertTrue(reopened.opened && reopened.saving)
            assertEquals(1, f.settings.signup.loads.size, "The reopened page must wait for the actual old mutation completion.")
            val accepted = ContactSignupSaveResult(false, locallyAccepted = true,
                actual = ContactSignupValue(false, ContactSignupValueSource.EXPLICIT))
            write.result(accepted); write.result(accepted); runCurrent()
            val reading = engine.state.value.settings.contactSignup
            assertEquals(reopened.token, reading.token)
            assertTrue(reading.loading)
            assertNull(reading.value, "The retired owner's write readback cannot populate the new page.")
            assertTrue(reading.readbackPending)
            assertEquals(false, reading.desiredEnabled)
            assertEquals(2, f.settings.signup.loads.size)
            val fresh = f.settings.signup.loads.last()
            assertTrue(fresh.active())
            fresh.result(SettingsResult(signupEnabled)); runCurrent()
            val mismatch = engine.state.value.settings.contactSignup
            assertEquals(reopened.token, mismatch.token)
            assertEquals(signupEnabled, mismatch.value)
            assertTrue(mismatch.readbackPending)
            assertEquals(1L, mismatch.acceptedRevision)
            assertTrue(engine.refreshSettingsContactSignup(mismatch.token)); runCurrent()
            val matching = ContactSignupValue(false, ContactSignupValueSource.EXPLICIT)
            f.settings.signup.loads.last().result(SettingsResult(matching)); runCurrent()
            assertEquals(matching, engine.state.value.settings.contactSignup.value)
            assertFalse(engine.state.value.settings.contactSignup.readbackPending,
                "Matching public readback clears pending; it does not establish server synchronization.")
            assertEquals(1, f.settings.signup.saves.size)
        }
    }

    @Test fun contactSignupRejectsOldGenerationOwnerAndInactivePageWithoutReadingOrWritingNativeState() = runTest {
        withEngine { engine, f ->
            val account = engine.state.value.settings.token
            assertFalse(engine.openSettingsContactSignup(account.copy(generation = account.generation - 1)) { true })
            assertFalse(engine.openSettingsContactSignup(account) { false })
            var visible = true
            assertTrue(engine.openSettingsContactSignup(account) { visible })
            visible = false; runCurrent()
            assertTrue(f.settings.signup.loads.isEmpty(), "The queued open must recheck its page lease on the actor.")
            visible = true
            assertTrue(engine.openSettingsContactSignup(account) { visible }); runCurrent()
            f.settings.signup.loads.single().result(SettingsResult(signupEnabled)); runCurrent()
            val page = engine.state.value.settings.contactSignup
            for (old in listOf(page.token.copy(generation = page.token.generation - 1), page.token.copy(owner = page.token.owner - 1))) {
                assertFalse(engine.saveSettingsContactSignup(old, page.revision, signupEnabled, false))
                assertFalse(engine.refreshSettingsContactSignup(old))
                assertFalse(engine.closeSettingsContactSignup(old))
            }
            visible = false
            assertTrue(engine.saveSettingsContactSignup(page.token, page.revision, signupEnabled, false),
                "A true result only admits the command; the actor owns the page-lease check.")
            assertTrue(engine.refreshSettingsContactSignup(page.token)); runCurrent()
            assertTrue(f.settings.signup.saves.isEmpty())
            assertEquals(1, f.settings.signup.loads.size)
            assertTrue(engine.closeSettingsContactSignup(page.token)); runCurrent()
            visible = true
            assertTrue(engine.openSettingsContactSignup(engine.state.value.settings.token) { visible }); runCurrent()
            val newOwner = engine.state.value.settings.contactSignup.token
            assertNotEquals(page.token, newOwner)
            assertFalse(engine.saveSettingsContactSignup(page.token, page.revision, signupEnabled, false))
            assertFalse(engine.closeSettingsContactSignup(page.token))
            assertEquals(newOwner, engine.state.value.settings.contactSignup.token)
            assertTrue(f.settings.signup.saves.isEmpty())
        }
    }

    @Test fun notificationExceptionsQueuedOpenAndEditRecheckSynchronousReadyWhileSnapshotLags() = runTest {
        withEngine { engine, f ->
            val account = engine.state.value.settings.token
            assertTrue(engine.openSettingsNotificationExceptions(account, NotificationScope.PRIVATE_CHATS) { true })
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertFalse(engine.openSettingsNotificationExceptions(account, NotificationScope.PRIVATE_CHATS) { true })
            runCurrent()
            assertTrue(f.settings.exceptions.lists.isEmpty(), "The actor must reject the queued open before the ungated facade.")
            assertFalse(engine.state.value.settings.notificationExceptions.opened)

            f.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.openSettingsNotificationExceptions(account, NotificationScope.PRIVATE_CHATS) { true })
            runCurrent()
            val page = engine.state.value.settings.notificationExceptions
            assertTrue(page.opened)
            assertEquals(listOf(NotificationScope.PRIVATE_CHATS), f.settings.exceptions.lists.map { it.scope })
            assertTrue(engine.editSettingsNotificationException(page.token, notificationPeer.chatId))
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertFalse(engine.editSettingsNotificationException(page.token, notificationPeer.chatId))
            runCurrent()
            assertTrue(f.settings.exceptions.reads.isEmpty(), "The actor must reject the queued editor read before the ungated facade.")
            assertTrue(engine.closeSettingsNotificationExceptions(page.token), "Exact owner cleanup stays available after non-closing READY loss.")
            runCurrent()
            assertFalse(engine.state.value.settings.notificationExceptions.opened)
            assertNotEquals(page.token, engine.state.value.settings.notificationExceptions.token)
        }
    }

    @Test fun notificationExceptionsQueuedSaveRechecksReadyAndExactCleanupRemainsAvailable() = runTest {
        withEngine { engine, f ->
            val page = openNotificationExceptionEditor(engine, f)
            val editor = checkNotNull(page.editor)
            val desired = editor.values.copy(useDefaultMuteFor = false, muteFor = 3_600)
            val edit = NotificationExceptionEdit(editor, desired, setOf(NotificationExceptionField.MUTE))
            assertTrue(engine.saveSettingsNotificationException(page.token, page.editorRevision, edit))
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            assertFalse(engine.saveSettingsNotificationException(page.token, page.editorRevision, edit))
            runCurrent()
            assertTrue(f.settings.exceptions.saves.isEmpty(), "The actor must reject the queued save before the ungated facade.")
            assertFalse(engine.state.value.settings.notificationExceptions.saving)
            val owner = engine.state.value.settings.notificationExceptions.token
            assertTrue(engine.closeSettingsNotificationExceptions(owner), "Exact owner cleanup is permitted after READY loss.")
            runCurrent()
            assertFalse(engine.state.value.settings.notificationExceptions.opened)
            assertNotEquals(owner, engine.state.value.settings.notificationExceptions.token)
        }
    }

    @Test fun notificationExceptionsRejectsOldGenerationAndMandatoryWritesThenAcceptsOneRealFakeSave() = runTest {
        withEngine { engine, f ->
            val page = openNotificationExceptionEditor(engine, f)
            val owner = engine.state.value.settings.notificationExceptions.token
            val revision = engine.state.value.settings.notificationExceptions.editorRevision
            val desired = checkNotNull(page.editor).values.copy(useDefaultMuteFor = false, muteFor = 1_800)
            val edit = NotificationExceptionEdit(checkNotNull(page.editor), desired, setOf(NotificationExceptionField.MUTE))

            val oldGeneration = owner.copy(generation = owner.generation - 1)
            assertFalse(engine.editSettingsNotificationException(oldGeneration, notificationPeer.chatId))
            assertFalse(engine.saveSettingsNotificationException(oldGeneration, revision, edit))
            assertEquals(1, f.settings.exceptions.reads.size)
            assertTrue(f.settings.exceptions.saves.isEmpty())

            f.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            assertFalse(engine.saveSettingsNotificationException(owner, revision, edit),
                "The synchronous mandatory-email gate rejects before actor snapshot publication.")
            runCurrent()
            assertTrue(engine.state.value.loginEmail.mandatory)
            assertFalse(engine.state.value.settings.notificationExceptions.opened)
            assertTrue(f.settings.exceptions.saves.isEmpty())

            f.gate.update(LoginEmailSuggestions(2)); runCurrent()
            assertFalse(engine.state.value.loginEmail.mandatory)
            openNotificationExceptionEditor(engine, f)
            val reopened = engine.state.value.settings.notificationExceptions
            val freshEditor = checkNotNull(reopened.editor)
            val freshDesired = freshEditor.values.copy(useDefaultMuteFor = false, muteFor = 1_800)
            val freshEdit = NotificationExceptionEdit(freshEditor, freshDesired, setOf(NotificationExceptionField.MUTE))
            assertTrue(engine.saveSettingsNotificationException(reopened.token, reopened.editorRevision, freshEdit))
            runCurrent()
            val save = f.settings.exceptions.saves.single()
            assertTrue(save.active())
            assertEquals(freshEdit, save.edit)
            assertEquals(1, f.settings.exceptions.saves.size)
            val accepted = NotificationExceptionWriteResult(locallyAccepted = true,
                actual = freshEditor.copy(values = freshDesired))
            save.result(accepted); save.result(accepted); runCurrent()
            val completed = engine.state.value.settings.notificationExceptions
            assertEquals(reopened.token, completed.token)
            assertFalse(completed.saving)
            assertEquals(freshDesired, completed.editor?.values)
            assertTrue(completed.readbackPending || completed.acceptedRevision > 0,
                "A locally accepted fake save must publish its actual readback or pending readback state.")
            assertEquals(1, f.settings.exceptions.saves.size)
        }
    }

    @Test fun savedNotificationSoundQueuedChooseIsRejectedOnceOnReadyLossThenRecoversWithFreshNegativeIdRead() = runTest {
        withEngine { engine, f ->
            val page = openSavedNotificationSounds(engine, f)
            val sound = checkNotNull(page.data).sounds.single()
            assertTrue(sound.id < -1L, "The synthetic server sound uses a valid negative opaque ID.")
            val queued = mutableListOf<SettingsResult<Long>>()
            assertTrue(engine.chooseSettingsSavedNotificationSound(page.token, page.revision, sound.id, queued::add))
            assertTrue(queued.isEmpty(), "The facade choice remains pending until the Engine actor owns it.")

            f.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            val immediate = mutableListOf<SettingsResult<Long>>()
            assertFalse(engine.chooseSettingsSavedNotificationSound(page.token, page.revision, sound.id, immediate::add))
            assertEquals(SettingsError.NOT_READY, immediate.single().error)
            runCurrent()
            assertEquals(SettingsError.NOT_READY, queued.single().error)
            runCurrent()
            assertEquals(1, queued.size, "Actor discard and native callback cannot complete a queued choice twice.")
            assertTrue(f.settings.sounds.reads.isEmpty(), "The actor must discard the queued choose before the ungated catalog.")

            f.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.state.value.settings.savedNotificationSounds.opened)
            assertTrue(engine.refreshSettingsSavedNotificationSounds(page.token))
            runCurrent()
            val freshLoad = f.settings.sounds.loads.last()
            assertTrue(freshLoad.active())
            freshLoad.result(SettingsResult(savedSoundCatalogData(sound)))
            runCurrent()
            val recovered = engine.state.value.settings.savedNotificationSounds
            val chosen = mutableListOf<SettingsResult<Long>>()
            assertTrue(engine.chooseSettingsSavedNotificationSound(recovered.token, recovered.revision, sound.id, chosen::add))
            runCurrent()
            val read = f.settings.sounds.reads.single()
            assertEquals(43L, read.accountUserId)
            assertEquals(sound.id, read.soundId)
            assertTrue(read.active())
            assertTrue(chosen.isEmpty())
            read.result(SettingsResult(sound))
            runCurrent()
            assertEquals(sound.id, chosen.single().value)
            assertNull(chosen.single().error)
            assertFalse(engine.state.value.settings.savedNotificationSounds.choosing)
            assertTrue(engine.closeSettingsSavedNotificationSounds(recovered.token))
            runCurrent()
            assertFalse(engine.state.value.settings.savedNotificationSounds.opened)
        }
    }

    @Test fun savedNotificationSoundRemoveRejectsOldGenerationAndMandatoryWritesAtThePublicBoundary() = runTest {
        withEngine { engine, f ->
            val page = openSavedNotificationSounds(engine, f)
            val sound = checkNotNull(page.data).sounds.single()
            val oldGeneration = page.token.copy(generation = page.token.generation - 1)
            assertFalse(engine.removeSettingsSavedNotificationSound(oldGeneration, page.revision, sound))
            assertTrue(f.settings.sounds.removes.isEmpty())

            f.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            assertFalse(engine.removeSettingsSavedNotificationSound(page.token, page.revision, sound),
                "The synchronous mandatory gate rejects a typed catalog mutation before actor publication.")
            runCurrent()
            assertTrue(engine.state.value.loginEmail.mandatory)
            assertFalse(engine.state.value.settings.savedNotificationSounds.opened)
            assertTrue(f.settings.sounds.removes.isEmpty())
        }
    }

    @Test fun savedNotificationSoundQueuedUploadReleasesItsBorrowAfterReadyLossWithoutCallingTheCatalog() = runTest {
        withEngine { engine, f ->
            val page = openSavedNotificationSounds(engine, f)
            var cleaned = 0
            val prepared = preparedNotificationSound { cleaned++ }
            assertTrue(engine.uploadSettingsSavedNotificationSound(page.token, page.revision, prepared))
            prepared.lease.release()
            assertEquals(0, cleaned, "The Engine retained its own file handle before enqueueing the upload.")

            f.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            runCurrent()
            assertEquals(1, cleaned, "Actor discard releases the borrowed handle after its base owner was released.")
            assertTrue(f.settings.sounds.adds.isEmpty(), "A stale queued upload never reaches the ungated fake catalog.")
            assertFalse(engine.state.value.settings.savedNotificationSounds.saving)
        }
    }

    @Test fun savedNotificationSoundAcceptedAddWaitsAcrossMandatoryOwnerChangeAndFreshLoadsAfterTerminal() = runTest {
        withEngine { engine, f ->
            val initialData = savedSoundCatalogData()
            val page = openSavedNotificationSounds(engine, f, initialData)
            var cleaned = 0
            val prepared = preparedNotificationSound { cleaned++ }
            assertTrue(engine.uploadSettingsSavedNotificationSound(page.token, page.revision, prepared))
            prepared.lease.release()
            runCurrent()
            val add = f.settings.sounds.adds.single()
            assertTrue(add.active())
            assertEquals(initialData, add.expected)
            assertEquals(prepared.upload, add.prepared)
            assertEquals(0, cleaned)

            f.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1)); runCurrent()
            assertFalse(add.active(), "Mandatory page retirement revokes the old mutation lease without releasing its file early.")
            assertFalse(engine.state.value.settings.savedNotificationSounds.opened)
            f.gate.update(LoginEmailSuggestions(2)); runCurrent()
            assertFalse(engine.state.value.loginEmail.mandatory)
            assertTrue(engine.openSettingsSavedNotificationSounds(engine.state.value.settings.token) { true })
            runCurrent()
            val newOwner = engine.state.value.settings.savedNotificationSounds
            assertNotEquals(page.token, newOwner.token)
            assertTrue(newOwner.opened && newOwner.saving)
            assertEquals(1, f.settings.sounds.loads.size,
                "The new page waits for the actual old add terminal before starting its fresh catalog load.")

            val added = savedSoundFixture(-73L).copy(title = "Accepted add")
            val actual = savedSoundCatalogData(initialData.sounds.single(), added)
            val terminal = SavedNotificationSoundMutationResult(locallyAccepted = true, sound = added, actual = actual)
            add.result(terminal)
            assertEquals(1, cleaned, "The old native terminal releases its borrowed file without waiting for actor publication.")
            add.result(SavedNotificationSoundMutationResult(error = SettingsError.REQUEST_FAILED))
            runCurrent()
            val waiting = engine.state.value.settings.savedNotificationSounds
            assertEquals(newOwner.token, waiting.token)
            assertTrue(waiting.loading)
            assertNull(waiting.data, "The retired owner's actual payload cannot populate the reopened page directly.")
            assertEquals(2, f.settings.sounds.loads.size)

            val fresh = f.settings.sounds.loads.last()
            assertTrue(fresh.active())
            fresh.result(SettingsResult(actual))
            runCurrent()
            val completed = engine.state.value.settings.savedNotificationSounds
            assertEquals(newOwner.token, completed.token)
            assertEquals(actual, completed.data)
            assertFalse(completed.loading)
            assertFalse(completed.saving)
            assertFalse(completed.readbackPending)
            assertEquals(1L, completed.acceptedRevision)
            assertEquals(1, cleaned, "A duplicate native terminal cannot release the same retained handle again.")
            assertEquals(2, f.settings.sounds.loads.size)
        }
    }

    @Test fun queuedSoundVolumeReadAndSaveRejectReadyAndMandatoryLossExactlyOnceWithoutWriting() = runTest {
        withEngine { engine, f ->
            val page = openSavedNotificationSounds(engine, f)
            val volumeFile = f.store.path.resolveSibling("notification-sound-volumes.properties")
            val target = NotificationSoundVolumeTarget.Scope(NotificationScope.PRIVATE_CHATS)
            val queuedRead = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
            assertTrue(engine.readSettingsNotificationSoundVolume(page.token, target, queuedRead::add))
            assertTrue(queuedRead.isEmpty())
            f.auth(AuthorizationPhase.OTHER_STEP)
            assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
            val immediateRead = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
            assertFalse(engine.readSettingsNotificationSoundVolume(page.token, target, immediateRead::add))
            assertEquals(SettingsError.NOT_READY, immediateRead.single().error)
            assertTrue(queuedRead.isEmpty())
            runCurrent()
            assertEquals(SettingsError.NOT_READY, queuedRead.single().error)
            runCurrent()
            assertEquals(1, queuedRead.size, "Public discard and actor discard must share an exact-once terminal.")
            assertFalse(Files.exists(volumeFile), "A queued read rejection has no file side effect.")

            f.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.refreshSettingsSavedNotificationSounds(page.token))
            runCurrent()
            f.settings.sounds.loads.last().result(SettingsResult(savedSoundCatalogData()))
            runCurrent()
            val readingResults = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
            assertTrue(engine.readSettingsNotificationSoundVolume(page.token, target, readingResults::add))
            runCurrent()
            val expected = assertNotNull(readingResults.single().value)
            assertEquals(43L, expected.accountUserId, "The target account comes from the fake native catalog child.")
            assertEquals(target, expected.target)
            assertNull(readingResults.single().error)
            assertNull(expected.overridePercent)
            assertFalse(Files.exists(volumeFile), "An unset read must not materialize a preference file.")

            val queuedSave = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
            assertTrue(engine.saveSettingsNotificationSoundVolume(page.token, expected, 76, queuedSave::add))
            assertTrue(queuedSave.isEmpty())
            f.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            val immediateSave = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
            assertFalse(engine.saveSettingsNotificationSoundVolume(page.token, expected, 54, immediateSave::add))
            assertEquals(SettingsError.NOT_READY, immediateSave.single().error)
            assertTrue(queuedSave.isEmpty())
            runCurrent()
            assertTrue(engine.state.value.loginEmail.mandatory)
            assertEquals(SettingsError.NOT_READY, queuedSave.single().error)
            runCurrent()
            assertEquals(1, queuedSave.size)
            assertFalse(Files.exists(volumeFile), "Mandatory discard must not write the local preference file.")
        }
    }

    @Test fun soundVolumeActualOwnerReadsSavesIsolatesAccountsAndRejectsClosedTokensBeforeRecovering() = runTest {
        withEngine { engine, f ->
            val page = openSavedNotificationSounds(engine, f)
            val target = NotificationSoundVolumeTarget.Scope(NotificationScope.PRIVATE_CHATS)
            val volumeFile = f.store.path.resolveSibling("notification-sound-volumes.properties")
            val reads = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
            assertTrue(engine.readSettingsNotificationSoundVolume(page.token, target, reads::add))
            runCurrent()
            val before = assertNotNull(reads.single().value)
            assertNull(reads.single().error)
            assertEquals(43L, before.accountUserId)
            assertEquals(target, before.target)
            assertNull(before.overridePercent)
            assertFalse(Files.exists(volumeFile))

            val writes = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
            assertTrue(engine.saveSettingsNotificationSoundVolume(page.token, before, 68, writes::add))
            runCurrent()
            val saved = assertNotNull(writes.single().value)
            assertNull(writes.single().error)
            assertEquals(43L, saved.accountUserId)
            assertEquals(target, saved.target)
            assertEquals(68, saved.overridePercent)
            assertEquals("notification-sound-volumes=1\n43\tscope\tPRIVATE_CHATS\t68\n", Files.readString(volumeFile))

            val otherAccount = FileNotificationSoundVolumeStore(volumeFile).read(44L, target)
            assertEquals(44L, otherAccount.value?.accountUserId)
            assertNull(otherAccount.value?.overridePercent, "A different account cannot read this account's override.")

            assertTrue(engine.closeSettingsSavedNotificationSounds(page.token))
            runCurrent()
            val oldRead = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
            assertFalse(engine.readSettingsNotificationSoundVolume(page.token, target, oldRead::add))
            assertEquals(SettingsError.NOT_READY, oldRead.single().error)
            val oldSave = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
            assertFalse(engine.saveSettingsNotificationSoundVolume(page.token, saved, 81, oldSave::add))
            assertEquals(SettingsError.NOT_READY, oldSave.single().error)
            assertEquals("notification-sound-volumes=1\n43\tscope\tPRIVATE_CHATS\t68\n", Files.readString(volumeFile),
                "Closed-owner calls cannot alter the real store.")

            val reopened = openSavedNotificationSounds(engine, f)
            assertNotEquals(page.token, reopened.token)
            val fresh = mutableListOf<SettingsResult<NotificationSoundVolumeReading>>()
            assertTrue(engine.readSettingsNotificationSoundVolume(reopened.token, target, fresh::add))
            runCurrent()
            assertNull(fresh.single().error)
            assertEquals(43L, fresh.single().value?.accountUserId)
            assertEquals(68, fresh.single().value?.overridePercent,
                "A new owner reads the value persisted by the previous valid owner.")
        }
    }

    private suspend fun TestScope.openSavedNotificationSounds(
        engine: TdEngine,
        f: Fixture,
        data: SavedNotificationSoundsData = savedSoundCatalogData(),
    ): SavedNotificationSoundsSnapshot {
        val account = engine.state.value.settings.token
        assertTrue(engine.openSettingsSavedNotificationSounds(account) { true })
        runCurrent()
        val load = f.settings.sounds.loads.last()
        assertTrue(load.active())
        load.result(SettingsResult(data))
        runCurrent()
        val page = engine.state.value.settings.savedNotificationSounds
        assertTrue(page.opened)
        assertEquals(data, page.data)
        return page
    }

    private fun preparedNotificationSound(cleaned: () -> Unit) = SettingsPreparedNotificationSound(
        PreparedNotificationSoundUpload("synthetic-notification-sound.mp3", 512, 1_000, "a".repeat(64)),
        ownedAttachmentLease(cleaned),
    )

    private suspend fun TestScope.openNotificationExceptionEditor(
        engine: TdEngine,
        f: Fixture,
    ): NotificationExceptionsSnapshot {
        val readsBefore = f.settings.exceptions.reads.size
        val account = engine.state.value.settings.token
        assertTrue(engine.openSettingsNotificationExceptions(account, NotificationScope.PRIVATE_CHATS) { true })
        runCurrent()
        val page = engine.state.value.settings.notificationExceptions
        assertTrue(page.opened)
        assertEquals(listOf(notificationPeer.chatId), page.rows.map { it.chatId })
        assertTrue(engine.editSettingsNotificationException(page.token, notificationPeer.chatId))
        runCurrent()
        assertEquals(notificationPeer.chatId, engine.state.value.settings.notificationExceptions.editor?.chatId)
        assertEquals(readsBefore + 1, f.settings.exceptions.reads.size)
        return engine.state.value.settings.notificationExceptions
    }

    private suspend fun TestScope.openProfileColorPage(
        engine: TdEngine,
        f: Fixture,
        active: () -> Boolean = { true },
    ): SettingsProfileColorsSnapshot {
        assertTrue(f.ready, "The positive fixture requires the synchronous native READY supplier.")
        assertTrue(f.gate.allowsOrdinary(), "The positive fixture requires the real ordinary-account gate to be open.")
        assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization)
        assertFalse(engine.state.value.loginEmail.mandatory)
        assertTrue(engine.state.value.settings.enabled)
        val before = f.settings.colors.loads.size
        assertTrue(engine.openSettingsProfileColors(engine.state.value.settings.token, 43, active)); runCurrent()
        assertEquals(before + 1, f.settings.colors.loads.size)
        val read = f.settings.colors.loads.last()
        assertTrue(read.active())
        read.result(SettingsResult(profileColorsData)); runCurrent()
        return engine.state.value.settings.profileColors.also {
            assertTrue(it.opened && !it.loading && !it.busy)
            assertEquals(profileColorsData, it.data)
        }
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture) -> Unit) {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
        try {
            engine.start(); runCurrent()
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.state.value.settings.enabled)
            engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE); runCurrent()
            assertEquals(43L, engine.state.value.settings.profile?.userId)
            block(engine, f)
        } finally {
            assertTrue(engine.close())
            val root = f.root.toAbsolutePath().normalize()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(path)
            } }
        }
    }

    private class Fixture : TdTransport {
        val root = Files.createTempDirectory("ordinary-ready-engine-")
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val settings = Settings()
        val chats = Chats()
        val gate = TdUserInteractionGate()
        var ready = false
        private lateinit var authorization: (AuthorizationDetails) -> Unit
        private lateinit var chatEvent: (ChatEvent) -> Unit
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }
        fun auth(phase: AuthorizationPhase) {
            this.ready = phase == AuthorizationPhase.READY
            authorization(AuthorizationDetails(phase))
        }
        fun event(value: ChatEvent) = chatEvent(value)
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit,
        ): TdSession {
            authorization = onAuthorization
            chatEvent = onChatEvent
            return object : SyntheticOpenKeySession {
                override fun authorizationReady() = this@Fixture.ready
                override val settings: TdSettingsSession = this@Fixture.settings
                override val chats: TdChatSession = this@Fixture.chats
                override val interactionGate = gate
                override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = result(null)
                override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
                override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No authorization email or login operation in ordinary Engine fixtures")
                override fun close() = auth(AuthorizationPhase.CLOSED)
            }
        }
    }

    /** No GetMe override: a native account identity and its local media preferences are unnecessary here. */
    private class Chats : TdChatSession {
        val support = Support()
        override val helpSupport: TdHelpSupportSession get() = support
        val histories = mutableListOf<Long>()
        val closed = mutableListOf<Long>()
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(endReached = true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            histories += chatId; result(ChatResult(emptyList()))
        }
        override fun open(chatId: Long) {}
        override fun close(chatId: Long) { closed += chatId }
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = error("No chat sends in this fixture")
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = error("No retries in this fixture")
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = result(ChatResult(Unit))
    }

    /** No active check here: actual Engine/controller ownership must reject every stale native phase. */
    private class Support : TdHelpSupportSession {
        data class Resolution(val active: () -> Boolean, val result: (ChatResult<TdSupportChat>) -> Unit)
        data class Open(val active: () -> Boolean, val result: (ChatResult<Unit>) -> Unit)
        val resolutions = mutableListOf<Resolution>()
        val opens = mutableListOf<Open>()
        override fun resolve(active: () -> Boolean, result: (ChatResult<TdSupportChat>) -> Unit) { resolutions += Resolution(active, result) }
        override fun open(target: TdSupportChat, active: () -> Boolean, result: (ChatResult<Unit>) -> Unit) { opens += Open(active, result) }
    }

    private class Extras : TdSettingsProfileExtrasSession {
        val uploads = mutableListOf<(SettingsResult<Unit>) -> Unit>()
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) =
            result(SettingsResult(SettingsProfileExtrasData(userId, 91, null, SettingsCalendarDate(26, 10, 2026))))
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?, isCurrent: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit,
        ) = error("No birthdate request in this fixture")
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto, isCurrent: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit,
        ) { uploads += result }
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) =
            error("No photo removal in this fixture")
    }

    private class Settings : TdSettingsSession {
        val signup = Signup()
        override val contactSignup: TdContactSignupSession get() = signup
        val exceptions = NotificationExceptions()
        override val notificationExceptions: TdNotificationExceptionsSession get() = exceptions
        val sounds = SavedSoundsCatalog()
        override val savedNotificationSounds: TdSavedNotificationSoundsSession get() = sounds
        val extras = Extras()
        override val profileExtras: TdSettingsProfileExtrasSession get() = extras
        val colors = ProfileColors()
        override val profileColors: TdSettingsProfileColorsSession get() = colors
        val policy = Policy()
        override val storagePolicy: TdSettingsStoragePolicySession get() = policy
        val profileWrites = mutableListOf<SettingsProfileEdit>()
        val passwordCommands = mutableListOf<SettingsPasswordOperation>()
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) = result(SettingsResult(SettingsProfile(43, "Synthetic", "", "", "", "")))
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) { profileWrites += edit; result(SettingsResult(Unit)) }
        override fun passwordState(isCurrent: () -> Boolean, result: (SettingsResult<SettingsPasswordState>) -> Unit) =
            result(SettingsResult(SettingsPasswordState(true, "Synthetic hint", true, false, null, "", 0)))
        override fun password(command: SettingsPasswordCommand, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPasswordOutcome>) -> Unit) {
            passwordCommands += command.operation
            result(SettingsResult(SettingsPasswordOutcome()))
        }
        override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) = unsupported(result)
        override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) = unsupported(result)
        override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) = unsupported(result)
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = unsupported(result)
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    }

    /** No gate or active checks: only the actual Engine/C2 controller may reject or dispatch these calls. */
    private class ProfileColors : TdSettingsProfileColorsSession {
        data class Load(val userId: Long, val active: () -> Boolean,
            val result: (SettingsResult<SettingsProfileColorsData>) -> Unit)
        data class Save(val expected: SettingsProfileColorsData, val part: SettingsProfileColorPart,
            val desired: SettingsProfileColorValue, val replaceGift: Boolean, val active: () -> Boolean,
            val result: (SettingsProfileColorMutationResult) -> Unit)
        data class Metadata(val active: () -> Boolean,
            val result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit)
        data class Resolve(val ids: List<Long>, val active: () -> Boolean,
            val result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit)
        val loads = mutableListOf<Load>()
        val saves = mutableListOf<Save>()
        val backgrounds = mutableListOf<Metadata>()
        val resolutions = mutableListOf<Resolve>()
        override fun observe(observer: (() -> Unit)?) {}
        override fun catalog() = profileColorsData.catalog
        override fun load(userId: Long, active: () -> Boolean, result: (SettingsResult<SettingsProfileColorsData>) -> Unit) {
            loads += Load(userId, active, result)
        }
        override fun save(expected: SettingsProfileColorsData, part: SettingsProfileColorPart, desired: SettingsProfileColorValue,
            replaceGift: Boolean, active: () -> Boolean, result: (SettingsProfileColorMutationResult) -> Unit,
        ) { saves += Save(expected, part, desired, replaceGift, active, result) }
        override fun backgroundEmojis(active: () -> Boolean, result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit) {
            backgrounds += Metadata(active, result)
        }
        override fun resolveEmojis(ids: List<Long>, active: () -> Boolean, result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit) {
            resolutions += Resolve(ids.toList(), active, result)
        }
        override fun retire() {}
    }

    /** Deliberately ungated so these cases exercise the real Engine and controller leases. */
    private class NotificationExceptions : TdNotificationExceptionsSession {
        data class ListCall(val scope: NotificationScope, val active: () -> Boolean)
        data class ResolveCall(val scope: NotificationScope, val ids: List<Long>, val active: () -> Boolean)
        data class ReadCall(val scope: NotificationScope, val chatId: Long, val active: () -> Boolean)
        data class SaveCall(
            val edit: NotificationExceptionEdit,
            val active: () -> Boolean,
            val result: (NotificationExceptionWriteResult) -> Unit,
        )

        val lists = mutableListOf<ListCall>()
        val resolutions = mutableListOf<ResolveCall>()
        val reads = mutableListOf<ReadCall>()
        val saves = mutableListOf<SaveCall>()
        private var currentPeer = notificationPeer

        override fun list(scope: NotificationScope, active: () -> Boolean, result: (SettingsResult<List<Long>>) -> Unit) {
            lists += ListCall(scope, active)
            result(SettingsResult(listOf(notificationPeer.chatId)))
        }

        override fun resolve(
            scope: NotificationScope,
            ids: List<Long>,
            active: () -> Boolean,
            result: (SettingsResult<List<NotificationExceptionRow>>) -> Unit,
        ) {
            resolutions += ResolveCall(scope, ids.toList(), active)
            result(SettingsResult(ids.map { id ->
                val peer = currentPeer.copy(chatId = id, scope = scope)
                NotificationExceptionRow(id, peer = peer)
            }))
        }

        override fun search(
            scope: NotificationScope,
            query: String,
            remote: Boolean,
            active: () -> Boolean,
            result: (SettingsResult<List<NotificationExceptionRow>>) -> Unit,
        ) = result(SettingsResult(emptyList()))

        override fun read(
            scope: NotificationScope,
            chatId: Long,
            active: () -> Boolean,
            result: (SettingsResult<NotificationExceptionPeer>) -> Unit,
        ) {
            reads += ReadCall(scope, chatId, active)
            result(SettingsResult(currentPeer.copy(chatId = chatId, scope = scope)))
        }

        override fun save(
            edit: NotificationExceptionEdit,
            active: () -> Boolean,
            result: (NotificationExceptionWriteResult) -> Unit,
        ) {
            saves += SaveCall(edit, active) { outcome ->
                outcome.actual?.let { currentPeer = it }
                result(outcome)
            }
        }
    }

    /** No active checks or filesystem reads: every rejection must come from Engine/controller ownership. */
    private class SavedSoundsCatalog : TdSavedNotificationSoundsSession {
        data class Load(
            val active: () -> Boolean,
            val result: (SettingsResult<SavedNotificationSoundsData>) -> Unit,
        )
        data class Read(
            val accountUserId: Long,
            val soundId: Long,
            val active: () -> Boolean,
            val result: (SettingsResult<SavedNotificationSound>) -> Unit,
        )
        data class Add(
            val expected: SavedNotificationSoundsData,
            val prepared: PreparedNotificationSoundUpload,
            val active: () -> Boolean,
            val result: (SavedNotificationSoundMutationResult) -> Unit,
        )
        data class Remove(
            val accountUserId: Long,
            val expected: SavedNotificationSound,
            val active: () -> Boolean,
            val result: (SavedNotificationSoundMutationResult) -> Unit,
        )

        val loads = mutableListOf<Load>()
        val reads = mutableListOf<Read>()
        val adds = mutableListOf<Add>()
        val removes = mutableListOf<Remove>()
        var observer: (() -> Unit)? = null
            private set
        var retireCount = 0
            private set

        override fun load(active: () -> Boolean, result: (SettingsResult<SavedNotificationSoundsData>) -> Unit) {
            loads += Load(active, result)
        }

        override fun read(accountUserId: Long, soundId: Long, active: () -> Boolean,
            result: (SettingsResult<SavedNotificationSound>) -> Unit,
        ) { reads += Read(accountUserId, soundId, active, result) }

        override fun add(expected: SavedNotificationSoundsData, prepared: PreparedNotificationSoundUpload,
            active: () -> Boolean, result: (SavedNotificationSoundMutationResult) -> Unit,
        ) { adds += Add(expected, prepared, active, result) }

        override fun remove(accountUserId: Long, expected: SavedNotificationSound, active: () -> Boolean,
            result: (SavedNotificationSoundMutationResult) -> Unit,
        ) { removes += Remove(accountUserId, expected, active, result) }

        override fun observe(observer: (() -> Unit)?) { this.observer = observer }
        override fun retire() { retireCount++; observer = null }
    }

    /** Deliberately ungated; actual Engine and controller ownership must prevent every stale native call. */
    private class Signup : TdContactSignupSession {
        data class Load(val active: () -> Boolean, val result: (SettingsResult<ContactSignupValue>) -> Unit)
        data class Save(val expected: ContactSignupValue, val desired: Boolean, val active: () -> Boolean,
            val result: (ContactSignupSaveResult) -> Unit)
        val loads = mutableListOf<Load>()
        val saves = mutableListOf<Save>()
        override fun observe(observer: (() -> Unit)?) {}
        override fun load(active: () -> Boolean, result: (SettingsResult<ContactSignupValue>) -> Unit) { loads += Load(active, result) }
        override fun save(expected: ContactSignupValue, desired: Boolean, active: () -> Boolean, result: (ContactSignupSaveResult) -> Unit) {
            saves += Save(expected, desired, active, result)
        }
    }

    /** This fake records every save even when active is false, so only the actual Engine can gate admission. */
    private class Policy : TdSettingsStoragePolicySession {
        data class Save(val expected: SettingsStoragePolicyValues, val draft: SettingsStoragePolicyDraft,
            val active: () -> Boolean, val completed: (SettingsStoragePolicySaveOutcome) -> Unit)
        val values = SettingsStoragePolicyValues(policyNumber(524_288), policyNumber(86_400),
            SettingsStoragePolicySwitch(false, false, SettingsStoragePolicySource.EXPLICIT), policyNumber(40_000), policyNumber(3_600))
        var reads = 0
        val saves = mutableListOf<Save>()
        override fun load(active: () -> Boolean, result: (SettingsStoragePolicyRead) -> Unit) {
            reads++
            result(SettingsStoragePolicyRead(values))
        }
        override fun save(expected: SettingsStoragePolicyValues, draft: SettingsStoragePolicyDraft, active: () -> Boolean,
            progress: (List<SettingsStoragePolicyStepResult>) -> Unit, result: (SettingsStoragePolicySaveOutcome) -> Unit,
        ) { saves += Save(expected, draft, active, result) }
    }

    private companion object {
        val profileColorsData = SettingsProfileColorsData(
            userId = 43, premium = true,
            values = SettingsProfileColorsValues(SettingsProfileColorValue(1, -71), SettingsProfileColorValue(-1)),
            catalog = SettingsProfileColorsCatalog(availableNameColorIds = listOf(1, 2),
                availableProfileColorIds = listOf(-1, 8), revision = 7),
            nativeRevision = 11,
        )
        fun profileColorEdit(revision: Long) = SettingsProfileColorEdit(SettingsProfileColorPart.NAME,
            profileColorsData.values.name, SettingsProfileColorValue(2, -71), revision)
        fun policyNumber(value: Long) = SettingsStoragePolicyNumber(value, value, SettingsStoragePolicySource.EXPLICIT)
        val supportTarget = TdSupportChat(700, ChatItem(900, "Synthetic support", privateUserId = 700, positions = emptyMap()))
        val signupEnabled = ContactSignupValue(true, ContactSignupValueSource.EXPLICIT)
        val notificationPeer = NotificationExceptionPeer(
            chatId = 501, scope = NotificationScope.PRIVATE_CHATS, title = "Synthetic peer",
            values = ChatNotificationValues(useDefaultMuteFor = false, muteFor = 7_200), sampledAtMillis = 100,
        )
        fun savedSoundFixture(id: Long = -61L) = SavedNotificationSound(
            id = id, durationSeconds = 1, date = 1, title = "Synthetic sound $id", data = "",
            file = MediaFile(id = 71, uniqueId = "fixture-sound-file-$id"),
        )
        fun savedSoundCatalogData(vararg sounds: SavedNotificationSound = arrayOf(savedSoundFixture())) =
            SavedNotificationSoundsData(43, sounds.toList(), NotificationSoundLimits(16_384, 30, 8))
    }

    private fun photo(cleaned: () -> Unit) = SettingsPreparedProfilePhoto("synthetic.jpg", 512, 32, "00".repeat(32), ownedAttachmentLease(cleaned))
}
