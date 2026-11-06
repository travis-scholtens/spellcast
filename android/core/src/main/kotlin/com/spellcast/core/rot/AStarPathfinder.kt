package com.spellcast.core.rot

import kotlin.math.abs
import kotlin.math.max

/**
 * 1:1 port of ROT.Path.AStar in functional style.
 */
class AStarPathfinder(
    private val toX: Int,
    private val toY: Int,
    private val topology: Int = 8,
    private val passableCallback: (x: Int, y: Int) -> Boolean
) {

    private data class Node(
        val pos: GridVec,
        val prev: Node?,
        val g: Int,
        val h: Int
    ) {
        val f: Int get() = g + h
    }

    private val dirs: List<GridVec> = when (topology) {
        4 -> RotDirs.DIRS_4
        6 -> RotDirs.DIRS_6
        8 -> listOf(
            RotDirs.DIRS_8[0],
            RotDirs.DIRS_8[2],
            RotDirs.DIRS_8[4],
            RotDirs.DIRS_8[6],
            RotDirs.DIRS_8[1],
            RotDirs.DIRS_8[3],
            RotDirs.DIRS_8[5],
            RotDirs.DIRS_8[7]
        )
        else -> error("Illegal topology: $topology")
    }

    fun compute(fromX: Int, fromY: Int, callback: (x: Int, y: Int) -> Unit) {
        computePath(fromX, fromY).forEach { (x, y) -> callback(x, y) }
    }

    fun computePath(fromX: Int, fromY: Int): List<GridVec> {
        val from = GridVec(fromX, fromY)
        val target = GridVec(toX, toY)
        val root = Node(pos = target, prev = null, g = 0, h = distance(target, from))
        val startNode = search(
            from = from,
            todo = listOf(root),
            done = mapOf(target to root)
        ) ?: return emptyList()
        return generateSequence(startNode) { it.prev }.map { it.pos }.toList()
    }

    private tailrec fun search(
        from: GridVec,
        todo: List<Node>,
        done: Map<GridVec, Node>
    ): Node? {
        val current = todo.firstOrNull() ?: return null
        if (current.pos == from) return current
        val neighbors = dirs
            .map { dir -> current.pos + dir }
            .filter { nextPos -> nextPos !in done && passableCallback(nextPos.x, nextPos.y) }
        val (nextTodo, nextDone) = neighbors.fold(todo.drop(1) to done) { (queue, visited), nextPos ->
            val node = Node(
                pos = nextPos,
                prev = current,
                g = current.g + 1,
                h = distance(nextPos, from)
            )
            insertPriority(queue, node) to (visited + (nextPos to node))
        }
        return search(from, nextTodo, nextDone)
    }

    private fun insertPriority(queue: List<Node>, node: Node): List<Node> =
        queue.indexOfFirst { item -> node.f < item.f || (node.f == item.f && node.h < item.h) }
            .let { idx ->
                if (idx == -1) {
                    queue + node
                } else {
                    queue.take(idx) + node + queue.drop(idx)
                }
            }

    private fun distance(pos: GridVec, from: GridVec): Int = when (topology) {
        4 -> abs(pos.x - from.x) + abs(pos.y - from.y)
        6 -> {
            val dx = abs(pos.x - from.x)
            val dy = abs(pos.y - from.y)
            dy + max(0, (dx - dy) / 2)
        }
        8 -> max(abs(pos.x - from.x), abs(pos.y - from.y))
        else -> error("Illegal topology: $topology")
    }
}
