package com.orbital.synth.core.engines.fm

/**
 * Thirty-two original six-operator routing graphs. These are NOT an exact
 * reconstruction of any proprietary instrument's algorithm-number mapping.
 *
 * Operators are indexed 0..5. Modulation travels from higher to lower indices:
 * every graph is acyclic and can be evaluated in one allocation-free pass.
 */
object FmAlgorithms {
    const val COUNT = 32

    private data class Definition(val name: String, val edges: String, val carriers: String)

    private val definitions = listOf(
        Definition("Six-stack", "5>4 4>3 3>2 2>1 1>0", "0"),
        Definition("Four + two", "5>4 4>3 3>2 1>0", "2 0"),
        Definition("Triple pairs", "5>4 3>2 1>0", "4 2 0"),
        Definition("Six-way additive", "", "5 4 3 2 1 0"),
        Definition("Five-to-one", "5>0 4>0 3>0 2>0 1>0", "0"),
        Definition("Branching ladder", "5>3 4>3 3>0 2>1 1>0", "0"),
        Definition("Braided cascade", "5>4 4>2 3>2 2>0 1>0", "0"),
        Definition("Diamond cascade", "5>4 5>3 4>2 3>2 2>0 1>0", "0"),
        Definition("Four-stack + pair", "5>4 4>3 3>0 2>1", "0 1"),
        Definition("Converging trees", "5>4 5>3 4>1 3>1 2>0 1>0", "0"),
        Definition("Staggered return", "5>4 4>2 3>1 2>0 1>0", "0"),
        Definition("Dual V mod", "5>3 4>3 3>2 2>0 1>0", "0"),
        Definition("Five-stack with tap", "5>4 4>3 3>2 2>0", "0 1"),
        Definition("Parallel articulators", "5>4 4>3 3>0 2>1", "0 1 2"),
        Definition("Four-wave interference", "5>4 4>0 3>2 2>0 1>0", "0"),
        Definition("Twin spectral trees", "5>4 4>3 3>0 2>1 1>0", "0"),
        Definition("Four-to-one spine", "5>2 4>2 3>2 2>0 1>0", "0"),
        Definition("Branch with overtone", "5>4 4>2 3>2 2>0", "0 1"),
        Definition("Dense steel", "5>4 4>3 3>0 2>0 1>0", "0"),
        Definition("Descending braid", "5>4 4>0 3>2 2>1 1>0", "0"),
        Definition("Cascade + harmonics", "5>4 4>3 3>2 2>0", "0 1 2"),
        Definition("Two-stage glow", "5>4 4>3 3>0 2>0", "0 1 2"),
        Definition("Dual tapped tine", "5>4 4>0 3>2 2>0", "0 1 2"),
        Definition("Open twin resonator", "5>4 4>0 3>2 2>1", "0 1 3"),
        Definition("Resonating tine", "5>4 4>0 3>2 2>0 1>0", "0 2"),
        Definition("Harmonic tap", "5>4 4>3 3>0 2>1", "0 1 4"),
        Definition("Tripartite stack", "5>4 4>3 3>2 2>1 1>0", "0 3"),
        Definition("Three-branch chorus", "5>4 4>0 3>2 2>0 1>0", "0 1 3"),
        Definition("Crossed carriers", "5>4 5>3 4>2 3>1 2>0 1>0", "0"),
        Definition("Split cross-mod", "5>4 5>3 4>2 3>1 2>0", "0 1"),
        Definition("Pulsing partials", "5>4 4>2 3>2 2>0 1>0", "0 2"),
        Definition("Spectral constellation", "5>4 5>2 4>1 3>1 2>0 1>0", "0 3")
    )

    internal class Graph internal constructor(
        val name: String,
        val targetsForOperator: Array<IntArray>,
        val carrierOperators: IntArray
    )

    private val graphs = definitions.map { definition ->
        val targets = Array(6) { ArrayList<Int>() }
        if (definition.edges.isNotEmpty()) {
            for (edge in definition.edges.split(' ')) {
                val parts = edge.split('>')
                check(parts.size == 2) { "Malformed FM graph edge: $edge" }
                val source = parts[0].toInt()
                val target = parts[1].toInt()
                check(source in 1..5 && target in 0 until source) {
                    "FM routing must be acyclic and descend in operator index: $edge"
                }
                check(target !in targets[source]) { "Duplicate FM routing edge: $edge" }
                targets[source].add(target)
            }
        }
        val carriers = definition.carriers.split(' ').map(String::toInt).toIntArray()
        check(carriers.isNotEmpty() && carriers.distinct().size == carriers.size)
        check(carriers.all { it in 0..5 })
        Graph(definition.name, Array(6) { targets[it].toIntArray() }, carriers)
    }

    init {
        check(graphs.size == COUNT)
        val signatures = graphs.map { graph ->
            graph.targetsForOperator.joinToString("|") { it.joinToString(",") } +
                ";" + graph.carrierOperators.sorted().joinToString(",")
        }
        check(signatures.toSet().size == COUNT) { "Every FM routing must be distinct" }
    }

    fun name(index: Int): String = graph(index).name

    internal fun graph(index: Int): Graph = graphs[index.coerceIn(0, COUNT - 1)]

    /** Defensive route snapshot for visualization and testing. */
    fun routes(index: Int): List<Pair<Int, Int>> {
        val graph = graph(index)
        return (0..5).flatMap { source ->
            graph.targetsForOperator[source].map { target -> source to target }
        }
    }

    fun carriers(index: Int): List<Int> = graph(index).carrierOperators.toList()
}
