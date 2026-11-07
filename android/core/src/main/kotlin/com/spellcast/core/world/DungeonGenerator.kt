package com.spellcast.core.world

import com.spellcast.core.model.FoeKind
import com.spellcast.core.model.GameData
import com.spellcast.core.rot.AStarPathfinder
import com.spellcast.core.rot.AleaRng
import com.spellcast.core.rot.GridVec
import com.spellcast.core.rot.PreciseShadowcasting
import com.spellcast.core.rot.RotMapDigger
import kotlin.math.floor
import kotlin.math.sqrt

data class Corpse(
    val glyph: Char,
    val timeOfDeath: Double
) {
    fun rotting(atTime: Double): Boolean = (atTime - timeOfDeath) > 0.9
    fun rotted(atTime: Double): Boolean = (atTime - timeOfDeath) > 1.0
}

data class ShrinePlacement(
    val x: Int,
    val y: Int,
    val type: Int
) {
    val pos: GridVec get() = GridVec(x, y)
}

data class FoeEntity(
    val id: Int,
    val pos: GridVec,
    val kind: FoeKind,
    val shiftedKind: FoeKind? = null,
    val hurtSinceShift: Boolean = false,
    val lastSawPlayer: Int? = null,
    val revealed: Boolean = false,
    val health: Int,
    val scheduledTime: Double,
    val lastActedAt: Double = -1.0
) {
    val effectiveKind: FoeKind
        get() = if (kind == GameData.shapeshifter) (shiftedKind ?: kind) else kind

    val speed: Int
        get() = effectiveKind.speed ?: 100
}

data class TileCell(
    val passable: Boolean,
    val glyph: Char?,
    val revealed: Boolean = false,
    val corpse: Corpse? = null
)

data class DungeonLevel(
    val depth: Int,
    val width: Int,
    val height: Int,
    val radius: Double,
    val up: GridVec,
    val down: GridVec,
    val playerStart: GridVec,
    val shrine: ShrinePlacement?,
    val map: Map<GridVec, TileCell>,
    val foes: List<FoeEntity>
) {
    val foesByPos: Map<GridVec, FoeEntity> = foes.associateBy { it.pos }

    val fov: PreciseShadowcasting = PreciseShadowcasting { x, y ->
        x in 0 until width && y in 0 until height && (map[GridVec(x, y)]?.passable == true)
    }
}

/**
 * Pure functional port of Spellcast's map generation, box-drawing wall autotiling,
 * FOV visibility queries, A* pathfinding, and corpse BFS placement from `app.js`.
 */
object DungeonGenerator {

    private const val DIR_N = 1
    private const val DIR_E = 2
    private const val DIR_S = 4
    private const val DIR_W = 8

    private val NEIGHBOR_OFFSETS_8: List<GridVec> = listOf(
        GridVec(-1, -1), GridVec(-1, 0), GridVec(-1, 1),
        GridVec(0, -1), GridVec(0, 1),
        GridVec(1, -1), GridVec(1, 0), GridVec(1, 1)
    )

    fun initialShrineDistribution(): Map<Int, Int> =
        GameData.shrines.indices.associateWith { GameData.shrines[it].frequency }

    fun foeDistribution(depth: Int, includeShapeshifter: Boolean = false): Map<Char, Double> {
        val slice = GameData.foeKinds.take(depth)
        val base = slice.associate { it.glyph to it.frequency.toDouble() }
        return if (includeShapeshifter && depth > 1) {
            val levelTiles = slice.sumOf { it.frequency }
            val totalTiles = GameData.foeKinds.sumOf { it.frequency }
            val blankWeight = GameData.shapeshifter.frequency * (levelTiles.toDouble() / totalTiles)
            base + (GameData.shapeshifter.glyph to blankWeight)
        } else {
            base
        }
    }

    fun chooseShiftedKind(depth: Int, rng: AleaRng): FoeKind =
        GameData.glyphKind.getValue(rng.getWeightedValue(foeDistribution(depth, includeShapeshifter = false)))

    fun generateMap(
        depth: Int,
        rngState: List<Double>,
        shrineDist: Map<Int, Int>,
        rng: AleaRng,
        startingFoeId: Int = depth * 10_000
    ): Pair<DungeonLevel, Map<Int, Int>> {
        val dim = when (depth) {
            1 -> 24
            2 -> 36
            3 -> 48
            4 -> 56
            else -> 64
        }
        rng.setState(rngState)
        repeat(depth) { rng.getUniform() }

        val radius = sqrt((dim * dim + dim * dim).toDouble())
        val diggerResult = RotMapDigger.generate(dim, dim, rng)

        val orderedCoords = (0 until dim).flatMap { x ->
            (0 until dim).map { y -> GridVec(x, y) }
        }
        val passableSet = orderedCoords.filter { diggerResult.cells[it] == 0 }.toSet()
        val initialFreeCells = orderedCoords.filter { it in passableSet }

        val wallGlyphs = computeWallGlyphs(dim, dim, passableSet)
        val tileMap = orderedCoords.associateWith { pos ->
            if (pos in passableSet) {
                TileCell(passable = true, glyph = '\u00b7')
            } else {
                TileCell(passable = false, glyph = wallGlyphs[pos])
            }
        }

        val rooms = diggerResult.rooms
        val upRoom = rng.getUniformInt(0, rooms.lastIndex)
        val downRoom = if (rooms.size > 1) {
            generateSequence { rng.getUniformInt(0, rooms.lastIndex) }.first { it != upRoom }
        } else {
            upRoom
        }
        val shrineRoom = when {
            depth == 1 -> generateSequence { rng.getUniformInt(0, rooms.lastIndex) }
                .first { rooms.size <= 2 || (it != upRoom && it != downRoom) }
            rng.getUniformInt(0, 1) != 0 -> null
            else -> rng.getUniformInt(0, rooms.lastIndex).takeUnless { it == upRoom || it == downRoom }
        }

        val upCenter = rooms[upRoom].center
        val downCenter = rooms[downRoom].center
        val up = if (depth > 1) GridVec(upCenter.x - 1, upCenter.y) else GridVec(-1, -1)
        val playerStart = if (depth == 1) upCenter else up
        val down = downCenter

        val freeAfterStairs = initialFreeCells.filterNot { it == up || it == down }

        val activeShrineWeights = shrineDist.filterValues { it > 0 }
        val shrine = if (shrineRoom != null && activeShrineWeights.values.sum() > 0) {
            val center = rooms[shrineRoom].center
            val type = if (depth == 1) 2 else rng.getWeightedValue(shrineDist)
            ShrinePlacement(x = center.x + 1, y = center.y, type = type)
        } else {
            null
        }
        val updatedShrineDist = if (shrine != null) {
            shrineDist + (shrine.type to ((shrineDist[shrine.type] ?: 1) - 1))
        } else {
            shrineDist
        }

        val kinds = foeDistribution(depth, includeShapeshifter = depth > 1)
        val (minFoes, maxFoes) = foeLimitsForDepth(depth)
        val count = rng.getUniformInt(minFoes, maxFoes)

        val (_, spawnedFoes) = (0 until count).fold(freeAfterStairs to emptyList<FoeEntity>()) { (free, acc), idxOffset ->
            if (free.isEmpty()) {
                free to acc
            } else {
                val idx = floor(rng.getUniform() * free.size).toInt()
                val loc = free[idx]
                val remainingFree = free.take(idx) + free.drop(idx + 1)
                val kind = GameData.glyphKind.getValue(rng.getWeightedValue(kinds))
                val shifted = if (kind == GameData.shapeshifter) chooseShiftedKind(depth, rng) else null
                val effective = shifted ?: kind
                val initialHealth = effective.health ?: 5
                val initialSpeed = effective.speed ?: 100
                val foe = FoeEntity(
                    id = startingFoeId + idxOffset,
                    pos = loc,
                    kind = kind,
                    shiftedKind = shifted,
                    health = initialHealth,
                    scheduledTime = 1.0 / initialSpeed
                )
                remainingFree to (acc + foe)
            }
        }

        return DungeonLevel(
            depth = depth,
            width = dim,
            height = dim,
            radius = radius,
            up = up,
            down = down,
            playerStart = playerStart,
            shrine = shrine,
            map = tileMap,
            foes = spawnedFoes
        ) to updatedShrineDist
    }

    private fun foeLimitsForDepth(depth: Int): Pair<Int, Int> = when {
        depth == 1 -> 2 to 2
        depth == 2 -> 4 to 5
        depth == 3 -> 6 to 9
        depth == 4 -> 9 to 13
        depth <= 26 -> {
            val base = (depth - 5) * (depth - 5) / 60
            (base + 10) to (base + 20)
        }
        else -> (2 * depth - 36) to (2 * depth - 26)
    }

    private fun computeWallGlyphs(
        width: Int,
        height: Int,
        passableSet: Set<GridVec>
    ): Map<GridVec, Char> {
        fun isExposed(pos: GridVec): Boolean =
            pos in passableSet || NEIGHBOR_OFFSETS_8.any { delta -> (pos + delta) in passableSet }

        fun isWall(pos: GridVec): Boolean =
            pos.x in 0 until width &&
                pos.y in 0 until height &&
                pos !in passableSet &&
                isExposed(pos)

        fun ninetyDegreeAdjacentWalls(loc: GridVec, dir: Int): List<Int> =
            (if (dir == DIR_N || dir == DIR_S) listOf(DIR_E, DIR_W) else listOf(DIR_N, DIR_S))
                .filter { adjDir -> isWall(loc + dirOffset(adjDir)) }

        val edgePairs = passableSet.flatMap { floorLoc ->
            listOf(DIR_E, DIR_W, DIR_N, DIR_S)
                .map { dir -> dir to (floorLoc + dirOffset(dir)) }
                .filter { (_, wallLoc) -> isWall(wallLoc) }
                .flatMap { (wallDir, wallLoc) ->
                    ninetyDegreeAdjacentWalls(wallLoc, wallDir).flatMap { adjDir ->
                        listOf(
                            wallLoc to adjDir,
                            (wallLoc + dirOffset(adjDir)) to oppositeDir(adjDir)
                        )
                    }
                }
        }

        return edgePairs
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, dirs) -> selectWall(dirs.fold(0) { acc, d -> acc or d }) }
    }

    private fun dirOffset(dir: Int): GridVec = when (dir) {
        DIR_E -> GridVec(1, 0)
        DIR_W -> GridVec(-1, 0)
        DIR_N -> GridVec(0, -1)
        DIR_S -> GridVec(0, 1)
        else -> GridVec(0, 0)
    }

    private fun oppositeDir(dir: Int): Int = when (dir) {
        DIR_E -> DIR_W
        DIR_W -> DIR_E
        DIR_N -> DIR_S
        DIR_S -> DIR_N
        else -> 0
    }

    fun selectWall(neighborWallDirections: Int): Char = when (neighborWallDirections) {
        3 -> '\u255a'
        5 -> '\u2551'
        6 -> '\u2554'
        7 -> '\u2560'
        9 -> '\u255d'
        10 -> '\u2550'
        11 -> '\u2569'
        12 -> '\u2557'
        13 -> '\u2563'
        14 -> '\u2566'
        15 -> '\u256c'
        else -> '\u25a2'
    }

    fun fovSet(level: DungeonLevel, loc: GridVec, radius: Int): Set<GridVec> =
        buildSet {
            level.fov.compute(loc.x, loc.y, radius) { x, y, _, _ ->
                add(GridVec(x, y))
            }
        }

    fun revealFrom(level: DungeonLevel, fromLoc: GridVec): Pair<DungeonLevel, String?> {
        val visible = fovSet(level, fromLoc, 7)
        val shrineNotice = level.shrine
            ?.takeIf { shrine -> shrine.pos in visible && level.map[shrine.pos]?.revealed != true }
            ?.let { shrine -> "You notice a Shrine of ${GameData.shrines[shrine.type].name}." }
        val updatedMap = visible.fold(level.map) { acc, pos ->
            acc[pos]?.takeUnless { it.revealed }?.let { tile ->
                acc + (pos to tile.copy(revealed = true))
            } ?: acc
        }
        return level.copy(map = updatedMap) to shrineNotice
    }

    fun locInView(level: DungeonLevel, fromLoc: GridVec, targetLoc: GridVec): Boolean =
        targetLoc in fovSet(level, fromLoc, level.radius.toInt())

    fun pathTo(level: DungeonLevel, fromLoc: GridVec, toLoc: GridVec): List<GridVec> {
        val avoid = listOf(level.down, level.up)
            .filter { stair -> stair != fromLoc && stair != toLoc }
            .toSet()
        val astar = AStarPathfinder(toLoc.x, toLoc.y, topology = 4) { x, y ->
            val pos = GridVec(x, y)
            pos !in avoid && level.map[pos]?.passable == true
        }
        return astar.computePath(fromLoc.x, fromLoc.y).drop(1)
    }

    fun nextStepTo(level: DungeonLevel, fromLoc: GridVec, toLoc: GridVec): GridVec? =
        pathTo(level, fromLoc, toLoc).firstOrNull()

    fun dropCorpse(
        map: Map<GridVec, TileCell>,
        origin: GridVec,
        excludeLocs: Set<GridVec>,
        corpse: Corpse
    ): Map<GridVec, TileCell> =
        findDropTarget(map, listOf(origin), setOf(origin), excludeLocs)
            ?.let { targetPos ->
                map[targetPos]?.let { cell -> map + (targetPos to cell.copy(corpse = corpse)) }
            } ?: map

    private tailrec fun findDropTarget(
        map: Map<GridVec, TileCell>,
        queue: List<GridVec>,
        seen: Set<GridVec>,
        excludeLocs: Set<GridVec>
    ): GridVec? {
        val current = queue.firstOrNull() ?: return null
        val cell = map[current]
        if (cell != null && cell.passable && cell.corpse == null && current !in excludeLocs) {
            return current
        }
        val neighbors = (-1..1).flatMap { dx ->
            (-1..1).mapNotNull { dy ->
                if (dx == 0 && dy == 0) null else GridVec(current.x + dx, current.y + dy)
            }
        }.filter { it in map && it !in seen }
        return findDropTarget(
            map = map,
            queue = queue.drop(1) + neighbors,
            seen = seen + neighbors,
            excludeLocs = excludeLocs
        )
    }
}
