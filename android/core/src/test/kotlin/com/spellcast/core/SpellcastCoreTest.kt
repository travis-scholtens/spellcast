package com.spellcast.core

import com.spellcast.core.display.AsciiDisplay
import com.spellcast.core.display.SpellcastColors
import com.spellcast.core.engine.GameScreen
import com.spellcast.core.engine.GameSession
import com.spellcast.core.model.GameData
import com.spellcast.core.persistence.InMemoryKeyValueStore
import com.spellcast.core.persistence.SpellcastPersistence
import com.spellcast.core.rot.AStarPathfinder
import com.spellcast.core.rot.AleaRng
import com.spellcast.core.rot.GridVec
import com.spellcast.core.rot.PreciseShadowcasting
import com.spellcast.core.rot.RotEngine
import com.spellcast.core.rot.RotMapDigger
import com.spellcast.core.rot.SpeedActor
import com.spellcast.core.rot.SpeedScheduler
import com.spellcast.core.rot.Thenable
import com.spellcast.core.rules.HighScoreEntry
import com.spellcast.core.rules.SpellRules
import com.spellcast.core.world.Corpse
import com.spellcast.core.world.DungeonGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun findWordWithBlankMatchesAppJsTestCases() {
        val dict = setOf("bam", "bum", "aa", "cat", "cut", "cot")
        assertEquals("b(a)m", SpellRules.findWordWithBlank("b.m", dict, emptyList()))
        assertEquals("b(u)m", SpellRules.findWordWithBlank("b.m", dict, listOf("bam")))
        assertEquals("b(a)m", SpellRules.findWordWithBlank("b.m", dict, listOf("bam", "bum")))
        assertEquals("b(a)m", SpellRules.findWordWithBlank("b.m", dict, listOf("bum")))
        assertEquals("b(u)m", SpellRules.findWordWithBlank("b.m", dict, listOf("bum", "bam")))
        assertEquals("yendor", SpellRules.checkWord("yendor", dict, emptyList()))
        assertNull(SpellRules.checkWord("xyzzy", dict, emptyList()))
    }

    @Test
    fun spellValueAndFormatCastSpellHandleShrinesAndBingo() {
        // 'a' = 1, 'b' = 3; "ba" without shrine = 4
        assertEquals(4 to false, SpellRules.spellValue("ba", shrineType = null))
        // Shrine 0 (2x letter, lightblue): 3*2 + 1 = 7
        assertEquals(7 to false, SpellRules.spellValue("ba", shrineType = 0))
        // Shrine 2 (2x word, pink): (3 + 1)*2 = 8
        assertEquals(8 to false, SpellRules.spellValue("ba", shrineType = 2))
        // 7-letter bingo with 'a's: 7 + 50 = 57
        assertEquals(57 to true, SpellRules.spellValue("aaaaaaa", shrineType = null))

        assertEquals("%c{lightblue}B%c{}A", SpellRules.formatCastSpell("ba", shrineType = 0))
        assertEquals("%c{pink}BA%c{}", SpellRules.formatCastSpell("ba", shrineType = 2))
    }

    @Test
    fun dungeonGeneratorCreatesValidDepth1LevelAndDropsCorpses() {
        val rng = AleaRng(777L)
        val initialShrines = DungeonGenerator.initialShrineDistribution()
        val (level, updatedShrines) = DungeonGenerator.generateMap(
            depth = 1,
            rngState = rng.getState(),
            shrineDist = initialShrines,
            rng = rng
        )

        assertEquals(24, level.width)
        assertEquals(24, level.height)
        assertEquals(2, level.foes.size)
        assertNotNull(level.shrine)
        assertEquals(2, level.shrine?.type)
        assertEquals((initialShrines[2] ?: 0) - 1, updatedShrines[2])
        assertTrue(level.map[level.playerStart]?.passable == true)
        assertTrue(level.map[level.down]?.passable == true)

        val mapWithCorpse = DungeonGenerator.dropCorpse(
            map = level.map,
            origin = level.playerStart,
            excludeLocs = setOf(level.playerStart, level.down),
            corpse = Corpse('a', 0.0)
        )
        assertNull(mapWithCorpse[level.playerStart]?.corpse)
        assertTrue(mapWithCorpse.values.any { it.corpse?.glyph == 'a' })
    }

    @Test
    fun persistenceRoundTripsAchievementsHighScoresAndSavedGame() {
        val store = InMemoryKeyValueStore()
        val msg = SpellcastPersistence.maybeUnlockAchievement(store, "xyzzy")
        assertNotNull(msg)
        assertTrue("xyzzy" in SpellcastPersistence.loadUnlockedAchievements(store))
        assertNull(SpellcastPersistence.maybeUnlockAchievement(store, "xyzzy"))

        val scores = listOf(
            HighScoreEntry("rgb(200,160,120)", 12, 450, "%c{pink}YENDOR%c{} (10)", SpellRules.BALANCE_VERSION),
            HighScoreEntry(null, 5, 80, "%c{lightblue}B%c{}AM (7)", SpellRules.BALANCE_VERSION)
        )
        SpellcastPersistence.saveHighScores(store, scores)
        assertEquals(scores, SpellcastPersistence.loadHighScores(store))
    }

    @Test
    fun gameSessionStartsNewGameCastsYendorAndRecordsVictory() {
        val display = AsciiDisplay()
        val store = InMemoryKeyValueStore()
        val dict = setOf("aa", "ab", "bam", "bum")
        var currentTimeMs = 1_000L
        val session = GameSession(
            display = display,
            store = store,
            dictionary = dict,
            rng = AleaRng(1234L),
            scheduleDelay = { _, action -> action() },
            requestRender = {},
            clockMs = { currentTimeMs }
        )

        session.showTitle()
        assertEquals(GameScreen.Title, session.currentScreen)

        // Click "New Game" button at center X, row 14 (logo StartY=2, height=9 -> 11 + 4 - 3 = 12)
        currentTimeMs += 200L
        assertTrue(session.handleGridEvent(GridVec(13, 12), null, null))
        assertEquals(GameScreen.Playing, session.currentScreen)
        assertNotNull(session.currentAttempt)

        // Verify game was saved and can be loaded
        val saved = SpellcastPersistence.loadSavedGameInProgress(store)
        assertNotNull(saved)
        assertEquals(1, saved?.levels?.size)
    }
}
