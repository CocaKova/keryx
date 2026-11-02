package chat.keryx.app

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own language, apart from the phone's.
 *
 * Android 13+ keeps the choice itself (LocaleManager, the same switch as Settings › Apps ›
 * Keryx › Language) and restarts our activities with it. Below 13 there is no such switch, so
 * the choice lives in a small prefs file and every entry point wraps its base context with
 * [wrap]. An empty tag means "follow the phone".
 */
object KeryxLocale {
    /** BCP-47 tags with a values-<qualifier> folder. Keep in step with res/; StringResourcesTest checks. */
    val SUPPORTED: List<String> = listOf(
        "en", "es", "fr", "de", "it", "pt-BR", "pt-PT", "nl", "pl", "ru", "uk", "tr",
        "ar", "he", "hi", "bn", "id", "vi", "th", "ja", "ko", "zh-CN", "zh-TW",
        "sv", "da", "nb", "fi", "cs", "el", "ro", "hu",
    )

    /** Languages translated by a model and not yet read by a native speaker. */
    fun isMachineTranslated(tag: String): Boolean = tag.isNotEmpty() && tag != "en"

    /** The language's own name for itself, e.g. "Deutsch", "日本語". */
    fun nativeName(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayName(l).replaceFirstChar { if (it.isLowerCase()) it.titlecase(l) else it.toString() }
    }

    private const val PREFS = "keryx_locale"
    private const val KEY = "tag"

    /** The chosen tag, or "" for the phone's language. */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales
                ?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()?.let(::match) ?: ""
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        }

    /**
     * Choose [tag] ("" = phone). On 13+ the system recreates the activities; below, the caller
     * gets `true` and must recreate the activity itself.
     */
    fun set(context: Context, tag: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return false
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        return true
    }

    /** For attachBaseContext below Android 13; a no-op on 13+ or when following the phone. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Map a system tag ("pt-BR", "zh-Hans-CN", "es-MX") onto one of [SUPPORTED]. */
    fun match(tag: String): String? {
        if (tag in SUPPORTED) return tag
        val l = Locale.forLanguageTag(tag)
        val lang = l.language
        if (lang == "zh") {
            val trad = l.script == "Hant" || l.country in setOf("TW", "HK", "MO")
            return if (trad) "zh-TW" else "zh-CN"
        }
        if (lang == "pt") return if (l.country == "PT") "pt-PT" else "pt-BR"
        if (lang == "iw") return "he"
        if (lang == "in") return "id"
        if (lang == "no" || lang == "nn") return "nb"
        return SUPPORTED.firstOrNull { it == lang }
    }
}
