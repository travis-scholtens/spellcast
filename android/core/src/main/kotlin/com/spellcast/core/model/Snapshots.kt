package com.spellcast.core.model

data class CorpseSnapshot(
    val glyph: Char,
    val ageTicks: Double // Game.scheduler.getTime() - corpse.timeOfDeath
)

data class CastSpellRecord(
    val tiles: String,
    val depth: Int,
    val shrineType: Int?,
    val formatted: String
)

data class FoeSnapshot(
    val x: Int,
    val y: Int,
    val kindGlyph: Char,
    val shiftedKindGlyph: Char?,
    val hurtSinceShift: Boolean,
    val lastSawPlayer: Int?,
    val revealed: Boolean,
    val health: Int,
    val scheduledTime: Double
)

data class TileSnapshot(
    val x: Int,
    val y: Int,
    val glyph: Char,
    val corpseGlyph: Char?,
    val corpseAgeTicks: Double?,
    val revealed: Boolean
)

data class LevelSnapshot(
    val depth: Int,
    val width: Int,
    val height: Int,
    val radius: Double,
    val upX: Int,
    val upY: Int,
    val downX: Int,
    val downY: Int,
    val shrineX: Int?,
    val shrineY: Int?,
    val shrineType: Int?,
    val tiles: List<TileSnapshot>,
    val foes: List<FoeSnapshot>
)

data class AttemptSnapshot(
    val rngState: List<Double>,
    val balanceVersion: Int,
    val ascended: Boolean,
    val complete: Boolean,
    val playerDepth: Int,
    val playerX: Int,
    val playerY: Int,
    val playerWoundedness: Int,
    val playerExperience: Int,
    val playerColor: String,
    val rack: List<CorpseSnapshot>,
    val remainingShrines: Map<Int, Int>,
    val triedShrine: Boolean,
    val castAtShrines: Int,
    val usedFoes: Set<Char>,
    val wastedCorpse: Boolean,
    val spellsCast: List<CastSpellRecord>,
    val goodWords: List<String>,
    val badPatterns: List<String>,
    val wordRepeated: Boolean,
    val attemptAchievements: Set<String>,
    val lastDrawnAgeTicks: Double? // Game.scheduler.getTime() - display.lastDrawnAt
)
