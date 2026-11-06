package com.spellcast.core.rot

data class GridVec(val x: Int, val y: Int) {
    operator fun plus(other: GridVec): GridVec = GridVec(x + other.x, y + other.y)
    operator fun minus(other: GridVec): GridVec = GridVec(x - other.x, y - other.y)
    operator fun times(scale: Int): GridVec = GridVec(x * scale, y * scale)
}

object RotDirs {
    val DIRS_4: List<GridVec> = listOf(
        GridVec(0, -1),
        GridVec(1, 0),
        GridVec(0, 1),
        GridVec(-1, 0)
    )

    val DIRS_8: List<GridVec> = listOf(
        GridVec(0, -1),
        GridVec(1, -1),
        GridVec(1, 0),
        GridVec(1, 1),
        GridVec(0, 1),
        GridVec(-1, 1),
        GridVec(-1, 0),
        GridVec(-1, -1)
    )

    val DIRS_6: List<GridVec> = listOf(
        GridVec(-1, -1),
        GridVec(1, -1),
        GridVec(2, 0),
        GridVec(1, 1),
        GridVec(-1, 1),
        GridVec(-2, 0)
    )

    fun forTopology(topology: Int): List<GridVec> = when (topology) {
        4 -> DIRS_4
        6 -> DIRS_6
        8 -> DIRS_8
        else -> error("Illegal topology: $topology")
    }
}
