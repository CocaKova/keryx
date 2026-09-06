package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.keryx.app.presentation.ChatViewModel
import kotlin.math.atan2

// The settings primitives: one group, one switch row, one anchor for search to land on.
// Split out of SettingsDialog.kt in 2.10 (Phase E): a move, zero behaviour change.

/**
 * A settings group (2.10): a heading, its rows, one hairline after. Flat, not boxed — until 2.10
 * every group was a bordered card on the dusk sky, a box inside a page inside a box, and the
 * rows inside were the third frame deep. Whitespace groups; a single hairline divides. (The
 * Desktop's DESIGN.md rule, word for word: "no card-in-card, no divider borders inside a panel.")
 * [anchor] names the group for search — a hit scrolls here and lights the heading.
 */
@Composable
internal fun SettingsCard(title: String, anchor: SettingsRow? = null, content: @Composable ColumnScope.() -> Unit) {
    val body: @Composable ColumnScope.() -> Unit = {
        KeryxSectionHeader(title, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 6.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), content = content)
        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            modifier = Modifier.padding(top = 18.dp),
        )
    }
    if (anchor != null) SettingsAnchor(anchor, body) else Column(content = body)
}

/** A choice's name, above its segmented row. */
@Composable
internal fun SettingsChoiceLabel(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 8.dp))
}

/**
 * Where a search hit lands (2.10). The page scrolls until the row is a hand's width from the
 * top, the row's ground glows accent for a breath, and the target is consumed — so a second
 * open of the same section is a plain open. Nothing here knows what search is; it knows only
 * that something asked for this row by name.
 */
class SettingsFocus(
    val target: String?,
    val scroll: androidx.compose.foundation.ScrollState,
    val contentTop: () -> Float,
    val consume: () -> Unit,
)

val LocalSettingsFocus = staticCompositionLocalOf<SettingsFocus?> { null }

@Composable
fun SettingsAnchor(row: SettingsRow, content: @Composable ColumnScope.() -> Unit) {
    val focus = LocalSettingsFocus.current
    val wanted = focus?.target == row.id
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    var rootY by remember { mutableStateOf<Float?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(wanted, rootY) {
        val y = rootY
        if (!wanted || focus == null || y == null) return@LaunchedEffect
        val offset = with(density) { 96.dp.toPx() }
        val to = (y - focus.contentTop() - offset).toInt().coerceAtLeast(0)
        focus.scroll.animateScrollTo(to)
        glow.snapTo(1f)
        glow.animateTo(0f, androidx.compose.animation.core.tween(1800))
        focus.consume()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rootY = it.positionInRoot().y + focus?.scroll?.value.orZero() }
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f * glow.value)),
        content = content,
    )
}

internal fun Int?.orZero(): Float = (this ?: 0).toFloat()

@Composable
fun SettingsSectionHeader(title: String) {
    KeryxSectionHeader(title, modifier = Modifier.padding(bottom = 16.dp))
}

/** The one switch row: title, caption, switch. [anchor] names it for search. */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    anchor: SettingsRow? = null,
) {
    val row: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(text = subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
    if (anchor != null) SettingsAnchor(anchor, row) else Column(content = row)
}
