package com.badnng.moe.tdlib

import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Actual Engine/controller ownership only; staged bytes are synthetic, never probed or uploaded. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProfileAnimationEngineTest {
    @Test fun immediatePublicRejectionReturnsBorrowedHandlesWithoutConsumingEditorsFile() = runTest {
        withEngine(startReady = false) { engine, f ->
            val source = f.animation()
            val rejected = mutableListOf<Long?>()
            assertFalse(engine.uploadSettingsProfileAnimation(SettingsProfileExtrasToken(1, 1, 43), 91, source.prepared, rejected::add))
            assertEquals(listOf<Long?>(null), rejected)
            assertTrue(Files.exists(source.path))
            assertEquals(0, source.cleaned)
            assertTrue(f.extras.animations.isEmpty())
            source.prepared.lease.release()
            assertEquals(1, source.cleaned)
            assertFalse(Files.exists(source.path))
            val released = mutableListOf<Long?>()
            assertFalse(engine.uploadSettingsProfileAnimation(SettingsProfileExtrasToken(1, 1, 43), 91, source.prepared, released::add))
            assertEquals(1, source.cleaned, "An already released editor handle cannot create new native or validation owners.")
            runCurrent()
            assertEquals(listOf<Long?>(null), rejected)
            assertEquals(listOf<Long?>(null), released)
        }
    }

    @Test fun queuedOwnerCloseReleasesBothRetainedHandlesBeforeAnySessionUpload() = runTest {
        withEngine { engine, f ->
            val token = openExtras(engine)
            val stale = f.animation()
            val staleAck = mutableListOf<Long?>()
            assertFalse(engine.uploadSettingsProfileAnimation(token.copy(owner = token.owner - 1), 91, stale.prepared, staleAck::add))
            assertEquals(listOf<Long?>(null), staleAck)
            assertEquals(0, stale.cleaned)
            stale.prepared.lease.release()
            assertEquals(1, stale.cleaned)
            val source = f.animation()
            val queuedAck = mutableListOf<Long?>()
            assertTrue(engine.closeSettingsProfileExtras(token))
            assertTrue(engine.uploadSettingsProfileAnimation(token, 91, source.prepared, queuedAck::add))
            assertTrue(queuedAck.isEmpty())
            source.prepared.lease.release()
            assertTrue(Files.exists(source.path), "Both queue-owned handles must be retained before the method returns.")
            runCurrent()
            assertTrue(f.extras.animations.isEmpty())
            assertEquals(1, source.cleaned)
            assertFalse(Files.exists(source.path))
            runCurrent()
            assertEquals(listOf<Long?>(null), queuedAck)
            assertEquals(listOf<Long?>(null), staleAck)
        }
    }

    @Test fun queuedNativeReadyLossOrMandatoryLockReturnsBothHandlesWithoutValidationWork() = runTest {
        for (mandatory in listOf(false, true)) {
            withEngine { engine, f ->
                val token = openExtras(engine)
                val source = f.animation()
                val queuedAck = mutableListOf<Long?>()
                assertTrue(engine.uploadSettingsProfileAnimation(token, 91, source.prepared, queuedAck::add))
                assertTrue(queuedAck.isEmpty())
                source.prepared.lease.release()
                if (mandatory) f.required() else f.auth(AuthorizationPhase.OTHER_STEP)
                assertEquals(AuthorizationPhase.READY, engine.state.value.status.authorization,
                    "The native gate changed before the actor processed its notification.")
                assertEquals(mandatory, f.ready)
                assertEquals(!mandatory, f.gate.allowsOrdinary())
                val immediate = f.animation()
                val immediateAck = mutableListOf<Long?>()
                assertFalse(engine.uploadSettingsProfileAnimation(token, 91, immediate.prepared, immediateAck::add))
                assertEquals(listOf<Long?>(null), immediateAck)
                assertEquals(0, immediate.cleaned, "Immediate gate rejection preserves the editor's handle.")
                immediate.prepared.lease.release()
                assertEquals(1, immediate.cleaned)
                assertTrue(Files.exists(source.path))
                runCurrent()
                assertTrue(f.extras.animations.isEmpty())
                assertEquals(1, source.cleaned)
                assertFalse(Files.exists(source.path))
                runCurrent()
                assertEquals(listOf<Long?>(null), queuedAck)
                assertEquals(listOf<Long?>(null), immediateAck)
            }
        }
    }

    @Test fun admittedUploadSurvivesEditorReleaseAndKeepsNativeOwnershipAfterValidationFinishes() = runTest {
        withEngine { engine, f ->
            val token = openExtras(engine)
            val source = f.animation()
            val admitted = mutableListOf<Long?>()
            assertTrue(engine.uploadSettingsProfileAnimation(token, 91, source.prepared, admitted::add))
            assertTrue(admitted.isEmpty(), "Public queue acceptance is not actor admission.")
            source.prepared.lease.release()
            assertEquals(0, source.cleaned)
            runCurrent()
            val admittedRevision = engine.state.value.settings.profileExtras.startedRevision
            assertTrue(admittedRevision > 0)
            assertEquals(listOf<Long?>(admittedRevision), admitted)
            val call = f.extras.animations.single()
            assertEquals(43L, call.userId)
            assertEquals(91L, call.expectedPhotoId)
            assertEquals(source.prepared.path, call.animation.path)
            assertEquals(source.prepared.size, call.animation.size)
            assertEquals(source.prepared.dimension, call.animation.dimension)
            assertEquals(source.prepared.durationMillis, call.animation.durationMillis)
            assertEquals(source.prepared.mainFrameTimestampSeconds, call.animation.mainFrameTimestampSeconds)
            assertEquals(source.prepared.sha256, call.animation.sha256)
            assertTrue(call.current())
            assertEquals(SettingsProfileExtrasOperation.UPLOAD_ANIMATION, engine.state.value.settings.profileExtras.operation)
            assertTrue(engine.state.value.settings.profileExtras.busy)
            call.finishValidation()
            assertTrue(Files.exists(source.path), "Validation owns a different handle from the still pending native upload.")
            assertEquals(0, source.cleaned)
            call.result(SettingsResult(Unit))
            assertEquals(1, source.cleaned, "Native result returns the last handle even before actor publication.")
            assertFalse(Files.exists(source.path))
            assertEquals(0L, engine.state.value.settings.profileExtras.acceptedRevision)
            runCurrent()
            val accepted = engine.state.value.settings.profileExtras
            assertTrue(accepted.acceptedRevision > 0)
            assertEquals(accepted.acceptedRevision, accepted.completedRevision)
            assertFalse(accepted.busy)
            assertEquals(listOf<Long?>(admittedRevision), admitted, "Native completion cannot send a second admission reply.")
        }
    }

    @Test fun confirmedClosedReturnsNativePoolButCannotDeleteFileStillOwnedByLocalValidation() = runTest {
        withEngine { engine, f ->
            val token = openExtras(engine)
            val source = f.animation()
            assertTrue(engine.uploadSettingsProfileAnimation(token, 91, source.prepared)); runCurrent()
            val call = f.extras.animations.single()
            source.prepared.lease.release()
            f.auth(AuthorizationPhase.CLOSING); runCurrent()
            assertFalse(call.current())
            assertEquals(0, source.cleaned)
            f.auth(AuthorizationPhase.CLOSED); runCurrent()
            assertTrue(Files.exists(source.path), "Confirmed Closed releases only native ownership, not unfinished validation.")
            assertEquals(0, source.cleaned)
            val closed = engine.state.value.settings
            call.finishValidation(); call.finishValidation()
            assertEquals(1, source.cleaned)
            assertFalse(Files.exists(source.path))
            call.result(SettingsResult(Unit)); call.result(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
            assertEquals(closed, engine.state.value.settings)
            assertEquals(1, source.cleaned)
        }
    }

    @Test fun nativeFailureAndDuplicateLateSuccessNeverPublishAcceptedRevisionOrReleaseTwice() = runTest {
        withEngine { engine, f ->
            val token = openExtras(engine)
            val source = f.animation()
            assertTrue(engine.uploadSettingsProfileAnimation(token, 91, source.prepared)); runCurrent()
            val call = f.extras.animations.single()
            source.prepared.lease.release(); call.finishValidation()
            call.result(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
            val failed = engine.state.value.settings.profileExtras
            assertEquals(SettingsError.FORBIDDEN, failed.error)
            assertEquals(0L, failed.acceptedRevision)
            assertTrue(failed.failedRevision > 0)
            assertFalse(failed.busy)
            assertEquals(1, source.cleaned)
            call.result(SettingsResult(Unit)); call.result(SettingsResult(Unit)); runCurrent()
            assertEquals(failed, engine.state.value.settings.profileExtras)
            assertEquals(1, source.cleaned)
        }
    }

    @Test fun closedOwnersLateSuccessCannotAcceptIntoReplacementOrDisturbItsNextAnimation() = runTest {
        withEngine { engine, f ->
            val token = openExtras(engine)
            val oldSource = f.animation()
            assertTrue(engine.uploadSettingsProfileAnimation(token, 91, oldSource.prepared)); runCurrent()
            val old = f.extras.animations.single()
            oldSource.prepared.lease.release(); old.finishValidation()
            assertTrue(engine.closeSettingsProfileExtras(token)); runCurrent()
            val freshToken = openExtras(engine)
            assertNotEquals(token, freshToken)
            assertFalse(old.current())
            assertTrue(engine.state.value.settings.profileExtras.busy, "The replacement still shares the actual pending mutation.")
            old.result(SettingsResult(Unit)); runCurrent()
            assertEquals(1, oldSource.cleaned)
            assertEquals(0L, engine.state.value.settings.profileExtras.acceptedRevision)
            assertFalse(engine.state.value.settings.profileExtras.busy)
            val freshSource = f.animation()
            assertTrue(engine.uploadSettingsProfileAnimation(freshToken, 91, freshSource.prepared)); runCurrent()
            val fresh = f.extras.animations.last()
            freshSource.prepared.lease.release()
            val pending = engine.state.value.settings
            old.result(SettingsResult(Unit)); old.result(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
            assertEquals(pending, engine.state.value.settings)
            assertTrue(fresh.current())
            assertEquals(0, freshSource.cleaned)
            assertEquals(1, oldSource.cleaned)
            fresh.finishValidation(); fresh.result(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
            assertEquals(1, freshSource.cleaned)
        }
    }

    @Test fun animationPhotoAndBirthdateUseOneMutationGateAndRejectedUploadsReturnTheirHandles() = runTest {
        withEngine { engine, f ->
            val token = openExtras(engine)
            val source = f.animation()
            val admitted = mutableListOf<Long?>()
            assertTrue(engine.uploadSettingsProfileAnimation(token, 91, source.prepared, admitted::add)); runCurrent()
            val admittedRevision = engine.state.value.settings.profileExtras.startedRevision
            assertTrue(admittedRevision > 0)
            assertEquals(listOf<Long?>(admittedRevision), admitted)
            source.prepared.lease.release()
            var photoCleaned = 0
            val blockedPhoto = photo { photoCleaned++ }
            assertTrue(engine.uploadSettingsProfilePhoto(token, 91, blockedPhoto))
            blockedPhoto.lease.release()
            assertTrue(engine.saveSettingsProfileBirthdate(token, null, SettingsBirthdate(1, 1)))
            assertTrue(engine.removeSettingsProfilePhoto(token, 91)); runCurrent()
            assertEquals(1, photoCleaned)
            assertTrue(f.extras.photos.isEmpty())
            assertTrue(f.extras.birthdates.isEmpty())
            assertEquals(0, f.extras.removals)
            assertEquals(SettingsProfileExtrasOperation.UPLOAD_ANIMATION, engine.state.value.settings.profileExtras.operation)
            val call = f.extras.animations.single()
            call.finishValidation(); call.result(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()

            assertTrue(engine.saveSettingsProfileBirthdate(token, null, SettingsBirthdate(1, 1))); runCurrent()
            assertEquals(1, f.extras.birthdates.size)
            val blockedByBirthdate = f.animation()
            val birthdateAck = mutableListOf<Long?>()
            assertTrue(engine.uploadSettingsProfileAnimation(token, 91, blockedByBirthdate.prepared, birthdateAck::add))
            assertTrue(birthdateAck.isEmpty())
            blockedByBirthdate.prepared.lease.release(); runCurrent()
            assertEquals(listOf<Long?>(null), birthdateAck)
            assertEquals(1, blockedByBirthdate.cleaned)
            assertEquals(1, f.extras.animations.size)
            f.extras.birthdates.single()(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()

            val pendingPhoto = photo { photoCleaned++ }
            assertTrue(engine.uploadSettingsProfilePhoto(token, 91, pendingPhoto)); runCurrent()
            pendingPhoto.lease.release()
            assertEquals(1, f.extras.photos.size)
            val blockedByPhoto = f.animation()
            val photoAck = mutableListOf<Long?>()
            assertTrue(engine.uploadSettingsProfileAnimation(token, 91, blockedByPhoto.prepared, photoAck::add))
            assertTrue(photoAck.isEmpty())
            blockedByPhoto.prepared.lease.release(); runCurrent()
            assertEquals(listOf<Long?>(null), photoAck)
            assertEquals(1, blockedByPhoto.cleaned)
            assertEquals(1, f.extras.animations.size)
            f.extras.photos.single()(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
            assertEquals(2, photoCleaned)
            assertFalse(engine.state.value.settings.profileExtras.busy)
            assertEquals(listOf<Long?>(admittedRevision), admitted)
            assertEquals(listOf<Long?>(null), birthdateAck)
            assertEquals(listOf<Long?>(null), photoAck)
        }
    }

    @Test fun actorMutationRejectionRepliesNullOnceWithoutNewRevisionAndThrowingConsumerCannotBreakEngine() = runTest {
        withEngine { engine, f ->
            val token = openExtras(engine)
            val before = engine.state.value.settings.profileExtras
            assertTrue(engine.saveSettingsProfileBirthdate(token, null, SettingsBirthdate(1, 1)))
            val rejected = f.animation()
            val rejectionAck = mutableListOf<Long?>()
            assertTrue(engine.uploadSettingsProfileAnimation(token, 91, rejected.prepared) { value ->
                rejectionAck += value
                throw IllegalStateException("synthetic rejected admission consumer failure")
            })
            rejected.prepared.lease.release()
            assertTrue(rejectionAck.isEmpty())
            assertEquals(before, engine.state.value.settings.profileExtras,
                "Both requests are still queued against an otherwise writable public snapshot.")
            assertTrue(Files.exists(rejected.path))
            runCurrent()
            val birthday = engine.state.value.settings.profileExtras
            assertEquals(1, f.extras.birthdates.size)
            assertTrue(f.extras.animations.isEmpty(), "Production controller canSave rejects after the birthday starts.")
            assertEquals(listOf<Long?>(null), rejectionAck)
            assertEquals(before.startedRevision + 1, birthday.startedRevision,
                "Rejected animation cannot start another mutation revision.")
            assertEquals(SettingsProfileExtrasOperation.BIRTHDATE, birthday.operation)
            assertTrue(birthday.busy)
            assertEquals(0L, birthday.acceptedRevision)
            assertEquals(1, rejected.cleaned)
            assertFalse(Files.exists(rejected.path), "The actor rejection returns native and validation handles.")

            f.extras.birthdates.single()(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
            assertFalse(engine.state.value.settings.profileExtras.busy)
            val accepted = f.animation()
            val acceptedAck = mutableListOf<Long?>()
            assertTrue(engine.uploadSettingsProfileAnimation(token, 91, accepted.prepared) { value ->
                acceptedAck += value
                throw IllegalStateException("synthetic accepted admission consumer failure")
            })
            accepted.prepared.lease.release(); runCurrent()
            val admittedRevision = engine.state.value.settings.profileExtras.startedRevision
            assertEquals(2L, admittedRevision)
            assertEquals(birthday.startedRevision + 1, admittedRevision,
                "Admission must report this animation's actor revision, not the completed birthday revision.")
            assertEquals(listOf<Long?>(admittedRevision), acceptedAck)
            val native = f.extras.animations.single()
            assertTrue(native.current(), "Engine still processes later admission despite the earlier consumer exception.")
            assertEquals(0L, engine.state.value.settings.profileExtras.acceptedRevision,
                "An actual started revision does not mean native success.")
            native.finishValidation()
            native.result(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
            native.result(SettingsResult(Unit)); runCurrent()
            assertFalse(engine.state.value.settings.profileExtras.busy)
            assertEquals(0L, engine.state.value.settings.profileExtras.acceptedRevision)
            assertEquals(1, accepted.cleaned)
            assertEquals(1, rejected.cleaned)
            assertEquals(listOf<Long?>(null), rejectionAck)
            assertEquals(listOf<Long?>(admittedRevision), acceptedAck)
        }
    }

    @Test fun directControllerRejectsChangedExpectedPhotoOrInactivePageAndReleasesBothHandles() {
        val root = Files.createTempDirectory("profile-animation-controller-")
        val session = Extras()
        val queue = ArrayDeque<() -> Unit>()
        var snapshot = SettingsProfileExtrasSnapshot()
        val visible = AtomicBoolean(true)
        val controller = SettingsProfileExtrasController(7, session, { queue.addLast(it) }, { snapshot = it })
        val pool = AttachmentSendLeases()
        val sources = mutableListOf<AnimationArtifact>()
        try {
            controller.open(43, visible::get)
            while (queue.isNotEmpty()) queue.removeFirst()()
            val token = snapshot.token
            fun rejected(expected: Long?) {
                val source = AnimationArtifact(root).also { sources += it }
                val native = assertNotNull(pool.retain(source.prepared.lease))
                val validation = assertNotNull(source.prepared.lease.retain())
                source.prepared.lease.release()
                assertNull(controller.uploadAnimation(token, expected, source.prepared, native, validation))
                assertEquals(1, source.cleaned)
                assertFalse(Files.exists(source.path))
                assertTrue(session.animations.isEmpty())
            }
            rejected(92)
            assertEquals(0L, snapshot.startedRevision)
            visible.set(false)
            rejected(91)
            assertFalse(snapshot.busy)
        } finally {
            controller.deactivate(); pool.releaseAll()
            sources.forEach { it.prepared.lease.release() }
            deleteFixture(root)
        }
    }

    private fun TestScope.openExtras(engine: TdEngine): SettingsProfileExtrasToken {
        assertTrue(engine.openSettingsProfileExtras(engine.state.value.settings.token, 43)); runCurrent()
        return engine.state.value.settings.profileExtras.also {
            assertEquals(43L, it.token.userId)
            assertEquals(91L, it.data?.photoId)
            assertFalse(it.loading)
        }.token
    }

    private suspend fun TestScope.withEngine(startReady: Boolean = true, block: suspend TestScope.(TdEngine, Fixture) -> Unit) {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
        try {
            if (startReady) {
                assertTrue(engine.start()); runCurrent()
                f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
                f.auth(AuthorizationPhase.READY); runCurrent()
                assertTrue(engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE)); runCurrent()
            }
            block(engine, f)
        } finally {
            assertTrue(engine.close())
            f.extras.animations.forEach { it.finishValidation() }
            f.sources.forEach { it.prepared.lease.release() }
            deleteFixture(f.root)
        }
    }

    private class AnimationArtifact(root: Path) {
        val path: Path = Files.createTempFile(root, "synthetic-animation-", ".mp4")
        var cleaned = 0
        val prepared: SettingsPreparedProfileAnimation
        init {
            Files.write(path, byteArrayOf(0, 1, 2, 3))
            prepared = SettingsPreparedProfileAnimation(path.toString(), 4, 800, 2000, 0.5, "00".repeat(32),
                ownedAttachmentLease { cleaned++; Files.deleteIfExists(path) })
        }
    }

    private class Fixture : TdTransport {
        val root = Files.createTempDirectory("profile-animation-engine-")
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val extras = Extras()
        val settings = Settings(extras)
        val gate = TdUserInteractionGate()
        val sources = mutableListOf<AnimationArtifact>()
        var ready = false
        private lateinit var authorization: (AuthorizationDetails) -> Unit
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }
        fun animation() = AnimationArtifact(root).also { sources += it }
        fun auth(phase: AuthorizationPhase) {
            ready = phase == AuthorizationPhase.READY
            authorization(AuthorizationDetails(phase))
        }
        fun required() = gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit,
        ): TdSession {
            authorization = onAuthorization
            return object : SyntheticOpenKeySession {
                override fun authorizationReady() = this@Fixture.ready
                override val settings: TdSettingsSession = this@Fixture.settings
                override val interactionGate = gate
                override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = result(null)
                override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
                override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login in this fixture")
                override fun close() = auth(AuthorizationPhase.CLOSED)
            }
        }
    }

    /** Ungated facade. It owns validation separately and permits native results before or after local work ends. */
    private class Extras : TdSettingsProfileExtrasSession {
        class Animation(val userId: Long, val expectedPhotoId: Long?, val animation: SettingsPreparedProfileAnimation,
            private var validation: AttachmentLease?, val current: () -> Boolean, val result: (SettingsResult<Unit>) -> Unit,
        ) {
            fun finishValidation() { validation?.release(); validation = null }
        }
        val animations = mutableListOf<Animation>()
        val photos = mutableListOf<(SettingsResult<Unit>) -> Unit>()
        val birthdates = mutableListOf<(SettingsResult<Unit>) -> Unit>()
        var removals = 0
        override fun uploadAnimation(userId: Long, expectedPhotoId: Long?, animation: SettingsPreparedProfileAnimation,
            validationLease: AttachmentLease, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) { animations += Animation(userId, expectedPhotoId, animation, validationLease, isCurrent, result) }
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) =
            result(SettingsResult(SettingsProfileExtrasData(userId, 91, null, SettingsCalendarDate(27, 10, 2026))))
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) { photos += result }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) { birthdates += result }
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            removals++
            result(SettingsResult(Unit))
        }
    }

    private class Settings(override val profileExtras: Extras) : TdSettingsSession {
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) =
            result(SettingsResult(SettingsProfile(43, "Synthetic", "", "", "", "")))
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
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
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }

    private companion object {
        fun photo(cleaned: () -> Unit) = SettingsPreparedProfilePhoto("synthetic.jpg", 512, 32, "00".repeat(32), ownedAttachmentLease(cleaned))
        fun deleteFixture(directory: Path) {
            val root = directory.toAbsolutePath().normalize()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(path)
            } }
        }
    }
}
