package com.spellcast.core

import com.spellcast.core.display.SpellcastColors
import com.spellcast.core.model.GameData
import com.spellcast.core.rot.AleaRng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellcastCoreTest {

    @Test
    fun aleaRngStateRoundTripIsDeterministic() {
        val rng = AleaRng(12345L).apply { repeat(10) { getUniform() } }
        val savedState = rng.getState()
        assertEquals(
            List(20) { rng.getUniform() },
            AleaRng(99999L).apply { setState(savedState) }.let { restored ->
                List(20) { restored.getUniform() }
            }
        )
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
