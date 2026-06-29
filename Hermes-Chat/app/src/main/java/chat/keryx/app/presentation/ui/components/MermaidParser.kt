package chat.keryx.app.presentation.ui.components

/**
 * Pure (Compose-free, unit-testable) parser for the common Mermaid **flowchart** subset
 * (`graph`/`flowchart` with TD/TB/BT/LR/RL). Returns null for anything it can't model (sequence,
 * gantt, class diagrams, …) so the renderer can fall back to showing the raw code. Supports node
 * shapes (rect, round, stadium, circle, diamond), single edges per line with optional `|labels|`,
 * and standalone node declarations. Subgraph/style/classDef/click lines are ignored.
 */
object MermaidParser {

    enum class Direction { VERTICAL, HORIZONTAL }
    enum class NodeShape { RECT, ROUND, STADIUM, DIAMOND, CIRCLE }

    data class Node(val id: String, val label: String, val shape: NodeShape)
    data class Edge(val from: String, val to: String, val label: String?)
    data class Graph(val direction: Direction, val nodes: List<Node>, val edges: List<Edge>)

    private val HEADER = Regex("""^(graph|flowchart)\s+(TB|TD|BT|LR|RL)\b.*""", RegexOption.IGNORE_CASE)
    // left  (arrow)(optional |label|)  right
    private val EDGE = Regex("""^(.+?)\s*(?:-->|---|-\.->|-\.-|==>|===|--o|--x)\s*(?:\|([^|]*)\|)?\s*(.+)$""")
    // id followed by an optional shape bracket.
    private val NODE = Regex("""^([A-Za-z0-9_]+)\s*(\(\(.*\)\)|\(\[.*\]\)|\[\[.*\]\]|\[.*\]|\(.*\)|\{.*\})?$""")
    private val IGNORE = listOf("subgraph", "end", "style", "classdef", "class ", "click", "linkstyle", "direction")

    fun parse(code: String): Graph? {
        val raw = code.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("%%") }
        if (raw.isEmpty()) return null
        val hm = HEADER.find(raw.first()) ?: return null
        val direction = when (hm.groupValues[2].uppercase()) {
            "LR", "RL" -> Direction.HORIZONTAL
            else -> Direction.VERTICAL
        }

        val nodes = LinkedHashMap<String, Node>()
        val edges = mutableListOf<Edge>()

        fun register(token: String): String? {
            val m = NODE.matchEntire(token.trim()) ?: return null
            val id = m.groupValues[1]
            val bracket = m.groupValues[2]
            if (bracket.isNotEmpty()) {
                val (shape, label) = shapeOf(bracket)
                nodes[id] = Node(id, label.ifBlank { id }, shape) // labeled form wins
            } else {
                nodes.getOrPut(id) { Node(id, id, NodeShape.RECT) }
            }
            return id
        }

        for (line in raw.drop(1)) {
            val lower = line.lowercase()
            if (IGNORE.any { lower.startsWith(it) }) continue
            val em = EDGE.matchEntire(line)
            if (em != null) {
                val from = register(em.groupValues[1])
                val to = register(em.groupValues[3])
                val label = em.groupValues[2].trim().ifBlank { null }
                if (from != null && to != null) edges += Edge(from, to, label)
            } else {
                register(line)
            }
        }
        if (nodes.isEmpty()) return null
        return Graph(direction, nodes.values.toList(), edges)
    }

    private fun shapeOf(bracket: String): Pair<NodeShape, String> {
        fun strip(prefix: String, suffix: String) =
            bracket.removePrefix(prefix).removeSuffix(suffix).trim().trim('"')
        return when {
            bracket.startsWith("((") -> NodeShape.CIRCLE to strip("((", "))")
            bracket.startsWith("([") -> NodeShape.STADIUM to strip("([", "])")
            bracket.startsWith("[[") -> NodeShape.RECT to strip("[[", "]]")
            bracket.startsWith("{") -> NodeShape.DIAMOND to strip("{", "}")
            bracket.startsWith("(") -> NodeShape.ROUND to strip("(", ")")
            bracket.startsWith("[") -> NodeShape.RECT to strip("[", "]")
            else -> NodeShape.RECT to bracket
        }
    }

    /**
     * Assign each node a rank (longest path from a root) and group them into rows. Real flowcharts
     * have loops (retry/back edges); we strip those (DFS back-edges) before ranking so a cycle can't
     * push downstream nodes into the wrong rank. Returns ranks as an ordered list of node-id rows.
     */
    fun rank(graph: Graph): List<List<String>> {
        val ids = graph.nodes.map { it.id }
        val adj = ids.associateWith { mutableListOf<String>() }
        graph.edges.forEach { adj[it.from]?.add(it.to) }

        // DFS coloring to find back edges (edge to a node currently on the stack).
        val color = HashMap<String, Int>().apply { ids.forEach { put(it, 0) } } // 0=white 1=gray 2=black
        val back = HashSet<Pair<String, String>>()
        fun dfs(start: String) {
            val stack = ArrayDeque<Pair<String, Int>>()
            stack.addLast(start to 0)
            color[start] = 1
            while (stack.isNotEmpty()) {
                val (u, ci) = stack.removeLast()
                val nbrs = adj[u] ?: emptyList()
                if (ci < nbrs.size) {
                    stack.addLast(u to ci + 1)
                    val v = nbrs[ci]
                    when (color[v]) {
                        0 -> { color[v] = 1; stack.addLast(v to 0) }
                        1 -> back.add(u to v)
                    }
                } else {
                    color[u] = 2
                }
            }
        }
        val indeg = ids.associateWith { 0 }.toMutableMap()
        graph.edges.forEach { indeg[it.to] = (indeg[it.to] ?: 0) + 1 }
        ids.filter { (indeg[it] ?: 0) == 0 }.forEach { if (color[it] == 0) dfs(it) }
        ids.forEach { if (color[it] == 0) dfs(it) }

        // Longest-path rank over the remaining DAG (skip back edges).
        val rankOf = ids.associateWith { 0 }.toMutableMap()
        repeat(ids.size + 1) {
            var changed = false
            for (e in graph.edges) {
                if ((e.from to e.to) in back) continue
                val want = (rankOf[e.from] ?: 0) + 1
                if (want > (rankOf[e.to] ?: 0)) { rankOf[e.to] = want; changed = true }
            }
            if (!changed) return@repeat
        }
        val maxRank = rankOf.values.maxOrNull() ?: 0
        return (0..maxRank).map { r -> ids.filter { (rankOf[it] ?: 0) == r } }
    }
}
