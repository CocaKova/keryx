package chat.keryx.app.transport.direct

import chat.keryx.core.model.BlockingKind
import chat.keryx.core.model.BlockingRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One batch `clarify` server request, walked one question at a time.
 *
 * The gateway asks several questions in one request (`params.questions`, each with a `qid`) and
 * takes the answers back one lock at a time (`clarify.lock {request_id, question_id, answer}`);
 * the lock that empties `remaining` resolves the whole request server-side. Keryx has one
 * blocking card, so the batch is shown as a sequence: this holds the questions and what is
 * locked so far, and hands out the next unanswered one as the card to show.
 *
 * Pure so it is testable without a socket. A reconnect replay carries `params.answers` — the
 * locks the server already accepted — and those questions are skipped, not asked again.
 */
class ClarifyBatch private constructor(
    val requestId: String,
    private val questions: List<Question>,
    locked: Map<String, String>,
) {
    data class Question(
        val qid: String,
        val text: String,
        val choices: List<String>,
        val multiSelect: Boolean,
    )

    private val answered: MutableMap<String, String> = locked.filterKeys { k -> questions.any { it.qid == k } }.toMutableMap()

    val size: Int get() = questions.size

    /** Question ids still unanswered, in asking order. */
    val remaining: List<String> get() = questions.map { it.qid }.filter { it !in answered }

    /** The card for the next unanswered question, or null when every question is locked. */
    fun current(): BlockingRequest? {
        val idx = questions.indexOfFirst { it.qid !in answered }
        if (idx < 0) return null
        val q = questions[idx]
        return BlockingRequest(
            kind = BlockingKind.CLARIFY,
            requestId = requestId,
            prompt = q.text,
            choices = q.choices,
            multiSelect = q.multiSelect,
            questionId = q.qid,
            ordinal = idx + 1,
            total = questions.size,
        )
    }

    /** Record one answer locally (the server is told separately). Unknown ids are ignored. */
    fun lock(qid: String, answer: String) {
        if (questions.any { it.qid == qid }) answered[qid] = answer
    }

    /** The server's `remaining` after a lock is authoritative; sync to it so a lock the server
     *  rejected (expired) or accepted from another surface is reflected here. */
    fun syncRemaining(serverRemaining: List<String>) {
        val stillOpen = serverRemaining.toSet()
        questions.forEach { q ->
            if (q.qid in stillOpen) answered.remove(q.qid)
            else if (q.qid !in answered) answered[q.qid] = ""
        }
    }

    companion object {
        /** Parse a batch request's params; null when `questions` is absent (a single question)
         *  or empty. */
        fun fromParams(requestId: String, params: JsonObject): ClarifyBatch? {
            val arr = params["questions"] as? JsonArray ?: return null
            val questions = arr.mapIndexedNotNull { i, el ->
                val o = el as? JsonObject ?: return@mapIndexedNotNull null
                val text = o["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return@mapIndexedNotNull null
                val choices = (o["choices"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                Question(
                    qid = o["qid"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "q$i",
                    text = text,
                    choices = choices,
                    multiSelect = o["multi_select"]?.jsonPrimitive?.contentOrNull == "true" && choices.isNotEmpty(),
                )
            }
            if (questions.isEmpty()) return null
            val locked = (params["answers"] as? JsonObject)
                ?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }
                ?.toMap() ?: emptyMap()
            return ClarifyBatch(requestId, questions, locked)
        }
    }
}
