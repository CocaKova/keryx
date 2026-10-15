package chat.keryx.app.presentation.tour

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo

/**
 * The tour's targets (3.0): every control wearing [tourTarget], by id, held as its live layout
 * so the tour measures it when it asks rather than trusting the last move it was told about.
 * The drawer slides on a layer, and a layer moving does not count as the content being placed
 * again, so a stored rectangle would still say "off screen" over an open drawer.
 *
 * Nothing here is state: the overlay reads targets once a frame while a tour runs, and no one
 * reads them otherwise.
 */
@Stable
class TourRegistry {
    private val targets = HashMap<String, LayoutCoordinates>()

    /** The target's visible bounds in the window right now, or null when none of it shows. */
    fun boundsOf(id: String): Rect? = targets[id]
        ?.takeIf { it.isAttached }
        ?.boundsInRoot()
        ?.takeIf { it.width >= 4f && it.height >= 4f }

    /** Whether some control wears [id] right now, on screen or not (the closed drawer's doors
     *  are composed and worn). What the screen draws decides what a tour may name. */
    fun isWorn(id: String): Boolean = targets[id]?.isAttached == true

    internal fun hold(id: String, coordinates: LayoutCoordinates) {
        targets[id] = coordinates
    }

    internal fun release(id: String, coordinates: LayoutCoordinates?) {
        // Only its own: two copies of a control can overlap for a frame as a place swaps in.
        if (coordinates == null || targets[id] === coordinates) targets.remove(id)
    }
}

val LocalTourRegistry = staticCompositionLocalOf<TourRegistry?> { null }

/**
 * Marks a control a tour can point at, under a stable [id] (the Desktop's `[data-tour="…"]`).
 * The bounds are the visible part only: in the closed drawer, or scrolled away, it reads as
 * not on screen, and the tour waits for it.
 */
fun Modifier.tourTarget(id: String): Modifier = this then TourTargetElement(id)

private data class TourTargetElement(val id: String) : ModifierNodeElement<TourTargetNode>() {
    override fun create() = TourTargetNode(id)
    override fun update(node: TourTargetNode) {
        if (node.id != id) {
            node.registry?.release(node.id, node.coordinates)
            node.id = id
            node.coordinates?.let { node.registry?.hold(id, it) }
        }
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "tourTarget"
        properties["id"] = id
    }
}

private class TourTargetNode(var id: String) :
    Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    var registry: TourRegistry? = null
    var coordinates: LayoutCoordinates? = null

    override fun onAttach() {
        registry = currentValueOf(LocalTourRegistry)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (this.coordinates === coordinates) return
        this.coordinates = coordinates
        registry?.hold(id, coordinates)
    }

    override fun onDetach() {
        registry?.release(id, coordinates)
        registry = null
        coordinates = null
    }
}
