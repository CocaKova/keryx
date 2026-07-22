package chat.keryx.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import chat.keryx.app.domain.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** MIME for a media payload — extension first, kind as the fallback wildcard. */
fun mediaMimeFor(fileName: String, kind: MediaKind): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val fromExt = if (ext.isNotBlank())
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null
    return fromExt ?: when (kind) {
        MediaKind.VIDEO -> "video/*"
        MediaKind.AUDIO -> "audio/*"
        MediaKind.IMAGE -> "image/*"
        MediaKind.FILE -> "*/*"
    }
}

private fun sanitized(fileName: String, kind: MediaKind): String {
    val fallbackExt = when (kind) {
        MediaKind.IMAGE -> "jpg"; MediaKind.VIDEO -> "mp4"
        MediaKind.AUDIO -> "ogg"; MediaKind.FILE -> "bin"
    }
    val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "keryx_media" }
    return if (safe.contains('.')) safe else "$safe.$fallbackExt"
}

/**
 * Writes media into the device's public collections (Pictures/Movies/Music/Download under a
 * Keryx folder) so it survives the app and shows up in Gallery/Files. Returns the
 * human-readable folder for the confirmation toast, or null on failure. MediaStore handles
 * name collisions on 29+; the legacy path dedups by suffix.
 */
suspend fun saveMediaToDevice(
    context: Context,
    bytes: ByteArray,
    fileName: String,
    kind: MediaKind,
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val name = sanitized(fileName, kind)
        val mime = mediaMimeFor(name, kind)
        val subDir = when (kind) {
            MediaKind.IMAGE -> Environment.DIRECTORY_PICTURES
            MediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
            MediaKind.AUDIO -> Environment.DIRECTORY_MUSIC
            MediaKind.FILE -> Environment.DIRECTORY_DOWNLOADS
        }
        val label = "$subDir/Keryx"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = when (kind) {
                MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                MediaKind.FILE -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, label)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            // Pre-Q: direct write into the public dir (WRITE_EXTERNAL_STORAGE, maxSdk 28).
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(subDir), "Keryx")
            dir.mkdirs()
            var out = File(dir, name)
            var n = 1
            while (out.exists()) {
                out = File(dir, "${name.substringBeforeLast('.')}_$n.${name.substringAfterLast('.')}")
                n++
            }
            out.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(out.path), arrayOf(mime), null)
        }
        label
    }.getOrNull()
}

/** Stages the bytes in the FileProvider cache dir and opens the system share sheet. */
suspend fun shareMedia(context: Context, bytes: ByteArray, fileName: String, kind: MediaKind): Boolean {
    return runCatching {
        val name = sanitized(fileName, kind)
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "media").apply { mkdirs() }
            File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { writeBytes(bytes) }
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mediaMimeFor(name, kind)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
