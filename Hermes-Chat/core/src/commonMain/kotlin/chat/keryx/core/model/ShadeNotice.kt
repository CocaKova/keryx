package chat.keryx.core.model

import chat.keryx.core.i18n.CoreWords
import chat.keryx.core.i18n.Phrase

/**
 * What a pending request looks like FROM THE NOTIFICATION SHADE — the pure half of G16's
 * "respond without opening the app". The Android layer renders a [ShadeNotice] verbatim;
 * everything that requires judgment (which buttons, which requests are shade-safe, how long
 * the notice stays honest) is decided here where it can be unit-tested.
 *
 * Three hard rules encoded below:
 *  - **The locked phone cannot say yes (2.16).** Anyone holding a locked phone could approve a
 *    guarded command from the shade. Every button that lets a command run asks for the unlock
 *    first ([ShadeAction.requiresUnlock]); Deny stays one tap, because refusing is always safe.
 *    The lock screen also gets [ShadeNotice.lockScreenBody] in place of the command itself.
 *  - **Credentials never ride the shade.** Sudo/secret answers are passwords; a lock-screen
 *    RemoteInput would echo them into the most shoulder-surfable surface the OS has. Those
 *    notices are tap-through only — the in-app card (with its masked field) owns the answer.
 *  - **A notice must die honestly.** Gateway approvals fail closed at `approvals.timeout`
 *    (300s stock) and blocking requests announce their own `.expire`; a button that outlives
 *    the wait would resolve nothing server-side, so every notice carries the lifetime after
 *    which the shade should let it go.
 */
data class ShadeNotice(
    val title: String,
    val body: String,
    /** Ordered one-tap buttons; empty when the notice is tap-through only. */
    val actions: List<ShadeAction>,
    /** Offer a free-text inline reply (clarify without choices). Never true for credentials. */
    val freeTextReply: Boolean,
    /** Milliseconds the notice stays valid; the shade should drop it after this. */
    val timeoutMs: Long,
    /** What a secure lock screen shows instead of [body]: the request's kind, never its
     *  content (the command line, the question, the variable name). */
    val lockScreenBody: String = CoreWords.t(Phrase.SHADE_UNLOCK_TO_SEE),
)

/**
 * One shade button: [label] for the human, [wireValue] for the gateway. [requiresUnlock] means
 * the system asks for the device credential before the tap goes through (Android 12+); an
 * approving button sets it, a refusing one never does.
 */
data class ShadeAction(val label: String, val wireValue: String, val requiresUnlock: Boolean = false)

/** A session's pending shade-answerable state — at most one notice per session keeps the
 *  shade legible. A blocking request outranks an approval when both are somehow live: it is
 *  the harder barrier (the whole dispatch is stopped, not just one tool). */
data class ShadePendingEntry(
    val approval: ApprovalRequest? = null,
    val blocking: BlockingRequest? = null,
) {
    val isEmpty: Boolean get() = approval == null && blocking == null
}

object ShadeNotices {

    /** Stock `approvals.timeout` — the gateway fails closed after this. A custom config can
     *  lengthen it server-side; the shade stays conservative so a button never outlives the
     *  wait it answers. */
    const val APPROVAL_TIMEOUT_MS: Long = 300_000

    /** Stock `agent.clarify_timeout` (600s); `.expire` clears earlier when the server says so. */
    const val BLOCKING_TIMEOUT_MS: Long = 600_000

    /** Android renders at most 3 action buttons on a notification. */
    const val MAX_ACTIONS: Int = 3

    fun forEntry(entry: ShadePendingEntry): ShadeNotice? = when {
        entry.blocking != null -> forBlocking(entry.blocking)
        entry.approval != null -> forApproval(entry.approval)
        else -> null
    }

    /** Approval buttons come from the gateway's own [ApprovalRequest.choices] (subsets of
     *  once/session/always/deny). "Approve" and "Deny" are the essentials; the third slot
     *  goes to the strongest remaining scope (always > session — "always" is the one worth
     *  a lock-screen shortcut; "session" is a power move better made from the in-app card). */
    fun forApproval(req: ApprovalRequest): ShadeNotice {
        val actions = buildList {
            if ("once" in req.choices) add(ShadeAction(CoreWords.t(Phrase.SHADE_APPROVE), "once", requiresUnlock = true))
            if ("always" in req.choices) add(ShadeAction(CoreWords.t(Phrase.SHADE_ALWAYS), "always", requiresUnlock = true))
            else if ("session" in req.choices) add(ShadeAction(CoreWords.t(Phrase.SHADE_THIS_SESSION), "session", requiresUnlock = true))
            if ("deny" in req.choices) add(ShadeAction(CoreWords.t(Phrase.SHADE_DENY), "deny"))
        }.take(MAX_ACTIONS)
        return ShadeNotice(
            title = CoreWords.t(Phrase.SHADE_APPROVAL_TITLE),
            body = req.description.ifBlank { req.command }.ifBlank { CoreWords.t(Phrase.SHADE_APPROVAL_BODY) },
            actions = actions,
            freeTextReply = false,
            timeoutMs = APPROVAL_TIMEOUT_MS,
            lockScreenBody = CoreWords.t(Phrase.SHADE_APPROVAL_LOCKED),
        )
    }

    fun forBlocking(req: BlockingRequest): ShadeNotice = when (req.kind) {
        BlockingKind.CLARIFY -> {
            // Multi-select needs checkboxes the shade doesn't have; >3 choices don't fit.
            // Both fall through to tap-only — never silently truncate the answer space.
            val asButtons = req.choices.isNotEmpty() &&
                !req.multiSelect &&
                req.choices.size <= MAX_ACTIONS
            ShadeNotice(
                title = CoreWords.t(Phrase.SHADE_ASKS_TITLE),
                body = req.prompt.ifBlank { CoreWords.t(Phrase.SHADE_ASKS_BODY) },
                actions = if (asButtons) req.choices.map { ShadeAction(it, it) } else emptyList(),
                // Free text only when the question IS free text; a choice question answered
                // off-list from the shade is more likely a typo than an intent.
                freeTextReply = req.choices.isEmpty(),
                timeoutMs = BLOCKING_TIMEOUT_MS,
                lockScreenBody = CoreWords.t(Phrase.SHADE_ASKS_LOCKED),
            )
        }
        BlockingKind.SUDO -> ShadeNotice(
            title = CoreWords.t(Phrase.SHADE_SUDO_TITLE),
            body = CoreWords.t(Phrase.SHADE_SUDO_BODY),
            actions = emptyList(),
            freeTextReply = false,
            timeoutMs = BLOCKING_TIMEOUT_MS,
            lockScreenBody = CoreWords.t(Phrase.SHADE_SUDO_LOCKED),
        )
        BlockingKind.SECRET -> ShadeNotice(
            title = CoreWords.t(Phrase.SHADE_SECRET_TITLE),
            body = CoreWords.t(
                Phrase.SHADE_SECRET_BODY,
                (if (req.envVar.isNotBlank()) "${req.envVar}: " else "") +
                    req.prompt.ifBlank { CoreWords.t(Phrase.SHADE_SECRET_FALLBACK) },
            ),
            actions = emptyList(),
            freeTextReply = false,
            timeoutMs = BLOCKING_TIMEOUT_MS,
            lockScreenBody = CoreWords.t(Phrase.SHADE_SECRET_LOCKED),
        )
    }
}
