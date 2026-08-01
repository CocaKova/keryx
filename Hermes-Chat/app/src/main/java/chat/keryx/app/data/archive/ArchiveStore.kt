package chat.keryx.app.data.archive

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The Archive's local index (1.26 "Mnemosyne"): every message the app has ever seen, in one
 * searchable SQLite database the app owns outright.
 *
 * Why not query Trixnity's own Room store? Its schema is an SDK internal that shifts between
 * versions, and its timeline rows are serialized JSON — unsearchable without a full decode pass.
 * This index costs a little duplication (plain text is tiny next to media) and buys a stable
 * schema, FTS4 full-text search, and date/media queries that answer in milliseconds.
 *
 * Server-side Matrix search is not an option at all: the rooms are E2EE and Synapse cannot see
 * into them. The phone is the only place the plaintext exists — so the phone carries the index.
 */
class ArchiveStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "keryx_archive.db", null, 1) {

    /** One indexed message. [mediaKind] uses the MediaKind enum name, null for plain text. */
    data class Entry(
        val eventId: String,
        val roomId: String,
        val sender: String,
        val timestamp: Long,
        val mediaKind: String?,
        val fileName: String,
        val body: String,
    )

    /** A search result: the entry plus a match snippet with [SNIP_START]/[SNIP_END] markers. */
    data class Hit(val entry: Entry, val snippet: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE msg (
                rowid INTEGER PRIMARY KEY,
                event_id TEXT UNIQUE NOT NULL,
                room_id TEXT NOT NULL,
                sender TEXT NOT NULL,
                ts INTEGER NOT NULL,
                media_kind TEXT,
                file_name TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_msg_room_ts ON msg(room_id, ts)")
        // unicode61 tokenizer: proper word breaks beyond ASCII (API 21+, safely below minSdk 24).
        db.execSQL("CREATE VIRTUAL TABLE msg_fts USING fts4(body, tokenize=unicode61)")
        db.execSQL(
            """CREATE TABLE saved (
                event_id TEXT PRIMARY KEY,
                room_id TEXT NOT NULL,
                sender TEXT NOT NULL,
                ts INTEGER NOT NULL,
                media_kind TEXT,
                file_name TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL,
                saved_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** The index belongs to one Matrix account. A different login gets a fresh archive. */
    @Synchronized
    fun ensureAccount(userId: String) {
        val db = writableDatabase
        val current = metaGet(db, "account")
        if (current == userId) return
        db.beginTransaction()
        try {
            listOf("msg", "msg_fts", "saved").forEach { db.execSQL("DELETE FROM $it") }
            metaPut(db, "account", userId)
            // Backfill flags belong to the wiped index — drop them with it.
            db.execSQL("DELETE FROM meta WHERE key LIKE 'complete|%'")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun backfillComplete(roomId: String): Boolean =
        metaGet(readableDatabase, "complete|$roomId") == "1"

    fun setBackfillComplete(roomId: String) =
        metaPut(writableDatabase, "complete|$roomId", "1")

    fun hasEvent(eventId: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM msg WHERE event_id=? LIMIT 1", arrayOf(eventId))
            .use { it.moveToFirst() }

    /** Insert entries new to the index (existing event ids are left untouched). Returns how many
     *  were actually new. */
    @Synchronized
    fun insertAll(entries: List<Entry>): Int {
        if (entries.isEmpty()) return 0
        val db = writableDatabase
        var fresh = 0
        db.beginTransaction()
        try {
            for (e in entries) {
                val rowId = db.insertWithOnConflict(
                    "msg",
                    null,
                    ContentValues().apply {
                        put("event_id", e.eventId)
                        put("room_id", e.roomId)
                        put("sender", e.sender)
                        put("ts", e.timestamp)
                        put("media_kind", e.mediaKind)
                        put("file_name", e.fileName)
                        put("body", e.body)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (rowId != -1L) {
                    fresh++
                    db.execSQL(
                        "INSERT INTO msg_fts(docid, body) VALUES (?, ?)",
                        arrayOf(rowId.toString(), e.body),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return fresh
    }

    fun count(roomId: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM msg WHERE room_id=?", arrayOf(roomId))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun search(roomId: String, rawQuery: String, limit: Int = 120): List<Hit> {
        val match = buildMatchQuery(rawQuery) ?: return emptyList()
        return readableDatabase.rawQuery(
            """SELECT m.event_id, m.room_id, m.sender, m.ts, m.media_kind, m.file_name, m.body,
                      snippet(msg_fts, '$SNIP_START', '$SNIP_END', '…', -1, 12) AS snip
               FROM msg_fts f JOIN msg m ON m.rowid = f.docid
               WHERE msg_fts MATCH ? AND m.room_id = ?
               ORDER BY m.ts DESC LIMIT $limit""",
            arrayOf(match, roomId),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Hit(c.entry(), c.getString(7))) }
        }
    }

    fun media(roomId: String, limit: Int = 600): List<Entry> =
        readableDatabase.rawQuery(
            "SELECT event_id, room_id, sender, ts, media_kind, file_name, body FROM msg " +
                "WHERE room_id=? AND media_kind IS NOT NULL ORDER BY ts DESC LIMIT $limit",
            arrayOf(roomId),
        ).use { c -> buildList { while (c.moveToNext()) add(c.entry()) } }

    /** The event to land on for a given day: the first message on/after [dayStartMillis], falling
     *  back to the last one before it (a quiet day still lands you in the right era). */
    fun eventForDate(roomId: String, dayStartMillis: Long): String? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT event_id FROM msg WHERE room_id=? AND ts>=? ORDER BY ts ASC LIMIT 1",
            arrayOf(roomId, dayStartMillis.toString()),
        ).use { if (it.moveToFirst()) return it.getString(0) }
        db.rawQuery(
            "SELECT event_id FROM msg WHERE room_id=? AND ts<? ORDER BY ts DESC LIMIT 1",
            arrayOf(roomId, dayStartMillis.toString()),
        ).use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    /** Oldest and newest indexed timestamps for the room, or null when empty. */
    fun timeSpan(roomId: String): Pair<Long, Long>? =
        readableDatabase.rawQuery(
            "SELECT MIN(ts), MAX(ts) FROM msg WHERE room_id=? ", arrayOf(roomId),
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) to c.getLong(1) else null
        }

    // --- saved messages ---

    fun savedIds(roomId: String): Set<String> =
        readableDatabase.rawQuery("SELECT event_id FROM saved WHERE room_id=?", arrayOf(roomId))
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    fun saved(roomId: String): List<Entry> =
        readableDatabase.rawQuery(
            "SELECT event_id, room_id, sender, ts, media_kind, file_name, body FROM saved " +
                "WHERE room_id=? ORDER BY saved_at DESC",
            arrayOf(roomId),
        ).use { c -> buildList { while (c.moveToNext()) add(c.entry()) } }

    @Synchronized
    fun addSaved(e: Entry) {
        writableDatabase.insertWithOnConflict(
            "saved",
            null,
            ContentValues().apply {
                put("event_id", e.eventId)
                put("room_id", e.roomId)
                put("sender", e.sender)
                put("ts", e.timestamp)
                put("media_kind", e.mediaKind)
                put("file_name", e.fileName)
                put("body", e.body)
                put("saved_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun removeSaved(eventId: String) {
        writableDatabase.delete("saved", "event_id=?", arrayOf(eventId))
    }

    // --- helpers ---

    private fun Cursor.entry() = Entry(
        eventId = getString(0),
        roomId = getString(1),
        sender = getString(2),
        timestamp = getLong(3),
        mediaKind = if (isNull(4)) null else getString(4),
        fileName = getString(5),
        body = getString(6),
    )

    private fun metaGet(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun metaPut(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            "meta",
            null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    companion object {
        // Ornate brackets no one types in chat; a stray real one just over-highlights harmlessly.
        const val SNIP_START = "⟪"
        const val SNIP_END = "⟫"

        /**
         * Turn what the user typed into an FTS4 MATCH expression: each word quoted (so FTS syntax
         * characters in the input can't break the query), the final word given a `*` so results
         * appear while a word is still being typed. Null when there's nothing to search.
         */
        fun buildMatchQuery(raw: String): String? {
            val words = raw.trim().split(Regex("\\s+"))
                .map { it.replace("\"", "") }
                .filter { it.isNotBlank() }
            if (words.isEmpty()) return null
            return words.mapIndexed { i, w ->
                if (i == words.lastIndex) "\"$w*\"" else "\"$w\""
            }.joinToString(" ")
        }
    }
}
