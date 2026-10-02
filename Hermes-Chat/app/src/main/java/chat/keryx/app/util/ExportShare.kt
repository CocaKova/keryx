package chat.keryx.app.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import chat.keryx.app.presentation.SessionExportDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hand an exported session to the system share sheet (2.16).
 *
 * Staged under the FileProvider's `media/` cache root (the only one it publishes), in its own
 * `exports/` folder that is emptied first: an export is a hand-off, not a library, and a phone
 * that exports a long chat every day should not keep every copy in its cache.
 */
suspend fun shareExport(context: Context, file: SessionExportDelegate.ExportFile): Boolean = runCatching {
    val staged = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "media/exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { writeBytes(file.bytes) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = file.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.subject)
        putExtra(Intent.EXTRA_TITLE, staged.name)
        // The grant rides the ClipData: without it the chooser's preview (and some targets)
        // can't read the file even with the flag set.
        clipData = ClipData.newRawUri(staged.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share ${staged.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
}.getOrDefault(false)
