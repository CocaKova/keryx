package chat.keryx.app.hands

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.app.SearchManager
import chat.keryx.core.model.PhoneAction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The hands (2.8.1): what the phone actually does with a [PhoneAction]. Every kind that the
 * system can carry as an Intent goes through [intentFor] — so the same Intent can ride a
 * notification button as a PendingIntent — and the two that can't (clipboard, torch) are
 * performed here directly. [perform] never throws: it answers with the one line to show the
 * person when nothing on this phone could do it.
 *
 * Nothing here runs without a tap. The chip in the bubble and the button on the notification
 * are both a person's thumb, and that is the whole consent model of V1.
 */
object PhoneHands {

    /** The Intent that performs [a], or null when the kind is not Intent-shaped (copy, torch). */
    fun intentFor(context: Context, a: PhoneAction): Intent? = when (a.kind) {
        PhoneAction.Kind.URL -> Intent(Intent.ACTION_VIEW, Uri.parse(a.primary))
        PhoneAction.Kind.DIAL -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.primary)))
        PhoneAction.Kind.SMS -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.primary))).apply {
            a.args.getOrNull(1)?.let { putExtra("sms_body", it) }
        }
        PhoneAction.Kind.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(a.primary))).apply {
            a.args.getOrNull(1)?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            a.args.getOrNull(2)?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
        PhoneAction.Kind.CALENDAR -> Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, a.primary)
            a.args.getOrNull(1)?.let { epochOf(it) }?.let { start ->
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                val end = a.args.getOrNull(2)?.let { epochOf(it) } ?: (start + 60 * 60 * 1000L)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                if (a.args.getOrNull(1)?.length == 10) putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            }
            a.args.getOrNull(3)?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        PhoneAction.Kind.ALARM -> Intent(AlarmClock.ACTION_SET_ALARM).apply {
            val (h, m) = PhoneAction.clockOf(a.primary) ?: (7 to 0)
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            a.args.getOrNull(1)?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            // The tap IS the confirmation: set it, and let the clock app's own toast say so.
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        PhoneAction.Kind.TIMER -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, PhoneAction.timerSeconds(a.primary) ?: 60)
            a.args.getOrNull(1)?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        PhoneAction.Kind.NAVIGATE -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.primary)))
        PhoneAction.Kind.SEARCH -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, a.primary)
        PhoneAction.Kind.PLAY -> Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, a.primary)
        }
        PhoneAction.Kind.OPEN -> launcherFor(context, a.primary)
        PhoneAction.Kind.SHARE -> Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.primary), null,
        )
        PhoneAction.Kind.COPY, PhoneAction.Kind.TORCH -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Do it. Null = done (or handed to the system); a string = the reason it couldn't be,
     * for a toast. Never throws — the tile is the last place an exception should surface.
     */
    fun perform(context: Context, a: PhoneAction): String? = runCatching {
        when (a.kind) {
            PhoneAction.Kind.COPY -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Keryx", a.primary))
                null
            }
            PhoneAction.Kind.TORCH -> torch(context, on = a.primary.lowercase() == "on")
            else -> {
                val intent = intentFor(context, a) ?: return@runCatching "Nothing on this phone can do that"
                if (a.kind == PhoneAction.Kind.OPEN && intent.component == null) return@runCatching "No app called “${a.primary}”"
                if (intent.resolveActivity(context.packageManager) == null && a.kind != PhoneAction.Kind.SHARE) {
                    return@runCatching "Nothing on this phone handles ${a.kind.token}"
                }
                context.startActivity(intent)
                null
            }
        }
    }.getOrElse { "Couldn't: ${it.message?.take(80) ?: it::class.simpleName}" }

    /** The launcher Intent for the installed app whose label matches [name] (case-insensitive,
     *  exact first, then contains) — or an Intent with no component when nothing does. */
    private fun launcherFor(context: Context, name: String): Intent {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(probe, 0)
        val want = name.trim().lowercase()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == want }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(want) }
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
            hit?.activityInfo?.let { setClassName(it.packageName, it.name) }
        }
    }

    private fun torch(context: Context, on: Boolean): String? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cid ->
            cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "No flash on this phone"
        cm.setTorchMode(id, on)
        return null
    }

    /** ISO-ish → epoch millis in the phone's zone (an offset in the string wins). */
    private fun epochOf(s: String): Long? {
        val t = s.trim().replace(' ', 'T')
        return try {
            when {
                t.length == 10 -> LocalDate.parse(t).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                t.endsWith("Z") || Regex("""[+-]\d{2}:?\d{2}$""").containsMatchIn(t) ->
                    OffsetDateTime.parse(t.replace(Regex("""([+-]\d{2})(\d{2})$"""), "$1:$2")).toInstant().toEpochMilli()
                else -> LocalDateTime.parse(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        } catch (_: DateTimeParseException) { null }
    }
}
