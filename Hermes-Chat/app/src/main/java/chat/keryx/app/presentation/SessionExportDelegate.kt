package chat.keryx.app.presentation

import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.app.transport.direct.sessionExport
import chat.keryx.core.model.ExportFormat
import chat.keryx.core.model.Routed
import chat.keryx.core.model.SessionExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Export and share out (2.16): one session, as the file the gateway's export route serves (JSON,
 * complete) or as a readable Markdown transcript built from that same export.
 *
 * The bytes come from here; putting them in front of the share sheet is the caller's (it holds
 * the Context). Dashboard route, so the direct door only.
 */
class SessionExportDelegate(
    private val rest: () -> GatewayRest?,
    private val profileFor: (String) -> String?,
) {
    /** False once this gateway has answered that it has no export route — the menu entry
     *  goes away instead of offering something that can only fail. */
    private val _supported = MutableStateFlow(true)
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    class ExportFile(val name: String, val mime: String, val subject: String, val bytes: ByteArray)

    suspend fun export(
        sessionId: String,
        title: String,
        format: ExportFormat,
        /** Who the agent's turns are headed with in the Markdown: a Bot Chat's bot, else "Hermes". */
        agentName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Result<ExportFile> {
        val r = rest() ?: return Result.failure(IllegalStateException("Export needs the gateway door."))
        val body = when (val got = r.sessionExport(sessionId, profileFor(sessionId))) {
            is Routed.Ok -> got.value
            is Routed.Failed -> return Result.failure(IllegalStateException(got.message))
            Routed.Missing -> {
                _supported.value = false
                return Result.failure(IllegalStateException("This gateway is too old to export a session."))
            }
        }
        val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
        val name = SessionExport.fileName(title, sessionId, format, nowMs, tz)
        val subject = title.ifBlank { "Hermes session" }
        return withContext(Dispatchers.Default) {
            when (format) {
                // The gateway's own file, byte for byte: re-importable, nothing reinterpreted.
                ExportFormat.JSON -> Result.success(ExportFile(name, format.mime, subject, body.encodeToByteArray()))
                ExportFormat.MARKDOWN -> {
                    val doc = SessionExport.parse(body)
                        ?: return@withContext Result.failure<ExportFile>(IllegalStateException("The export came back unreadable."))
                    val md = SessionExport.markdown(doc, agentName, tz)
                    Result.success(ExportFile(name, format.mime, subject, md.encodeToByteArray()))
                }
            }
        }
    }
}
