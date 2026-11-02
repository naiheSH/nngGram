package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.media.*
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProfileStickerAvatarMediaEngineTest {
    @Test fun retainedAndRepeatedlyRestrictedHandlesNeverRelaxSourceAuthority() {
        val media = FakeMedia()
        val first = AtomicBoolean(true); val second = AtomicBoolean(true)
        val original = media.encoded(ProfileStickerAvatarMediaRole.FULL)
        val restricted = checkNotNull(original.restrictSource(first::get))
        val strictest = checkNotNull(restricted.restrictSource(second::get))
        val retained = checkNotNull(strictest.retain())
        original.close(); restricted.close(); strictest.close()
        val source = retained.sourceLease()
        assertTrue(source())
        first.set(false); assertFalse(source())
        first.set(true); assertFalse(source())
        retained.close(); assertEquals(0L, media.budget.used())
    }

    @Test fun transferredEncodedCannotPublishAfterAnyOriginalSourceRevocation() {
        runTest {
            for (mode in listOf("stop", "mandatory", "ready", "picker", "selection", "catalog")) {
                withEngine { engine, f ->
                    val (parent, picker, selection) = prepared(engine, f)
                    val environment = checkNotNull(engine.profileStickerAvatarMedia.value)
                    val ui = ControlledUi()
                    val workers = ProfileStickerAvatarArtworkWorkers()
                    val codec = ControlledCodec(blockOpen = true)
                    val provider = JvmProfileStickerAvatarArtworkProvider(environment.generation, environment.reader, ui, codec, workers)
                    try {
                        val results = mutableListOf<SettingsResult<ProfileStickerAvatarPreviewClip>>()
                        val lease = provider.preview(parent, picker, selection, 16, { true }, results::add)
                        runCurrent()
                        val sourceRead = f.media.reads.single()
                        sourceRead.done(SettingsResult(f.media.encoded(ProfileStickerAvatarMediaRole.FULL)))
                        assertTrue(codec.entered.await(5, TimeUnit.SECONDS)); assertEquals(1, sourceRead.closed)
                        assertEquals(16L, f.media.budget.used(), "worker retains encoded after bridge closes")
                        revoke(engine, f, parent, picker, mode)
                        assertEquals(16L, f.media.budget.used(), "revocation is not physical worker completion")
                        codec.release.countDown()
                        awaitMedia { f.media.budget.used() == 0L && ui.size() > 0 }
                        ui.drain()
                        assertTrue(results.none { it.value != null }, "stale result for $mode")
                        assertEquals(1, codec.clip.closed.get())
                        lease.close()
                    } finally { codec.release.countDown(); provider.close(); ui.drain(); workers.close() }
                }
            }
        }
    }

    @Test fun borrowedFrameRevokesImmediatelyButUiOwnsPixelsUntilClose() {
        runTest {
            for (mode in listOf("stop", "mandatory", "ready", "picker", "selection", "catalog")) {
                withEngine { engine, f ->
                    val (parent, picker, selection) = prepared(engine, f)
                    val environment = checkNotNull(engine.profileStickerAvatarMedia.value)
                    val ui = ControlledUi(); val workers = ProfileStickerAvatarArtworkWorkers()
                    val codec = ControlledCodec(blockSecondFrame = true)
                    val provider = JvmProfileStickerAvatarArtworkProvider(environment.generation, environment.reader, ui, codec, workers)
                    try {
                        var preview: ProfileStickerAvatarPreviewClip? = null
                        val lease = provider.preview(parent, picker, selection, 16, { true }) { preview = it.value }
                        runCurrent()
                        val sourceRead = f.media.reads.single()
                        sourceRead.done(SettingsResult(f.media.encoded(ProfileStickerAvatarMediaRole.FULL)))
                        awaitMedia { ui.size() > 0 }; ui.drain()
                        assertEquals(1, sourceRead.closed)
                        val clip = assertNotNull(preview)
                        var image: ProfileStickerAvatarArtwork? = null
                        val first = clip.frame(1, { true }) { image = it.value }
                        awaitMedia { ui.size() > 0 }; ui.drain()
                        val borrowed = assertNotNull(image); assertNotNull(borrowed.image)
                        var later = 0
                        val second = clip.frame(2, { true }) { if (it.value != null) later++ }
                        assertTrue(codec.entered.await(5, TimeUnit.SECONDS))
                        revoke(engine, f, parent, picker, mode)
                        assertNull(borrowed.image, "source revoked before UI replaces provider: $mode")
                        assertEquals(0, codec.clip.disposed.get(), "UI still owns displayed pixels")
                        assertEquals(0, codec.clip.closed.get(), "active worker retains codec")
                        codec.release.countDown(); awaitMedia { ui.size() > 0 }; ui.drain()
                        assertEquals(0, later); assertEquals(1, codec.clip.disposed.get())
                        first.close(); second.close(); lease.close(); provider.close()
                        assertNull(borrowed.image); assertEquals(2, codec.clip.disposed.get())
                        assertEquals(1, codec.clip.closed.get())
                    } finally { codec.release.countDown(); provider.close(); ui.drain(); workers.close() }
                }
            }
        }
    }

    @Test fun thumbnailSurvivesSelectionChangeButRevokesOnCatalogChange() {
        runTest {
            withEngine { engine, f ->
                val (parent, picker, _) = prepared(engine, f)
                val env = checkNotNull(engine.profileStickerAvatarMedia.value)
                val ui = ControlledUi(); val workers = ProfileStickerAvatarArtworkWorkers()
                val codec = ControlledCodec()
                val provider = JvmProfileStickerAvatarArtworkProvider(env.generation, env.reader, ui, codec, workers)
                try {
                    var artwork: ProfileStickerAvatarArtwork? = null
                    val lease = provider.thumbnail(parent, picker, f.native.candidate, 16, { true }) { artwork = it.value }
                    runCurrent()
                    f.media.reads.single().done(SettingsResult(f.media.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL)))
                    awaitMedia { ui.size() > 0 }; ui.drain()
                    val borrowed = assertNotNull(artwork); assertNotNull(borrowed.image)
                    assertEquals(1, f.media.reads.single().closed)
                    select(engine, parent, picker, f.native.candidate)
                    assertNotNull(borrowed.image, "selection replacement does not revoke catalog thumbnail")
                    val pending = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                    assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, { true }, pending::add)); runCurrent()
                    assertNull(borrowed.image, "catalog epoch revoked as new read starts")
                    assertTrue(pending.isEmpty())
                    f.native.defaultReads.first().done(SettingsResult(f.native.candidatePage())); runCurrent()
                    assertNull(borrowed.image, "old duplicate cannot revive old catalog pixels")
                    assertTrue(pending.isEmpty())
                    f.native.defaultReads.last().done(SettingsResult(f.native.candidatePage())); runCurrent()
                    assertNull(borrowed.image, "completion cannot revive old catalog pixels")
                    lease.close(); assertEquals(1, codec.disposed.get())
                } finally { provider.close(); ui.drain(); workers.close() }
            }
        }
    }

    private fun TestScope.revoke(engine: TdEngine, f: Fixture, parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket, mode: String) {
        when (mode) {
            "stop" -> { async(start = CoroutineStart.UNDISPATCHED) { engine.stop() } }
            "mandatory" -> f.block(true)
            "ready" -> f.auth(AuthorizationPhase.WAITING_CODE)
            "picker" -> { assertTrue(engine.closeSettingsProfileStickerAvatar(parent, picker)); runCurrent() }
            "selection" -> { select(engine, parent, picker, f.native.candidate) }
            "catalog" -> {
                assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, { true }) {})
                runCurrent() // The old catalog is revoked before this pending native result.
            }
            else -> error("Unknown synthetic revocation")
        }
    }

    @Test fun environmentWaitsForCorrelatedSelfAndIgnoresOtherUserUpdates() {
        runTest {
            val f = Fixture()
            val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
            try {
                assertTrue(engine.start()); runCurrent()
                f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
                f.auth(AuthorizationPhase.READY); runCurrent()
                assertNull(engine.profileStickerAvatarMedia.value)
                f.event(ChatEvent.SenderName(77, "Synthetic other", profile = AccountProfile(77, "Synthetic other")))
                runCurrent(); assertNull(engine.profileStickerAvatarMedia.value)
                checkNotNull(f.chats.me).invoke(ChatResult(AccountProfile(43, "Synthetic self")))
                runCurrent(); assertNotNull(engine.profileStickerAvatarMedia.value)
            } finally {
                val stopped = async { engine.close() }; runCurrent(); assertTrue(stopped.await()); f.delete()
            }
        }
    }

    @Test fun realEngineCarriesExactTicketsIntoBothNativeMediaRoles() {
        runTest {
            withEngine { engine, f ->
                val (parent, picker, selected) = prepared(engine, f)
                val reader = checkNotNull(engine.profileStickerAvatarMedia.value).reader
                val thumb = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                val full = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                val t = reader.thumbnail(parent, picker, f.native.candidate, { true }, thumb::add)
                val p = reader.preview(parent, picker, selected, { true }, full::add)
                assertTrue(f.media.reads.isEmpty()); runCurrent()
                assertEquals(2, f.media.reads.size)
                val a = f.media.reads[0]; val b = f.media.reads[1]
                assertSame(picker, a.picker); assertSame(f.native.candidate, a.candidate); assertNull(a.selection)
                assertSame(picker, b.picker); assertSame(selected, b.selection); assertNull(b.candidate)
                assertTrue(a.active()); assertTrue(b.active())
                a.done(SettingsResult(f.media.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL)))
                b.done(SettingsResult(f.media.encoded(ProfileStickerAvatarMediaRole.FULL)))
                assertNotNull(thumb.single().value); assertNotNull(full.single().value)
                thumb.single().value!!.close(); full.single().value!!.close(); t.close(); p.close()
                assertEquals(0L, f.media.budget.used())
            }
        }
    }

    @Test fun parentPickerAndCurrentSelectionAreRequiredAtActorAdmission() {
        runTest {
            withEngine { engine, f ->
                val (parent, picker, selected) = prepared(engine, f)
                val reader = checkNotNull(engine.profileStickerAvatarMedia.value).reader
                val results = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                reader.thumbnail(parent.copy(owner = parent.owner + 1), picker, f.native.candidate, { true }, results::add)
                reader.thumbnail(parent, ProfileStickerAvatarPickerTicket(), f.native.candidate, { true }, results::add)
                reader.preview(parent, picker, ProfileStickerAvatarSelectionTicket(), { true }, results::add)
                runCurrent()
                assertEquals(listOf(SettingsError.NOT_READY, SettingsError.NOT_READY, SettingsError.NOT_READY), results.map { it.error })
                assertTrue(f.media.reads.isEmpty())
                val next = select(engine, parent, picker, f.native.candidate)
                assertNotSame(selected, next)
                val stale = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                reader.preview(parent, picker, selected, { true }, stale::add); runCurrent()
                assertEquals(SettingsError.NOT_READY, stale.single().error)
                assertTrue(f.media.reads.isEmpty())
            }
        }
    }

    @Test fun nativeReadyAndOrdinaryGateRejectImmediatelyAndWhileQueued() {
        runTest {
            for (mandatory in listOf(false, true)) for (queued in listOf(false, true)) {
                withEngine { engine, f ->
                    val (parent, picker, _) = prepared(engine, f)
                    val env = checkNotNull(engine.profileStickerAvatarMedia.value)
                    val responses = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                    if (!queued) f.block(mandatory)
                    env.reader.thumbnail(parent, picker, f.native.candidate, { true }, responses::add)
                    if (queued) f.block(mandatory)
                    runCurrent()
                    assertEquals(SettingsError.NOT_READY, responses.single().error)
                    assertTrue(f.media.reads.isEmpty())
                }
            }
        }
    }

    @Test fun changingSelectionRevokesOnlyOldFullLeaseAndAllowsNewSelection() {
        runTest {
            withEngine { engine, f ->
                val (parent, picker, selection) = prepared(engine, f)
                val reader = checkNotNull(engine.profileStickerAvatarMedia.value).reader
                val old = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                reader.preview(parent, picker, selection, { true }, old::add); runCurrent()
                val read = f.media.reads.single(); assertTrue(read.active())
                val next = select(engine, parent, picker, f.native.candidate)
                assertFalse(read.active())
                read.done(SettingsResult(f.media.encoded(ProfileStickerAvatarMediaRole.FULL)))
                assertEquals(SettingsError.NOT_READY, old.single().error); assertEquals(0L, f.media.budget.used())
                val fresh = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                val lease = reader.preview(parent, picker, next, { true }, fresh::add); runCurrent()
                assertSame(next, f.media.reads.last().selection)
                lease.close()
            }
        }
    }

    @Test fun queuedStopRevokesBeforeActorAndNeverSubmitsMedia() {
        runTest {
            withEngine { engine, f ->
                val (parent, picker, _) = prepared(engine, f)
                val env = checkNotNull(engine.profileStickerAvatarMedia.value)
                val responses = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                env.reader.thumbnail(parent, picker, f.native.candidate, { true }, responses::add)
                val stop = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
                runCurrent()
                assertTrue(stop.await()); assertTrue(f.media.reads.isEmpty())
                assertEquals(SettingsError.NOT_READY, responses.single().error)
                assertNull(engine.profileStickerAvatarMedia.value)
            }
        }
    }

    @Test fun pickerCloseAndStopRevokeInflightAndCloseLateEncoded() {
        runTest {
            for (stop in listOf(false, true)) withEngine { engine, f ->
                val (parent, picker, selected) = prepared(engine, f)
                val env = checkNotNull(engine.profileStickerAvatarMedia.value)
                val results = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                env.reader.preview(parent, picker, selected, { true }, results::add); runCurrent()
                val read = f.media.reads.single(); assertTrue(read.active())
                if (stop) {
                    val stopped = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
                    runCurrent(); assertTrue(stopped.await())
                } else { assertTrue(engine.closeSettingsProfileStickerAvatar(parent, picker)); runCurrent() }
                assertFalse(read.active())
                val encoded = f.media.encoded(ProfileStickerAvatarMediaRole.FULL)
                onWorker { read.done(SettingsResult(encoded)) }
                assertEquals(0L, f.media.budget.used())
                assertTrue(results.none { it.value != null })
                assertEquals(1, read.closed)
            }
        }
    }

    @Test fun syncCallbackBeforeSourceBindingAndConsumerCloseStillCloseSourceOnce() {
        runTest {
            withEngine { engine, f ->
                val (parent, picker, _) = prepared(engine, f)
                val env = checkNotNull(engine.profileStickerAvatarMedia.value)
                lateinit var lease: AutoCloseable
                val responses = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                f.media.onRead = { it.done(SettingsResult(f.media.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL))) }
                lease = env.reader.thumbnail(parent, picker, f.native.candidate, { true }) {
                    responses += it; lease.close()
                }
                runCurrent()
                assertNotNull(responses.single().value)
                assertEquals(1, f.media.reads.single().closed)
                responses.single().value!!.close(); lease.close()
                assertEquals(1, f.media.reads.single().closed)
                assertEquals(0L, f.media.budget.used())
            }
        }
    }

    @Test fun closeBeforeActorBindingIsSilentAndSubmitsNoNativeRead() {
        runTest {
            withEngine { engine, f ->
                val (parent, picker, _) = prepared(engine, f)
                val env = checkNotNull(engine.profileStickerAvatarMedia.value)
                val results = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                val lease = env.reader.thumbnail(parent, picker, f.native.candidate, { true }, results::add)
                lease.close(); lease.close(); runCurrent()
                assertTrue(f.media.reads.isEmpty()); assertTrue(results.isEmpty())
            }
        }
    }

    @Test fun duplicateAndThrowingCallbacksCannotConsumeTransferredOrLeakExtraHandles() {
        runTest {
            withEngine { engine, f ->
                val (parent, picker, _) = prepared(engine, f)
                val env = checkNotNull(engine.profileStickerAvatarMedia.value)
                val responses = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                val lease = env.reader.thumbnail(parent, picker, f.native.candidate, { true }, responses::add); runCurrent()
                val read = f.media.reads.single()
                val first = f.media.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL)
                onWorker {
                    read.done(SettingsResult(first)); read.done(SettingsResult(first))
                    read.done(SettingsResult(f.media.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL)))
                }
                assertEquals(1, responses.size); assertEquals(16L, f.media.budget.used())
                responses.single().value!!.close(); lease.close(); assertEquals(0L, f.media.budget.used())
                val throws = env.reader.thumbnail(parent, picker, f.native.candidate, { true }) { error("synthetic observer") }
                runCurrent()
                f.media.reads.last().done(SettingsResult(f.media.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL)))
                assertEquals(0L, f.media.budget.used()); assertEquals(1, f.media.reads.last().closed)
                throws.close()
            }
        }
    }

    @Test fun retiredGenerationCannotReviveAfterSameAccountRestarts() {
        runTest {
            withEngine { engine, f ->
                val (parent, picker, _) = prepared(engine, f)
                val old = checkNotNull(engine.profileStickerAvatarMedia.value)
                val stop = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }; runCurrent(); assertTrue(stop.await())
                assertTrue(engine.start()); runCurrent()
                f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
                f.auth(AuthorizationPhase.READY); runCurrent()
                checkNotNull(f.chats.me).invoke(ChatResult(AccountProfile(43, "Synthetic self"))); runCurrent()
                val fresh = checkNotNull(engine.profileStickerAvatarMedia.value)
                assertNotSame(old, fresh); assertNotEquals(old.generation, fresh.generation)
                val results = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
                old.reader.thumbnail(parent, picker, f.native.candidate, { true }, results::add); runCurrent()
                assertEquals(SettingsError.NOT_READY, results.single().error); assertTrue(f.media.reads.isEmpty())
            }
        }
    }

    private suspend fun TestScope.prepared(engine: TdEngine, f: Fixture): Triple<SettingsProfileExtrasToken,
        ProfileStickerAvatarPickerTicket, ProfileStickerAvatarSelectionTicket> {
        val parent = openExtras(engine, f)
        val picker = openPicker(engine, parent)
        val catalog = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, { true }, catalog::add)); runCurrent()
        f.native.defaultReads.last().done(SettingsResult(f.native.candidatePage())); runCurrent()
        assertSame(f.native.candidate, catalog.single().value?.candidates?.single()?.ticket)
        return Triple(parent, picker, select(engine, parent, picker, f.native.candidate))
    }
    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture) -> Unit) {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(engine.start()); advanceUntilIdle()
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); advanceUntilIdle()
            f.auth(AuthorizationPhase.READY); advanceUntilIdle()
            assertNull(engine.profileStickerAvatarMedia.value)
            checkNotNull(f.chats.me).invoke(ChatResult(AccountProfile(43, "Synthetic self"))); advanceUntilIdle()
            assertNotNull(engine.profileStickerAvatarMedia.value)
            assertTrue(engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE))
            withTimeout(5_000) { engine.state.first { it.settings.profile?.userId == 43L } }
            assertEquals(43L, engine.state.value.settings.profile?.userId)
            block(engine, f)
        } finally {
            f.closeImmediately = true
            if (f.closeCalls > 0 && !f.closed) f.auth(AuthorizationPhase.CLOSED)
            val close = async { engine.close() }
            advanceUntilIdle()
            val closed = close.await()
            if (closed) f.delete()
            assertTrue(closed)
        }
    }

    private suspend fun TestScope.openExtras(engine: TdEngine, f: Fixture): SettingsProfileExtrasToken {
        val parent = engine.state.value.settings.token
        assertTrue(engine.openSettingsProfileExtras(parent, 43))
        // Await the actor publication, rather than assuming channel resumption has
        // completed while the submitting test coroutine is still on its stack.
        withTimeout(5_000) { engine.state.first { it.settings.profileExtras.token.userId == 43L &&
            !it.settings.profileExtras.loading } }
        val page = engine.state.value.settings.profileExtras
        assertEquals(43L, page.token.userId)
        assertEquals(91L, page.data?.photoId)
        assertFalse(page.loading)
        return page.token
    }

    private fun TestScope.openPicker(engine: TdEngine, parent: SettingsProfileExtrasToken,
        active: () -> Boolean = { true },
    ): ProfileStickerAvatarPickerTicket {
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarPickerTicket>>()
        assertTrue(engine.openSettingsProfileStickerAvatar(parent, active, results::add)); runCurrent()
        return assertNotNull(results.single().value)
    }

    private fun TestScope.select(engine: TdEngine, parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket, candidate: ProfileStickerAvatarCandidateTicket,
    ): ProfileStickerAvatarSelectionTicket {
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
        assertTrue(engine.selectSettingsProfileStickerAvatar(parent, picker, candidate, { true }, results::add)); runCurrent()
        return assertNotNull(results.single().value)
    }

    private fun assertReceipt(engine: TdEngine, parent: SettingsProfileExtrasToken, revision: Long,
        result: SettingsResult<Unit>,
    ) {
        val receipt = engine.profileMutationReceipts.snapshot().single()
        assertEquals(parent, receipt.key.token)
        assertEquals(revision, receipt.key.revision)
        assertEquals(SettingsProfileExtrasOperation.UPLOAD_STICKER_AVATAR, receipt.key.operation)
        assertEquals(result, receipt.result)
    }

    private fun onWorker(action: () -> Unit) {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true, name = "profile-sticker-engine-worker") {
            try { action() } catch (error: Throwable) { failure.set(error) } finally { completed.countDown() }
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
    }

    private class ControlledUi : ProfileStickerAvatarUiDispatch {
        private val queue = ConcurrentLinkedQueue<Pair<() -> Unit, () -> Unit>>()
        override fun post(deliver: () -> Unit, discard: () -> Unit) { queue.add(deliver to discard) }
        fun size() = queue.size
        fun drain() { while (true) (queue.poll() ?: break).first() }
    }

    private class ControlledCodec(private val blockOpen: Boolean = false,
        private val blockSecondFrame: Boolean = false) : ProfileStickerAvatarCodec {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = AtomicInteger()
        val clip = Clip()
        override fun thumbnail(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean,
            permit: ArtworkWorkerPermit): ArtworkFrame = ArtworkFrame(ImageBitmap(1, 1), false) { disposed.incrementAndGet() }
        override fun open(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean,
            permit: ArtworkWorkerPermit): ArtworkCodecClip {
            if (blockOpen) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            return clip
        }
        inner class Clip : ArtworkCodecClip {
            override val animated = false
            override val durationMillis = 0L
            override val frameDurationMillis = 0L
            val closed = AtomicInteger()
            val disposed = AtomicInteger()
            private val frames = AtomicInteger()
            override fun frame(timeMillis: Long, active: () -> Boolean, permit: ArtworkWorkerPermit): ArtworkFrame {
                if (frames.incrementAndGet() == 2 && blockSecondFrame) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                return ArtworkFrame(ImageBitmap(1, 1), false) { disposed.incrementAndGet() }
            }
            override fun close() { closed.incrementAndGet() }
        }
    }

    private fun awaitMedia(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) { check(System.nanoTime() < deadline) { "Synthetic media timeout" }; Thread.sleep(5) }
    }

    private class Fixture : TdTransport {
        val root: Path = Files.createTempDirectory("nnggram-profile-sticker-engine-").toRealPath()
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val gate = TdUserInteractionGate()
        val ready = AtomicBoolean(false)
        val native = FakeAvatar()
        val chats = FakeChats()
        val media = FakeMedia()
        val settings = FakeSettings(FakeExtras(native))
        var closeImmediately = true
        var closeCalls = 0
        var closed = false
        private lateinit var authorization: (AuthorizationDetails) -> Unit
        private lateinit var onEvent: (ChatEvent) -> Unit
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }

        fun auth(phase: AuthorizationPhase) {
            ready.set(phase == AuthorizationPhase.READY)
            if (phase == AuthorizationPhase.CLOSED) closed = true
            authorization(AuthorizationDetails(phase))
        }
        fun block(mandatory: Boolean) {
            if (mandatory) gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            else ready.set(false)
        }
        fun event(value: ChatEvent) = onEvent(value)
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit,
        ): TdSession {
            authorization = onAuthorization
            onEvent = onChatEvent
            closed = false
            return object : SyntheticOpenKeySession, ProfileStickerAvatarMediaSession {
                override val stickerAvatarMedia: ProfileStickerAvatarMediaFiles = this@Fixture.media
                override val chats: TdChatSession = this@Fixture.chats
                override fun retireDownloads() { media.retire() }
                override fun authorizationReady() = ready.get()
                override val interactionGate = gate
                override val settings: TdSettingsSession = this@Fixture.settings
                override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) { result(null) }
                override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) { result(null) }
                override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login in sticker tests")
                override fun close() { closeCalls++; if (closeImmediately) auth(AuthorizationPhase.CLOSED) }
            }
        }

        fun delete() {
            val parent = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
            val checked = root.toRealPath()
            check(checked.parent == parent && checked.fileName.toString().startsWith("nnggram-profile-sticker-engine-"))
            Files.walk(checked).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    check(path.toAbsolutePath().normalize().startsWith(checked))
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private class FakeChats : TdChatSession {
        var me: ((ChatResult<AccountProfile>) -> Unit)? = null
        override fun getMe(result: (ChatResult<AccountProfile>) -> Unit) { me = result }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) { result(ChatResult(ChatLoadResult(true))) }
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) = error("No history")
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = error("No sends")
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = error("No retry")
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = error("No reads")
    }

    private class FakeMedia : ProfileStickerAvatarMediaFiles {
        data class Read(val picker: ProfileStickerAvatarPickerTicket, val candidate: ProfileStickerAvatarCandidateTicket?,
            val selection: ProfileStickerAvatarSelectionTicket?, val active: () -> Boolean,
            val done: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit, var closed: Int = 0)
        val reads = mutableListOf<Read>()
        val budget = ProfileStickerAvatarEncodedBudget()
        var retired = 0
        var onRead: ((Read) -> Unit)? = null
        fun encoded(role: ProfileStickerAvatarMediaRole): ProfileStickerAvatarEncoded {
            val bytes = ByteArray(16)
            val media = ProfileStickerAvatarOwnedMedia(role,
                ProfileStickerAvatarAsset(MediaFile(1, 16, uniqueId = "synthetic"), MediaFormat.WEBP, 1, 1), false)
            return ProfileStickerAvatarEncoded.take(media, bytes, checkNotNull(budget.reserve(16)))
        }
        private fun read(picker: ProfileStickerAvatarPickerTicket, candidate: ProfileStickerAvatarCandidateTicket?,
            selection: ProfileStickerAvatarSelectionTicket?, active: () -> Boolean,
            done: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): ProfileStickerAvatarReadLease {
            val read = Read(picker, candidate, selection, active, done)
            reads += read
            onRead?.invoke(read)
            return object : ProfileStickerAvatarReadLease { override fun close() { read.closed++ } }
        }
        override fun thumbnail(picker: ProfileStickerAvatarPickerTicket, candidate: ProfileStickerAvatarCandidateTicket,
            active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) =
            read(picker, candidate, null, active, result)
        override fun full(picker: ProfileStickerAvatarPickerTicket, selection: ProfileStickerAvatarSelectionTicket,
            active: () -> Boolean, result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) =
            read(picker, null, selection, active, result)
        override fun retire() { retired++ }
    }

    private class FakeAvatar : TdProfileStickerAvatarSession {
        data class Open(val userId: Long, val active: () -> Boolean, val picker: ProfileStickerAvatarPickerTicket)
        data class CandidateRead(val picker: ProfileStickerAvatarPickerTicket, val done: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit)
        data class PackList(val picker: ProfileStickerAvatarPickerTicket, val kind: ProfileStickerAvatarPackKind,
            val done: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit)
        data class PackRead(val picker: ProfileStickerAvatarPickerTicket, val pack: ProfileStickerAvatarPackTicket,
            val done: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit)
        data class Selection(val picker: ProfileStickerAvatarPickerTicket, val candidate: ProfileStickerAvatarCandidateTicket,
            val selection: ProfileStickerAvatarSelectionTicket)
        data class Write(val picker: ProfileStickerAvatarPickerTicket, val selection: ProfileStickerAvatarSelectionTicket,
            val expectedPhotoId: Long?, val background: ProfileStickerAvatarBackground, val active: () -> Boolean,
            val done: (SettingsResult<Unit>) -> Unit)
        val candidate = ProfileStickerAvatarCandidateTicket()
        val pack = ProfileStickerAvatarPackTicket()
        val opens = mutableListOf<Open>()
        val closes = mutableListOf<ProfileStickerAvatarPickerTicket>()
        val defaultReads = mutableListOf<CandidateRead>()
        val packLists = mutableListOf<PackList>()
        val packReads = mutableListOf<PackRead>()
        val selections = mutableListOf<Selection>()
        val writes = mutableListOf<Write>()
        var onSave: (((SettingsResult<Unit>) -> Unit) -> Unit)? = null

        override fun open(userId: Long, active: () -> Boolean): ProfileStickerAvatarPickerTicket =
            ProfileStickerAvatarPickerTicket().also { opens += Open(userId, active, it) }
        override fun close(ticket: ProfileStickerAvatarPickerTicket) { closes += ticket }
        override fun retire() {}
        override fun loadDefault(ticket: ProfileStickerAvatarPickerTicket, result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit) {
            defaultReads += CandidateRead(ticket, result)
        }
        override fun loadInstalledPacks(ticket: ProfileStickerAvatarPickerTicket, kind: ProfileStickerAvatarPackKind,
            result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
        ) { packLists += PackList(ticket, kind, result) }
        override fun openPack(ticket: ProfileStickerAvatarPickerTicket, packTicket: ProfileStickerAvatarPackTicket,
            result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
        ) { packReads += PackRead(ticket, packTicket, result) }
        override fun select(ticket: ProfileStickerAvatarPickerTicket,
            candidateTicket: ProfileStickerAvatarCandidateTicket,
        ): ProfileStickerAvatarSelectionTicket = ProfileStickerAvatarSelectionTicket().also {
            selections += Selection(ticket, candidateTicket, it)
        }
        override fun save(ticket: ProfileStickerAvatarPickerTicket, selectionTicket: ProfileStickerAvatarSelectionTicket,
            expectedPhotoId: Long?, background: ProfileStickerAvatarBackground, active: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit,
        ) {
            writes += Write(ticket, selectionTicket, expectedPhotoId, background, active, result)
            onSave?.invoke(result)
        }
        fun candidatePage() = ProfileStickerAvatarCandidatePage(listOf(ProfileStickerAvatarCandidate(candidate,
            ProfileStickerAvatarIdentity.CustomEmoji(-73), 0, 0, 100, 100, true)), false)
        fun packPage() = ProfileStickerAvatarPackPage(listOf(ProfileStickerAvatarPack(pack, "synthetic", 1,
            ProfileStickerAvatarPackKind.CUSTOM_EMOJI)), false)
    }

    private class FakeExtras(override val stickerAvatar: TdProfileStickerAvatarSession) : TdSettingsProfileExtrasSession {
        val loadCalls = AtomicInteger()
        val loadCallbacks = AtomicInteger()
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) {
            loadCalls.incrementAndGet()
            result(SettingsResult(SettingsProfileExtrasData(userId, 91, null, SettingsCalendarDate(27, 10, 2026))))
            loadCallbacks.incrementAndGet()
        }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            result(SettingsResult(error = SettingsError.UNSUPPORTED))
        }
    }

    private class FakeSettings(override val profileExtras: FakeExtras) : TdSettingsSession {
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) {
            result(SettingsResult(SettingsProfile(43, "Synthetic", "", "", "", "")))
        }
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
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    }

    private companion object {
        fun background(vararg values: Int) = checkNotNull(ProfileStickerAvatarBackground.fromColors(values.toList()))
    }
}
