package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** One generation's cache. Only actual native updates establish or revise its immutable value. */
internal class OfficialTdReactionNotifications(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdReactionNotificationsSession {
    private val alive = AtomicBoolean(true)
    private val lock = Any()
    private var latest = ReactionNotificationNativeState()
    private var observer: ((ReactionNotificationNativeState) -> Unit)? = null
    private var mutation: Attempt? = null

    override fun state(): ReactionNotificationNativeState = synchronized(lock) { latest }

    override fun observe(observer: ((ReactionNotificationNativeState) -> Unit)?) {
        synchronized(lock) { if (alive.get()) this.observer = observer }
    }

    override fun retire() {
        synchronized(lock) {
            alive.set(false)
            observer = null
            // An already dispatched write still owns its eventual local-acceptance result.
        }
    }

    fun accept(update: TdApi.Object) {
        if (update !is TdApi.UpdateReactionNotificationSettings || !alive.get()) return
        val value = decode(update.notificationSettings)
        val notification = synchronized(lock) {
            if (!alive.get()) return
            latest = ReactionNotificationNativeState(
                value = value ?: latest.value,
                revision = latest.revision + 1,
                error = if (value == null) SettingsError.INVALID_VALUE else null,
            )
            observer to latest
        }
        // Foreign code may reenter or wait for another thread using this session.
        if (alive.get()) runCatching { notification.first?.invoke(notification.second) }
    }

    override fun save(
        expected: ReactionNotificationNativeState,
        patch: ReactionNotificationPatch,
        active: () -> Boolean,
        result: (ReactionNotificationSaveResult) -> Unit,
    ) {
        if (!current(active)) {
            result(ReactionNotificationSaveResult(actual = state(), error = SettingsError.NOT_READY))
            return
        }
        var rejected: ReactionNotificationSaveResult? = null
        val attempt = synchronized(lock) {
            val error = validationError(expected, patch)
            val desired = latest.value?.let(patch::applyTo)
            when {
                error != null -> {
                    rejected = ReactionNotificationSaveResult(desired = desired, actual = latest, error = error)
                    null
                }
                desired == latest.value -> {
                    rejected = ReactionNotificationSaveResult(desired = desired, actual = latest)
                    null
                }
                else -> Attempt(requireNotNull(desired)).also { mutation = it }
            }
        }
        if (attempt == null) {
            result(requireNotNull(rejected))
            return
        }

        // Build fresh Java objects; never lend mutable update objects back to TDLib.
        val command = TdApi.SetReactionNotificationSettings(encode(attempt.desired))
        val ownerActive = current(active)
        val failure = synchronized(lock) {
            when {
                !ownerActive || !alive.get() -> SettingsError.NOT_READY
                latest.error != null -> latest.error
                latest != expected -> SettingsError.CONFLICT
                else -> null
            }
        }
        if (failure != null) {
            complete(attempt, null, failure, active, result)
            return
        }
        try {
            SettingsSpecialMutationContext.write(command, send, { response -> complete(attempt, response, null, active, result) }, {
                complete(attempt, null, SettingsError.REQUEST_FAILED, active, result)
            })
        } catch (_: Exception) {
            complete(attempt, null, SettingsError.REQUEST_FAILED, active, result)
        }
    }

    /** Called only under [lock]; foreign ownership predicates are evaluated by the caller. */
    private fun validationError(
        expected: ReactionNotificationNativeState,
        patch: ReactionNotificationPatch,
    ): SettingsError? = when {
        !alive.get() || latest.value == null -> SettingsError.NOT_READY
        latest.error != null -> latest.error
        latest != expected -> SettingsError.CONFLICT
        mutation != null -> SettingsError.NOT_READY
        patch.isEmpty -> SettingsError.INVALID_VALUE
        else -> null
    }

    private fun complete(
        attempt: Attempt,
        response: TdApi.Object?,
        failure: SettingsError?,
        active: () -> Boolean,
        result: (ReactionNotificationSaveResult) -> Unit,
    ) {
        val uncertain = SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE
        if (uncertain) {
            if (attempt.finished.get()) return
            result(ReactionNotificationSaveResult(desired = attempt.desired, error = SettingsError.NOT_READY))
            return
        }
        if (!attempt.finished.compareAndSet(false, true)) return
        val ownerActive = current(active)
        val actual = synchronized(lock) {
            if (mutation === attempt) mutation = null
            latest
        }
        val accepted = response is TdApi.Ok
        result(ReactionNotificationSaveResult(
            locallyAccepted = accepted,
            desired = attempt.desired,
            actual = actual,
            error = when {
                !ownerActive -> SettingsError.NOT_READY
                accepted -> actual.error
                response != null -> settingsError(response)
                SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.NOT_DISPATCHED -> SettingsError.NOT_READY
                failure != null -> failure
                else -> SettingsError.REQUEST_FAILED
            },
        ))
    }

    private fun current(active: () -> Boolean): Boolean =
        alive.get() && runCatching(active).getOrDefault(false) && alive.get()

    private class Attempt(val desired: ReactionNotificationValue) {
        val finished = AtomicBoolean()
    }

    private fun decode(value: TdApi.ReactionNotificationSettings?): ReactionNotificationValue? {
        value ?: return null
        return ReactionNotificationValue(
            message = decode(value.messageReactionSource) ?: return null,
            story = decode(value.storyReactionSource) ?: return null,
            poll = decode(value.pollVoteSource) ?: return null,
            soundId = value.soundId,
            showSender = value.showPreview,
        )
    }

    private fun decode(source: TdApi.ReactionNotificationSource?): ReactionNotificationSource? = when (source) {
        is TdApi.ReactionNotificationSourceNone -> ReactionNotificationSource.NONE
        is TdApi.ReactionNotificationSourceContacts -> ReactionNotificationSource.CONTACTS
        is TdApi.ReactionNotificationSourceAll -> ReactionNotificationSource.ALL
        else -> null
    }

    private fun encode(value: ReactionNotificationValue) = TdApi.ReactionNotificationSettings(
        encode(value.message), encode(value.story), encode(value.poll), value.soundId, value.showSender,
    )

    private fun encode(source: ReactionNotificationSource): TdApi.ReactionNotificationSource = when (source) {
        ReactionNotificationSource.NONE -> TdApi.ReactionNotificationSourceNone()
        ReactionNotificationSource.CONTACTS -> TdApi.ReactionNotificationSourceContacts()
        ReactionNotificationSource.ALL -> TdApi.ReactionNotificationSourceAll()
    }
}
