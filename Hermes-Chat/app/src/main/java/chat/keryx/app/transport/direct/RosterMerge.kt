package chat.keryx.app.transport.direct

/**
 * Fold a short "most recently active" page into the roster already held (2.17.1).
 *
 * A row in [fresh] replaces the held row with its id; a fresh row now archived leaves the list;
 * everything else stays where the last full read put it. The result keeps the gateway's
 * `order=recent` shape: newest activity first, ties in the order they were held.
 *
 * A fresh row that compaction moved to a new id names its old ids in [GatewayRest.SessionRow.lineage];
 * the held row under the old id is the same conversation and leaves (2.19). Matching on id
 * alone kept both, and the drawer showed one chat twice until the next full read.
 */
internal fun mergeRecent(
    held: List<GatewayRest.SessionRow>,
    fresh: List<GatewayRest.SessionRow>,
): List<GatewayRest.SessionRow> {
    if (fresh.isEmpty()) return held
    val byId = fresh.associateBy { it.id }
    val kept = held.filter { it.id !in byId }
    val merged = fresh.filter { !it.archived } + kept
    return dropSuperseded(merged).sortedByDescending { it.lastActive }
}

/** [rows] without any a newer row's compaction lineage has replaced. */
internal fun dropSuperseded(rows: List<GatewayRest.SessionRow>): List<GatewayRest.SessionRow> =
    chat.keryx.core.model.SessionTree.dropSuperseded(rows, { it.id }, { it.lineage })
