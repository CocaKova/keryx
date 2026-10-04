package chat.keryx.app.transport.direct

/**
 * Fold a short "most recently active" page into the roster already held (2.17.1).
 *
 * A row in [fresh] replaces the held row with its id; a fresh row now archived leaves the list;
 * everything else stays where the last full read put it. The result keeps the gateway's
 * `order=recent` shape: newest activity first, ties in the order they were held.
 */
internal fun mergeRecent(
    held: List<GatewayRest.SessionRow>,
    fresh: List<GatewayRest.SessionRow>,
): List<GatewayRest.SessionRow> {
    if (fresh.isEmpty()) return held
    val byId = fresh.associateBy { it.id }
    val kept = held.filter { it.id !in byId }
    val merged = fresh.filter { !it.archived } + kept
    return merged.sortedByDescending { it.lastActive }
}
