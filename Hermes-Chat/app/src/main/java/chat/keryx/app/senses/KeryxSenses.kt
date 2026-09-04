package chat.keryx.app.senses

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Senses (2.3 §4) — "the herald carries news back".
 *
 * The phone tells the agent the few things only the phone knows: how much battery is left, what
 * time it actually is where the user is standing, and (coarsely) where that is. It travels *inside
 * the user's own message body* as a `⟦keryx:sense|…⟧` marker, so it is E2EE-wrapped exactly like
 * the message it rides on and needs no gateway change whatsoever. Nothing is read, and nothing
 * leaves the phone, until the user presses send.
 *
 * Every sense is opt-in and off by default. The throttle and formatting live in pure functions
 * (no Android types) so the rules that decide what the agent learns are unit-testable.
 */

// --- Preferences -------------------------------------------------------------------------------

/**
 * Senses' own SharedPreferences file. Deliberately *not* [chat.keryx.app.domain.repository.SettingsRepository]:
 * telemetry consent is its own thing, it must be greppable in one place, and clearing it must never
 * disturb the user's account or theme.
 */
class SensesPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var batteryEnabled: Boolean
        get() = sp.getBoolean(KEY_BATTERY, false)
        set(value) { sp.edit().putBoolean(KEY_BATTERY, value).apply() }

    var timeEnabled: Boolean
        get() = sp.getBoolean(KEY_TIME, false)
        set(value) { sp.edit().putBoolean(KEY_TIME, value).apply() }

    var placeEnabled: Boolean
        get() = sp.getBoolean(KEY_PLACE, false)
        set(value) { sp.edit().putBoolean(KEY_PLACE, value).apply() }

    /** No sense on = Senses is off entirely, and [KeryxSenses.read] short-circuits. */
    val anyEnabled: Boolean get() = batteryEnabled || timeEnabled || placeEnabled

    fun lastSent(roomId: String): Long = sp.getLong(PREFIX_LAST_SENT + roomId, 0L)

    fun lastPayload(roomId: String): String? = sp.getString(PREFIX_LAST_PAYLOAD + roomId, null)

    fun recordSent(roomId: String, atMs: Long, payload: String) {
        sp.edit()
            .putLong(PREFIX_LAST_SENT + roomId, atMs)
            .putString(PREFIX_LAST_PAYLOAD + roomId, payload)
            .apply()
    }

    /** Most recent send across every room — the one line the settings card shows. 0 = never. */
    fun lastSentAnywhere(): Long =
        sp.all.entries
            .filter { it.key.startsWith(PREFIX_LAST_SENT) }
            .mapNotNull { it.value as? Long }
            .maxOrNull() ?: 0L

    /**
     * Forget every room's throttle stamp, so the very next message carries a fresh marker. This is
     * what "send with my next message" means — Senses never sends on its own, so the only honest
     * way to offer "now" is to stop holding the next one back.
     */
    fun clearThrottle() {
        val editor = sp.edit()
        sp.all.keys
            .filter { it.startsWith(PREFIX_LAST_SENT) || it.startsWith(PREFIX_LAST_PAYLOAD) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun addChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val FILE = "keryx_senses"
        const val KEY_BATTERY = "battery_enabled"
        const val KEY_TIME = "time_enabled"
        const val KEY_PLACE = "place_enabled"
        const val PREFIX_LAST_SENT = "last_sent_"
        const val PREFIX_LAST_PAYLOAD = "last_payload_"
    }
}

// --- Reading -----------------------------------------------------------------------------------

/** One snapshot of what the phone knows. A null field means "that sense is off or unavailable". */
data class SenseReading(
    val battery: String?,
    val localTime: String?,
    val place: String?,
) {
    val isEmpty: Boolean get() = battery == null && localTime == null && place == null
}

object KeryxSenses {

    /** At most one marker per room per half hour unless the payload changed class. */
    const val MIN_INTERVAL_MS: Long = 30L * 60L * 1000L

    /** Battery is quantised to 20-point steps for the "did it change class?" test. */
    const val BATTERY_STEP: Int = 20

    /** Hard ceiling on the reverse-geocode; past it we ship rounded coordinates instead. */
    private const val GEOCODE_TIMEOUT_MS: Long = 250L

    // --- Marker --------------------------------------------------------------------------------

    /**
     * A complete sense marker sitting at the very end of a message, with whatever whitespace glued
     * it there. Kept in the same `⟦…⟧` family as the other Keryx markers (`ask`, `skill`,
     * `telemetry`) and, like them, never spans a newline.
     */
    val MARKER_RE = Regex("""\s*⟦keryx:sense\|[^⟧\n]*⟧\s*$""")

    /**
     * `⟦keryx:sense|battery=22%·charging|local=23:10 CDT|at=Austin TX (±1 km)⟧`, absent parts
     * omitted. Empty string when there is nothing to say.
     */
    fun marker(reading: SenseReading): String {
        val parts = buildList {
            reading.battery?.let { add("battery=$it") }
            reading.localTime?.let { add("local=$it") }
            reading.place?.let { add("at=$it") }
        }
        if (parts.isEmpty()) return ""
        return parts.joinToString(separator = "|", prefix = "⟦keryx:sense|", postfix = "⟧")
    }

    /**
     * Drops a trailing sense marker (and the whitespace that attached it) — the ME-side strip, so
     * the user's own bubble shows what the user typed and not the telemetry it carried. The render
     * side wires this in; here we only guarantee the rule.
     */
    fun stripMarker(text: String): String = text.replace(MARKER_RE, "")

    // --- Pure rules ----------------------------------------------------------------------------

    /**
     * The equivalence class of a reading: two readings with the same class carry no *news*, so the
     * 30-minute throttle applies. Deliberately excludes the clock (which changes every minute and
     * would defeat the throttle) and quantises battery, so only a charger being plugged in, a
     * meaningful drain, or actually moving forces an early send.
     */
    fun payloadClass(reading: SenseReading): String {
        val battery = reading.battery
        val step = batteryPercent(battery)?.let { (it / BATTERY_STEP).toString() } ?: "-"
        val charging = when {
            battery == null -> "-"
            isCharging(battery) -> "1"
            else -> "0"
        }
        val place = reading.place ?: "-"
        return "b=$step|c=$charging|p=$place"
    }

    /**
     * True when the marker has earned its place in this message: never sent before, half an hour
     * elapsed, the payload changed class, or the clock jumped backwards (never trust a stale
     * future timestamp to mute us forever).
     */
    fun shouldSend(nowMs: Long, lastSentMs: Long, lastPayload: String?, payload: String): Boolean {
        if (lastSentMs <= 0L) return true
        if (nowMs < lastSentMs) return true
        if (nowMs - lastSentMs >= MIN_INTERVAL_MS) return true
        return lastPayload != payload
    }

    /** Slash commands are addressed to Keryx, not to the agent — never decorate one. */
    fun isDecoratable(text: String): Boolean =
        text.isNotBlank() && !text.trimStart().startsWith("/")

    /** `22%·charging` / `78%`. */
    fun formatBattery(percent: Int, charging: Boolean): String {
        val clamped = percent.coerceIn(0, 100)
        return if (charging) "$clamped%·charging" else "$clamped%"
    }

    /** `23:10 CDT`. */
    fun formatClock(hour: Int, minute: Int, zoneAbbr: String): String {
        val hhmm = String.format(Locale.US, "%02d:%02d", hour, minute)
        return if (zoneAbbr.isBlank()) hhmm else "$hhmm $zoneAbbr"
    }

    /** ~1.1 km of ambiguity in each direction — the resolution the whole feature promises. */
    fun roundCoord(value: Double): Double = Math.round(value * 100.0) / 100.0

    /** `Austin TX (±1 km)`, or `30.27,-97.74 (±1 km)` when the geocoder had nothing. */
    fun formatPlace(lat: Double, lon: Double, locality: String?, region: String?): String {
        val town = locality?.trim().orEmpty()
        if (town.isEmpty()) {
            return String.format(Locale.US, "%.2f,%.2f %s", roundCoord(lat), roundCoord(lon), PRECISION)
        }
        val reg = region?.trim().orEmpty()
        return if (reg.isEmpty()) "$town $PRECISION" else "$town $reg $PRECISION"
    }

    /**
     * The short form of an administrative area: US states become their postal code, anything
     * already short stays as-is, and everything else falls back to the country code — so the
     * marker reads `Austin TX` / `Berlin DE` and never `Austin Texas, United States`.
     */
    fun regionLabel(adminArea: String?, countryCode: String?): String? {
        val admin = adminArea?.trim().orEmpty()
        US_STATES[admin.lowercase(Locale.US)]?.let { return it }
        if (admin.isNotEmpty() && admin.length <= 3) return admin.uppercase(Locale.US)
        return countryCode?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.US)
    }

    /** The settings card's one-line summary. */
    fun lastSentLabel(nowMs: Long, lastSentMs: Long): String {
        if (lastSentMs <= 0L) return "Never"
        val mins = (nowMs - lastSentMs).coerceAtLeast(0L) / 60_000L
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            else -> "${mins / (60 * 24)}d ago"
        }
    }

    /** Leading integer of `22%·charging` — null when the battery sense is off/unreadable. */
    internal fun batteryPercent(battery: String?): Int? {
        val digits = battery?.takeWhile { it.isDigit() }.orEmpty()
        return digits.toIntOrNull()
    }

    internal fun isCharging(battery: String): Boolean = battery.contains(CHARGING_SUFFIX)

    // --- Android side --------------------------------------------------------------------------

    /**
     * Reads every enabled sense. Bounded by design: the battery comes from the *sticky* broadcast
     * (no receiver registered, no wait), the clock is local arithmetic, and location is
     * last-known-only — no active request, no network fix. The optional reverse-geocode runs off
     * this thread behind a [GEOCODE_TIMEOUT_MS] budget and is memoised per rounded coordinate, so
     * the whole call stays well inside ~300ms even on the first send.
     */
    fun read(context: Context, prefs: SensesPrefs): SenseReading {
        if (!prefs.anyEnabled) return SenseReading(null, null, null)
        return SenseReading(
            battery = if (prefs.batteryEnabled) readBattery(context) else null,
            localTime = if (prefs.timeEnabled) readLocalTime() else null,
            place = if (prefs.placeEnabled) readPlace(context) else null,
        )
    }

    /**
     * Appends the marker to [text] on its own line when a sense is enabled and the throttle allows
     * it, recording the send against [roomId]. Returns [text] untouched otherwise — blank text and
     * slash commands are never decorated.
     */
    fun decorateOutgoing(context: Context, roomId: String, text: String): String {
        if (!isDecoratable(text)) return text
        val prefs = SensesPrefs(context)
        if (!prefs.anyEnabled) return text
        val reading = read(context, prefs)
        if (reading.isEmpty) return text
        val marker = marker(reading)
        if (marker.isEmpty()) return text
        val payload = payloadClass(reading)
        val now = System.currentTimeMillis()
        if (!shouldSend(now, prefs.lastSent(roomId), prefs.lastPayload(roomId), payload)) return text
        prefs.recordSent(roomId, now, payload)
        return text.trimEnd() + "\n" + marker
    }

    private fun readBattery(context: Context): String? {
        val sticky: Intent? = try {
            context.applicationContext
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        }
        if (sticky == null) return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return formatBattery(level * 100 / scale, charging)
    }

    /** `23:10 CDT` in the device's own zone. */
    private fun readLocalTime(): String? = try {
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
        val abbr = java.time.format.DateTimeFormatter.ofPattern("zzz", Locale.US).format(now)
        formatClock(now.hour, now.minute, abbr)
    } catch (_: Throwable) {
        null
    }

    private fun readPlace(context: Context): String? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val manager = context.applicationContext
            .getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val fix = lastKnown(manager, LocationManager.NETWORK_PROVIDER)
            ?: lastKnown(manager, LocationManager.PASSIVE_PROVIDER)
            ?: return null

        // Coarsen *before* anything else sees the coordinates — including the geocoder.
        val lat = roundCoord(fix.latitude)
        val lon = roundCoord(fix.longitude)
        val named = geocode(context, lat, lon)
        return formatPlace(lat, lon, named?.first, named?.second)
    }

    private fun lastKnown(manager: LocationManager, provider: String): Location? = try {
        manager.getLastKnownLocation(provider)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        // Provider not present on this device (common for NETWORK on de-Googled ROMs).
        null
    } catch (_: Throwable) {
        null
    }

    // The geocoder is a synchronous IPC that may reach the network, so it is never allowed to run
    // on the caller's thread. One rounded coordinate resolves to one name forever (~1 km cells),
    // which makes every send after the first free.
    private val geocodeExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "keryx-senses-geocode").apply { isDaemon = true }
        }
    }

    @Volatile
    private var geocodeCache: Pair<String, Pair<String?, String?>?>? = null

    /** locality to region, or null when the device can't name this cell in time. */
    private fun geocode(context: Context, lat: Double, lon: Double): Pair<String?, String?>? {
        if (!Geocoder.isPresent()) return null
        val cell = String.format(Locale.US, "%.2f,%.2f", lat, lon)
        geocodeCache?.let { if (it.first == cell) return it.second }

        val appContext = context.applicationContext
        val task = FutureTask<Pair<String?, String?>?>(
            Callable { geocodeBlocking(appContext, lat, lon) },
        )
        val named = try {
            geocodeExecutor.execute(task)
            task.get(GEOCODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            task.cancel(true)
            return null // Don't cache a timeout; the next send gets another try.
        }
        geocodeCache = cell to named
        return named
    }

    @Suppress("DEPRECATION")
    private fun geocodeBlocking(context: Context, lat: Double, lon: Double): Pair<String?, String?>? =
        try {
            val address = Geocoder(context, Locale.getDefault())
                .getFromLocation(lat, lon, 1)
                ?.firstOrNull()
            val locality = address?.locality?.takeIf { it.isNotBlank() }
                ?: address?.subAdminArea?.takeIf { it.isNotBlank() }
            if (locality == null) null
            else locality to regionLabel(address?.adminArea, address?.countryCode)
        } catch (_: Throwable) {
            // No backing geocoder service, no network, malformed coordinates — all mean "no name".
            null
        }

    private const val CHARGING_SUFFIX = "·charging"
    private const val PRECISION = "(±1 km)"

    private val US_STATES: Map<String, String> = mapOf(
        "alabama" to "AL", "alaska" to "AK", "arizona" to "AZ", "arkansas" to "AR",
        "california" to "CA", "colorado" to "CO", "connecticut" to "CT", "delaware" to "DE",
        "district of columbia" to "DC", "florida" to "FL", "georgia" to "GA", "hawaii" to "HI",
        "idaho" to "ID", "illinois" to "IL", "indiana" to "IN", "iowa" to "IA",
        "kansas" to "KS", "kentucky" to "KY", "louisiana" to "LA", "maine" to "ME",
        "maryland" to "MD", "massachusetts" to "MA", "michigan" to "MI", "minnesota" to "MN",
        "mississippi" to "MS", "missouri" to "MO", "montana" to "MT", "nebraska" to "NE",
        "nevada" to "NV", "new hampshire" to "NH", "new jersey" to "NJ", "new mexico" to "NM",
        "new york" to "NY", "north carolina" to "NC", "north dakota" to "ND", "ohio" to "OH",
        "oklahoma" to "OK", "oregon" to "OR", "pennsylvania" to "PA", "rhode island" to "RI",
        "south carolina" to "SC", "south dakota" to "SD", "tennessee" to "TN", "texas" to "TX",
        "utah" to "UT", "vermont" to "VT", "virginia" to "VA", "washington" to "WA",
        "west virginia" to "WV", "wisconsin" to "WI", "wyoming" to "WY", "puerto rico" to "PR",
    )
}
