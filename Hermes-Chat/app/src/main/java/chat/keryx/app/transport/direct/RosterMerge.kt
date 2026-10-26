package chat.keryx.app.transport.direct

import chat.keryx.core.model.RoomProfile

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

/**
 * The chats this phone minted that the gateway's list does not carry yet, and no others (3.0).
 *
 * A placeholder only bridges the gap until its chat is first listed. Once the list names it, under
 * its own id or under the id compaction moved it to, the listed row IS that conversation. Kept past
 * that point, it came back as a second "New session" row the moment a compaction gave the chat a
 * new id and the old one left the list.
 */
internal fun unlistedPending(
    pending: List<RoomProfile>,
    listed: Set<String>,
    forwarded: (String) -> String,
): List<RoomProfile> = pending.filterNot { it.id in listed || forwarded(it.id) in listed }
