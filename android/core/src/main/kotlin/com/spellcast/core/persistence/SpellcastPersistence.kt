package com.spellcast.core.persistence

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter
import com.spellcast.core.model.CastSpellRecord
import com.spellcast.core.model.GameData
import com.spellcast.core.rot.GridVec
import com.spellcast.core.rules.HighScoreEntry
import com.spellcast.core.rules.SpellRules
import com.spellcast.core.world.Corpse
import com.spellcast.core.world.DungeonLevel
import com.spellcast.core.world.FoeEntity
import com.spellcast.core.world.ShrinePlacement
import com.spellcast.core.world.TileCell
import java.io.StringWriter

interface KeyValueStore {
    operator fun get(key: String): String?
    operator fun set(key: String, value: String)
    fun remove(key: String)
    operator fun contains(key: String): Boolean = get(key) != null
}

class InMemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private var entries: Map<String, String> = initial

    override fun get(key: String): String? = entries[key]

    override fun set(key: String, value: String) {
        entries = entries + (key to value)
    }

    override fun remove(key: String) {
        entries = entries - key
    }
}

class GdxPreferencesKeyValueStore(private val prefsName: String = "spellcast_storage") : KeyValueStore {
    private val prefs by lazy { Gdx.app.getPreferences(prefsName) }

    override fun get(key: String): String? =
        if (prefs.contains(key)) prefs.getString(key, null) else null

    override fun set(key: String, value: String) {
        prefs.putString(key, value).flush()
    }

    override fun remove(key: String) {
        prefs.remove(key)
        prefs.flush()
    }
}

data class SavedPlayerSnapshot(
    val pos: GridVec,
    val levelIndex: Int,
    val color: String,
    val experience: Int,
    val woundedness: Int
)

data class SavedAttemptSnapshot(
    val rngState: List<Double>,
    val ascended: Boolean,
    val balanceVersion: Int,
    val complete: Boolean,
    val player: SavedPlayerSnapshot,
    val shrines: Map<Int, Int>,
    val triedShrine: Boolean,
    val castAtShrines: Int,
    val usedFoes: Set<Char>,
    val wastedCorpse: Boolean,
    val rack: List<Corpse>,
    val spellsCast: List<CastSpellRecord>,
    val goodWords: List<String>,
    val badPatterns: List<String>,
    val wordRepeated: Boolean,
    val achievements: Set<String>,
    val lastDrawnAt: Double?
)

data class SavedGameSnapshot(
    val version: Int,
    val rngState: List<Double>,
    val attempt: SavedAttemptSnapshot,
    val levels: List<DungeonLevel>
)

/**
 * Pure functional persistence adapter compatible with `app.js`'s `localStorage` schema:
 * - `spellyAchievements`
 * - `spellyHighScores`
 * - `spellyGameState`
 * - `spellyLevelState0..N`
 */
object SpellcastPersistence {

    private const val ACHIEVEMENTS_KEY = "spellyAchievements"
    private const val HIGH_SCORES_KEY = "spellyHighScores"
    private const val GAME_STATE_KEY = "spellyGameState"
    private const val LEVEL_STATE_PREFIX = "spellyLevelState"
    private const val STORAGE_SCHEMA_VERSION = 0

    private fun JsonValue.childrenSeq(): Sequence<JsonValue> =
        generateSequence(child) { it.next }

    fun loadUnlockedAchievements(store: KeyValueStore): Set<String> =
        runCatching {
            val raw = store[ACHIEVEMENTS_KEY] ?: return emptySet()
            val root = JsonReader().parse(raw)
            if (root.size >= 2 && root.get(0).asInt() == STORAGE_SCHEMA_VERSION) {
                root.get(1).childrenSeq()
                    .filter { it.asBoolean() }
                    .mapNotNull { it.name }
                    .toSet()
            } else {
                emptySet()
            }
        }.getOrDefault(emptySet())

    fun saveUnlockedAchievements(store: KeyValueStore, unlocked: Set<String>) {
        val json = buildJson {
            array()
            value(STORAGE_SCHEMA_VERSION.toLong())
            `object`()
            unlocked.forEach { key -> set(key, true) }
            pop()
            pop()
        }
        store[ACHIEVEMENTS_KEY] = json
    }

    fun maybeUnlockAchievement(store: KeyValueStore, key: String): String? {
        val achievement = GameData.achievements.firstOrNull { it.key == key } ?: return null
        val unlocked = loadUnlockedAchievements(store)
        val canUnlock = key !in unlocked &&
            !achievement.retired &&
            achievement.deps.all { it in unlocked }
        return if (canUnlock) {
            saveUnlockedAchievements(store, unlocked + key)
            SpellRules.formatAchievementMessage(achievement.key, achievement.didMsg)
        } else {
            null
        }
    }

    fun loadHighScores(store: KeyValueStore): List<HighScoreEntry> =
        runCatching {
            val raw = store[HIGH_SCORES_KEY] ?: return emptyList()
            val root = JsonReader().parse(raw)
            if (root.size >= 2 && root.get(0).asInt() == STORAGE_SCHEMA_VERSION) {
                root.get(1).childrenSeq().map { entry ->
                    HighScoreEntry(
                        victoryColor = entry.get(0)?.takeUnless { it.isNull }?.asString(),
                        depth = entry.get(1).asInt(),
                        totalScore = entry.get(2).asInt(),
                        bestWord = entry.get(3)?.takeUnless { it.isNull }?.asString(),
                        balanceVersion = if (entry.size > 4 && !entry.get(4).isNull) entry.get(4).asInt() else -1
                    )
                }.toList()
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

    fun saveHighScores(store: KeyValueStore, scores: List<HighScoreEntry>) {
        val json = buildJson {
            array()
            value(STORAGE_SCHEMA_VERSION.toLong())
            array()
            scores.forEach { score ->
                array()
                value(score.victoryColor)
                value(score.depth.toLong())
                value(score.totalScore.toLong())
                value(score.bestWord)
                value(score.balanceVersion.toLong())
                pop()
            }
            pop()
            pop()
        }
        store[HIGH_SCORES_KEY] = json
    }

    fun clearSavedGame(store: KeyValueStore) {
        store.remove(GAME_STATE_KEY)
        generateSequence(0) { it + 1 }
            .map { "$LEVEL_STATE_PREFIX$it" }
            .takeWhile { it in store }
            .toList()
            .forEach { store.remove(it) }
    }

    fun saveGame(
        store: KeyValueStore,
        currentRngState: List<Double>,
        schedulerTime: Double,
        snapshot: SavedAttemptSnapshot,
        currentLevel: DungeonLevel,
        foeScheduledTime: (FoeEntity) -> Double
    ) {
        val levelIndex = snapshot.player.levelIndex
        store[GAME_STATE_KEY] = serializeGameState(currentRngState, schedulerTime, snapshot)
        store["$LEVEL_STATE_PREFIX$levelIndex"] = serializeLevelState(currentLevel, schedulerTime, foeScheduledTime)
    }

    fun saveLevel(
        store: KeyValueStore,
        levelIndex: Int,
        level: DungeonLevel,
        schedulerTime: Double,
        foeScheduledTime: (FoeEntity) -> Double
    ) {
        store["$LEVEL_STATE_PREFIX$levelIndex"] = serializeLevelState(level, schedulerTime, foeScheduledTime)
    }

    fun loadSavedGameInProgress(store: KeyValueStore, currentSchedulerTime: Double = 0.0): SavedGameSnapshot? =
        runCatching {
            val rawGame = store[GAME_STATE_KEY] ?: return null
            val root = JsonReader().parse(rawGame)
            val version = root.getInt("v", -1)
            val attemptJson = root.get("attempt") ?: return null
            val complete = attemptJson.getBoolean("f", false)
            if (version != SpellRules.GAME_VERSION || complete) return null

            val rngState = root.get("rng").childrenSeq().map { it.asDouble() }.toList()
            val attempt = parseAttemptSnapshot(attemptJson, currentSchedulerTime)
            val levels = generateSequence(0) { it + 1 }
                .map { "$LEVEL_STATE_PREFIX$it" }
                .takeWhile { it in store }
                .mapNotNull { key -> store[key]?.let { parseLevelState(JsonReader().parse(it), currentSchedulerTime) } }
                .toList()
            if (levels.isEmpty() || attempt.player.levelIndex !in levels.indices) return null

            SavedGameSnapshot(
                version = version,
                rngState = rngState,
                attempt = attempt,
                levels = levels
            )
        }.getOrNull()

    private fun serializeGameState(
        currentRngState: List<Double>,
        schedulerTime: Double,
        snapshot: SavedAttemptSnapshot
    ): String = buildJson {
        `object`()
        set("v", SpellRules.GAME_VERSION.toLong())
        array("rng")
        currentRngState.forEach { value(it) }
        pop()
        `object`("attempt")
        `object`("player")
        set("x", snapshot.player.pos.x.toLong())
        set("y", snapshot.player.pos.y.toLong())
        set("color", snapshot.player.color)
        set("experience", snapshot.player.experience.toLong())
        set("woundedness", snapshot.player.woundedness.toLong())
        set("levelIndex", snapshot.player.levelIndex.toLong())
        pop()
        array("rack")
        snapshot.rack.forEach { c ->
            `object`()
            set("g", c.glyph.toString())
            set("t", schedulerTime - c.timeOfDeath)
            pop()
        }
        pop()
        `object`("sh")
        snapshot.shrines.forEach { (idx, freq) -> set(idx.toString(), freq.toLong()) }
        pop()
        set("tsh", snapshot.triedShrine)
        set("csh", snapshot.castAtShrines.toLong())
        `object`("uf")
        snapshot.usedFoes.forEach { ch -> set(ch.toString(), true) }
        pop()
        set("w", snapshot.wastedCorpse)
        array("s")
        snapshot.rngState.forEach { value(it) }
        pop()
        set("a", snapshot.ascended)
        set("b", snapshot.balanceVersion.toLong())
        set("f", snapshot.complete)
        array("c")
        snapshot.spellsCast.forEach { spell ->
            array()
            value(spell.tiles)
            value(spell.depth.toLong())
            value(spell.shrineType?.toLong())
            value(spell.formatted)
            pop()
        }
        pop()
        `object`("d")
        array("g")
        snapshot.goodWords.forEach { value(it) }
        pop()
        array("b")
        snapshot.badPatterns.forEach { value(it) }
        pop()
        pop()
        set("r", snapshot.wordRepeated)
        `object`("h")
        snapshot.achievements.forEach { ach -> set(ach, true) }
        pop()
        if (snapshot.lastDrawnAt != null) {
            set("ld", schedulerTime - snapshot.lastDrawnAt)
        }
        pop()
        pop()
    }

    private fun parseAttemptSnapshot(attemptJson: JsonValue, currentSchedulerTime: Double): SavedAttemptSnapshot {
        val playerJson = attemptJson.get("player")
        val player = SavedPlayerSnapshot(
            pos = GridVec(playerJson.getInt("x"), playerJson.getInt("y")),
            levelIndex = playerJson.getInt("levelIndex"),
            color = playerJson.getString("color"),
            experience = playerJson.getInt("experience"),
            woundedness = playerJson.getInt("woundedness")
        )
        val shrines = attemptJson.get("sh")?.childrenSeq()?.associate {
            it.name.toInt() to it.asInt()
        } ?: emptyMap()
        val usedFoes = attemptJson.get("uf")?.childrenSeq()
            ?.filter { it.asBoolean() }
            ?.mapNotNull { it.name?.firstOrNull() }
            ?.toSet() ?: emptySet()
        val rack = attemptJson.get("rack")?.childrenSeq()?.map { c ->
            Corpse(
                glyph = c.getString("g")[0],
                timeOfDeath = currentSchedulerTime - c.getDouble("t")
            )
        }?.toList() ?: emptyList()
        val spellsCast = attemptJson.get("c")?.childrenSeq()?.map { s ->
            val tiles = s.get(0).asString()
            val depth = s.get(1).asInt()
            val shrineType = s.get(2)?.takeUnless { it.isNull }?.asInt()
            val formatted = if (s.size >= 4 && !s.get(3).isNull) {
                s.get(3).asString()
            } else {
                SpellRules.formatCastSpell(tiles, shrineType)
            }
            CastSpellRecord(tiles = tiles, depth = depth, shrineType = shrineType, formatted = formatted)
        }?.toList() ?: emptyList()
        val dictJson = attemptJson.get("d")
        val goodWords = dictJson?.get("g")?.childrenSeq()?.map { it.asString() }?.toList() ?: emptyList()
        val badPatterns = dictJson?.get("b")?.childrenSeq()?.map { it.asString() }?.toList() ?: emptyList()
        val achievements = attemptJson.get("h")?.childrenSeq()
            ?.filter { it.asBoolean() }
            ?.mapNotNull { it.name }
            ?.toSet() ?: emptySet()
        val lastDrawnAt = attemptJson.get("ld")?.takeUnless { it.isNull }?.let { currentSchedulerTime - it.asDouble() }

        return SavedAttemptSnapshot(
            rngState = attemptJson.get("s").childrenSeq().map { it.asDouble() }.toList(),
            ascended = attemptJson.getBoolean("a", false),
            balanceVersion = attemptJson.getInt("b", -1),
            complete = attemptJson.getBoolean("f", false),
            player = player,
            shrines = shrines,
            triedShrine = attemptJson.getBoolean("tsh", false),
            castAtShrines = attemptJson.getInt("csh", 0),
            usedFoes = usedFoes,
            wastedCorpse = attemptJson.getBoolean("w", false),
            rack = rack,
            spellsCast = spellsCast,
            goodWords = goodWords,
            badPatterns = badPatterns,
            wordRepeated = attemptJson.getBoolean("r", true),
            achievements = achievements,
            lastDrawnAt = lastDrawnAt
        )
    }

    private fun serializeLevelState(
        level: DungeonLevel,
        schedulerTime: Double,
        foeScheduledTime: (FoeEntity) -> Double
    ): String = buildJson {
        `object`()
        set("depth", level.depth.toLong())
        set("width", level.width.toLong())
        set("height", level.height.toLong())
        set("radius", level.radius)
        `object`("up")
        set("x", level.up.x.toLong())
        set("y", level.up.y.toLong())
        pop()
        `object`("down")
        set("x", level.down.x.toLong())
        set("y", level.down.y.toLong())
        pop()
        if (level.shrine != null) {
            `object`("shrine")
            set("x", level.shrine.x.toLong())
            set("y", level.shrine.y.toLong())
            set("type", level.shrine.type.toLong())
            pop()
        } else {
            set("shrine", null as String?)
        }
        array("tileData")
        level.map.forEach { (pos, tile) ->
            if (tile.glyph != null) {
                `object`()
                set("k", "${pos.x},${pos.y}")
                `object`("v")
                if (tile.corpse != null) {
                    set("c", tile.corpse.glyph.toString())
                    set("f", schedulerTime - tile.corpse.timeOfDeath)
                } else {
                    set("c", tile.glyph.toString())
                }
                if (tile.revealed) {
                    set("r", 1L)
                }
                pop()
                pop()
            }
        }
        pop()
        array("foeData")
        level.foes.forEachIndexed { idx, foe ->
            `object`()
            set("k", idx.toLong())
            `object`("v")
            set("id", foe.id.toLong())
            set("x", foe.pos.x.toLong())
            set("y", foe.pos.y.toLong())
            set("kindGlyph", foe.kind.glyph.toString())
            foe.lastSawPlayer?.let { set("lastSawPlayer", it.toLong()) } ?: set("lastSawPlayer", null as String?)
            set("revealed", foe.revealed)
            set("health", foe.health.toLong())
            set("t", foeScheduledTime(foe))
            if (foe.kind == GameData.shapeshifter) {
                set("shiftedKindGlyph", (foe.shiftedKind ?: foe.kind).glyph.toString())
                if (foe.hurtSinceShift) {
                    set("hurtSinceShift", true)
                }
            }
            pop()
            pop()
        }
        pop()
        pop()
    }

    private fun parseLevelState(levelJson: JsonValue, currentSchedulerTime: Double): DungeonLevel {
        val depth = levelJson.getInt("depth")
        val width = levelJson.getInt("width")
        val height = levelJson.getInt("height")
        val radius = levelJson.getDouble("radius")
        val upJson = levelJson.get("up")
        val downJson = levelJson.get("down")
        val up = GridVec(upJson.getInt("x"), upJson.getInt("y"))
        val down = GridVec(downJson.getInt("x"), downJson.getInt("y"))
        val shrine = levelJson.get("shrine")?.takeUnless { it.isNull }?.let { s ->
            ShrinePlacement(x = s.getInt("x"), y = s.getInt("y"), type = s.getInt("type"))
        }

        val explicitTiles = levelJson.get("tileData")?.childrenSeq()?.associate { entry ->
            val parts = entry.getString("k").split(",")
            val pos = GridVec(parts[0].toInt(), parts[1].toInt())
            val v = entry.get("v")
            val ch = v.getString("c")[0]
            val isCorpse = ch in GameData.glyphKind
            val passable = isCorpse || ch == '\u00b7'
            val corpse = if (isCorpse) {
                Corpse(glyph = ch, timeOfDeath = currentSchedulerTime - v.getDouble("f", 0.0))
            } else {
                null
            }
            val glyph = if (isCorpse) '\u00b7' else ch
            val revealed = v.has("r")
            pos to TileCell(passable = passable, glyph = glyph, revealed = revealed, corpse = corpse)
        } ?: emptyMap()

        val allCoords = (0 until width).flatMap { x ->
            (0 until height).map { y -> GridVec(x, y) }
        }
        val fullMap = allCoords.associateWith { pos ->
            explicitTiles[pos] ?: TileCell(passable = false, glyph = null)
        }

        val foes = levelJson.get("foeData")?.childrenSeq()?.mapIndexed { idx, entry ->
            val v = entry.get("v")
            val kind = GameData.glyphKind.getValue(v.getString("kindGlyph")[0])
            val shiftedKind = if (kind == GameData.shapeshifter && v.has("shiftedKindGlyph")) {
                GameData.glyphKind[v.getString("shiftedKindGlyph")[0]]
            } else {
                null
            }
            val effectiveSpeed = (shiftedKind ?: kind).speed ?: 100
            FoeEntity(
                id = v.getInt("id", depth * 10_000 + idx),
                pos = GridVec(v.getInt("x"), v.getInt("y")),
                kind = kind,
                shiftedKind = shiftedKind,
                hurtSinceShift = v.getBoolean("hurtSinceShift", false),
                lastSawPlayer = v.get("lastSawPlayer")?.takeUnless { it.isNull }?.asInt(),
                revealed = v.getBoolean("revealed", false),
                health = v.getInt("health"),
                scheduledTime = v.getDouble("t", 1.0 / effectiveSpeed)
            )
        }?.toList() ?: emptyList()

        return DungeonLevel(
            depth = depth,
            width = width,
            height = height,
            radius = radius,
            up = up,
            down = down,
            playerStart = up,
            shrine = shrine,
            map = fullMap,
            foes = foes
        )
    }

    private fun buildJson(block: JsonWriter.() -> Unit): String =
        StringWriter().also { sw ->
            JsonWriter(sw).apply {
                setOutputType(JsonWriter.OutputType.json)
                block()
                close()
            }
        }.toString()
}
