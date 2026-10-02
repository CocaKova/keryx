package chat.keryx.core.model

/**
 * What one `session.redirect` came to (2.16). Redirect is the stronger of the two mid-turn hands:
 * steer (`session.steer`) waits for the next tool result and never interrupts; redirect cancels
 * the model call in flight and re-asks with the correction appended, keeping the work already
 * done (hermes `agent/interrupt_control.py` `redirect`). During a tool it degrades to a steer on
 * the gateway's side and still answers `redirected`.
 */
enum class RedirectOutcome {
    /** The live call was cancelled and re-asked with the correction. */
    REDIRECTED,

    /** The turn was between calls (still building, or compacting): the gateway queued the
     *  correction as the next turn itself — the client must not queue it again. */
    QUEUED,

    /** The reply finished before the redirect landed; the correction is the client's to queue. */
    REJECTED,

    /** This gateway or agent cannot redirect at all (4010, or a gateway without the verb):
     *  the option goes away and the correction steers instead. */
    UNSUPPORTED;

    companion object {
        /** `{status: redirected|queued|rejected}`; anything else is a refusal, never a success. */
        fun ofStatus(status: String?): RedirectOutcome = when (status) {
            "redirected" -> REDIRECTED
            "queued" -> QUEUED
            else -> REJECTED
        }

        /** The error codes that mean "never here": 4010 (agent lacks active-turn redirect) and
         *  -32601 (the gateway predates the verb). Null = an ordinary failure, worth reporting. */
        fun ofErrorCode(code: Int): RedirectOutcome? = when (code) {
            4010, -32601 -> UNSUPPORTED
            else -> null
        }
    }
}
