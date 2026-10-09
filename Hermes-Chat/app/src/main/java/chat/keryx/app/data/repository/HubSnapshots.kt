package chat.keryx.app.data.repository

/**
 * Which gateway answers the offline hub cache keeps (2.19.1). Only the panels that are read back
 * on launch: anything else (a transcript, one kanban task, one skill) was written on every poll,
 * never read, and grew the cache file without bound.
 */
object HubSnapshots {
    val KEPT: Set<String> = setOf(
        "/health/detailed", "/api/jobs", "/api/sessions", "/v1/skills",
        "/keryx/toolsets", "/v1/models", "/keryx/config", "/keryx/brains",
        "keryx://console/runs",
    )

    /** [key] as stored: the path, plus ".<gateway>" when scoped to one. */
    fun keeps(key: String): Boolean = KEPT.any { key == it || key.startsWith("$it.") }
}
