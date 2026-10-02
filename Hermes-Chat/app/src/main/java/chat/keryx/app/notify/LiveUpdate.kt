package chat.keryx.app.notify

import android.app.Notification
import android.content.Context
import android.os.Build

/**
 * Android 16 Live Update (2.16): the run notice asks to be promoted — the status-bar chip and
 * the lock screen's top slot (and, on One UI, possibly the Now Bar; unverified) — and draws the
 * flight plan as [Notification.ProgressStyle] segments, one per step, the landed ones lit.
 *
 * Below API 36 the notice is returned untouched. The promotion is a request: the system may
 * decline it (the user can switch it off per app), and a promoted notice may not be colorized,
 * so on 36+ the agent's colour moves from the background to the accent.
 */
object LiveUpdate {
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun promote(context: Context, built: Notification, planDone: Int, planTotal: Int, accent: Int): Notification {
        if (Build.VERSION.SDK_INT < 36) return built
        return runCatching<Notification> {
            val b = Notification.Builder.recoverBuilder(context, built)
                .setColorized(false)
                .setColor(accent)
                // Notification.EXTRA_REQUEST_PROMOTED_ONGOING, by its value: the builder method
                // is newer than this compile SDK, and the extra is what it sets.
                .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            if (planTotal > 0) {
                val lit = accent
                val dim = (accent and 0x00FFFFFF) or (0x55 shl 24)
                val segments = (0 until planTotal).map { i ->
                    Notification.ProgressStyle.Segment(1).setColor(if (i < planDone) lit else dim)
                }
                b.setStyle(
                    Notification.ProgressStyle()
                        .setProgressSegments(segments)
                        .setProgress(planDone.coerceIn(0, planTotal))
                        .setStyledByProgress(false),
                )
            }
            b.build()
        }.getOrElse {
            android.util.Log.w("KeryxLiveUpdate", "promotion skipped: ${it.message}")
            built
        }
    }
}
