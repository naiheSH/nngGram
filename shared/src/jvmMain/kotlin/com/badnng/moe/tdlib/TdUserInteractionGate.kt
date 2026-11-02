package com.badnng.moe.tdlib

import com.badnng.moe.settings.LoginEmailSuggestions
import com.badnng.moe.security.LocalLockWitness
import org.drinkless.tdlib.TdApi

/** Synchronous native boundary: a queued actor/UI update cannot postpone a required-email lock. */
class TdUserInteractionGate {
    private val lock = Any()
    private var active = true
    private var suggestions = LoginEmailSuggestions()
    private var satisfiedRequiredEpoch = 0L
    private var emailRevision = 0L
    private var observer: ((Boolean) -> Unit)? = null
    @Volatile private var localWitness: () -> LocalLockWitness = { LocalLockWitness(0, false) }
    private val callbackWitness = ThreadLocal<LocalLockWitness?>()
    private val callbackEmailRevision = ThreadLocal<Long?>()
    private val terminalCallback = ThreadLocal<Boolean>()
    private val indeterminateWriteCallback = ThreadLocal<Boolean>()
    private val settingsMutationContext = ThreadLocal<Boolean>()

    /** The coordinator's witness read is lock-free; do not acquire its worker/owner lock here. */
    internal fun bindLocalWitness(value: () -> LocalLockWitness) = synchronized(lock) { localWitness = value }

    /** Synchronizes against a native dispatch already entering send, then invalidates UI owners. */
    internal fun localWitnessChanged() = synchronized(lock) { observer?.invoke(allowsOrdinary()) }

    internal fun allowsLocal(): Boolean = !localWitness().blocked
    internal fun callbackWitness(): LocalLockWitness? = callbackWitness.get()
    internal fun callbackEmailRevision(): Long? = callbackEmailRevision.get()
    internal fun acceptsEmailRevision(captured: Long?): Boolean = captured == null || synchronized(lock) {
        captured == emailRevision && (!suggestions.requiredPresent || satisfiedRequiredEpoch == suggestions.requiredEpoch)
    }
    /** A closure captured at submission; unlocking never reauthorizes the old operation. */
    internal fun captureSpecialAuthority(): () -> Boolean {
        val witness = callbackWitness.get() ?: localWitness()
        val email = synchronized(lock) { callbackEmailRevision.get() ?: emailRevision }
        return { synchronized(lock) {
            val now = localWitness()
            active && !now.blocked && now == witness && emailRevision == email &&
                (!suggestions.requiredPresent || satisfiedRequiredEpoch == suggestions.requiredEpoch)
        } }
    }
    internal fun callbackIsTerminal(): Boolean = terminalCallback.get() == true
    internal fun callbackWriteIndeterminate(): Boolean = indeterminateWriteCallback.get() == true
    internal fun withSettingsMutation(work: () -> Unit) {
        val previous = settingsMutationContext.get()
        settingsMutationContext.set(true)
        try { work() } finally {
            if (previous == null) settingsMutationContext.remove() else settingsMutationContext.set(previous)
        }
    }
    /** Capture once per page/operation; unlocking cannot revive the captured authority. */
    internal fun captureFolderAuthority(): () -> Boolean = synchronized(lock) {
        val captured = localWitness()
        val email = emailRevision
        val valid: () -> Boolean = { synchronized(lock) { active && !captured.blocked && localWitness() == captured &&
            emailRevision == email && (!suggestions.requiredPresent || satisfiedRequiredEpoch == suggestions.requiredEpoch) } }
        valid
    }

    internal fun callbackContextIsCurrent(): Boolean {
        val local = callbackWitness.get()?.let { val now = localWitness(); !now.blocked && now == it } ?: true
        val email = acceptsEmailRevision(callbackEmailRevision.get())
        return local && email
    }

    internal fun pushCallbackWitness(value: LocalLockWitness?): LocalLockWitness? {
        val previous = callbackWitness.get()
        if (value == null) callbackWitness.remove() else callbackWitness.set(value)
        return previous
    }
    internal fun pushCallbackEmailRevision(value: Long?): Long? {
        val previous = callbackEmailRevision.get()
        if (value == null) callbackEmailRevision.remove() else callbackEmailRevision.set(value)
        return previous
    }
    internal fun emailRequiredBlocked(): Boolean = synchronized(lock) {
        active && suggestions.requiredPresent && satisfiedRequiredEpoch != suggestions.requiredEpoch
    }

    /** Invoked synchronously on native flips. Consumers enqueue work and also inspect allowsOrdinary. */
    fun observe(value: ((Boolean) -> Unit)?) = synchronized(lock) {
        observer = value
        value?.invoke(allowsOrdinary())
    }

    fun allowsOrdinary(): Boolean = synchronized(lock) {
        active && !localWitness().blocked && (!suggestions.requiredPresent || satisfiedRequiredEpoch == suggestions.requiredEpoch)
    }

    /** Only the native READY atomic changes here; callbacks/revocation run after this lock releases. */
    internal fun updateAuthorizationReady(update: () -> Unit) = synchronized(lock) { update() }

    internal fun update(value: LoginEmailSuggestions) = synchronized(lock) {
        val before = allowsOrdinary()
        if (value.revision >= suggestions.revision) {
            if (suggestions.requiredPresent != value.requiredPresent || suggestions.requiredEpoch != value.requiredEpoch) emailRevision++
            suggestions = value
        }
        if (before != allowsOrdinary()) observer?.invoke(allowsOrdinary())
    }

    internal fun satisfyRequired(epoch: Long) = synchronized(lock) {
        val before = allowsOrdinary()
        if (active && suggestions.requiredPresent && suggestions.requiredEpoch == epoch && satisfiedRequiredEpoch != epoch) {
            satisfiedRequiredEpoch = epoch
            emailRevision++
        }
        if (before != allowsOrdinary()) observer?.invoke(allowsOrdinary())
    }

    internal fun retire() = synchronized(lock) {
        val before = allowsOrdinary()
        active = false
        if (before) observer?.invoke(false)
        observer = null
    }

    /** Exact resource relinquishment only. This does not authorize a new read, mutation or download. */
    internal fun allowsNative(request: TdApi.Function<*>): Boolean = synchronized(lock) {
        active && ((request is TdApi.CloseChat || request is TdApi.CancelDownloadFile) ||
            (!localWitness().blocked && (!suggestions.requiredPresent || satisfiedRequiredEpoch == suggestions.requiredEpoch)))
    }

    /** Storage only: false proves native send was not entered. The caller defers callbacks. */
    internal fun dispatchStorage(submit: () -> Boolean): Boolean = synchronized(lock) {
        if (!active || !callbackContextIsCurrent() || localWitness().blocked ||
            (suggestions.requiredPresent && satisfiedRequiredEpoch != suggestions.requiredEpoch)) false
        else submit()
    }

    /** Storage admission and READY change share the same lock; synchronous fakes deliver only after release. */
    internal fun dispatchStorageRequest(request: TdApi.Function<*>, authorized: () -> Boolean,
        send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
        result: (TdApi.Object) -> Unit, failed: () -> Unit,
    ): Boolean {
        val witness = callbackWitness.get() ?: localWitness()
        val deferred = ArrayList<() -> Unit>()
        var submitting = true
        fun deliver(work: () -> Unit) {
            val immediate = synchronized(lock) { if (submitting) { deferred.add(work); false } else true }
            if (immediate) work()
        }
        val accepted = synchronized(lock) {
            val now = localWitness()
            val emailAtAdmission = callbackEmailRevision.get() ?: emailRevision
            if (!active || !callbackContextIsCurrent() || now.blocked || now.epoch != witness.epoch ||
                (suggestions.requiredPresent && satisfiedRequiredEpoch != suggestions.requiredEpoch) || !authorized()) false
            else {
                // Native termination and permission to publish are independent. The
                // receipt always drains its original physical slot, even after lock
                // or retirement. Only its original admission may publish a value.
                val context = SettingsStorageCallbackContext(
                    current = { synchronized(lock) {
                        val current = localWitness()
                        active && !current.blocked && current.epoch == witness.epoch &&
                            emailRevision == emailAtAdmission &&
                            (!suggestions.requiredPresent || satisfiedRequiredEpoch == suggestions.requiredEpoch) && authorized()
                    } },
                    deliver = { work -> withCallbackWitness(witness, email = emailAtAdmission, work = work) },
                    resume = ::withoutCallbackContext,
                )
                fun receipt(work: () -> Unit) = withoutCallbackContext {
                    SettingsStorageCallbackContext.withContext(context, work)
                }
                try {
                    send(request, { response -> deliver {
                        receipt { result(response) }
                    } }, { deliver { receipt(failed) } })
                } catch (_: Throwable) {
                    // Submission may already have occurred. Report uncertainty with
                    // the original context after releasing dispatch locks; a real
                    // response queued first still wins the coordinator's terminal.
                    deferred.add { receipt(failed) }
                }
                finally { submitting = false }
                true
            }
        }
        deferred.forEach { it() }
        return accepted
    }

    /** A new queued storage request must obtain its own admission, never the old receipt's TLS. */
    internal fun withoutCallbackContext(work: () -> Unit) {
        val witness = callbackWitness.get()
        val email = callbackEmailRevision.get()
        val terminal = terminalCallback.get()
        val indeterminate = indeterminateWriteCallback.get()
        val mutation = settingsMutationContext.get()
        callbackWitness.remove()
        callbackEmailRevision.remove()
        terminalCallback.remove()
        indeterminateWriteCallback.remove()
        settingsMutationContext.remove()
        try {
            SettingsStorageCallbackContext.without {
                FolderMutationTerminal.without { SettingsSpecialMutationContext.without(work) }
            }
        } finally {
            if (witness == null) callbackWitness.remove() else callbackWitness.set(witness)
            if (email == null) callbackEmailRevision.remove() else callbackEmailRevision.set(email)
            if (terminal == null) terminalCallback.remove() else terminalCallback.set(terminal)
            if (indeterminate == null) indeterminateWriteCallback.remove() else indeterminateWriteCallback.set(indeterminate)
            if (mutation == null) settingsMutationContext.remove() else settingsMutationContext.set(mutation)
        }
    }

    /** Local protection also applies to login and mandatory-email traffic, which bypass ordinary email policy. */
    internal fun guardLocal(send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit):
        (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit = protectedSend(send, ordinary = false)

    internal fun guard(send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit):
        (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit = protectedSend(send, ordinary = true)

    private fun protectedSend(send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit, ordinary: Boolean):
        (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit = { request, result, failed ->
        val cleanup = request is TdApi.CloseChat || request is TdApi.CancelDownloadFile
        // Resource acquisition must settle its original owner even after admission expires;
        // otherwise an accepted download/open can no longer be relinquished by that owner.
        val resourceTerminal = ordinary && (request is TdApi.OpenChat || request is TdApi.DownloadFile)
        // These are already-submitted message mutations. Their real result must reconcile
        // the original request even if the local gate closes before the result arrives.
        val messageTerminal = ordinary && (request is TdApi.SendMessage || request is TdApi.ResendMessages ||
            request is TdApi.EditMessageText || request is TdApi.EditMessageCaption ||
            request is TdApi.ForwardMessages || request is TdApi.DeleteMessages)
        // Only the concrete commands used by SettingsController.mutate are admitted
        // for internal settlement. Other settings controllers retain their own owners.
        val settingsMutation = settingsMutationContext.get() == true
        val settingsTerminal = ordinary && settingsMutation && (request is TdApi.SetName || request is TdApi.SetUsername ||
            request is TdApi.SetBio || request is TdApi.SetScopeNotificationSettings ||
            request is TdApi.SetUserPrivacySettingRules || request is TdApi.SetAccountTtl ||
            request is TdApi.TerminateSession || request is TdApi.SetMessageSenderBlockList ||
            request is TdApi.AddProxy || request is TdApi.EnableProxy || request is TdApi.DisableProxy ||
            request is TdApi.RemoveProxy)
        val folderMutation = FolderMutationTerminal.active()
        val folderTerminal = ordinary && folderMutation && (request is TdApi.CreateChatFolder ||
            request is TdApi.DeleteChatFolder || request is TdApi.ReorderChatFolders ||
            request is TdApi.ToggleChatFolderTags || request is TdApi.EditChatFolder)
        val specialScope = SettingsSpecialMutationContext.scopeFor(request)
        val specialTerminal = specialScope != null
        // Configured-account writes can traverse the login transport. Exact marked writes
        // still obey ordinary account policy; unmarked login/mandatory traffic keeps its policy.
        val ordinaryPolicy = ordinary || specialTerminal
        val terminal = resourceTerminal || messageTerminal || settingsTerminal || folderTerminal || specialTerminal
        val witness = callbackWitness.get() ?: localWitness()
        val deferred = ArrayList<() -> Unit>()
        var submitting = true
        var submissionError: Throwable? = null
        fun deliver(work: () -> Unit) {
            val immediate = synchronized(lock) {
                if (submitting) { deferred.add(work); false } else true
            }
            if (immediate) work()
        }
        val rejected = synchronized(lock) {
            val now = localWitness()
            val emailAtAdmission = callbackEmailRevision.get() ?: emailRevision
            if (!active || (!cleanup && (!callbackContextIsCurrent() || now.blocked || now.epoch != witness.epoch ||
                    (ordinaryPolicy && suggestions.requiredPresent && satisfiedRequiredEpoch != suggestions.requiredEpoch)))) true
            else {
                // Client.send is synchronous submission; lockNow waits only for this admission, not disk/KDF work.
                try {
                    send(request, { response -> deliver {
                        val valid = synchronized(lock) {
                            val current = localWitness()
                            cleanup || (active && !current.blocked && current.epoch == witness.epoch &&
                                (!ordinaryPolicy || (emailRevision == emailAtAdmission &&
                                    (!suggestions.requiredPresent || satisfiedRequiredEpoch == suggestions.requiredEpoch))))
                        }
                        // A completed mutation cannot be relabelled as failed merely because
                        // the local gate closed: its original owner needs the true receipt.
                        // The old email revision remains in its context and cannot admit a new request.
                        val reconcile = folderTerminal || (terminal && synchronized(lock) { active })
                        withCallbackWitness(witness, terminal = terminal && (valid || reconcile),
                            email = emailAtAdmission.takeIf { ordinaryPolicy }, settingsMutation = settingsMutation,
                            folderMutation = folderMutation,
                            folderReceipt = when {
                                !folderTerminal -> FolderMutationReceipt.NOT_DISPATCHED
                                response is TdApi.Error -> FolderMutationReceipt.ERROR_RESPONSE
                                else -> FolderMutationReceipt.RESPONSE
                            }) {
                            if (specialTerminal) SettingsSpecialMutationContext.withReceipt(when (response) {
                                is TdApi.Ok -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED)
                                is TdApi.Error -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.REJECTED, settingsError(response))
                                else -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE)
                            }) { result(response) }
                            else if (valid || reconcile) result(response) else failed()
                        }
                    } }, {
                        // Capture before deferral: an inner gate may prove raw native send was not entered.
                        val inheritedFailure = if (specialTerminal) SettingsSpecialMutationContext.dispatchFailureFor(checkNotNull(specialScope)) else null
                        deliver { withCallbackWitness(witness, terminal = settingsTerminal || folderTerminal,
                        email = emailAtAdmission.takeIf { ordinaryPolicy }, indeterminateWrite = settingsTerminal,
                        settingsMutation = settingsMutation, folderMutation = folderMutation,
                        folderReceipt = if (folderTerminal) FolderMutationReceipt.UNKNOWN else FolderMutationReceipt.NOT_DISPATCHED) {
                            if (specialTerminal) SettingsSpecialMutationContext.withDispatchFailure(checkNotNull(specialScope),
                                inheritedFailure ?: SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE), failed)
                            else failed()
                        } }
                    })
                } catch (error: Throwable) { submissionError = error }
                finally { submitting = false }
                false
            }
        }
        if (rejected) withCallbackWitness(witness, email = callbackEmailRevision.get(),
            settingsMutation = settingsMutation, folderMutation = folderMutation) {
                if (specialTerminal) SettingsSpecialMutationContext.withDispatchFailure(checkNotNull(specialScope),
                    SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.NOT_DISPATCHED), failed)
                else failed()
            }
        else {
            deferred.forEach { it() }
            submissionError?.let { error ->
                if (specialTerminal) withCallbackWitness(witness,
                    email = callbackEmailRevision.get()) {
                    SettingsSpecialMutationContext.withDispatchFailure(checkNotNull(specialScope),
                        SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE), failed)
                }
                else if (settingsTerminal || folderTerminal) withCallbackWitness(witness, terminal = true,
                    email = callbackEmailRevision.get(), indeterminateWrite = true, settingsMutation = settingsMutation,
                    folderMutation = folderMutation,
                    folderReceipt = if (folderTerminal) FolderMutationReceipt.UNKNOWN else FolderMutationReceipt.NOT_DISPATCHED,
                    work = failed)
                else throw error
            }
        }
    }

    private fun withCallbackWitness(witness: LocalLockWitness, terminal: Boolean = false, email: Long? = null,
        indeterminateWrite: Boolean = false, settingsMutation: Boolean = false,
        folderMutation: Boolean = false, folderReceipt: FolderMutationReceipt = FolderMutationReceipt.NOT_DISPATCHED,
        work: () -> Unit) {
        val previous = callbackWitness.get()
        val previousEmail = callbackEmailRevision.get()
        val previousTerminal = terminalCallback.get()
        val previousIndeterminate = indeterminateWriteCallback.get()
        val previousMutation = settingsMutationContext.get()
        callbackWitness.set(witness)
        if (email == null) callbackEmailRevision.remove() else callbackEmailRevision.set(email)
        terminalCallback.set(terminal)
        indeterminateWriteCallback.set(indeterminateWrite)
        settingsMutationContext.set(settingsMutation)
        try { FolderMutationTerminal.scoped(folderMutation, folderReceipt, work) } finally {
            if (previous == null) callbackWitness.remove() else callbackWitness.set(previous)
            if (previousEmail == null) callbackEmailRevision.remove() else callbackEmailRevision.set(previousEmail)
            if (previousTerminal == null) terminalCallback.remove() else terminalCallback.set(previousTerminal)
            if (previousIndeterminate == null) indeterminateWriteCallback.remove() else indeterminateWriteCallback.set(previousIndeterminate)
            if (previousMutation == null) settingsMutationContext.remove() else settingsMutationContext.set(previousMutation)
        }
    }
}
