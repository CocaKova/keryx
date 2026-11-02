package chat.keryx.app

import chat.keryx.core.i18n.CountPhrase
import chat.keryx.core.i18n.Phrase
import chat.keryx.core.i18n.Words

/**
 * :core's words in the app's language (3.0). Core names each phrase it builds ([Phrase],
 * [CountPhrase]) with English beside it; here each one finds its string resource, read through
 * [KeryxText] so it follows the language chosen in Settings. The `when`s are exhaustive: a
 * phrase added to core without a resource here does not compile. CoreWordsTest holds the
 * English XML to core's own English.
 */
object KeryxCoreWords : Words {
    override fun phrase(p: Phrase): String = KeryxText.get(res(p))

    override fun count(p: CountPhrase, count: Int): String = KeryxText.pluralTemplate(plural(p), count)

    @androidx.annotation.StringRes
    fun res(p: Phrase): Int = when (p) {
        Phrase.NOTICE_PHOTO -> R.string.p2_core_notice_photo
        Phrase.NOTICE_ATTACHMENT -> R.string.app_attachment
        Phrase.NOTICE_NEW_MESSAGE -> R.string.app_new_message
        Phrase.SHADE_UNLOCK_TO_SEE -> R.string.p2_core_shade_unlock_to_see
        Phrase.SHADE_APPROVE -> R.string.work_mission_approve
        Phrase.SHADE_ALWAYS -> R.string.chat_approval_always
        Phrase.SHADE_THIS_SESSION -> R.string.p2_core_shade_this_session
        Phrase.SHADE_DENY -> R.string.chat_approval_deny
        Phrase.SHADE_APPROVAL_TITLE -> R.string.chat_approval_needed
        Phrase.SHADE_APPROVAL_BODY -> R.string.p2_core_shade_approval_body
        Phrase.SHADE_APPROVAL_LOCKED -> R.string.p2_core_shade_approval_locked
        Phrase.SHADE_ASKS_TITLE -> R.string.p2_core_shade_asks_title
        Phrase.SHADE_ASKS_BODY -> R.string.p2_core_shade_asks_body
        Phrase.SHADE_ASKS_LOCKED -> R.string.p2_core_shade_asks_locked
        Phrase.SHADE_SUDO_TITLE -> R.string.p2_core_shade_sudo_title
        Phrase.SHADE_SUDO_BODY -> R.string.p2_core_shade_sudo_body
        Phrase.SHADE_SUDO_LOCKED -> R.string.p2_core_shade_sudo_locked
        Phrase.SHADE_SECRET_TITLE -> R.string.p2_core_shade_secret_title
        Phrase.SHADE_SECRET_FALLBACK -> R.string.p2_core_shade_secret_fallback
        Phrase.SHADE_SECRET_BODY -> R.string.p2_core_shade_secret_body
        Phrase.SHADE_SECRET_LOCKED -> R.string.p2_core_shade_secret_locked
        Phrase.RUN_WRITING -> R.string.p2_core_run_writing
        Phrase.RUN_THINKING -> R.string.chat_thinking
        Phrase.RUN_STEP -> R.string.p2_core_run_step
        Phrase.RUN_AGENT_WORKING -> R.string.p2_core_run_agent_working
        Phrase.RUN_WORKING_IN -> R.string.p2_core_run_working_in
        Phrase.LEX_ROOM -> R.string.p2_core_lex_room
        Phrase.LEX_ROOMS -> R.string.p2_core_lex_rooms
        Phrase.LEX_QUICK_ROOMS -> R.string.p2_core_lex_quick_rooms
        Phrase.LEX_ROOMS_HEADER -> R.string.p2_core_lex_rooms_header
        Phrase.LEX_PIN_QUICK -> R.string.p2_core_lex_pin_quick
        Phrase.LEX_UNPIN_QUICK -> R.string.p2_core_lex_unpin_quick
        Phrase.LEX_NO_ROOMS -> R.string.p2_core_lex_no_rooms
        Phrase.LEX_SELECT_ROOM -> R.string.p2_core_lex_select_room
        Phrase.LEX_SHARE_ROOM -> R.string.p2_core_lex_share_room
        Phrase.LEX_OPEN_ROOM_QUICK -> R.string.p2_core_lex_open_room_quick
        Phrase.LEX_SESSION -> R.string.p2_core_lex_session
        Phrase.LEX_SESSIONS -> R.string.p2_core_lex_sessions
        Phrase.LEX_PINNED -> R.string.chat_room_pinned
        Phrase.LEX_SESSIONS_HEADER -> R.string.settings_section_sessions
        Phrase.LEX_PIN -> R.string.p2_core_lex_pin
        Phrase.LEX_UNPIN -> R.string.p2_core_lex_unpin
        Phrase.LEX_PIN_HINT -> R.string.p2_core_lex_pin_hint
        Phrase.LEX_NO_SESSIONS -> R.string.p2_core_lex_no_sessions
        Phrase.LEX_OPEN_SESSION -> R.string.p2_core_lex_open_session
        Phrase.LEX_SHARE_SESSION -> R.string.p2_core_lex_share_session
        Phrase.LEX_OPEN_SESSION_QUICK -> R.string.p2_core_lex_open_session_quick
        Phrase.CRON_DAILY -> R.string.p2_core_cron_daily
        Phrase.CRON_HOURLY -> R.string.p2_core_cron_hourly
        Phrase.CRON_EVERY_MIN -> R.string.p2_core_cron_every_min
        Phrase.CRON_DAILY_AT -> R.string.p2_core_cron_daily_at
        Phrase.CRON_WEEKDAYS_AT -> R.string.p2_core_cron_weekdays_at
        Phrase.CRON_WEEKENDS_AT -> R.string.p2_core_cron_weekends_at
        Phrase.CRON_DAYS_AT -> R.string.p2_core_cron_days_at
        Phrase.CRON_MONTHLY_AT -> R.string.p2_core_cron_monthly_at
        Phrase.CRON_SUN -> R.string.p2_core_cron_sun
        Phrase.CRON_MON -> R.string.p2_core_cron_mon
        Phrase.CRON_TUE -> R.string.p2_core_cron_tue
        Phrase.CRON_WED -> R.string.p2_core_cron_wed
        Phrase.CRON_THU -> R.string.p2_core_cron_thu
        Phrase.CRON_FRI -> R.string.p2_core_cron_fri
        Phrase.CRON_SAT -> R.string.p2_core_cron_sat
        Phrase.CRON_NEXT_IN -> R.string.p2_core_cron_next_in
        Phrase.CRON_UNDER_A_MINUTE -> R.string.p2_core_cron_under_a_minute
        Phrase.CRON_MINUTES_SHORT -> R.string.p2_core_cron_minutes_short
        Phrase.CRON_HOURS_SHORT -> R.string.p2_core_cron_hours_short
        Phrase.CRON_DAYS_SHORT -> R.string.p2_core_cron_days_short
        Phrase.CTX_SUMMARY_NEXT -> R.string.p2_core_ctx_summary_next
        Phrase.CTX_FILLING_UP -> R.string.p2_core_ctx_filling_up
        Phrase.CTX_LEFT -> R.string.p2_core_ctx_left
        Phrase.CTX_THE_AGENT -> R.string.p2_core_ctx_the_agent
        Phrase.CTX_THEN -> R.string.p2_core_ctx_then
        Phrase.CTX_FILLS_WINDOW -> R.string.p2_core_ctx_fills_window
        Phrase.CTX_NEXT_SUMMARY -> R.string.p2_core_ctx_next_summary
        Phrase.CTX_FILLS_THEN -> R.string.p2_core_ctx_fills_then
        Phrase.CTX_BEFORE_SUMMARY -> R.string.p2_core_ctx_before_summary
        Phrase.CTX_BEFORE_SUMMARY_WINDOW -> R.string.p2_core_ctx_before_summary_window
        Phrase.CTX_MODEL_HOLDS -> R.string.p2_core_ctx_model_holds
        Phrase.ROSTER_TODAY -> R.string.chat_today
        Phrase.ROSTER_YESTERDAY -> R.string.chat_yesterday
        Phrase.ROSTER_THIS_WEEK -> R.string.p2_core_roster_this_week
        Phrase.ROSTER_OLDER -> R.string.p2_core_roster_older
        Phrase.ROSTER_UNDATED -> R.string.p2_core_roster_undated
        Phrase.FIND_NO_MATCH -> R.string.p2_core_find_no_match
        Phrase.FIND_POSITION -> R.string.p2_core_find_position
        Phrase.GOAL_TURN -> R.string.p2_core_goal_turn
        Phrase.GOAL_GATES -> R.string.p2_core_goal_gates
        Phrase.GOAL_DONE -> R.string.p2_core_goal_done
        Phrase.GOAL_PAUSED -> R.string.p2_core_goal_paused
        Phrase.GOAL_WAITING -> R.string.p2_core_goal_waiting
        Phrase.GOAL_BLOCKED -> R.string.p2_core_goal_blocked
        Phrase.GOAL_PAUSE -> R.string.p2_core_goal_pause
        Phrase.GOAL_RESUME -> R.string.p2_core_goal_resume
        Phrase.GOAL_STOP_WAITING -> R.string.p2_core_goal_stop_waiting
        Phrase.GOAL_CLEAR -> R.string.chat_clear
        Phrase.UPDATE_UNREACHABLE -> R.string.p2_core_update_unreachable
        Phrase.UPDATE_BEHIND_UNKNOWN -> R.string.p2_core_update_behind_unknown
        Phrase.UPDATE_UP_TO_DATE -> R.string.p2_core_update_up_to_date
        Phrase.EFFORT_OFF -> R.string.settings_off
        Phrase.EFFORT_MINIMAL -> R.string.p2_core_effort_minimal
        Phrase.EFFORT_LOW -> R.string.p2_core_effort_low
        Phrase.EFFORT_MEDIUM -> R.string.p2_core_effort_medium
        Phrase.EFFORT_HIGH -> R.string.p2_core_effort_high
        Phrase.EFFORT_XHIGH -> R.string.p2_core_effort_xhigh
        Phrase.EFFORT_MAX -> R.string.p2_core_effort_max
        Phrase.EFFORT_ULTRA -> R.string.p2_core_effort_ultra
        Phrase.ERR_GENERIC -> R.string.p2_core_err_generic
        Phrase.ERR_404 -> R.string.p2_core_err_404
        Phrase.ERR_AUTH -> R.string.p2_core_err_auth
        Phrase.ERR_SERVER -> R.string.p2_core_err_server
        Phrase.ERR_NO_HOST -> R.string.p2_core_err_no_host
        Phrase.ERR_REFUSED -> R.string.p2_core_err_refused
        Phrase.ERR_TIMEOUT -> R.string.p2_core_err_timeout
        Phrase.ERR_NOT_CONNECTED -> R.string.p2_core_err_not_connected
    }

    @androidx.annotation.PluralsRes
    fun plural(p: CountPhrase): Int = when (p) {
        CountPhrase.RUN_TOOL_CALLS -> R.plurals.p2_core_run_tool_calls
        CountPhrase.RUN_AGENTS_WORKING -> R.plurals.p2_core_run_agents_working
        CountPhrase.CRON_EVERY_DAYS -> R.plurals.p2_core_cron_every_days
        CountPhrase.CRON_EVERY_HOURS -> R.plurals.p2_core_cron_every_hours
        CountPhrase.CTX_ROOM_BEFORE_FULL -> R.plurals.p2_core_ctx_room_before_full
        CountPhrase.UPDATE_COMMITS_BEHIND -> R.plurals.p2_core_update_commits_behind
        CountPhrase.CTX_ROOM_THEN -> R.plurals.p2_core_ctx_room_then
    }
}
