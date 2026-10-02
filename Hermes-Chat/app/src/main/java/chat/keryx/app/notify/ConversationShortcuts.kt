package chat.keryx.app.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import chat.keryx.app.MainActivity
import chat.keryx.app.R

/**
 * Conversation shortcuts (2.16). A message notification already named its shortcut
 * (`setShortcutId(roomId)`), but nothing ever published one, so Android had nothing to match:
 * no People section, no conversation priority, no bubbles. Each session that notifies or is
 * opened is pushed here as a long-lived shortcut whose intent is its `keryx://session/<id>` link.
 * `pushDynamicShortcut` keeps the most recent few and rate-limits itself; any refusal is ignored
 * — a missing shortcut costs a nicety, never a notification.
 */
object ConversationShortcuts {
    fun push(context: Context, roomId: String, name: String) {
        if (roomId.isBlank()) return
        runCatching {
            val label = name.trim().ifBlank { "Keryx" }
            val person = Person.Builder().setName(label).setKey(roomId).setBot(true).build()
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(chat.keryx.core.model.KeryxLink.session(roomId)),
                context,
                MainActivity::class.java,
            )
            val shortcut = ShortcutInfoCompat.Builder(context, roomId)
                .setShortLabel(label.take(24))
                .setLongLabel(label.take(64))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .setLongLived(true)
                .setPerson(person)
                .setCategories(setOf(CATEGORY_CONVERSATION))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }.onFailure { android.util.Log.w("KeryxShortcuts", "push failed for ${roomId.take(8)}: ${it.message}") }
    }

    /** ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, spelled out for minSdk. */
    private const val CATEGORY_CONVERSATION = "android.shortcut.conversation"
}
