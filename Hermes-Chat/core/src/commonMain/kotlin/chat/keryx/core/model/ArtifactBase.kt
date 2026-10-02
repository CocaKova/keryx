package chat.keryx.core.model

/**
 * An artifact's siblings (2.16). A page opened by path loads under a synthetic origin
 * ([ORIGIN] + its folder), so `<link href="style.css">` and `<img src="img/a.png">` ask for
 * real sibling paths; the viewer answers those from the gateway's file API. Only paths inside
 * the artifact's own folder are answered — a page cannot walk up out of it with `..`.
 */
object ArtifactBase {
    const val HOST = "artifact.keryx.invalid"
    const val ORIGIN = "https://$HOST"

    /** The folder a page at [path] resolves its relative links against, or null at the root. */
    fun folderOf(path: String): String? =
        path.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() }

    /** The base URL to load [path] under: "https://artifact.keryx.invalid/home/me/site/". */
    fun baseUrl(path: String): String? = folderOf(path)?.let { dir ->
        ORIGIN + (if (dir.startsWith("/")) dir else "/$dir") + "/"
    }

    /**
     * The gateway path a request on the synthetic origin asks for, or null when it isn't one, or
     * it leaves [folder]. [requestPath] is the URL's path (percent-decoded).
     */
    fun resolve(folder: String, host: String?, requestPath: String?): String? {
        if (host != HOST || requestPath.isNullOrEmpty()) return null
        val parts = ArrayList<String>()
        for (seg in requestPath.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        val resolved = "/" + parts.joinToString("/")
        val root = "/" + folder.trim('/')
        return resolved.takeIf { it.startsWith("$root/") }
    }

    /** A content type for a sibling, by extension; the WebView needs one to run CSS and JS. */
    fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }
}
