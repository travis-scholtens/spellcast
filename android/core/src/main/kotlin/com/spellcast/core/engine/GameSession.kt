package com.spellcast.core.engine

import com.badlogic.gdx.Input
import com.spellcast.core.display.AsciiDisplay
import com.spellcast.core.display.SpellcastColors
import com.spellcast.core.model.CastSpellRecord
import com.spellcast.core.model.ExperienceLevel
import com.spellcast.core.model.GameData
import com.spellcast.core.model.Shrine
import com.spellcast.core.persistence.KeyValueStore
import com.spellcast.core.persistence.SavedAttemptSnapshot
import com.spellcast.core.persistence.SavedGameSnapshot
import com.spellcast.core.persistence.SavedPlayerSnapshot
import com.spellcast.core.persistence.SpellcastPersistence
import com.spellcast.core.rot.AleaRng
import com.spellcast.core.rot.GridVec
import com.spellcast.core.rot.RotDirs
import com.spellcast.core.rot.RotEngine
import com.spellcast.core.rot.SpeedActor
import com.spellcast.core.rot.SpeedScheduler
import com.spellcast.core.rot.Thenable
import com.spellcast.core.rules.HighScoreEntry
import com.spellcast.core.rules.SpellRules
import com.spellcast.core.world.Corpse
import com.spellcast.core.world.DungeonGenerator
import com.spellcast.core.world.DungeonLevel
import com.spellcast.core.world.FoeEntity
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ModalTile(
    val corpse: Corpse,
    val fromRack: Boolean,
    val color: String
)

data class ActiveModal(
    val closeLocs: Set<GridVec>,
    val actLocs: Set<GridVec> = emptySet()
)

data class PlayerState(
    val pos: GridVec,
    val levelIndex: Int,
    val color: String,
    val experience: Int = 0,
    val woundedness: Int = 0,
    val path: List<GridVec> = emptyList(),
    val modalTiles: List<ModalTile> = emptyList()
) {
    val health: Int get() = 50 - woundedness
    val levelDef: ExperienceLevel get() = GameData.experienceLevels[GameData.levelIndexFor(experience)]
    val speed: Int get() = levelDef.speed
}

data class AttemptState(
    val rngState: List<Double>,
    val ascended: Boolean = false,
    val balanceVersion: Int = SpellRules.BALANCE_VERSION,
    val complete: Boolean = false,
    val player: PlayerState,
    val shrines: Map<Int, Int>,
    val triedShrine: Boolean = false,
    val castAtShrines: Int = 0,
    val usedFoes: Set<Char> = emptySet(),
    val wastedCorpse: Boolean = false,
    val rack: List<Corpse> = emptyList(),
    val spellsCast: List<CastSpellRecord> = emptyList(),
    val goodWords: List<String> = emptyList(),
    val badPatterns: List<String> = emptyList(),
    val wordRepeated: Boolean = false,
    val achievements: Set<String> = emptySet(),
    val levels: List<DungeonLevel>,
    val messages: List<String> = emptyList(),
    val activeModal: ActiveModal? = null,
    val lastDrawnAt: Double? = null
) {
    val currentLevel: DungeonLevel
        get() = levels[player.levelIndex]

    fun updateCurrentLevel(transform: (DungeonLevel) -> DungeonLevel): AttemptState =
        copy(levels = levels.mapIndexed { idx, lvl -> if (idx == player.levelIndex) transform(lvl) else lvl })

    fun message(text: String?): AttemptState =
        if (text.isNullOrEmpty()) this else copy(messages = messages + text)

    fun levelsHaveCorpses(): Boolean =
        levels.any { lvl -> lvl.map.values.any { it.corpse != null } }

    fun levelsHaveFoesRevealed(): Boolean =
        levels.any { lvl -> lvl.foes.any { it.revealed } }

    fun levelsHaveFoes(): Boolean =
        levels.any { it.foes.isNotEmpty() }

    fun addSuccessfulSpellWord(word: String): AttemptState {
        val bare = SpellRules.deparenthesize(word)
        return copy(
            wordRepeated = wordRepeated || bare in goodWords,
            goodWords = (goodWords - bare) + bare
        )
    }

    fun addFailedSpell(spell: String): AttemptState =
        if (spell in badPatterns) this else copy(badPatterns = badPatterns + spell)

    fun spellWillSucceed(spell: String): Boolean {
        val re = Regex("^$spell$")
        return goodWords.any { re.matches(it) }
    }

    fun spellWillFail(spell: String): Boolean =
        badPatterns.any { pat ->
            Regex("^${pat.removePrefix("^").removeSuffix("$")}$").matches(spell)
        }

    fun toSavedSnapshot(): SavedAttemptSnapshot =
        SavedAttemptSnapshot(
            rngState = rngState,
            ascended = ascended,
            balanceVersion = balanceVersion,
            complete = complete,
            player = SavedPlayerSnapshot(
                pos = player.pos,
                levelIndex = player.levelIndex,
                color = player.color,
                experience = player.experience,
                woundedness = player.woundedness
            ),
            shrines = shrines,
            triedShrine = triedShrine,
            castAtShrines = castAtShrines,
            usedFoes = usedFoes,
            wastedCorpse = wastedCorpse,
            rack = rack,
            spellsCast = spellsCast,
            goodWords = goodWords,
            badPatterns = badPatterns,
            wordRepeated = wordRepeated,
            achievements = achievements,
            lastDrawnAt = lastDrawnAt
        )
}

sealed interface GameScreen {
    data object Title : GameScreen
    data object Tutorial : GameScreen
    data class Achievements(val selectedIndex: Int = -1) : GameScreen
    data class HighScores(val highlightIndex: Int = -1) : GameScreen
    data object Playing : GameScreen
    data object GameSummary : GameScreen
}

/**
 * Functional port of the full Spellcast game loop (`Game`, `Attempt`, `Player`, `Foe`) from `app.js`.
 */
class GameSession(
    private val display: AsciiDisplay,
    private val store: KeyValueStore,
    private val dictionary: Set<String>,
    private val rng: AleaRng = AleaRng(),
    private val scheduleDelay: (delayMs: Long, action: () -> Unit) -> Unit = { _, action -> action() },
    private val requestRender: () -> Unit = {},
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    private sealed interface TurnActor : SpeedActor {
        data class Player(val session: GameSession) : TurnActor {
            override fun getSpeed(): Int = session.state.attempt?.player?.speed ?: 75
            override fun act(): Thenable? = session.onPlayerAct()
            override fun equals(other: Any?): Boolean = other is Player
            override fun hashCode(): Int = 1
        }

        data class Foe(val id: Int, val session: GameSession) : TurnActor {
            override fun getSpeed(): Int =
                session.state.attempt?.currentLevel?.foes?.firstOrNull { it.id == id }?.speed ?: 100

            override fun act(): Thenable? = session.onFoeAct(id)
            override fun equals(other: Any?): Boolean = other is Foe && other.id == id
            override fun hashCode(): Int = id
        }
    }

    private data class UiButton(
        val locs: Set<GridVec>,
        val action: () -> Unit
    )

    private data class SessionState(
        val screen: GameScreen = GameScreen.Title,
        val attempt: AttemptState? = null,
        val playerActing: Boolean = false,
        val rackTileLocations: Map<Int, Int> = emptyMap(),
        val buttons: List<UiButton> = emptyList(),
        val completedAtMs: Long = 0L,
        val newHighScoreIndex: Int = -1,
        val lastEventMs: Long = 0L
    )

    private val scheduler = SpeedScheduler<TurnActor>()
    private val engine = RotEngine(scheduler)
    private var state = SessionState()

    val currentScreen: GameScreen get() = state.screen
    val currentAttempt: AttemptState? get() = state.attempt

    fun showTitle() {
        val savedInProgress = SpellcastPersistence.loadSavedGameInProgress(store, scheduler.getTime())
        val baseButtons = listOf(
            (if (savedInProgress != null) "Resume  Game" else "  New Game  ") to {
                display.clear()
                if (savedInProgress != null) {
                    rng.setState(savedInProgress.rngState)
                    startAttempt(restoreAttempt(savedInProgress), isRestored = true)
                } else {
                    SpellcastPersistence.clearSavedGame(store)
                    startAttempt(createFreshAttempt(), isRestored = false)
                }
            },
            "Instructions" to { showTutorial() }
        )
        val extraButtons = if (SpellcastPersistence.loadHighScores(store).isNotEmpty()) {
            listOf(
                "High  Scores" to { showHighScores(-1) },
                "Achievements" to { showAchievements(-1) }
            )
        } else {
            emptyList()
        }
        val allButtonDefs = baseButtons + extraButtons

        display.clear()
        val logoStartY = 2 + ((display.rows - 26) / 2).coerceAtLeast(0)
        val afterLogoY = drawLogo(logoStartY)
        val spacing = 4

        val uiButtons = allButtonDefs.mapIndexed { i, (label, action) ->
            val ymax = (afterLogoY + spacing * (i + 1)).toDouble()
            val locs = drawButton(label, 0.0, 0.0, display.columns.toDouble(), ymax)
            UiButton(locs = locs, action = action)
        }

        state = state.copy(
            screen = GameScreen.Title,
            buttons = uiButtons,
            playerActing = false
        )
        requestRender()
    }

    fun showTutorial() {
        val lines = listOf(
            "Fight monsters",
            "Use their corpses to cast spells to score points",
            "Scoring points makes  you more powerful",
            "Start with the spell \"AA\" (a kind of lava)",
            "Spells cast on a shrine (like %b{${GameData.shrines[2].color}} %b{}) are worth more",
            "7-corpse spells get a bonus",
            "Cast the spell of \"YENDOR\" to win",
            "Source: github.com/travis-scholtens/spellcast"
        ).map { "\u2022 $it" }

        showTextScreen(lines)
        state = state.copy(screen = GameScreen.Tutorial, buttons = emptyList())
        requestRender()
    }

    fun showAchievements(selectedIndex: Int = -1) {
        val unlocked = SpellcastPersistence.loadUnlockedAchievements(store)
        val marginX = ((display.columns - 26) / 2).coerceAtLeast(0)
        val textWidth = (display.columns - marginX - 4).coerceAtMost(22).coerceAtLeast(18)
        display.clear()

        val unlockedEntries = GameData.achievements.withIndex().filter { it.value.key in unlocked }
        val (yAfterUnlocked, unlockedButtons) = unlockedEntries.fold(2 to emptyList<UiButton>()) { (y, btns), (i, ach) ->
            display.draw(marginX + 2, y, GameData.UNLOCK_GLYPH, "yellow")
            display.drawText(marginX + 4, y, SpellRules.surroundText(ach.name, "white"))
            val nextSelected = if (i != selectedIndex) i else -1
            val hitLocs = ach.name.indices.map { offset -> GridVec(marginX + 4 + offset, y) }.toSet()
            val dy = if (i == selectedIndex) {
                display.drawText(marginX + 4, y + 1, SpellRules.surroundText(ach.todoMsg, "white"), textWidth) + 2
            } else {
                1
            }
            (y + dy) to (btns + UiButton(hitLocs) { showAchievements(nextSelected) })
        }

        val lockedCandidates = GameData.achievements.withIndex().filter { (_, ach) ->
            ach.key !in unlocked && !ach.retired
        }
        val (hiddenLocked, visibleLocked) = lockedCandidates.partition { (_, ach) ->
            ach.deps.any { dep -> dep !in unlocked }
        }

        val (yAfterLocked, lockedButtons) = visibleLocked.fold(yAfterUnlocked to unlockedButtons) { (y, btns), (i, ach) ->
            display.draw(marginX + 2, y, GameData.LOCK_GLYPH, "gray")
            display.drawText(marginX + 4, y, SpellRules.surroundText(ach.name, "gray"))
            val nextSelected = if (i != selectedIndex) i else -1
            val hitLocs = ach.name.indices.map { offset -> GridVec(marginX + 4 + offset, y) }.toSet()
            val dy = if (i == selectedIndex) {
                display.drawText(marginX + 4, y + 1, SpellRules.surroundText(ach.todoMsg, "gray"), textWidth) + 2
            } else {
                1
            }
            (y + dy) to (btns + UiButton(hitLocs) { showAchievements(nextSelected) })
        }

        if (hiddenLocked.isNotEmpty()) {
            display.drawText(
                marginX + 4,
                yAfterLocked + 1,
                SpellRules.surroundText("+${hiddenLocked.size} more still hidden", "gray"),
                textWidth
            )
        }

        val doneLocs = drawButton("Done", 0.0, 0.0, display.columns.toDouble(), display.rows.toDouble())
        val doneButton = UiButton(doneLocs) { showTitle() }

        state = state.copy(
            screen = GameScreen.Achievements(selectedIndex),
            buttons = lockedButtons + doneButton
        )
        requestRender()
    }

    fun showHighScores(highlightIndex: Int = -1) {
        val scores = SpellRules.sortScores(SpellcastPersistence.loadHighScores(store)).take(8)
        val marginX = ((display.columns - 26) / 2).coerceAtLeast(0)
        val rowWidth = 26.coerceAtMost(display.columns)
        display.clear()

        scores.forEachIndexed { i, entry ->
            val y = 1 + 3 * i
            if (i == highlightIndex) {
                display.draw(marginX, y, '\u2571', "yellow")
                display.draw(marginX, y + 1, '\u2572', "yellow")
                display.draw(marginX + rowWidth - 1, y, '\u2572', "yellow")
                display.draw(marginX + rowWidth - 1, y + 1, '\u2571', "yellow")
            }
            if (entry.balanceVersion < SpellRules.BALANCE_VERSION) {
                display.draw(marginX + if (i == highlightIndex) 1 else 0, y, '*', "gray")
            }
            display.drawText(marginX + 2, y, "${i + 1}.")
            display.draw(
                marginX + 4,
                y,
                if (entry.victoryColor != null) '@' else '\u2620',
                entry.victoryColor ?: "gray"
            )
            display.drawText(marginX + 6, y, " on ${entry.depth}, ${entry.totalScore}")
            entry.bestWord?.let { word ->
                display.drawText(marginX + 4, y + 1, word)
            }
        }

        state = state.copy(
            screen = GameScreen.HighScores(highlightIndex),
            buttons = emptyList()
        )
        requestRender()
    }

    fun redrawCurrentScreen() {
        when (val screen = state.screen) {
            is GameScreen.Title -> showTitle()
            is GameScreen.Tutorial -> showTutorial()
            is GameScreen.Achievements -> showAchievements(screen.selectedIndex)
            is GameScreen.HighScores -> showHighScores(screen.highlightIndex)
            is GameScreen.GameSummary -> state.attempt?.let { showGameSummary(it) }
            is GameScreen.Playing -> state.attempt?.let { attempt ->
                drawWholeMap(attempt)
                if (attempt.player.modalTiles.isNotEmpty()) {
                    renderActiveModal(attempt)
                }
                requestRender()
            }
        }
    }

    fun handleTouch(screenX: Int, screenY: Int): Boolean {
        val (col, row) = display.eventToPosition(screenX, screenY)
        if (col < 0 || row < 0) return false
        val tileCoord = state.attempt?.let { attempt ->
            getTileClickCoords(screenX, screenY, attempt.player.pos, attempt.currentLevel)
        }
        return handleGridEvent(GridVec(col, row), tileCoord, keyCode = null)
    }

    fun handleKeyDown(keyCode: Int): Boolean =
        handleGridEvent(gridPos = null, tilePos = null, keyCode = keyCode)

    fun handleGridEvent(gridPos: GridVec?, tilePos: GridVec?, keyCode: Int?): Boolean {
        val now = clockMs()
        if (now - state.lastEventMs < 100L) return false
        state = state.copy(lastEventMs = now)

        return when (state.screen) {
            is GameScreen.Title, is GameScreen.Achievements -> {
                if (gridPos == null) return false
                val hit = state.buttons.firstOrNull { gridPos in it.locs } ?: return false
                hit.action()
                true
            }
            is GameScreen.Tutorial, is GameScreen.HighScores -> {
                showTitle()
                true
            }
            is GameScreen.GameSummary -> {
                if (now - state.completedAtMs <= 1000L) return false
                SpellcastPersistence.clearSavedGame(store)
                scheduler.clear()
                val highScoreIdx = state.newHighScoreIndex
                state = state.copy(attempt = null)
                if (highScoreIdx in 0..7) {
                    showHighScores(highScoreIdx)
                } else {
                    showTitle()
                }
                true
            }
            is GameScreen.Playing -> {
                val attempt = state.attempt ?: return false
                if (attempt.complete || !state.playerActing) return false
                val acted = when {
                    keyCode != null -> handlePlayerKey(attempt, keyCode)
                    gridPos != null -> handlePlayerClick(attempt, gridPos, tilePos)
                    else -> false
                }
                if (acted) {
                    state = state.copy(playerActing = false)
                    engine.unlock()
                }
                true
            }
        }
    }

    private fun createFreshAttempt(): AttemptState {
        val initialRngState = rng.getState()
        val initialShrines = DungeonGenerator.initialShrineDistribution()
        val (rawLevel1, updatedShrines) = DungeonGenerator.generateMap(
            depth = 1,
            rngState = initialRngState,
            shrineDist = initialShrines,
            rng = rng
        )
        SpellcastPersistence.saveLevel(store, 0, rawLevel1, scheduler.getTime()) { it.scheduledTime }

        val colorBase = rng.getUniformInt(80, 255)
        val playerColor = "rgb($colorBase,${colorBase - 40},${colorBase - 80})"
        val (revealedLevel1, shrineMsg) = DungeonGenerator.revealFrom(rawLevel1, rawLevel1.playerStart)

        return AttemptState(
            rngState = initialRngState,
            shrines = updatedShrines,
            player = PlayerState(
                pos = revealedLevel1.playerStart,
                levelIndex = 0,
                color = playerColor
            ),
            levels = listOf(revealedLevel1)
        ).message(shrineMsg)
    }

    private fun restoreAttempt(saved: SavedGameSnapshot): AttemptState {
        val a = saved.attempt
        return AttemptState(
            rngState = a.rngState,
            ascended = a.ascended,
            balanceVersion = a.balanceVersion,
            complete = a.complete,
            player = PlayerState(
                pos = a.player.pos,
                levelIndex = a.player.levelIndex,
                color = a.player.color,
                experience = a.player.experience,
                woundedness = a.player.woundedness
            ),
            shrines = a.shrines,
            triedShrine = a.triedShrine,
            castAtShrines = a.castAtShrines,
            usedFoes = a.usedFoes,
            wastedCorpse = a.wastedCorpse,
            rack = a.rack,
            spellsCast = a.spellsCast,
            goodWords = a.goodWords,
            badPatterns = a.badPatterns,
            wordRepeated = a.wordRepeated,
            achievements = a.achievements,
            levels = saved.levels,
            lastDrawnAt = a.lastDrawnAt
        )
    }

    private fun startAttempt(attempt: AttemptState, isRestored: Boolean) {
        scheduler.clear()
        state = state.copy(
            screen = GameScreen.Playing,
            attempt = attempt,
            playerActing = false,
            buttons = emptyList()
        )
        if (!isRestored) {
            SpellcastPersistence.saveLevel(store, 0, attempt.levels[0], scheduler.getTime()) { it.scheduledTime }
        }
        drawWholeMap(state.attempt ?: attempt)
        scheduler.add(TurnActor.Player(this), repeat = true, time = 0.0)
        scheduleFoes(attempt.currentLevel.foes)
        engine.start()
        requestRender()
    }

    private fun scheduleFoes(foes: List<FoeEntity>) {
        foes.forEach { foe ->
            scheduler.add(TurnActor.Foe(foe.id, this), repeat = true, time = foe.scheduledTime)
        }
    }

    private fun unscheduleFoes(foes: List<FoeEntity>) {
        foes.forEach { foe ->
            scheduler.remove(TurnActor.Foe(foe.id, this))
        }
    }

    private fun saveCurrentGame() {
        val attempt = state.attempt ?: return
        SpellcastPersistence.saveGame(
            store = store,
            currentRngState = rng.getState(),
            schedulerTime = scheduler.getTime(),
            snapshot = attempt.toSavedSnapshot(),
            currentLevel = attempt.currentLevel,
            foeScheduledTime = { foe ->
                scheduler.getTimeOf(TurnActor.Foe(foe.id, this)) ?: foe.scheduledTime
            }
        )
    }

    private fun onPlayerAct(): Thenable? {
        engine.lock()
        val initialAttempt = state.attempt ?: return null
        if (initialAttempt.complete) {
            showGameSummary(initialAttempt)
            requestRender()
            return null
        }

        val messageCountBefore = initialAttempt.messages.size
        val drawnAttempt = drawWholeMap(initialAttempt)
        val hasNewDrawMessages = drawnAttempt.messages.size > messageCountBefore
        val postDrawAttempt = if (hasNewDrawMessages) {
            drawnAttempt.copy(
                player = drawnAttempt.player.copy(path = emptyList()),
                messages = emptyList()
            )
        } else {
            drawnAttempt.copy(messages = emptyList())
        }
        state = state.copy(attempt = postDrawAttempt, playerActing = true)
        requestRender()

        if (postDrawAttempt.player.path.isNotEmpty() && walkPathStep()) {
            return Thenable { cb ->
                state = state.copy(playerActing = false)
                engine.unlock()
                scheduleDelay(10L) {
                    if (state.screen == GameScreen.Playing && state.attempt?.complete == false) {
                        cb()
                        requestRender()
                    }
                }
            }
        }

        saveCurrentGame()
        return null
    }

    private fun walkPathStep(): Boolean {
        val attempt = state.attempt ?: return false
        val nextPos = attempt.player.path.firstOrNull() ?: return false
        val remainingPath = attempt.player.path.drop(1)
        val level = attempt.currentLevel
        val tile = level.map[nextPos]
        val occupant = level.foesByPos[nextPos]

        if (tile == null || !tile.passable || !tile.revealed || occupant != null) {
            return false
        }

        val movedAttempt = attempt.copy(
            player = attempt.player.copy(pos = nextPos, path = remainingPath)
        )
        val msgCountBefore = movedAttempt.messages.size

        val (afterCorpse, pickedCorpse) = takeCorpseAtPlayer(movedAttempt)
        val (afterStairs, usedStair) = useStairsIfOnStair(afterCorpse)
        val onShrine = afterStairs.currentLevel.shrine?.pos == afterStairs.player.pos
        val (revealedLevel, shrineNotice) = DungeonGenerator.revealFrom(
            afterStairs.currentLevel,
            afterStairs.player.pos
        )
        val withReveal = afterStairs
            .updateCurrentLevel { revealedLevel }
            .message(shrineNotice)

        val shouldStopPath = pickedCorpse || usedStair || onShrine || withReveal.messages.size > msgCountBefore
        val finalAttempt = if (shouldStopPath) {
            withReveal.copy(player = withReveal.player.copy(path = emptyList()))
        } else {
            withReveal
        }

        state = state.copy(attempt = finalAttempt)
        return true
    }

    private fun takeCorpseAtPlayer(attempt: AttemptState): Pair<AttemptState, Boolean> {
        val pos = attempt.player.pos
        val tile = attempt.currentLevel.map[pos] ?: return attempt to false
        val corpse = tile.corpse ?: return attempt to false
        return if (attempt.rack.size < SpellRules.RACK_SIZE) {
            val name = GameData.glyphKind.getValue(corpse.glyph).name
            val updatedLevel = attempt.currentLevel.copy(
                map = attempt.currentLevel.map + (pos to tile.copy(corpse = null))
            )
            attempt
                .updateCurrentLevel { updatedLevel }
                .copy(rack = attempt.rack + corpse)
                .message("You take the $name corpse.") to true
        } else {
            attempt to true
        }
    }

    private fun useStairsIfOnStair(attempt: AttemptState): Pair<AttemptState, Boolean> {
        val level = attempt.currentLevel
        val pos = attempt.player.pos
        return when (pos) {
            level.down -> {
                val clearedShrineAttempt = attempt.updateCurrentLevel { it.copy(shrine = null) }
                state = state.copy(attempt = clearedShrineAttempt)
                saveCurrentGame()

                val snapshotOldLevel = snapshotScheduledFoeTimes(clearedShrineAttempt.currentLevel)
                val withSavedOld = clearedShrineAttempt.updateCurrentLevel { snapshotOldLevel }
                val nextIndex = withSavedOld.player.levelIndex + 1
                val nextExists = nextIndex < withSavedOld.levels.size

                val (withTargetLevel, _) = if (nextExists) {
                    withSavedOld to withSavedOld.levels[nextIndex]
                } else {
                    val (generated, nextShrines) = DungeonGenerator.generateMap(
                        depth = nextIndex + 1,
                        rngState = withSavedOld.rngState,
                        shrineDist = withSavedOld.shrines,
                        rng = rng
                    )
                    withSavedOld.copy(
                        levels = withSavedOld.levels + generated,
                        shrines = nextShrines
                    ) to generated
                }

                val targetLevel = withTargetLevel.levels[nextIndex]
                unscheduleFoes(snapshotOldLevel.foes)
                scheduleFoes(targetLevel.foes)

                val transitioned = withTargetLevel.copy(
                    player = withTargetLevel.player.copy(
                        pos = targetLevel.up,
                        levelIndex = nextIndex,
                        woundedness = if (nextExists) withTargetLevel.player.woundedness else 0
                    )
                ).message(
                    if (nextExists) {
                        "You descend deeper into the dungeon."
                    } else {
                        "You descend deeper into the dungeon and your health is restored."
                    }
                )
                state = state.copy(attempt = transitioned)
                saveCurrentGame()
                transitioned to true
            }
            level.up -> {
                state = state.copy(attempt = attempt)
                saveCurrentGame()

                val snapshotOldLevel = snapshotScheduledFoeTimes(attempt.currentLevel)
                val withSavedOld = attempt.updateCurrentLevel { snapshotOldLevel }
                val prevIndex = (withSavedOld.player.levelIndex - 1).coerceAtLeast(0)
                val targetLevel = withSavedOld.levels[prevIndex]

                unscheduleFoes(snapshotOldLevel.foes)
                scheduleFoes(targetLevel.foes)

                val transitioned = withSavedOld.copy(
                    player = withSavedOld.player.copy(
                        pos = targetLevel.down,
                        levelIndex = prevIndex
                    )
                ).message("You climb back towards the surface.")

                state = state.copy(attempt = transitioned)
                saveCurrentGame()
                transitioned to true
            }
            else -> attempt to false
        }
    }

    private fun snapshotScheduledFoeTimes(level: DungeonLevel): DungeonLevel =
        level.copy(
            foes = level.foes.map { foe ->
                foe.copy(
                    scheduledTime = scheduler.getTimeOf(TurnActor.Foe(foe.id, this)) ?: foe.scheduledTime
                )
            }
        )

    private fun dealDamageToFoe(attempt: AttemptState, foe: FoeEntity): AttemptState {
        val damage = floor(rng.getNormal(attempt.player.levelDef.meanDamage, 2.0))
            .toInt()
            .coerceIn(0, foe.health)
        val newHealth = foe.health - damage
        val name = foe.effectiveKind.name

        return when {
            newHealth == 0 -> {
                scheduler.remove(TurnActor.Foe(foe.id, this))
                val updatedMap = DungeonGenerator.dropCorpse(
                    map = attempt.currentLevel.map,
                    origin = foe.pos,
                    excludeLocs = setOf(attempt.currentLevel.up, attempt.currentLevel.down, attempt.player.pos),
                    corpse = Corpse(glyph = foe.kind.glyph, timeOfDeath = scheduler.getTime())
                )
                attempt.updateCurrentLevel { lvl ->
                    lvl.copy(
                        map = updatedMap,
                        foes = lvl.foes.filterNot { it.id == foe.id }
                    )
                }.message("You kill the $name!")
            }
            damage > 0 -> {
                val updatedFoe = foe.copy(
                    health = newHealth,
                    hurtSinceShift = foe.hurtSinceShift || (foe.kind == GameData.shapeshifter)
                )
                attempt.updateCurrentLevel { lvl ->
                    lvl.copy(foes = lvl.foes.map { if (it.id == foe.id) updatedFoe else it })
                }.message("You hit the $name! %c{red}-$damage")
            }
            else -> attempt.message("You miss the $name.")
        }
    }

    private fun handlePlayerKey(attempt: AttemptState, keyCode: Int): Boolean {
        if (attempt.activeModal != null) return false
        val dirIndex = when (keyCode) {
            Input.Keys.UP, Input.Keys.DPAD_UP, 38 -> 0
            Input.Keys.RIGHT, Input.Keys.DPAD_RIGHT, 39 -> 1
            Input.Keys.DOWN, Input.Keys.DPAD_DOWN, 40 -> 2
            Input.Keys.LEFT, Input.Keys.DPAD_LEFT, 37 -> 3
            else -> return false
        }
        val nextPos = attempt.player.pos + RotDirs.DIRS_4[dirIndex]
        val tile = attempt.currentLevel.map[nextPos] ?: return false
        if (!tile.passable) return false

        val occupant = attempt.currentLevel.foesByPos[nextPos]
        val nextAttempt = if (occupant != null) {
            dealDamageToFoe(attempt, occupant)
        } else {
            val moved = attempt.copy(player = attempt.player.copy(pos = nextPos))
            val (afterCorpse, _) = takeCorpseAtPlayer(moved)
            val (afterStairs, _) = useStairsIfOnStair(afterCorpse)
            val (revealedLevel, shrineNotice) = DungeonGenerator.revealFrom(
                afterStairs.currentLevel,
                afterStairs.player.pos
            )
            afterStairs.updateCurrentLevel { revealedLevel }.message(shrineNotice)
        }
        state = state.copy(attempt = nextAttempt)
        return true
    }

    private fun handlePlayerClick(attempt: AttemptState, gridPos: GridVec, tilePos: GridVec?): Boolean =
        when {
            gridPos.y == display.rows - 2 -> {
                state.rackTileLocations[gridPos.x]?.let { rackSlot ->
                    clickRackTile(attempt, rackSlot)
                }
                false
            }
            attempt.activeModal != null -> {
                handleModalClick(attempt, gridPos)
            }
            tilePos != null -> {
                val tile = attempt.currentLevel.map[tilePos]
                if (tile != null && tile.revealed) {
                    val path = DungeonGenerator.pathTo(attempt.currentLevel, attempt.player.pos, tilePos)
                    val firstStep = path.firstOrNull()
                    val occupant = firstStep?.let { attempt.currentLevel.foesByPos[it] }
                    if (occupant != null) {
                        state = state.copy(attempt = dealDamageToFoe(attempt, occupant))
                    } else {
                        state = state.copy(
                            attempt = attempt.copy(player = attempt.player.copy(path = path))
                        )
                        walkPathStep()
                    }
                    true
                } else {
                    false
                }
            }
            else -> false
        }

    private fun clickRackTile(attempt: AttemptState, slotIndex: Int) {
        val updatedAttempt = when {
            slotIndex in attempt.rack.indices -> {
                val corpse = attempt.rack[slotIndex]
                val remainingRack = attempt.rack.take(slotIndex) + attempt.rack.drop(slotIndex + 1)
                attempt.copy(
                    rack = remainingRack,
                    player = attempt.player.copy(
                        modalTiles = attempt.player.modalTiles + ModalTile(corpse, fromRack = true, color = "tan")
                    )
                )
            }
            slotIndex == 6 -> {
                val playerPos = attempt.player.pos
                val tile = attempt.currentLevel.map[playerPos]
                val corpse = tile?.corpse
                if (tile != null && corpse != null) {
                    attempt
                        .updateCurrentLevel { lvl ->
                            lvl.copy(map = lvl.map + (playerPos to tile.copy(corpse = null)))
                        }
                        .copy(
                            player = attempt.player.copy(
                                modalTiles = attempt.player.modalTiles + ModalTile(corpse, fromRack = false, color = "gray")
                            )
                        )
                } else {
                    attempt
                }
            }
            else -> attempt
        }

        if (updatedAttempt.player.modalTiles.isNotEmpty()) {
            val modal = renderActiveModal(updatedAttempt)
            state = state.copy(attempt = updatedAttempt.copy(activeModal = modal))
            requestRender()
        }
    }

    private fun renderActiveModal(attempt: AttemptState): ActiveModal {
        val tiles = attempt.player.modalTiles
        val button = when {
            tiles.size > 1 -> "Cast"
            tiles[0].fromRack -> "Drop"
            else -> null
        }
        val width = if (button != null) min(26, max(12, 6 + 3 * tiles.size)) else 8
        val height = if (button != null) 8 else 4
        val (modalCenter, activeModal) = drawModalBox(width, height, button)

        val shrine = attempt.currentLevel.shrine
            ?.takeIf { it.pos == attempt.player.pos }
            ?.let { GameData.shrines[it.type] }
        val bestLetterValue = tiles.maxOfOrNull { GameData.values[it.corpse.glyph] ?: 0 } ?: 0
        val bestLetterIndex = tiles.indexOfFirst { (GameData.values[it.corpse.glyph] ?: 0) == bestLetterValue }
        val padding = if (tiles.size == 1) 1 else 0

        tiles.forEachIndexed { i, modalTile ->
            val tileShrine = shrine?.takeIf { it.isWholeWord || i == bestLetterIndex }
            drawRackTile(
                x = modalCenter.first - width / 2.0 + 3 * i + 4 + padding,
                y = modalCenter.second - height / 4.0,
                glyph = modalTile.corpse.glyph,
                color = modalTile.color,
                shrine = tileShrine
            )
        }

        val spell = tiles.map { it.corpse.glyph }.joinToString("")
        when {
            attempt.spellWillSucceed(spell) -> display.draw(
                modalCenter.first + 4.0,
                modalCenter.second - height / 2.0 + height - 3.0,
                '\u2713',
                "lime"
            )
            attempt.spellWillFail(spell) -> display.draw(
                modalCenter.first + 4.0,
                modalCenter.second - height / 2.0 + height - 3.0,
                '\u2717',
                "red"
            )
        }

        drawRackTiles(attempt, attempt.currentLevel.map[attempt.player.pos]?.corpse)
        return activeModal
    }

    private fun handleModalClick(attempt: AttemptState, gridPos: GridVec): Boolean {
        val modal = attempt.activeModal ?: return false
        val tiles = attempt.player.modalTiles

        if (gridPos in modal.closeLocs) {
            val restoredRack = attempt.rack + tiles.filter { it.fromRack }.map { it.corpse }
            val floorCorpse = tiles.firstOrNull { !it.fromRack }?.corpse
            val playerPos = attempt.player.pos
            val restoredAttempt = attempt
                .updateCurrentLevel { lvl ->
                    if (floorCorpse != null) {
                        val cell = lvl.map[playerPos]
                        if (cell != null) lvl.copy(map = lvl.map + (playerPos to cell.copy(corpse = floorCorpse))) else lvl
                    } else {
                        lvl
                    }
                }
                .copy(
                    rack = restoredRack,
                    player = attempt.player.copy(modalTiles = emptyList()),
                    activeModal = null
                )
            val redrawn = drawWholeMap(restoredAttempt)
            state = state.copy(attempt = redrawn)
            requestRender()
            return false
        }

        if (gridPos in modal.actLocs) {
            val playerPos = attempt.player.pos
            val corpseUnderfoot = attempt.currentLevel.map[playerPos]?.corpse != null
            val rawActed = if (tiles.size == 1) {
                executeDropCorpse(attempt, tiles[0].corpse)
            } else {
                executeCastSpell(attempt, tiles)
            }
            val actedAttempt = rawActed.copy(
                player = rawActed.player.copy(modalTiles = emptyList()),
                activeModal = null
            )

            val finalAttempt = if (corpseUnderfoot && !actedAttempt.complete) {
                takeCorpseAtPlayer(actedAttempt).first
            } else {
                actedAttempt
            }

            state = state.copy(attempt = finalAttempt)
            if (finalAttempt.complete) {
                showGameSummary(finalAttempt)
                requestRender()
                return false
            }
            saveCurrentGame()
            return true
        }

        return false
    }

    private fun executeDropCorpse(attempt: AttemptState, corpse: Corpse): AttemptState {
        val name = GameData.glyphKind.getValue(corpse.glyph).name
        val now = scheduler.getTime()
        return if (corpse.rotted(now)) {
            attempt
                .copy(wastedCorpse = true)
                .message("You drop the $name corpse and it immediately rots to nothing.")
        } else {
            val updatedMap = DungeonGenerator.dropCorpse(
                map = attempt.currentLevel.map,
                origin = attempt.player.pos,
                excludeLocs = setOf(attempt.currentLevel.up, attempt.currentLevel.down),
                corpse = corpse
            )
            val msg = if (corpse.rotting(now)) {
                "You drop the $name corpse, which begins to rot."
            } else {
                "You drop the $name corpse."
            }
            attempt.updateCurrentLevel { it.copy(map = updatedMap) }.message(msg)
        }
    }

    private fun executeCastSpell(attempt: AttemptState, tiles: List<ModalTile>): AttemptState {
        val spell = tiles.map { it.corpse.glyph }.joinToString("")
        val word = SpellRules.checkWord(spell, dictionary, attempt.goodWords)
        val shrineType = attempt.currentLevel.shrine
            ?.takeIf { it.pos == attempt.player.pos }
            ?.type

        val afterShrineConsume = if (shrineType != null) {
            attempt
                .updateCurrentLevel { it.copy(shrine = null) }
                .copy(triedShrine = true)
        } else {
            attempt
        }

        if (word == null) {
            val failed = afterShrineConsume
                .addFailedSpell(spell)
                .copy(wastedCorpse = true)
            return if (spell == "xyzzy") {
                unlockAttemptAchievement(failed, "xyzzy")
            } else {
                failed.message("That's not a spell.")
            }
        }

        val previousExperience = afterShrineConsume.player.experience
        val (score, isBingo) = SpellRules.spellValue(spell, shrineType)
        val formatted = SpellRules.formatCastSpell(word, shrineType)
        val newExperience = previousExperience + score
        val verb = if (SpellRules.deparenthesize(word) == "trump") "Fuck" else "You cast"
        val castRecord = CastSpellRecord(
            tiles = spell,
            depth = afterShrineConsume.currentLevel.depth,
            shrineType = shrineType,
            formatted = formatted
        )

        val baseCast = afterShrineConsume
            .copy(
                player = afterShrineConsume.player.copy(experience = newExperience),
                spellsCast = afterShrineConsume.spellsCast + castRecord,
                usedFoes = afterShrineConsume.usedFoes + spell.toSet(),
                castAtShrines = afterShrineConsume.castAtShrines + if (shrineType != null) 1 else 0
            )
            .addSuccessfulSpellWord(word)
            .message("$verb $formatted. %c{${GameData.levelIcon.color}}+$score${GameData.levelIcon.glyph}")
            .let { acc ->
                if (GameData.levelIndexFor(newExperience) > GameData.levelIndexFor(previousExperience)) {
                    acc.message("You have improved.")
                } else {
                    acc
                }
            }
            .let { acc ->
                if (isBingo && acc.levels.size < 4) {
                    unlockAttemptAchievement(acc, "earliest_bingo")
                } else {
                    acc
                }
            }
            .let { acc ->
                val allFoesUsed = GameData.shapeshifter.glyph in acc.usedFoes &&
                    GameData.foeKinds.all { it.glyph in acc.usedFoes }
                if (allFoesUsed) unlockAttemptAchievement(acc, "use_every_foe") else acc
            }
            .let { acc ->
                val totalShrines = GameData.shrines.sumOf { it.frequency }
                if (shrineType != null && acc.castAtShrines == totalShrines) {
                    unlockAttemptAchievement(acc, "every_shrine")
                } else {
                    acc
                }
            }

        state = state.copy(attempt = baseCast)

        return if (word == "yendor") {
            val withCorpseCheck = if (!baseCast.wastedCorpse && baseCast.levelsHaveCorpses()) {
                baseCast.copy(wastedCorpse = true)
            } else {
                baseCast
            }
            completeGame(withCorpseCheck, won = true, previousExperience = previousExperience)
        } else {
            baseCast
        }
    }

    private fun unlockAttemptAchievement(
        attempt: AttemptState,
        key: String,
        appendMessage: Boolean = true
    ): AttemptState {
        val unlockedMsg = SpellcastPersistence.maybeUnlockAchievement(store, key) ?: return attempt
        val updated = attempt.copy(achievements = attempt.achievements + key)
        return if (appendMessage) updated.message(unlockedMsg) else updated
    }

    private fun completeGame(
        attempt: AttemptState,
        won: Boolean,
        previousExperience: Int = attempt.player.experience
    ): AttemptState {
        val afterVictory = if (won) {
            val checks = listOfNotNull(
                "victory_no_shrines".takeIf { !attempt.triedShrine },
                "victory_shallowest".takeIf { attempt.levels.size < 26 },
                "victory_no_fizzles".takeIf { attempt.badPatterns.isEmpty() },
                "victory_at_low_level_2".takeIf { previousExperience <= 350 },
                "victory_no_bingos".takeIf { attempt.spellsCast.all { it.tiles.length < 7 } },
                "victory_use_every_corpse".takeIf { !attempt.wastedCorpse },
                "victory_kill_all_seen".takeIf { !attempt.levelsHaveFoesRevealed() },
                "victory_clear_every_depth".takeIf { !attempt.levelsHaveFoes() },
                "victory_no_repeats".takeIf { !attempt.wordRepeated },
                "victory"
            )
            checks.fold(attempt.copy(ascended = true)) { acc, achKey ->
                unlockAttemptAchievement(acc, achKey, appendMessage = false)
            }
        } else {
            attempt
        }

        val completed = afterVictory.copy(complete = true)
        val highScoreIdx = maybeAddScore(completed)
        state = state.copy(
            attempt = completed,
            completedAtMs = clockMs(),
            newHighScoreIndex = highScoreIdx
        )
        saveCurrentGame()
        return completed
    }

    private fun maybeAddScore(attempt: AttemptState): Int {
        val values = SpellRules.spellValues(attempt.spellsCast)
        val total = values.sum()
        val bestIdx = if (values.isNotEmpty()) values.indexOf(values.max()) else -1
        val newEntry = HighScoreEntry(
            victoryColor = if (attempt.ascended) attempt.player.color else null,
            depth = attempt.currentLevel.depth,
            totalScore = total,
            bestWord = if (bestIdx >= 0) SpellRules.formatSpell(attempt.spellsCast[bestIdx], values[bestIdx]) else null,
            balanceVersion = attempt.balanceVersion
        )
        val existing = SpellcastPersistence.loadHighScores(store)
        val combined = if (total > 0) existing + newEntry else existing
        val sorted = SpellRules.sortScores(combined).take(12)
        SpellcastPersistence.saveHighScores(store, sorted)
        return if (total > 0) sorted.indexOf(newEntry) else -1
    }

    private fun showGameSummary(attempt: AttemptState) {
        val values = SpellRules.spellValues(attempt.spellsCast)
        val bestIdx = if (values.isNotEmpty()) values.indexOf(values.max()) else -1
        val statusPrefix = when {
            attempt.ascended -> "Won at"
            attempt.player.health <= 0 -> "Died at"
            else -> "At"
        }
        val achievementLines = GameData.achievements
            .filter { it.key in attempt.achievements }
            .map { SpellRules.formatAchievementMessage(it.key, it.doneMsg ?: it.didMsg) }
        val bestSpellLine = if (bestIdx >= 0) {
            listOf("Best spell: ${SpellRules.formatSpell(attempt.spellsCast[bestIdx], values[bestIdx])}")
        } else {
            emptyList()
        }
        val lines = listOf("$statusPrefix depth ${attempt.currentLevel.depth}") +
            achievementLines +
            listOf(
                "Spells cast: ${attempt.spellsCast.size}",
                "Total value: ${values.sum()}"
            ) +
            bestSpellLine

        showTextScreen(lines)
        state = state.copy(screen = GameScreen.GameSummary, buttons = emptyList())
    }

    private fun onFoeAct(foeId: Int): Thenable? {
        val attempt = state.attempt ?: return null
        if (attempt.complete) return null
        val level = attempt.currentLevel
        val foe = level.foes.firstOrNull { it.id == foeId } ?: return null
        if (!foe.revealed) return null

        val playerPos = attempt.player.pos
        val inView = DungeonGenerator.locInView(level, foe.pos, playerPos)
        val nextLoc = if (inView || foe.lastSawPlayer != null) {
            DungeonGenerator.nextStepTo(level, foe.pos, playerPos)
        } else {
            null
        }
        val previousActedAt = foe.lastActedAt
        val updatedLastSaw = when {
            inView -> 0
            foe.lastSawPlayer != null && foe.lastSawPlayer < 3 -> foe.lastSawPlayer + 1
            else -> null
        }

        val seenUpdatedFoe = foe.copy(
            lastActedAt = scheduler.getTime(),
            lastSawPlayer = updatedLastSaw
        )
        val seenAttempt = attempt.updateCurrentLevel { lvl ->
            lvl.copy(foes = lvl.foes.map { if (it.id == foeId) seenUpdatedFoe else it })
        }
        state = state.copy(attempt = seenAttempt)

        if (nextLoc == null) return null

        val (shiftedAttempt, activeFoe) = if (
            seenUpdatedFoe.kind == GameData.shapeshifter &&
            seenUpdatedFoe.hurtSinceShift &&
            seenUpdatedFoe.health < seenAttempt.player.levelDef.meanDamage
        ) {
            val newKind = DungeonGenerator.chooseShiftedKind(level.depth, rng)
            if (newKind != seenUpdatedFoe.shiftedKind) {
                val newHealth = newKind.health ?: seenUpdatedFoe.health
                val healed = newHealth - seenUpdatedFoe.health
                val shifted = seenUpdatedFoe.copy(
                    hurtSinceShift = false,
                    shiftedKind = newKind,
                    health = newHealth
                )
                val msg = "The shapeshifter changes form, from ${seenUpdatedFoe.shiftedKind?.name} to ${newKind.name}, regenerating its health! %c{green}+$healed"
                seenAttempt
                    .updateCurrentLevel { lvl ->
                        lvl.copy(foes = lvl.foes.map { if (it.id == foeId) shifted else it })
                    }
                    .message(msg) to shifted
            } else {
                seenAttempt to seenUpdatedFoe
            }
        } else {
            seenAttempt to seenUpdatedFoe
        }

        val afterActionAttempt = if (nextLoc == playerPos) {
            val damage = floor(rng.getNormal(3.0, 1.0))
                .toInt()
                .coerceIn(0, shiftedAttempt.player.health)
            val hurtPlayer = shiftedAttempt.player.copy(
                path = emptyList(),
                woundedness = shiftedAttempt.player.woundedness + damage
            )
            val withHurtPlayer = shiftedAttempt.copy(player = hurtPlayer)
            if (hurtPlayer.health <= 0) {
                val finished = completeGame(withHurtPlayer, won = false)
                engine.lock()
                showGameSummary(finished)
                requestRender()
                return null
            } else if (damage > 0) {
                withHurtPlayer.message(
                    "The ${activeFoe.effectiveKind.name} hits you! %c{${GameData.healthIcon.color}}-$damage${GameData.healthIcon.glyph}"
                )
            } else {
                withHurtPlayer.message("The ${activeFoe.effectiveKind.name} misses you.")
            }
        } else {
            if (shiftedAttempt.currentLevel.foesByPos[nextLoc] != null) {
                state = state.copy(attempt = shiftedAttempt)
                return null
            }
            val movedFoe = activeFoe.copy(pos = nextLoc)
            shiftedAttempt.updateCurrentLevel { lvl ->
                lvl.copy(foes = lvl.foes.map { if (it.id == foeId) movedFoe else it })
            }
        }

        state = state.copy(attempt = afterActionAttempt)

        val lastDrawn = afterActionAttempt.lastDrawnAt
        if (lastDrawn != null && previousActedAt > lastDrawn) {
            state = state.copy(attempt = drawWholeMap(afterActionAttempt))
            requestRender()
            return Thenable { cb ->
                scheduleDelay(100L) {
                    if (state.screen == GameScreen.Playing && state.attempt?.complete == false) {
                        cb()
                        requestRender()
                    }
                }
            }
        }
        return null
    }

    private fun drawWholeMap(attempt: AttemptState): AttemptState {
        display.clear()
        val cols = display.columns
        val rows = display.rows
        val now = scheduler.getTime()

        display.drawText(0, 0, "D: ${attempt.currentLevel.depth}")
        val levelText = "L: ${GameData.levelIndexFor(attempt.player.experience)}"
        display.drawText(cols - levelText.length, 0, levelText)
        drawScore(cols, 0, attempt.player.experience)

        val viewportCoords = (1 until cols - 1).flatMap { dx ->
            (1 until rows - 3).map { dy -> dx to dy }
        }

        val updatedAttempt = viewportCoords.fold(attempt) { acc, (dx, dy) ->
            val worldPos = GridVec(
                x = acc.player.pos.x - cols / 2 + dx,
                y = acc.player.pos.y - rows / 2 + dy
            )
            val level = acc.currentLevel
            if (worldPos.x !in 0 until level.width || worldPos.y !in 0 until level.height) {
                display.draw(dx, dy, null, "black", "black")
                acc
            } else if (worldPos == acc.player.pos) {
                display.draw(dx, dy, '@', acc.player.color, "black")
                acc
            } else {
                val tile = level.map[worldPos]
                if (tile == null || !tile.revealed) {
                    display.draw(dx, dy, null, "white", "black")
                    acc
                } else {
                    val bg = level.shrine
                        ?.takeIf { it.pos == worldPos }
                        ?.let { GameData.shrines[it.type].color }
                        ?: "black"
                    val foe = level.foesByPos[worldPos]
                    val corpse = tile.corpse

                    when {
                        foe != null -> {
                            display.draw(dx, dy, foe.effectiveKind.glyph, foe.effectiveKind.color, bg)
                            if (!foe.revealed) {
                                val revealedFoe = foe.copy(revealed = true)
                                acc.updateCurrentLevel { lvl ->
                                    lvl.copy(foes = lvl.foes.map { if (it.id == foe.id) revealedFoe else it })
                                }.copy(player = acc.player.copy(path = emptyList()))
                            } else {
                                acc
                            }
                        }
                        corpse != null -> {
                            if (corpse.rotted(now)) {
                                val name = GameData.glyphKind.getValue(corpse.glyph).name
                                display.draw(dx, dy, tile.glyph, "white", bg)
                                acc.updateCurrentLevel { lvl ->
                                    lvl.copy(map = lvl.map + (worldPos to tile.copy(corpse = null)))
                                }.copy(wastedCorpse = true)
                                    .message("The $name corpse rots away to nothing.")
                            } else {
                                val glyph = if (corpse.glyph == '.') '\u25a0' else corpse.glyph
                                val fg = if (corpse.rotting(now)) "darkgreen" else "darkgray"
                                display.draw(dx, dy, glyph, fg, bg)
                                val newlyRotting = acc.lastDrawnAt != null &&
                                    corpse.rotting(now) &&
                                    !corpse.rotting(acc.lastDrawnAt)
                                if (newlyRotting) {
                                    val name = GameData.glyphKind.getValue(corpse.glyph).name
                                    acc.message("The $name corpse begins to rot.")
                                } else {
                                    acc
                                }
                            }
                        }
                        worldPos == level.up -> {
                            display.draw(dx, dy, '\u261d', "white", bg)
                            acc
                        }
                        worldPos == level.down -> {
                            display.draw(dx, dy, '\u261f', "white", bg)
                            acc
                        }
                        else -> {
                            display.draw(dx, dy, tile.glyph, "white", bg)
                            acc
                        }
                    }
                }
            }
        }

        val wrappedMessages = SpellRules.wrapMessages(updatedAttempt.messages, cols)
        wrappedMessages.forEachIndexed { idx, line ->
            (0 until cols).forEach { col -> display.draw(col, idx + 1, null) }
            display.drawText(if (line.startsWith(" ")) 2 else 0, idx + 1, line)
        }

        val meterTop = wrappedMessages.size + 1
        val meterHeight = (rows - 3 - wrappedMessages.size - 2).coerceAtLeast(1)
        drawMeter(
            x = 0,
            y = meterTop,
            dy = meterHeight,
            rangeLow = 0,
            rangeHigh = 50,
            value = 50 - updatedAttempt.player.woundedness,
            glyph = GameData.healthIcon.glyph,
            color = GameData.healthIcon.color
        )

        val expLevelIndex = GameData.levelIndexFor(updatedAttempt.player.experience)
        val expLow = GameData.experienceLevels[(expLevelIndex - 1).coerceAtLeast(0)].expThreshold
        val expHigh = GameData.experienceLevels[expLevelIndex].expThreshold
        val expVal = if (expLevelIndex < GameData.experienceLevels.lastIndex) {
            updatedAttempt.player.experience
        } else {
            expHigh
        }
        drawMeter(
            x = cols - 1,
            y = meterTop,
            dy = meterHeight,
            rangeLow = expLow,
            rangeHigh = expHigh,
            value = expVal,
            glyph = GameData.levelIcon.glyph,
            color = GameData.levelIcon.color
        )

        drawRackTiles(updatedAttempt, updatedAttempt.currentLevel.map[updatedAttempt.player.pos]?.corpse)
        val finalAttempt = updatedAttempt.copy(lastDrawnAt = now)
        state = state.copy(attempt = finalAttempt)
        return finalAttempt
    }

    private fun drawRackTiles(attempt: AttemptState, floorCorpse: Corpse?) {
        val cols = display.columns
        val rows = display.rows
        val rackOriginX = ((cols - 26) / 2).coerceAtLeast(0)
        val right = 20

        (0 until right).forEach { dx ->
            val glyph = when (dx) {
                0 -> '\u2514'
                right - 1 -> '\u2518'
                else -> '\u2500'
            }
            display.draw(rackOriginX + dx, rows - 1, glyph, "tan")
        }

        (0 until SpellRules.RACK_SIZE).forEach { i ->
            clearRackSpace(rackOriginX + 2.0 + 3.0 * i, (rows - 2).toDouble())
        }

        val rackSlots = attempt.rack.indices.flatMap { i ->
            drawRackTile(
                x = rackOriginX + 2.0 + 3.0 * i,
                y = (rows - 2).toDouble(),
                glyph = attempt.rack[i].glyph,
                color = "tan",
                shrine = null
            )
            listOf(
                (rackOriginX + 2 + 3 * i) to i,
                (rackOriginX + 2 + 3 * i + 1) to i
            )
        }

        val left = 21
        (left until 26).forEach { dx ->
            val glyph = when (dx) {
                left -> '\u2514'
                25 -> '\u2518'
                else -> '\u2500'
            }
            display.draw(rackOriginX + dx, rows - 1, glyph, "gray")
        }

        clearRackSpace(rackOriginX + 23.0, (rows - 2).toDouble())
        val floorSlots = if (floorCorpse != null) {
            drawRackTile(
                x = rackOriginX + 23.0,
                y = (rows - 2).toDouble(),
                glyph = floorCorpse.glyph,
                color = "gray",
                shrine = null
            )
            listOf(
                (rackOriginX + 23) to 6,
                (rackOriginX + 24) to 6
            )
        } else {
            emptyList()
        }

        state = state.copy(rackTileLocations = (rackSlots + floorSlots).toMap())
    }

    private fun clearRackSpace(x: Double, y: Double) {
        display.clearRect(x - 1.0, y, x + 1.5, y)
    }

    private fun drawRackTile(
        x: Double,
        y: Double,
        glyph: Char,
        color: String,
        shrine: Shrine?
    ) {
        val value = GameData.values[glyph] ?: 0
        val (ax, digits) = if (value < 10) {
            (x - 0.5) to (if (value > 0) listOf(value) else emptyList())
        } else {
            (x - 0.75) to listOf(value / 10, value % 10)
        }

        if (glyph == '.') {
            display.draw(ax, y, null, "black", color)
        } else {
            display.draw(ax, y, glyph, "black", color)
        }
        if (shrine != null) {
            display.draw(ax, y + 1.0, '\u2501', shrine.color)
        }

        if (digits.isNotEmpty()) {
            digits.forEachIndexed { i, digit ->
                display.draw(ax + 1.0 + 0.75 * i, y, (0x2080 + digit).toChar(), "black", color)
                if (shrine != null) {
                    display.draw(ax + 1.0 + 0.75 * i, y + 1.0, '\u2501', shrine.color)
                }
            }
        } else {
            display.draw(ax + 1.0, y, null, "black", color)
            if (shrine != null) {
                display.draw(ax + 1.0, y + 1.0, '\u2501', shrine.color)
            }
        }
    }

    private fun drawModalBox(
        xmax: Int,
        ymax: Int,
        button: String?
    ): Pair<Pair<Double, Double>, ActiveModal> {
        val originX = (display.columns - xmax) / 2.0
        val originY = (display.rows - ymax) / 2.0
        val center = (originX + xmax / 2.0) to (originY + ymax / 2.0)

        display.clearRect(originX - 0.5, originY - 0.5, originX + xmax - 0.5, originY + ymax - 0.5)

        (0 until xmax).forEach { x ->
            (0 until ymax).forEach { y ->
                val glyph = when (x) {
                    0 -> when (y) {
                        0 -> '\u256d'
                        2 -> '\u251c'
                        ymax - 1 -> '\u2570'
                        else -> '\u2502'
                    }
                    1 -> when (y) {
                        0 -> '\u2500'
                        1 -> '\u2613'
                        2 -> '\u2500'
                        ymax - 1 -> '\u2500'
                        else -> null
                    }
                    2 -> when (y) {
                        0 -> '\u252c'
                        1 -> '\u2502'
                        2 -> '\u2518'
                        ymax - 1 -> '\u2500'
                        else -> null
                    }
                    xmax - 1 -> when (y) {
                        0 -> '\u256e'
                        ymax - 1 -> '\u256f'
                        else -> '\u2502'
                    }
                    else -> if (y == 0 || y == ymax - 1) '\u2500' else null
                }
                display.draw(originX + x, originY + y, glyph)
            }
        }

        val closeLocs = expandFractionalGridVec(originX + 1.0, originY + 1.0)
        val actLocs = if (button != null) {
            drawButton(button, originX, originY, xmax.toDouble(), ymax.toDouble())
        } else {
            emptySet()
        }

        return center to ActiveModal(closeLocs = closeLocs, actLocs = actLocs)
    }

    private fun drawButton(
        button: String,
        originX: Double,
        originY: Double,
        xmax: Double,
        ymax: Double
    ): Set<GridVec> {
        val leftX = originX + xmax / 2.0 - button.length / 2.0 - 1.0
        val topY = originY + ymax - 4.0
        display.clearRect(leftX, topY, leftX + button.length + 1.0, topY + 2.0)

        (0 until button.length + 2).forEach { x ->
            (0 until 3).forEach { y ->
                val glyph = when {
                    x == 0 && y == 0 -> '\u256d'
                    x == 0 && y == 2 -> '\u2570'
                    x == 0 -> '\u2502'
                    x == button.length + 1 && y == 0 -> '\u256e'
                    x == button.length + 1 && y == 2 -> '\u256f'
                    x == button.length + 1 -> '\u2502'
                    y == 0 || y == 2 -> '\u2500'
                    else -> null
                }
                display.draw(leftX + x, topY + y, glyph)
            }
        }

        val firstNonSpace = button.indexOfFirst { it != ' ' }.coerceAtLeast(0)
        display.drawText(
            originX + xmax / 2.0 - button.length / 2.0 + firstNonSpace,
            originY + ymax - 3.0,
            button.trim()
        )

        return (-1..button.length).flatMap { dx ->
            (-1..1).flatMap { dy ->
                expandFractionalGridVec(
                    originX + xmax / 2.0 - button.length / 2.0 + dx,
                    originY + ymax - 3.0 + dy
                )
            }
        }.toSet()
    }

    private fun expandFractionalGridVec(x: Double, y: Double): Set<GridVec> =
        listOf(floor(x).toInt(), ceil(x).toInt()).distinct().flatMap { ix ->
            listOf(floor(y).toInt(), ceil(y).toInt()).distinct().map { iy ->
                GridVec(ix, iy)
            }
        }.toSet()

    private fun drawLogo(startY: Int): Int {
        val logoOriginX = ((display.columns - 24) / 2).coerceAtLeast(1)
        GameData.LOGO.forEachIndexed { rowIdx, line ->
            line.forEachIndexed { colIdx, ch ->
                if (ch != ' ') {
                    display.draw(
                        logoOriginX + colIdx,
                        startY + rowIdx,
                        ch,
                        SpellcastColors.logoColor(
                            colIdx.toDouble() / line.length.coerceAtLeast(1),
                            rowIdx.toDouble() / GameData.LOGO.size
                        ),
                        "black"
                    )
                }
            }
        }
        return startY + GameData.LOGO.size
    }

    private fun showTextScreen(lines: List<String>) {
        display.clear()
        val marginX = ((display.columns - 26) / 2).coerceAtLeast(0)
        val maxW = (display.columns - marginX * 2).coerceAtLeast(26)
        lines.fold(0) { y, line ->
            y + display.drawText(marginX, y, line, maxW) + 1
        }
    }

    private fun drawScore(xmax: Int, y: Int, score: Int) {
        val text = score.toString()
        val x = (xmax - text.length) / 2
        display.drawText(x, y, text)
    }

    private fun drawMeter(
        x: Int,
        y: Int,
        dy: Int,
        rangeLow: Int,
        rangeHigh: Int,
        value: Int,
        glyph: Char,
        color: String
    ) {
        val range = (rangeHigh - rangeLow).coerceAtLeast(1)
        val clamped = (value - rangeLow).coerceIn(0, range)
        val totalEighths = ((clamped.toDouble() / range) * dy * 8.0).roundToInt()
        val fullCells = totalEighths / 8
        val remainder = totalEighths % 8

        (0..dy).forEach { i ->
            when {
                i < fullCells -> display.draw(x, y + dy - i, '\u2588', color, "darkgray")
                i == fullCells -> display.draw(
                    x,
                    y + dy - i,
                    if (remainder == 0) null else (0x2580 + remainder).toChar(),
                    color,
                    "darkgray"
                )
                i < dy -> display.draw(x, y + dy - i, '\u2588', "darkgray")
            }
        }
        display.draw(x, y, glyph, color)
    }

    private fun getTileClickCoords(
        screenX: Int,
        screenY: Int,
        playerPos: GridVec,
        level: DungeonLevel
    ): GridVec? {
        val (col, row) = display.eventToPosition(screenX, screenY)
        if (col < 0 || row < 0) return null
        val centerTile = GridVec(
            x = playerPos.x - display.columns / 2 + col,
            y = playerPos.y - display.rows / 2 + row
        )
        return (-1..1).flatMap { dx ->
            (-1..1).mapNotNull { dy ->
                val candidate = GridVec(centerTile.x + dx, centerTile.y + dy)
                if (level.map[candidate]?.passable == true) {
                    val candidateCol = candidate.x - playerPos.x + display.columns / 2
                    val candidateRow = candidate.y - playerPos.y + display.rows / 2
                    val (cx, cy) = display.cellCenterPx(candidateCol, candidateRow)
                    val d2 = (screenX - cx) * (screenX - cx) + (screenY - cy) * (screenY - cy)
                    candidate to d2
                } else {
                    null
                }
            }
        }.minByOrNull { it.second }?.first
    }
}
