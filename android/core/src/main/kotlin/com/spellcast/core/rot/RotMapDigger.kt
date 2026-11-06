package com.spellcast.core.rot

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sign

data class DiggerOptions(
    val roomWidth: IntRange = 3..9,
    val roomHeight: IntRange = 3..5,
    val corridorLength: IntRange = 3..10,
    val dugPercentage: Double = 0.2,
    val timeLimitMs: Long = 1000L
)

data class Room(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val doors: Set<GridVec> = emptySet()
) {
    val left: Int get() = x1
    val right: Int get() = x2
    val top: Int get() = y1
    val bottom: Int get() = y2

    val center: GridVec
        get() = GridVec(
            floor((x1 + x2) / 2.0 + 0.5).toInt(),
            floor((y1 + y2) / 2.0 + 0.5).toInt()
        )

    fun isValid(
        isWall: (Int, Int) -> Boolean,
        canBeDug: (Int, Int) -> Boolean
    ): Boolean =
        ((x1 - 1)..(x2 + 1)).all { x ->
            ((y1 - 1)..(y2 + 1)).all { y ->
                if (x == x1 - 1 || x == x2 + 1 || y == y1 - 1 || y == y2 + 1) {
                    isWall(x, y)
                } else {
                    canBeDug(x, y)
                }
            }
        }

    fun create(digCallback: (x: Int, y: Int, value: Int) -> Unit) {
        ((x1 - 1)..(x2 + 1)).forEach { x ->
            ((y1 - 1)..(y2 + 1)).forEach { y ->
                digCallback(
                    x,
                    y,
                    when {
                        GridVec(x, y) in doors -> 2
                        x == x1 - 1 || x == x2 + 1 || y == y1 - 1 || y == y2 + 1 -> 1
                        else -> 0
                    }
                )
            }
        }
    }

    fun withAddedDoors(isWall: (Int, Int) -> Boolean): Room =
        copy(
            doors = ((x1 - 1)..(x2 + 1)).flatMap { x ->
                ((y1 - 1)..(y2 + 1))
                    .filter { y ->
                        (x == x1 - 1 || x == x2 + 1 || y == y1 - 1 || y == y2 + 1) &&
                            !isWall(x, y)
                    }
                    .map { y -> GridVec(x, y) }
            }.toSet()
        )

    companion object {
        fun createRandomAt(
            x: Int,
            y: Int,
            dx: Int,
            dy: Int,
            options: DiggerOptions,
            rng: AleaRng
        ): Room {
            val width = rng.getUniformInt(options.roomWidth.first, options.roomWidth.last)
            val height = rng.getUniformInt(options.roomHeight.first, options.roomHeight.last)
            val door = setOf(GridVec(x, y))
            return when {
                dx == 1 -> (y - floor(rng.getUniform() * height).toInt()).let { y2 ->
                    Room(x + 1, y2, x + width, y2 + height - 1, door)
                }
                dx == -1 -> (y - floor(rng.getUniform() * height).toInt()).let { y2 ->
                    Room(x - width, y2, x - 1, y2 + height - 1, door)
                }
                dy == 1 -> (x - floor(rng.getUniform() * width).toInt()).let { x2 ->
                    Room(x2, y + 1, x2 + width - 1, y + height, door)
                }
                dy == -1 -> (x - floor(rng.getUniform() * width).toInt()).let { x2 ->
                    Room(x2, y - height, x2 + width - 1, y - 1, door)
                }
                else -> error("dx or dy must be 1 or -1")
            }
        }

        fun createRandomCenter(
            cx: Int,
            cy: Int,
            options: DiggerOptions,
            rng: AleaRng
        ): Room {
            val width = rng.getUniformInt(options.roomWidth.first, options.roomWidth.last)
            val height = rng.getUniformInt(options.roomHeight.first, options.roomHeight.last)
            val x1 = cx - floor(rng.getUniform() * width).toInt()
            val y1 = cy - floor(rng.getUniform() * height).toInt()
            return Room(x1, y1, x1 + width - 1, y1 + height - 1)
        }
    }
}

data class Corridor(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val endsWithAWall: Boolean = true
) {
    fun validate(
        isWall: (Int, Int) -> Boolean,
        canBeDug: (Int, Int) -> Boolean
    ): Corridor? {
        val dx = (endX - startX).sign
        val dy = (endY - startY).sign
        val nx = dy
        val ny = -dx
        val initialLength = 1 + max(abs(endX - startX), abs(endY - startY))
        val validLength = (0 until initialLength).takeWhile { i ->
            val x = startX + i * dx
            val y = startY + i * dy
            canBeDug(x, y) && isWall(x + nx, y + ny) && isWall(x - nx, y - ny)
        }.count()

        if (validLength == 0) return null
        val actualEndX = startX + (validLength - 1) * dx
        val actualEndY = startY + (validLength - 1) * dy
        if (validLength == 1 && isWall(actualEndX + dx, actualEndY + dy)) return null

        val firstCornerBad = !isWall(actualEndX + dx + nx, actualEndY + dy + ny)
        val secondCornerBad = !isWall(actualEndX + dx - nx, actualEndY + dy - ny)
        val endsWall = isWall(actualEndX + dx, actualEndY + dy)
        if ((firstCornerBad || secondCornerBad) && endsWall) return null

        return Corridor(startX, startY, actualEndX, actualEndY, endsWall)
    }

    fun cells(): List<GridVec> {
        val dx = (endX - startX).sign
        val dy = (endY - startY).sign
        val length = 1 + max(abs(endX - startX), abs(endY - startY))
        return (0 until length).map { i -> GridVec(startX + i * dx, startY + i * dy) }
    }

    fun priorityWalls(): List<GridVec> =
        if (!endsWithAWall) {
            emptyList()
        } else {
            val dx = (endX - startX).sign
            val dy = (endY - startY).sign
            val nx = dy
            val ny = -dx
            listOf(
                GridVec(endX + dx, endY + dy),
                GridVec(endX + nx, endY + ny),
                GridVec(endX - nx, endY - ny)
            )
        }

    companion object {
        fun createRandomAt(
            x: Int,
            y: Int,
            dx: Int,
            dy: Int,
            options: DiggerOptions,
            rng: AleaRng
        ): Corridor =
            rng.getUniformInt(options.corridorLength.first, options.corridorLength.last).let { length ->
                Corridor(x, y, x + dx * length, y + dy * length)
            }
    }
}

data class DiggerResult(
    val width: Int,
    val height: Int,
    val cells: Map<GridVec, Int>,
    val rooms: List<Room>,
    val corridors: List<Corridor>
)

/**
 * 1:1 port of ROT.Map.Digger in functional style.
 */
object RotMapDigger {

    private val FEATURES: Map<String, Int> = mapOf(
        "Room" to 4,
        "Corridor" to 4
    )
    private const val FEATURE_ATTEMPTS = 20

    private data class DigState(
        val width: Int,
        val height: Int,
        val map: Map<GridVec, Int>,
        val walls: Map<GridVec, Int> = emptyMap(),
        val dug: Int = 0,
        val rooms: List<Room> = emptyList(),
        val corridors: List<Corridor> = emptyList()
    ) {
        fun isWall(x: Int, y: Int): Boolean =
            x in 0 until width && y in 0 until height && (map[GridVec(x, y)] ?: 1) == 1

        fun canBeDug(x: Int, y: Int): Boolean =
            x in 1 until (width - 1) && y in 1 until (height - 1) && (map[GridVec(x, y)] ?: 1) == 1
    }

    fun generate(
        width: Int,
        height: Int,
        rng: AleaRng,
        options: DiggerOptions = DiggerOptions(),
        callback: ((x: Int, y: Int, value: Int) -> Unit)? = null
    ): DiggerResult {
        val initialMap = (0 until width).flatMap { x ->
            (0 until height).map { y -> GridVec(x, y) to 1 }
        }.toMap()

        val firstRoom = Room.createRandomCenter(width / 2, height / 2, options, rng)
        val afterFirst = applyRoom(
            DigState(width = width, height = height, map = initialMap, rooms = listOf(firstRoom)),
            firstRoom
        )

        val area = ((width - 2) * (height - 2)).coerceAtLeast(1)
        val dugState = digLoop(
            state = afterFirst,
            area = area,
            options = options,
            rng = rng,
            startTimeMs = System.currentTimeMillis()
        )

        val roomsWithDoors = dugState.rooms.map { room ->
            room.copy(doors = emptySet()).withAddedDoors(dugState::isWall)
        }

        if (callback != null) {
            (0 until width).forEach { x ->
                (0 until height).forEach { y ->
                    callback(x, y, dugState.map[GridVec(x, y)] ?: 1)
                }
            }
        }

        return DiggerResult(
            width = width,
            height = height,
            cells = dugState.map,
            rooms = roomsWithDoors,
            corridors = dugState.corridors
        )
    }

    private tailrec fun digLoop(
        state: DigState,
        area: Int,
        options: DiggerOptions,
        rng: AleaRng,
        startTimeMs: Long
    ): DigState {
        if (System.currentTimeMillis() - startTimeMs > options.timeLimitMs) return state
        val (wall, wallsWithoutCurrent) = findWall(state.walls, rng) ?: return state
        val stateAfterWallPop = state.copy(walls = wallsWithoutCurrent)
        val dir = getDiggingDirection(stateAfterWallPop, wall)

        val nextState = if (dir == null) {
            stateAfterWallPop
        } else {
            (1..FEATURE_ATTEMPTS).firstNotNullOfOrNull {
                tryFeature(stateAfterWallPop, wall, dir, options, rng)
            }?.let { withFeature ->
                withFeature.copy(
                    walls = removeSurroundingWalls(
                        removeSurroundingWalls(withFeature.walls, wall),
                        wall - dir
                    )
                )
            } ?: stateAfterWallPop
        }

        val hasPriorityWalls = nextState.walls.values.any { it > 1 }
        return if (nextState.dug.toDouble() / area < options.dugPercentage || hasPriorityWalls) {
            digLoop(nextState, area, options, rng, startTimeMs)
        } else {
            nextState
        }
    }

    private fun findWall(
        walls: Map<GridVec, Int>,
        rng: AleaRng
    ): Pair<GridVec, Map<GridVec, Int>>? {
        val prio2 = walls.entries.filter { it.value == 2 }.map { it.key }
        val pool = prio2.ifEmpty { walls.entries.filter { it.value != 2 }.map { it.key } }
        val chosen = rng.randomElement(pool) ?: return null
        return chosen to (walls - chosen)
    }

    private fun getDiggingDirection(state: DigState, wall: GridVec): GridVec? {
        if (wall.x !in 1 until (state.width - 1) || wall.y !in 1 until (state.height - 1)) return null
        return RotDirs.DIRS_4
            .filter { delta -> state.map[wall + delta] == 0 }
            .singleOrNull()
            ?.let { delta -> delta * -1 }
    }

    private fun tryFeature(
        state: DigState,
        wall: GridVec,
        dir: GridVec,
        options: DiggerOptions,
        rng: AleaRng
    ): DigState? =
        when (rng.getWeightedValue(FEATURES)) {
            "Room" -> Room.createRandomAt(wall.x, wall.y, dir.x, dir.y, options, rng)
                .takeIf { it.isValid(state::isWall, state::canBeDug) }
                ?.let { room -> applyRoom(state.copy(rooms = state.rooms + room), room) }
            else -> Corridor.createRandomAt(wall.x, wall.y, dir.x, dir.y, options, rng)
                .validate(state::isWall, state::canBeDug)
                ?.let { corridor -> applyCorridor(state.copy(corridors = state.corridors + corridor), corridor) }
        }

    private fun applyRoom(state: DigState, room: Room): DigState =
        ((room.x1 - 1)..(room.x2 + 1)).flatMap { x ->
            ((room.y1 - 1)..(room.y2 + 1)).map { y -> GridVec(x, y) }
        }.fold(state) { acc, pos ->
            val isPerimeter = pos.x == room.x1 - 1 || pos.x == room.x2 + 1 ||
                pos.y == room.y1 - 1 || pos.y == room.y2 + 1
            if (pos in room.doors || !isPerimeter) {
                acc.copy(map = acc.map + (pos to 0), dug = acc.dug + 1)
            } else {
                acc.copy(walls = acc.walls + (pos to 1))
            }
        }

    private fun applyCorridor(state: DigState, corridor: Corridor): DigState =
        corridor.cells().let { cells ->
            state.copy(
                map = state.map + cells.associateWith { 0 },
                dug = state.dug + cells.size,
                walls = state.walls + corridor.priorityWalls().associateWith { 2 }
            )
        }

    private fun removeSurroundingWalls(
        walls: Map<GridVec, Int>,
        center: GridVec
    ): Map<GridVec, Int> =
        walls - RotDirs.DIRS_4.flatMap { delta -> listOf(center + delta, center + delta * 2) }.toSet()
}
