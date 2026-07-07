package chat.keryx.app.presentation.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import chat.keryx.app.data.remote.HermesStreamClient.PetInfo
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The petdex mascot from the Hermes desktop app, scaled down to a drawer-header ornament.
 *
 * The spritesheet (rows = animation states, fixed-size frames) comes from `GET /keryx/pet` — the
 * same payload the desktop's floating pet and the TUI render, so the phone shows the same pet in
 * the same poses. This component only steps frames; *which* pose to show is the caller's call.
 */

/** Poses the drawer pet can strike — the subset of desktop activity states the drawer can know. */
enum class PetPose { IDLE, WAVE, RUN }

/** Sheet row-name aliases per pose: Codex sheets name rows `waving`/`running`, legacy ones
 *  `wave`/`run`. Same alias table as the engine's `state_row_index`. */
private fun rowIndexFor(pose: PetPose, stateRows: List<String>): Int {
    val aliases = when (pose) {
        PetPose.IDLE -> listOf("idle")
        PetPose.WAVE -> listOf("wave", "waving")
        PetPose.RUN -> listOf("run", "running")
    }
    for (name in aliases) {
        val i = stateRows.indexOf(name)
        if (i >= 0) return i
    }
    return 0 // idle row tops both taxonomies
}

private class PetSheet(val image: ImageBitmap, val frameW: Int, val frameH: Int)

/** Decode the base64 sheet at half resolution: the full Codex atlas is ~11MB as ARGB and the
 *  drawer draws a ~28dp sprite — half-res frames (96×104) are still ≥1:1 at that size. */
private fun decodeSheet(info: PetInfo): PetSheet? = runCatching {
    val bytes = Base64.decode(info.spritesheetBase64, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return@runCatching null
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
    // Derive the decoded frame box from the actual output size rather than assuming the sampler
    // honored inSampleSize exactly (webp decoders may round).
    val scale = bmp.width.toFloat() / bounds.outWidth
    PetSheet(
        image = bmp.asImageBitmap(),
        frameW = (info.frameW * scale).roundToInt().coerceAtLeast(1),
        frameH = (info.frameH * scale).roundToInt().coerceAtLeast(1),
    )
}.getOrNull()

/**
 * A small animated pet. [running] gates the frame ticker — the drawer composes offscreen, so the
 * host passes real visibility (same contract as the Braille snake beside it).
 */
@Composable
fun PetSprite(
    info: PetInfo,
    pose: PetPose,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val sheet = remember(info.revision) { decodeSheet(info) } ?: return
    val row = rowIndexFor(pose, info.stateRows)
    val rowName = info.stateRows.getOrNull(row) ?: "idle"
    // Real frame count for this row (ragged sheets pad short rows with blanks — stepping into
    // the padding reads as the pet blinking out).
    val frames = (info.framesByRow[rowName] ?: info.framesPerState).coerceAtLeast(1)

    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(running, rowName, frames, info.revision) {
        frame = 0
        if (!running) return@LaunchedEffect
        // petdex timing: one loop of framesPerState frames per loopMs, whatever the row's length.
        val stepMs = (info.loopMs / info.framesPerState.coerceAtLeast(1)).coerceAtLeast(50).toLong()
        while (true) {
            delay(stepMs)
            frame = (frame + 1) % frames
        }
    }

    Canvas(modifier = modifier) {
        drawFrame(sheet, row, frame)
    }
}

private fun DrawScope.drawFrame(sheet: PetSheet, row: Int, frame: Int) {
    // Guard against a frame index outside the decoded sheet (e.g. taxonomy said 9 rows but the
    // sheet has 8) — draw idle's first frame instead of throwing over an ornament.
    val maxRow = (sheet.image.height / sheet.frameH) - 1
    val maxCol = (sheet.image.width / sheet.frameW) - 1
    val r = row.coerceIn(0, maxRow.coerceAtLeast(0))
    val c = frame.coerceIn(0, maxCol.coerceAtLeast(0))
    drawImage(
        image = sheet.image,
        srcOffset = IntOffset(c * sheet.frameW, r * sheet.frameH),
        srcSize = IntSize(sheet.frameW, sheet.frameH),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        // Pixel art: nearest-neighbor keeps the sprite crisp instead of smearing it.
        filterQuality = FilterQuality.None,
    )
}
