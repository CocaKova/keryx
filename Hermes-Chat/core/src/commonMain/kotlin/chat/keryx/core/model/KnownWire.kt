package chat.keryx.core.model

/**
 * Every Hermes name Keryx matches on, in one place (2.19).
 *
 * The `todo` → `todo_list` rename blanked the Flight Plan for a week because nothing compared
 * the names the app matches on with the names Hermes sends. `tools/hermes_drift.py` reads this
 * file and diffs it against a live Hermes tree (tool registry, alias table, gateway contract);
 * the ship gate runs it after every build. Two tests keep this table honest from the other
 * side: `KnownWireTest` (every tool [ToolGrammar] names is here) and `KnownWireSourceTest`
 * (every dotted wire literal in the app's source is here).
 *
 * The script parses the `setOf(...)` blocks below by name, so keep them plain string lists.
 */
object KnownWire {

    /** Tools the app draws by name: verbs, families, targets, gerund lines. */
    val TOOLS: Set<String> = setOf(
        "browser_click", "browser_navigate", "browser_type", "clarify", "computer_use",
        "cronjob_manage", "delegate_task", "execute_code", "gui_tour", "ha_call_service",
        "ha_get_state", "ha_list_entities", "ha_list_services", "image_generate",
        "manage_connections", "memory", "patch", "process_manage", "react_to_message",
        "read_file", "search_files", "session_search", "show_tip", "skill_manage", "skill_view",
        "skills_list", "terminal", "text_to_speech", "todo_list", "video_analyze",
        "video_generate", "vision_analyze", "web_extract", "web_search", "write_file", "x_search",
    )

    /**
     * Which argument IS the call, per tool (Hermes `agent/display.build_tool_preview`): the
     * one-line preview on a tool row. The script checks each against the tool's schema, so an
     * argument rename shows up here instead of as a blank preview.
     */
    val PRIMARY_ARGS: Map<String, String> = mapOf(
        "terminal" to "command", "web_search" to "query", "web_extract" to "urls",
        "read_file" to "path", "write_file" to "path", "patch" to "path",
        "search_files" to "pattern", "browser_navigate" to "url",
        "browser_click" to "ref", "browser_type" to "text",
        "image_generate" to "prompt", "text_to_speech" to "text",
        "vision_analyze" to "question", "skill_view" to "name", "skills_list" to "category",
        "cronjob_manage" to "action", "process_manage" to "action",
        "ha_call_service" to "entity_id", "ha_get_state" to "entity_id", "ha_list_entities" to "domain",
        "x_search" to "query", "video_analyze" to "question", "execute_code" to "code", "browser_exec" to "code",
        "delegate_task" to "tasks", "clarify" to "questions", "skill_manage" to "operations",
    )

    /** Tool families matched by prefix; each must still name at least one Hermes tool. */
    val TOOL_PREFIXES: Set<String> = setOf("browser_", "kanban_")

    /** Tools Hermes adds around the registry (the deferred-tool bridge and its lookups). */
    val META_TOOLS: Set<String> = setOf("tool_call", "tool_search", "tool_describe")

    /**
     * Names kept for old transcripts and older gateways — expected to be absent from a
     * current Hermes. The renamed five come from [ToolWire.LEGACY_ALIASES]; the rest predate it.
     */
    val LEGACY_TOOLS: Set<String> = setOf(
        "todo", "cronjob", "process", "tour", "tip",
        "list_files", "edit_file", "session_search_recall",
    )

    /** Gateway JSON-RPC methods the app calls. */
    val METHODS: Set<String> = setOf(
        "approval.respond", "clarify.lock", "client.capabilities", "command.dispatch", "commands.catalog",
        "complete.path", "config.get", "config.set", "file.attach", "groups.approve",
        "groups.create", "groups.disband", "groups.list", "groups.log", "groups.rename",
        "groups.retry", "groups.send", "groups.state", "groups.stop", "image.attach_bytes",
        "message.react", "model.options", "profiles.configure", "profiles.create",
        "profiles.get_asset", "profiles.list", "projects.archive", "projects.create",
        "projects.delete", "projects.list", "projects.project_sessions", "projects.tree",
        "prompt.btw", "prompt.submit", "session.active_list", "session.branch", "session.close",
        "session.compress", "session.context_breakdown", "session.control",
        "session.control.read", "session.create", "session.events.since", "session.interrupt",
        "session.list", "session.redirect", "session.resume", "session.steer", "session.title",
        "session.undo", "session.usage", "session.workspace.move", "slash.exec",
        "subagent.interrupt", "subagent.steer", "subagent.tail",
    )

    /** Gateway notifications the app handles. */
    val EVENTS: Set<String> = setOf(
        "approval.cancelled", "btw.complete", "gateway.ready", "message.complete",
        "message.delta", "message.interim", "message.start", "reasoning.available",
        "reasoning.delta", "request.cancel", "review.summary", "session.control.update",
        "session.info", "session.reclaimed", "session.usage", "sessions.changed",
        "status.update", "subagent.complete", "subagent.progress", "subagent.spawn_requested",
        "subagent.start", "subagent.thinking", "subagent.tool", "thinking.delta", "todo.updated",
        "tool.complete", "tool.generating", "tool.output_risk", "tool.start", "error",
    )

    /** Server→client requests the app answers (anything else is declined with 4404). */
    val SERVER_REQUESTS: Set<String> = setOf("approval", "clarify", "sudo", "secret")

    /** Events from before blocking prompts became server requests; still read, no longer sent. */
    val LEGACY_EVENTS: Set<String> = setOf(
        "approval.request", "clarify.request", "clarify.expire", "sudo.request", "sudo.expire",
        "secret.request", "secret.expire",
    )

    /** `session.control` actions the goal strip sends. */
    val CONTROL_ACTIONS: Set<String> = setOf("goal.pause", "goal.clear")

    /** SSE events on the API server's `/v1/runs` stream (the stream door). */
    val RUN_EVENTS: Set<String> = setOf(
        "tool.started", "tool.completed", "tool.progress", "tool.failed", "run.completed",
        "run.failed", "run.cancelled", "assistant.delta", "assistant.completed",
        "approval.responded",
    )

    /** Every name above that a dotted literal in the app's source may be. */
    val ALL_DOTTED: Set<String> get() =
        METHODS + EVENTS + LEGACY_EVENTS + CONTROL_ACTIONS + RUN_EVENTS
}
