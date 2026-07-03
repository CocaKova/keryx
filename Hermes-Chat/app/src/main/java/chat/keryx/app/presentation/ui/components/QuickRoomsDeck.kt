package chat.keryx.app.presentation.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.domain.model.RoomProfile
import kotlin.math.min

/**
 * Quick Rooms shown as a horizontal deck of monogram avatars. The selected room wears a living
 * Braille-dot ring (the same motif as the connect screen) as the "you are here" indicator.
 */
@Composable
fun QuickRoomsDeck(
    rooms: List<RoomProfile>,
    selectedRoomId: String?,
    onRoomClick: (RoomProfile) -> Unit,
    avatarLoader: suspend (String) -> ByteArray?,
    onRoomLongPress: (RoomProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rooms.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        rooms.forEach { room ->
            QuickRoomAvatar(
                room = room,
                selected = room.id == selectedRoomId,
                onClick = { onRoomClick(room) },
                onLongPress = { onRoomLongPress(room) },
                avatarLoader = avatarLoader,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickRoomAvatar(
    room: RoomProfile,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    avatarLoader: suspend (String) -> ByteArray?,
) {
    val accent = MaterialTheme.colorScheme.primary
    val avatarUrl = room.avatarUrl
    val cachedAvatar = androidx.compose.runtime.remember(avatarUrl) { avatarUrl?.let { KeryxBitmapCache.get(it) } }
    val avatarBitmap by produceState<ImageBitmap?>(initialValue = cachedAvatar, avatarUrl) {
        if (cachedAvatar != null || avatarUrl == null) return@produceState
        value = avatarLoader(avatarUrl)?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
                ?.also { KeryxBitmapCache.put(avatarUrl, it) }
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(60.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(58.dp)) {
            if (selected) {
                BrailleSnakeAnimation(
                    modifier = Modifier.fillMaxSize(),
                    color = accent,
                    color2 = MaterialTheme.colorScheme.tertiary,
                    snakeLength = 16,
                    periodMillis = 2800,
                    glyphSize = 9f,
                    pathProvider = ::ringPath,
                )
            }
            val bmp = avatarBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = room.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(CircleShape),
                )
            } else {
                MonogramAvatar(name = room.name, accent = accent, highlighted = selected)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = shortLabel(room.name),
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MonogramAvatar(name: String, accent: Color, highlighted: Boolean) {
    val base = avatarColor(name)
    val bg = if (highlighted) lerp(base, accent, 0.35f) else base
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg.copy(alpha = if (highlighted) 0.95f else 0.7f)),
    ) {
        Text(
            text = monogram(name),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** A near-circular ring path for the Braille snake to trace around an avatar. */
private fun ringPath(size: Size): Path {
    val path = Path()
    val r = min(size.width, size.height) / 2f * 0.94f
    val cx = size.width / 2f
    val cy = size.height / 2f
    path.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
    return path
}

private fun monogram(name: String): String {
    val cleaned = name.trimStart('@', '#', '!').trim()
    return cleaned.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
}

private fun shortLabel(name: String): String = name.trimStart('@', '#', '!').trim()

/** Deterministic, tasteful avatar color derived from the room name. */
private val AVATAR_PALETTE = listOf(
    Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFFFB74D),
    Color(0xFFBA68C8), Color(0xFF4DB6AC), Color(0xFF7986CB), Color(0xFFF06292),
)

private fun avatarColor(name: String): Color {
    val idx = (name.hashCode() and 0x7FFFFFFF) % AVATAR_PALETTE.size
    return AVATAR_PALETTE[idx]
}
