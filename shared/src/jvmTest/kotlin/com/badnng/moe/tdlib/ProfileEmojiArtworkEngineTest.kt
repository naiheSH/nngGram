package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.media.*
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

/** DRAFT: actual Engine actor, synthetic session/source protocol, no codec, JNI, account or audio. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEmojiArtworkEngineTest {
    @Test fun waitsForNativeReadyAndCorrelatedGetMeInsteadOfSelectingAnUpdateUser() = runTest {
        val f = fixture()
        f.engine.start(); runCurrent()
        val native = f.transport.sessions.single()
        native.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
        assertNull(f.engine.profileEmojiArtwork.value)
        native.auth(AuthorizationPhase.READY); runCurrent()
        assertNotNull(native.chats.me)
        native.update(ChatEvent.SenderName(77, "Synthetic other", profile = AccountProfile(77, "Synthetic other")))
        runCurrent()
        assertNull(f.engine.state.value.chats.account)
        assertNull(f.engine.profileEmojiArtwork.value)
        assertTrue(f.sources.isEmpty())
        native.chats.me!!.invoke(ChatResult(AccountProfile(43, "Synthetic self"))); runCurrent()
        assertEquals(43L, f.sources.single().owner.currentAccountUserId())
        assertSame(f.sources.single(), f.engine.profileEmojiArtwork.value)
        assertEquals(0, native.colors.reads)
        assertTrue(f.engine.close())
    }

    @Test fun providerInstallationBorrowsTheOwningNativeFacadesAndDoesNotEagerlyReadArtwork() = runTest {
        val f = fixture(); ready(f)
        val native = f.transport.sessions.single()
        val source = f.sources.single()
        assertSame(native.colors, source.owner.catalog)
        assertSame(native.files, source.owner.files)
        assertEquals(f.engine.state.value.generation, source.generation)
        assertTrue(source.owner.outputAllowed())
        assertEquals(0, native.colors.reads)
        assertEquals(0, native.files.reads)
        assertTrue(f.engine.close())
    }

    @Test fun liveRequestRunsOnlyWhenTheActualActorAdmitsIt() = runTest {
        val f = fixture(); ready(f)
        val source = f.sources.single()
        source.request()
        assertEquals(0, f.transport.sessions.single().colors.reads)
        runCurrent()
        assertEquals(1, f.transport.sessions.single().colors.reads)
        assertEquals(0, source.discarded)
        assertTrue(f.engine.close())
    }

    @Test fun queuedRequestLosingNativeReadyIsDiscardedBeforeTheOlderReadySnapshotCanAuthorizeRpc() = runTest {
        val f = fixture(); ready(f)
        val native = f.transport.sessions.single()
        val old = f.sources.single()
        old.request()
        native.auth(AuthorizationPhase.OTHER_STEP)
        assertEquals(AuthorizationPhase.READY, f.engine.state.value.status.authorization)
        assertFalse(old.owner.outputAllowed())
        old.request() // Public admission also rejects immediately.
        assertEquals(1, old.discarded)
        runCurrent()
        assertEquals(2, old.discarded)
        assertEquals(0, native.colors.reads)
        assertEquals(1, old.closed)
        assertNull(f.engine.profileEmojiArtwork.value)
        native.auth(AuthorizationPhase.READY); runCurrent()
        val fresh = f.sources.last()
        assertNotSame(old, fresh)
        assertFalse(old.owner.outputAllowed())
        fresh.request(); runCurrent()
        assertEquals(1, native.colors.reads)
        assertTrue(f.engine.close())
    }

    @Test fun mandatoryLockDiscardsQueuedWorkAndUnlockCreatesAFreshSourceWithoutRevivingOldOwners() = runTest {
        val f = fixture(); ready(f)
        val native = f.transport.sessions.single()
        val old = f.sources.single()
        old.request()
        native.interactionGate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
        assertTrue(native.ready)
        assertFalse(old.owner.outputAllowed())
        runCurrent()
        assertNull(f.engine.profileEmojiArtwork.value)
        assertEquals(1, old.discarded)
        assertEquals(1, old.closed)
        assertEquals(0, native.colors.reads)
        native.interactionGate.update(LoginEmailSuggestions(2)); runCurrent()
        val fresh = f.sources.last()
        assertNotSame(old, fresh)
        old.request(); fresh.request(); runCurrent()
        assertFalse(old.owner.outputAllowed())
        assertEquals(2, old.discarded)
        assertEquals(1, native.colors.reads)
        assertTrue(f.engine.close())
    }

    @Test fun rapidLockAndUnlockBeforeActorDrainStillRetiresTheOldSourceEpoch() = runTest {
        val f = fixture(); ready(f)
        val native = f.transport.sessions.single()
        val old = f.sources.single()
        old.request()
        native.interactionGate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
        native.interactionGate.update(LoginEmailSuggestions(2))
        assertTrue(native.interactionGate.allowsOrdinary())
        assertFalse(old.owner.outputAllowed())
        runCurrent()
        assertEquals(1, old.closed)
        assertEquals(1, old.discarded)
        assertNotSame(old, f.engine.profileEmojiArtwork.value)
        assertEquals(0, native.colors.reads)
        assertTrue(f.engine.close())
    }

    @Test fun stopRevisionDiscardsQueuedWorkAndClosesSourceBeforeBorrowedResourcesRetire() = runTest {
        val f = fixture(); ready(f)
        val native = f.transport.sessions.single()
        val old = f.sources.single()
        old.request()
        val stopped = async(start = CoroutineStart.UNDISPATCHED) { f.engine.stop() }
        runCurrent()
        assertTrue(stopped.await())
        assertEquals(1, old.discarded)
        assertEquals(1, old.closed)
        assertFalse(old.owner.outputAllowed())
        assertNull(f.engine.profileEmojiArtwork.value)
        assertEquals(0, native.colors.reads)
        val closedAt = f.events.indexOf("source-close")
        assertTrue(closedAt >= 0)
        assertTrue(closedAt < f.events.indexOf("files-retire"))
        assertTrue(closedAt < f.events.indexOf("broker-retire"))
        assertTrue(closedAt < f.events.indexOf("catalog-retire"))
        assertTrue(f.engine.close())
    }

    @Test fun oldGenerationAndItsLateNativeCallbackCannotAuthorizeWorkOrInvalidateANewProvider() = runTest {
        val f = fixture(); ready(f)
        val first = f.transport.sessions.single()
        val old = f.sources.single()
        assertTrue(f.engine.stop())
        ready(f)
        val second = f.transport.sessions.last()
        val fresh = f.sources.last()
        assertNotEquals(old.generation, fresh.generation)
        first.auth(AuthorizationPhase.OTHER_STEP)
        old.request(); fresh.request(); runCurrent()
        assertSame(fresh, f.engine.profileEmojiArtwork.value)
        assertTrue(fresh.owner.outputAllowed())
        assertFalse(old.owner.outputAllowed())
        assertEquals(1, old.discarded)
        assertEquals(0, first.colors.reads)
        assertEquals(1, second.colors.reads)
        assertTrue(f.engine.close())
    }

    @Test fun synchronousNativeReadyLossWithoutAnActorEventStillRejectsPublicAdmission() = runTest {
        val f = fixture(); ready(f)
        val native = f.transport.sessions.single()
        val source = f.sources.single()
        native.ready = false
        assertEquals(AuthorizationPhase.READY, f.engine.state.value.status.authorization)
        source.request(); runCurrent()
        assertEquals(1, source.discarded)
        assertEquals(0, native.colors.reads)
        assertTrue(f.engine.close())
    }

    @Test fun unconfirmedSelfFailureNeverInstallsSourceOrDownloadsFiles() = runTest {
        val f = fixture()
        f.engine.start(); runCurrent()
        val native = f.transport.sessions.single()
        native.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
        native.auth(AuthorizationPhase.READY); runCurrent()
        native.chats.me!!.invoke(ChatResult(error = ChatError.REQUEST_FAILED)); runCurrent()
        assertNull(f.engine.state.value.chats.account)
        assertNull(f.engine.profileEmojiArtwork.value)
        assertTrue(f.sources.isEmpty())
        assertEquals(0, native.colors.reads)
        assertEquals(0, native.files.reads)
        assertTrue(f.engine.close())
    }

    private fun TestScope.fixture(): Fixture {
        val root = Files.createTempDirectory("profile-artwork-engine-draft-")
        val configuration = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        Files.writeString(configuration.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n")
        val events = mutableListOf<String>()
        val transport = Transport(events)
        val sources = mutableListOf<Source>()
        val engine = syntheticOpenEngine(configuration, transport, StandardTestDispatcher(testScheduler))
        engine.profileEmojiArtworkFactory = { Source(it, events).also(sources::add) }
        return Fixture(engine, transport, sources, events)
    }

    private fun TestScope.ready(f: Fixture) {
        assertTrue(f.engine.start()); runCurrent()
        val native = f.transport.sessions.last()
        native.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
        native.auth(AuthorizationPhase.READY); runCurrent()
        native.chats.me!!.invoke(ChatResult(AccountProfile(43, "Synthetic self"))); runCurrent()
        assertNotNull(f.engine.profileEmojiArtwork.value)
    }

    private data class Fixture(val engine: TdEngine, val transport: Transport,
        val sources: MutableList<Source>, val events: MutableList<String>)

    /** The fake does not gate native work itself: admission is exclusively the real Engine's responsibility. */
    private class Source(val owner: ProfileEmojiArtworkEnvironment, private val events: MutableList<String>) : ProfileEmojiArtworkProvider {
        override val generation get() = owner.generation
        var closed = 0
        var discarded = 0
        fun request() {
            acquire(ProfileEmojiArtworkToken(generation, owner.currentAccountUserId(), 1, 1, -71), null,
                ProfileEmojiArtworkRequest(ProfileEmojiArtworkRole.OPTION_THUMBNAIL, 48), { true }) {}
        }
        override fun acquire(token: ProfileEmojiArtworkToken, expected: SettingsBackgroundEmoji?, request: ProfileEmojiArtworkRequest,
            active: () -> Boolean, result: (SettingsResult<ProfileEmojiArtwork>) -> Unit): ProfileEmojiArtworkLease {
            owner.admit({ owner.catalog.resolveEmojis(listOf(-71), { true }) {} }, { discarded++ })
            return object : ProfileEmojiArtworkLease { override fun close() {} }
        }
        override fun close() { closed++; events += "source-close" }
    }

    private class Transport(private val events: MutableList<String>) : TdTransport {
        val sessions = mutableListOf<Session>()
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit): TdSession =
            Session(onAuthorization, onChatEvent, events).also(sessions::add)
    }

    private class Session(private val authorization: (AuthorizationDetails) -> Unit,
        val update: (ChatEvent) -> Unit, private val events: MutableList<String>) : SyntheticOpenKeySession {
        var ready = false
        val colors = Colors(events)
        val files = EmojiFiles(events)
        override val chats = Chats()
        override val settings: TdSettingsSession = Settings(colors)
        override val profileEmojiFiles: TdProfileEmojiFilesSession = files
        override val interactionGate = TdUserInteractionGate()
        override fun authorizationReady() = ready
        fun auth(phase: AuthorizationPhase) { ready = phase == AuthorizationPhase.READY; authorization(AuthorizationDetails(phase)) }
        override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) = result(null)
        override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) = result(null)
        override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login in fixtures")
        override fun retireDownloads() { files.retire(); events += "broker-retire" }
        override fun close() = auth(AuthorizationPhase.CLOSED)
    }

    private class Chats : TdChatSession {
        var me: ((ChatResult<AccountProfile>) -> Unit)? = null
        override fun getMe(result: (ChatResult<AccountProfile>) -> Unit) { me = result }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(endReached = true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) = error("No history")
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = error("No sends")
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = error("No retries")
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = error("No reads")
    }

    private class EmojiFiles(private val events: MutableList<String>) : TdProfileEmojiFilesSession {
        var reads = 0
        override fun read(token: ProfileEmojiResourceToken, expected: SettingsBackgroundEmoji, role: ProfileEmojiFileRole,
            maximumBytes: Int, active: () -> Boolean, result: (SettingsResult<ProfileEmojiEncoded>) -> Unit): ProfileEmojiFileLease {
            reads++
            return object : ProfileEmojiFileLease { override fun close() {} }
        }
        override fun retire() { events += "files-retire" }
    }

    private class Colors(private val events: MutableList<String>) : TdSettingsProfileColorsSession {
        var reads = 0
        override fun observe(observer: (() -> Unit)?) {}
        override fun catalog() = SettingsProfileColorsCatalog()
        override fun load(userId: Long, active: () -> Boolean, result: (SettingsResult<SettingsProfileColorsData>) -> Unit) { reads++ }
        override fun save(expected: SettingsProfileColorsData, part: SettingsProfileColorPart, desired: SettingsProfileColorValue,
            replaceGift: Boolean, active: () -> Boolean, result: (SettingsProfileColorMutationResult) -> Unit) = error("No mutation")
        override fun backgroundEmojis(active: () -> Boolean, result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit) { reads++ }
        override fun resolveEmojis(ids: List<Long>, active: () -> Boolean, result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit) { reads++ }
        override fun retire() { events += "catalog-retire" }
    }

    private class Settings(override val profileColors: Colors) : TdSettingsSession {
        override fun retire() = profileColors.retire()
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) = unsupported(result)
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
}
