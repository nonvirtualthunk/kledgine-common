package arx.core

import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectHeapPriorityQueue
import kotlinx.serialization.Serializable

class Pathfinder<T> (
    val adjacencyFunction : (T, MutableList<T>) -> Unit ,
    val cost : (T, T) -> Double,
    val heuristic : (T, T) -> Double,
    val endCondition : (SearchState) -> Boolean = { _ -> false }
) {

    class SimpleCostFunction<T>(val fn : (T) -> Double) : (T,T) -> Double {
        override fun invoke(p1: T, p2: T): Double {
            return fn(p2)
        }
    }

    class SingleDestination<T>(val t : T) : (T) -> Boolean {
        override fun invoke(p1: T): Boolean {
            return p1 == t
        }
    }

    @Serializable
    data class Path<T>(val steps : List<T>, val costs : List<Double>, val totalCost : Double) {
        internal fun append(other : Path<T>) : Path<T> {
            return Path(steps + other.steps, costs + other.costs, other.totalCost.max(totalCost))
        }

        fun subPath(maxCost: Number) : Path<T> {
            val maxCostF = maxCost.toDouble()
            var newCost = 0.0
            val newSteps = mutableListOf<T>()
            val newCosts = mutableListOf<Double>()
            for (i in steps.indices) {
                if (costs[i] <= maxCostF) {
                    newSteps.add(steps[i])
                    newCosts.add(costs[i])
                    newCost = costs[i]
                }
            }
            return Path(newSteps, newCosts, newCost)
        }

        fun dropLast(num : Int) : Path<T> {
            return if (steps.size > num) {
                Path(steps.dropLast(num), costs.dropLast(num), totalCost - costs.subList(costs.size - num, costs.size).sum())
            } else {
                Path(emptyList(), emptyList(), 0.0)
            }
        }

        override fun toString(): String {
            return if (steps.isNotEmpty()) {
                "Path(${steps.first()} -> ${steps.last()}, cost : $totalCost)"
            } else {
                "Path()"
            }
        }


    }

    data class SearchNode<T>(val value : T, val parent: SearchNode<T>?, val g : Double, val h : Double) {
        val f : Double get() { return g + h }

        fun toPath() : Path<T> {
            val selfPath = Path(listOf(value), listOf(g), g)
            return if (parent == null) {
                selfPath
            } else {
                parent.toPath().append(selfPath)
            }
        }
    }


    fun findPath(from : T, to : (T) -> Boolean, heuristicalTarget : T) : Path<T>? {
        val heap = ObjectHeapPriorityQueue<SearchNode<T>>(compareBy { s -> s.f })
        val openMap = Object2DoubleOpenHashMap<T>()


        heap.enqueue(SearchNode(from, null, 0.0, heuristic(from, heuristicalTarget)))

        val adjacencyList = mutableListOf<T>()
        val searchState = SearchState(0.0, 0)
        while (!heap.isEmpty) {
            val node = heap.dequeue()

            // if we're finished then great, return the path
            if (to(node.value)) {
                return node.toPath()
            }

            // check if we should short circuit the search
            searchState.nodesExamined++
            searchState.lowestPossibleCost = node.g
            if (endCondition(searchState)) {
                return null
            }

            adjacencyFunction(node.value, adjacencyList)
            for (adj in adjacencyList) {
                val adjCost = node.g + cost(node.value, adj)
                val curBestCost = openMap.getOrDefault(adj as Any?, Double.MAX_VALUE)
                if (curBestCost > adjCost) {
                    openMap.put(adj, adjCost)
                    heap.enqueue(SearchNode(adj, node, adjCost, heuristic(adj, heuristicalTarget)))
                }
            }
            adjacencyList.clear()
        }

        return null
    }

    fun emptyPath(position : T) : Path<T> {
        return Path(steps = listOf(position), listOf(0.0), 0.0)
    }
}

data class SearchState(var lowestPossibleCost : Double, var nodesExamined : Int)



class FloodSearcher<T> (
    val adjacencyFunction : (T, MutableList<T>) -> Unit ,
    val cost : (T, T) -> Double,
    val endCondition : (SearchState) -> Boolean
) {


    data class SearchNode<T>(val value : T, val parent: SearchNode<T>?, val g : Double) {
        fun toPath() : Pathfinder.Path<T> {
            val selfPath = Pathfinder.Path(listOf(value), listOf(g), g)
            return if (parent == null) {
                selfPath
            } else {
                parent.toPath().append(selfPath)
            }
        }
    }

    fun flood(from: List<T>, visit: (SearchNode<T>) -> Unit) {
        val heap = ObjectHeapPriorityQueue<SearchNode<T>>(compareBy { s -> s.g })
        val openMap = Object2DoubleOpenHashMap<T>()

        for (f in from) {
            heap.enqueue(SearchNode(f, null, 0.0))
        }

        val adjacencyList = mutableListOf<T>()
        val searchState = SearchState(0.0, 0)
        while (!heap.isEmpty) {
            val node = heap.dequeue()

            visit(node)

            // check if we should short circuit the search
            searchState.nodesExamined++
            searchState.lowestPossibleCost = node.g
            if (endCondition(searchState)) {
                return
            }

            adjacencyFunction(node.value, adjacencyList)
            for (adj in adjacencyList) {
                val adjCost = node.g + cost(node.value, adj)
                val curBestCost = openMap.getOrDefault(adj as Any?, Double.MAX_VALUE)
                if (curBestCost > adjCost) {
                    if (curBestCost < Double.MAX_VALUE) {
                        Noto.warn("unexpected better node found")
                    }
                    openMap.put(adj, adjCost)
                    heap.enqueue(SearchNode(adj, node, adjCost))
                }
            }
            adjacencyList.clear()
        }
    }

}