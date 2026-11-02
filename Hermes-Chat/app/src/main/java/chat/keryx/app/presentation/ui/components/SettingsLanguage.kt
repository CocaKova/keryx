package chat.keryx.app.presentation.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.keryx.app.KeryxLocale
import chat.keryx.app.R

/** Where a reader reports a clumsy translated line. */
private const val TRANSLATION_ISSUES_URL = "https://github.com/CocaKova/keryx/issues"

/** The app's chosen language tag, "" for the phone's. */
@Composable
internal fun currentLanguageTag(): String {
    val context = LocalContext.current
    return remember(context) { KeryxLocale.current(context) }
}

/** A choice as the list shows it: "Follow phone", or the language in its own name. */
@Composable
internal fun languageChoiceName(tag: String): String =
    if (tag.isEmpty()) stringResource(R.string.p2_language_follow_phone) else KeryxLocale.nativeName(tag)

/**
 * Settings › Language (3.0): "Follow phone" first, then every language Keryx carries, each in
 * its own name and sorted by it — a person looking for "Deutsch" should not need to know the
 * English word "German". A pick on Android 13+ is the system's own per-app language, and the
 * system restarts the activity; below 13 the choice is ours and we restart it here.
 */
@Composable
internal fun LanguageChoices() {
    val context = LocalContext.current
    var current by remember(context) { mutableStateOf(KeryxLocale.current(context)) }
    val options = remember {
        val collator = java.text.Collator.getInstance(java.util.Locale.ROOT)
        KeryxLocale.SUPPORTED.map { it to KeryxLocale.nativeName(it) }.sortedWith(compareBy(collator) { it.second })
    }
    // What "Follow phone" lands on today, so that row can say it.
    val phoneTag = remember {
        val phone = android.content.res.Resources.getSystem().configuration.locales
        (0 until phone.size()).firstNotNullOfOrNull { KeryxLocale.match(phone[it].toLanguageTag()) } ?: "en"
    }

    fun choose(tag: String) {
        if (tag == current) return
        current = tag
        if (KeryxLocale.set(context, tag)) context.findActivity()?.recreate()
    }

    LanguageRow(
        title = stringResource(R.string.p2_language_follow_phone),
        subtitle = stringResource(R.string.p2_language_follow_phone_now, KeryxLocale.nativeName(phoneTag)),
        selected = current.isEmpty(),
    ) { choose("") }
    options.forEach { (tag, name) ->
        LanguageRow(title = name, subtitle = null, selected = current == tag) { choose(tag) }
    }

    val shownTag = current.ifEmpty { phoneTag }
    if (KeryxLocale.isMachineTranslated(shownTag)) {
        val uri = LocalUriHandler.current
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(KeryxRadius.field))
                .clickable { runCatching { uri.openUri(TRANSLATION_ISSUES_URL) } }
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            Text(
                stringResource(R.string.p2_language_ai_note),
                fontSize = KeryxType.caption,
                color = chat.keryx.app.theme.quietInk(alpha = 0.7f),
            )
            Text(
                TRANSLATION_ISSUES_URL.removePrefix("https://"),
                fontSize = KeryxType.caption,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LanguageRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.field))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = KeryxType.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) Text(
                subtitle,
                fontSize = KeryxType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) Icon(
            Icons.Default.Check,
            contentDescription = stringResource(R.string.p2_language_selected),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
