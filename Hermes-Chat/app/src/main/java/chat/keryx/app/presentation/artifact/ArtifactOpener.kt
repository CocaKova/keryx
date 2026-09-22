package chat.keryx.app.presentation.artifact

import androidx.compose.runtime.compositionLocalOf

/**
 * What a card or a chip knows about the page it wants opened (2.13). [path] is the file on the
 * gateway host, when the prose or the `MEDIA:` line named one; [roomId] + [eventId] name the
 * media message whose bytes the transport already carries, on either door. One of the two must
 * be present; the viewer prefers the bytes it already has over a second trip to the host.
 */
data class ArtifactRef(
    val path: String?,
    val name: String,
    val roomId: String? = null,
    val eventId: String? = null,
)

/**
 * The way from a bubble to the viewer. The render chain does not carry the nav stack (the same
 * reason the Skill Forge opens through [chat.keryx.app.presentation.ui.components.LocalSkillForgeOpener]),
 * so the app root provides this once and every media card and prose chip reads it.
 *
 * [canReadPaths] is the direct door: only there can a bare path in the prose be fetched, so
 * only there does a sentence earn an "Open" chip. A `MEDIA:` card opens on both doors through
 * its bytes. Null = no host is listening (a preview, a test) and the card falls back to the
 * system chooser it always had.
 */
class ArtifactOpener(
    val canReadPaths: Boolean,
    private val onOpen: (ArtifactRef) -> Unit,
) {
    fun open(ref: ArtifactRef) = onOpen(ref)
}

val LocalArtifactOpener = compositionLocalOf<ArtifactOpener?> { null }
