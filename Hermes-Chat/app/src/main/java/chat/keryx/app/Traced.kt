package chat.keryx.app

/** A named slice in a Perfetto trace (3.0 perf §4), so a cold-start trace says which part of
 *  start-up took the time. Costs a flag check unless a trace is recording. */
internal inline fun <T> traced(name: String, block: () -> T): T {
    android.os.Trace.beginSection(name)
    try {
        return block()
    } finally {
        android.os.Trace.endSection()
    }
}
