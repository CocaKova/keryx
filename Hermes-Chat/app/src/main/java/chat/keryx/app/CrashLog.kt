package chat.keryx.app

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal local crash journal. An uncaught exception is appended to `files/keryx-crash.log`
 * before the system handler takes over, so a user can share what actually happened instead of
 * "it just closed". No network, no analytics — the log never leaves the device unless the user
 * explicitly shares it from Settings → Diagnostics.
 */
object CrashLog {

    private const val FILE_NAME = "keryx-crash.log"
    private const val MAX_BYTES = 128 * 1024L // keep the tail; a crash log is not an archive

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { append(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun append(context: Context, thread: Thread, throwable: Throwable) {
        val file = file(context)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            append("\n=== ").append(stamp)
            append(" · thread ").append(thread.name)
            append(" · v").append(BuildConfig.VERSION_NAME)
            append(" ===\n")
            append(android.util.Log.getStackTraceString(throwable))
        }
        file.appendText(entry)
        // Trim to the newest MAX_BYTES so the log can't grow unbounded across many crashes.
        if (file.length() > MAX_BYTES) {
            val tail = file.readBytes().let { it.copyOfRange((it.size - MAX_BYTES).toInt(), it.size) }
            file.writeBytes(tail)
        }
    }

    fun read(context: Context): String =
        file(context).takeIf { it.exists() }?.readText().orEmpty()

    fun clear(context: Context) {
        file(context).delete()
    }

    /** Hand the log to the system share sheet as plain text. */
    fun share(context: Context) {
        val text = read(context)
        if (text.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Keryx crash log")
            putExtra(Intent.EXTRA_TEXT, text.takeLast(60_000))
        }
        context.startActivity(
            Intent.createChooser(send, "Share crash log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
