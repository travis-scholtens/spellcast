package com.spellcast.core

import com.spellcast.core.display.SpellcastColors
import com.spellcast.core.model.GameData
import com.spellcast.core.rot.AStarPathfinder
import com.spellcast.core.rot.AleaRng
import com.spellcast.core.rot.GridVec
import com.spellcast.core.rot.PreciseShadowcasting
import com.spellcast.core.rot.RotEngine
import com.spellcast.core.rot.RotMapDigger
import com.spellcast.core.rot.SpeedActor
import com.spellcast.core.rot.SpeedScheduler
import com.spellcast.core.rot.Thenable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellcastCoreTest {

    @Test
    fun aleaRngStateRoundTripIsDeterministic() {
        val rng = AleaRng(12345L).apply { repeat(10) { getUniform() } }
        val savedState = rng.getState()
        assertEquals(
            List(20) { rng.getUniform() },
            AleaRng(99999L).setState(savedState).let { restored ->
                List(20) { restored.getUniform() }
            }
        )
    }

    @Test
    fun rotMapDiggerIsDeterministicAndCreatesRoomsAndCorridors() {
        val result1 = RotMapDigger.generate(24, 24, AleaRng(42L))
        val result2 = RotMapDigger.generate(24, 24, AleaRng(42L))

        assertEquals(result1.cells, result2.cells)
        assertEquals(result1.rooms, result2.rooms)
        assertEquals(result1.corridors, result2.corridors)
        assertTrue(result1.rooms.isNotEmpty())
        assertTrue(result1.cells.values.any { it == 0 })
    }

    @Test
    fun preciseShadowcastingOccludesBehindWall() {
        val wall = GridVec(5, 4)
        val fov = PreciseShadowcasting { x, y -> GridVec(x, y) != wall }
        val visible = mutableSetOf<GridVec>()
        fov.compute(5, 5, 4) { x, y, _, _ ->
            visible.add(GridVec(x, y))
        }

        assertTrue(GridVec(5, 5) in visible)
        assertTrue(wall in visible)
        assertFalse(GridVec(5, 2) in visible)
        assertTrue(GridVec(5, 7) in visible)
    }

    @Test
    fun aStarPathfinderFindsShortestFourWayPathAroundObstacle() {
        val blocked = setOf(GridVec(2, 1), GridVec(2, 2), GridVec(2, 3))
        val astar = AStarPathfinder(toX = 4, toY = 2, topology = 4) { x, y ->
            x in 0..5 && y in 0..5 && GridVec(x, y) !in blocked
        }
        val path = astar.computePath(fromX = 1, fromY = 2)

        assertTrue(path.isNotEmpty())
        assertEquals(GridVec(1, 2), path.first())
        assertEquals(GridVec(4, 2), path.last())
        assertTrue(path.none { it in blocked })
    }

    @Test
    fun speedSchedulerAndEngineOrderActorsBySpeed() {
        val log = mutableListOf<String>()
        val slow = object : SpeedActor {
            override fun getSpeed(): Int = 100
            override fun act(): Thenable? {
                log.add("slow")
                return null
            }
        }
        val fast = object : SpeedActor {
            override fun getSpeed(): Int = 200
            override fun act(): Thenable? {
                log.add("fast")
                return if (log.size >= 3) Thenable { } else null
            }
        }

        val scheduler = SpeedScheduler<SpeedActor>()
            .add(slow, repeat = true)
            .add(fast, repeat = true)
        val engine = RotEngine(scheduler)
        engine.start()

        assertEquals(listOf("fast", "slow", "fast"), log)
        assertEquals(0.01, scheduler.getTime(), 1e-9)
    }

    @Test
    fun experienceLevelThresholdsMatchBingoPy() {
        assertEquals(1, GameData.levelIndexFor(0))
        assertEquals(2, GameData.levelIndexFor(2))
        assertEquals(3, GameData.levelIndexFor(7))
        assertEquals(30, GameData.levelIndexFor(1036))
    }

    @Test
    fun logoColorGradientMatchesOriginal() {
        assertEquals("rgb(255,0,0)", SpellcastColors.logoColor(0.0, 0.0))
        assertEquals("rgb(255,255,0)", SpellcastColors.logoColor(0.5, 0.5))
        assertEquals("rgb(255,255,255)", SpellcastColors.logoColor(1.0, 1.0))
    }

    @Test
    fun foeAndShrineTablesComplete() {
        assertEquals(26, GameData.foeKinds.size)
        assertEquals(4, GameData.shrines.size)
        assertEquals(15, GameData.achievements.size)
        assertTrue(GameData.values['z'] == 10)
        assertTrue(GameData.values['.'] == 0)
    }
}
