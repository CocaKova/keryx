package chat.keryx.core.model

/**
 * The model picker's rows as a pure function of the catalog, the phone's recents, the search
 * query and which groups the user opened — so the sheet only draws, and the grouping is
 * testable without Compose (the ControlsTab / CronTiles house pattern).
 *
 * The categorisation is what a person switching brains on a phone actually asks:
 *  - **Where does it run?** Local endpoints (this machine, the LAN, the tailnet) come first,
 *    then the cloud logins, then virtual routes (MoA) last.
 *  - **Whose model is it?** An aggregator (Nous, OpenRouter: one login, many labs) is split
 *    by lab, the lab's featured few shown and the older tail folded behind "N more" — the
 *    same shortlist the gateway computes, never a phone-side allowlist. A single-lab provider
 *    is one flat group, flagship-first as the gateway orders it, its long tail folded past
 *    [SINGLE_LAB_FOLD].
 *  - **What did I use lately?** Recents first, resolved against the live catalog so a
 *    route that logged out cannot be picked from memory.
 * A query flattens all of that: every group opens, every tail shows, only matches remain.
 */
object ModelPicker {

    /** A single-lab row shows this many before folding; the fold never hides just one. */
    const val SINGLE_LAB_FOLD = 6

    /** An aggregator lab with no featured shortlist shows this many before folding. */
    const val LAB_FOLD = 5

    const val MAX_RECENTS = 4

    /** Recents ledger key — provider and model together, since ids repeat across providers. */
    fun recentKey(choice: ModelChoice): String = "${choice.provider}|${choice.name}"

    enum class Kind { LOCAL, CLOUD, AGGREGATOR, VIRTUAL }

    data class Group(
        val key: String,
        /** A lab's name inside an aggregator; null for a provider's one flat group. */
        val title: String?,
        val shown: List<ModelChoice>,
        /** How many sit behind "more" (0 = nothing folded). */
        val folded: Int,
        val expanded: Boolean,
    )

    data class Section(
        val key: String,
        val title: String,
        val subtitle: String,
        val kind: Kind?,
        val provider: ModelProvider?,
        val groups: List<Group>,
    ) {
        val count: Int get() = groups.sumOf { it.shown.size + it.folded }
    }

    data class Plan(
        val current: ModelChoice?,
        val sections: List<Section>,
    ) {
        val isEmpty: Boolean get() = sections.isEmpty()
    }

    // ---- classification --------------------------------------------------------------------

    private val LOCAL_RUNTIMES = setOf("lmstudio", "ollama", "vllm", "llamacpp", "llama.cpp", "silas-brain", "local")

    /** Where a provider's models run — the first axis of the picker. */
    fun kindOf(p: ModelProvider): Kind = when {
        p.source == "virtual" || p.slug == "moa" -> Kind.VIRTUAL
        isPrivateEndpoint(p.apiUrl) -> Kind.LOCAL
        p.apiUrl.isBlank() && p.slug.lowercase() in LOCAL_RUNTIMES -> Kind.LOCAL
        p.models.mapTo(HashSet()) { it.lab }.filter { it.isNotEmpty() }.size >= 2 -> Kind.AGGREGATOR
        else -> Kind.CLOUD
    }

    /** loopback, RFC-1918, link-local, `.local`/`.lan`/`.internal`/`.ts.net`, or a bare hostname. */
    fun isPrivateEndpoint(url: String): Boolean {
        if (url.isBlank()) return false
        val host = hostOf(url).lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "0.0.0.0" || host == "::1" || host.startsWith("127.")) return true
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return true
        if (host.startsWith("172.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        if (host.startsWith("100.")) { // CGNAT / tailnet 100.64.0.0/10
            val second = host.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 64..127) return true
        }
        val suffixes = listOf(".local", ".lan", ".internal", ".home", ".ts.net", ".home.arpa")
        if (suffixes.any { host.endsWith(it) }) return true
        // A bare hostname ("spark", "blackpearl") is a name only a LAN resolves.
        return !host.contains('.') && host.none { it.isDigit() && host.all { c -> c.isDigit() || c == '.' } }
    }

    fun hostOf(url: String): String {
        var s = url.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringBefore('?')
        s = s.substringAfter('@')
        // [v6]:port or host:port
        if (s.startsWith("[")) return s.substringAfter('[').substringBefore(']')
        return s.substringBefore(':')
    }

    private val LAB_NAMES = mapOf(
        "anthropic" to "Anthropic", "openai" to "OpenAI", "google" to "Google", "x-ai" to "xAI",
        "xai" to "xAI", "moonshotai" to "Moonshot", "deepseek" to "DeepSeek", "qwen" to "Qwen",
        "meta-llama" to "Meta", "meta" to "Meta", "mistralai" to "Mistral", "mistral" to "Mistral",
        "z-ai" to "Z.ai", "zai" to "Z.ai", "nousresearch" to "Nous", "minimax" to "MiniMax",
        "cohere" to "Cohere", "perplexity" to "Perplexity", "amazon" to "Amazon", "microsoft" to "Microsoft",
        "nvidia" to "NVIDIA", "stepfun" to "StepFun", "arcee-ai" to "Arcee", "baidu" to "Baidu",
        "tencent" to "Tencent", "bytedance" to "ByteDance", "inception" to "Inception", "ai21" to "AI21",
    )

    /** A lab's display name from its id segment: known names as they write them, else Title Case. */
    fun labName(lab: String): String = LAB_NAMES[lab.lowercase()]
        ?: lab.split('-', '_').filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    /** The subtitle under a section head: what the row is, in five words. */
    fun subtitleOf(p: ModelProvider, kind: Kind): String {
        val n = maxOf(p.totalModels, p.models.size)
        val models = if (n == 1) "1 model" else "$n models"
        return when (kind) {
            Kind.LOCAL -> hostOf(p.apiUrl).ifBlank { "this machine" } + " · " + models
            Kind.AGGREGATOR -> {
                val labs = p.models.mapTo(LinkedHashSet()) { it.lab }.count { it.isNotEmpty() }
                "$models · $labs labs" + if (p.freeTier) " · free tier" else ""
            }
            Kind.CLOUD -> models + if (p.freeTier) " · free tier" else ""
            Kind.VIRTUAL -> "a route over routes"
        }
    }

    // ---- the plan ---------------------------------------------------------------------------

    fun plan(
        catalog: ModelCatalog?,
        recents: List<String> = emptyList(),
        query: String = "",
        expanded: Set<String> = emptySet(),
    ): Plan {
        if (catalog == null) return Plan(null, emptyList())
        val providers = catalog.usable
        val all = providers.flatMap { it.models }
        val current = all.firstOrNull { catalog.isCurrent(it) }
        val q = query.trim().lowercase()
        val searching = q.isNotEmpty()
        val terms = q.split(' ').filter { it.isNotEmpty() }
        fun matches(m: ModelChoice, p: ModelProvider): Boolean {
            if (!searching) return true
            val hay = listOf(m.name, m.shortName, labName(m.lab), p.name, p.slug).joinToString(" ").lowercase()
            return terms.all { hay.contains(it) }
        }

        val sections = ArrayList<Section>()

        // Recents: what you switch between. Resolved live; the current model is the header.
        if (!searching && recents.isNotEmpty()) {
            val byKey = HashMap<String, ModelChoice>()
            for (m in all) byKey.putIfAbsent(recentKey(m), m)
            val rows = recents.mapNotNull { byKey[it] }
                .filter { current == null || it != current }
                .distinct().take(MAX_RECENTS)
            if (rows.isNotEmpty()) sections += Section(
                key = "recent", title = "Recent", subtitle = "what you switched between",
                kind = null, provider = null,
                groups = listOf(Group("recent", null, rows, 0, true)),
            )
        }

        val classified = providers.map { it to kindOf(it) }
        val order = listOf(Kind.LOCAL, Kind.CLOUD, Kind.AGGREGATOR, Kind.VIRTUAL)
        for (kind in order) for ((p, k) in classified) {
            if (k != kind) continue
            val visible = p.models.filter { matches(it, p) }
            if (visible.isEmpty()) continue
            val groups = if (kind == Kind.AGGREGATOR) labGroups(p, visible, expanded, searching)
                else listOf(flatGroup(p, visible, expanded, searching, kind))
            sections += Section(
                key = p.slug, title = p.name, subtitle = subtitleOf(p, kind),
                kind = kind, provider = p, groups = groups,
            )
        }
        return Plan(current, sections)
    }

    private fun flatGroup(p: ModelProvider, models: List<ModelChoice>, expanded: Set<String>, searching: Boolean, kind: Kind): Group {
        val key = p.slug
        val open = searching || key in expanded || kind == Kind.LOCAL
        return fold(key, null, models, SINGLE_LAB_FOLD, open)
    }

    private fun labGroups(p: ModelProvider, models: List<ModelChoice>, expanded: Set<String>, searching: Boolean): List<Group> {
        val byLab = LinkedHashMap<String, MutableList<ModelChoice>>()
        for (m in models) byLab.getOrPut(m.lab) { ArrayList() } += m
        return byLab.map { (lab, rows) ->
            val key = "${p.slug}/$lab"
            val open = searching || key in expanded
            val featured = rows.filter { it.featured }
            if (featured.isNotEmpty() && !open) {
                // The gateway's shortlist IS the fold line: featured shown, the rest counted.
                Group(key, labName(lab).ifBlank { p.name }, featured, rows.size - featured.size, false)
            } else fold(key, labName(lab).ifBlank { p.name }, rows, LAB_FOLD, open)
        }
    }

    /** Show the head, count the tail — unless the tail would be a single row (then show it). */
    private fun fold(key: String, title: String?, rows: List<ModelChoice>, head: Int, open: Boolean): Group {
        if (open || rows.size <= head + 1) return Group(key, title, rows, 0, true)
        return Group(key, title, rows.take(head), rows.size - head, false)
    }

    /** Push a pick onto the recents ledger: newest first, no repeats, capped. */
    fun pushRecent(recents: List<String>, choice: ModelChoice, cap: Int = MAX_RECENTS * 2): List<String> {
        val k = recentKey(choice)
        return (listOf(k) + recents.filterNot { it == k }).take(cap)
    }
}
