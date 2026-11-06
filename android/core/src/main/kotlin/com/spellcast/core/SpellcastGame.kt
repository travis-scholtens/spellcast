package com.spellcast.core

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.spellcast.core.display.AsciiDisplay
import com.spellcast.core.display.SpellcastColors
import com.spellcast.core.model.GameData
import kotlin.math.roundToInt

/**
 * Core LibGDX game entrypoint implementing:
 * - Non-continuous event-driven rendering (`Gdx.graphics.isContinuousRendering = false`)
 * - Display-adaptive ASCII grid layout (`24dp x 38dp` cells, minimum 26x26)
 * - Authentic Spellcast splash/title screen (`logo.txt`, gradient colors, rounded ASCII buttons),
 *   Instructions, Achievements, High Scores, and Dungeon HUD preview
 * - AAOS driving safety lockout overlay
 */
class SpellcastGame : ApplicationAdapter() {

    sealed interface Screen {
        data object Title : Screen
        data object Instructions : Screen
        data class Achievements(val selectedIndex: Int = -1) : Screen
        data object HighScores : Screen
        data object Dungeon : Screen
    }

    private data class GridPoint(val x: Int, val y: Int)

    private data class HitTarget(
        val hitLocs: Set<GridPoint>,
        val nextScreen: Screen
    )

    private data class UiState(
        val screen: Screen = Screen.Title,
        val isDrivingRestricted: Boolean = false,
        val targets: List<HitTarget> = emptyList()
    )

    private data class GraphicsContext(
        val batch: SpriteBatch,
        val camera: OrthographicCamera,
        val display: AsciiDisplay
    )

    private var gfx: GraphicsContext? = null
    private var uiState: UiState = UiState()

    private val inputProcessor = object : InputAdapter() {
        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (uiState.isDrivingRestricted) return true
            val currentGfx = gfx ?: return false
            val (col, row) = currentGfx.display.eventToPosition(screenX, screenY)
            if (col < 0 || row < 0) return false
            handleGridClick(GridPoint(col, row))
            return true
        }
    }

    override fun create() {
        Gdx.graphics.isContinuousRendering = false
        gfx = GraphicsContext(
            batch = SpriteBatch(),
            camera = OrthographicCamera().apply {
                setToOrtho(false, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
            },
            display = AsciiDisplay("SpellyFreeMono.ttf").apply { init() }
        )
        Gdx.input.inputProcessor = inputProcessor
        updateState(uiState.copy(screen = Screen.Title))
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        gfx?.let { ctx ->
            ctx.camera.setToOrtho(false, width.toFloat(), height.toFloat())
            ctx.camera.update()
            ctx.batch.projectionMatrix = ctx.camera.combined
            ctx.display.resize(width, height)
            updateState(uiState)
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        gfx?.let { ctx ->
            ctx.camera.update()
            ctx.batch.projectionMatrix = ctx.camera.combined
            ctx.batch.begin()
            ctx.display.render(ctx.batch)
            ctx.batch.end()
        }
    }

    fun setDrivingRestricted(restricted: Boolean) {
        if (uiState.isDrivingRestricted == restricted) return
        Gdx.app?.postRunnable {
            Gdx.input.inputProcessor = if (restricted) null else inputProcessor
            updateState(uiState.copy(isDrivingRestricted = restricted))
        }
    }

    fun showTitle() {
        updateState(uiState.copy(screen = Screen.Title))
    }

    private fun handleGridClick(point: GridPoint) {
        val nextScreen = when (uiState.screen) {
            is Screen.Title, is Screen.Achievements ->
                uiState.targets.firstOrNull { point in it.hitLocs }?.nextScreen
            is Screen.Instructions, is Screen.HighScores, is Screen.Dungeon ->
                Screen.Title
        }
        nextScreen?.let { updateState(uiState.copy(screen = it)) }
    }

    private fun updateState(next: UiState) {
        val display = gfx?.display ?: return
        val targets = if (next.isDrivingRestricted) {
            renderDrivingRestrictionOverlay(display)
            emptyList()
        } else {
            renderScreen(display, next.screen)
        }
        uiState = next.copy(targets = targets)
        Gdx.graphics.requestRendering()
    }

    private fun renderScreen(display: AsciiDisplay, screen: Screen): List<HitTarget> {
        display.clear()
        return when (screen) {
            is Screen.Title -> renderTitle(display)
            is Screen.Instructions -> {
                renderInstructions(display)
                emptyList()
            }
            is Screen.Achievements -> renderAchievements(display, screen.selectedIndex)
            is Screen.HighScores -> {
                renderHighScores(display)
                emptyList()
            }
            is Screen.Dungeon -> {
                renderDungeonPreview(display)
                emptyList()
            }
        }
    }

    /**
     * Exact port of `Game.drawLogo(y)` and `Game.showTitle()` from `app.js`,
     * centered horizontally on display-adaptive grids (`columns >= 26`).
     */
    private fun renderTitle(display: AsciiDisplay): List<HitTarget> {
        val logoOriginX = ((display.columns - 24) / 2).coerceAtLeast(1)
        val logoStartY = 2 + ((display.rows - 26) / 2).coerceAtLeast(0)

        GameData.LOGO.forEachIndexed { rowIdx, line ->
            line.forEachIndexed { colIdx, ch ->
                if (ch != ' ') {
                    display.draw(
                        logoOriginX + colIdx,
                        logoStartY + rowIdx,
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

        return listOf(
            "  New Game  " to Screen.Dungeon,
            "Instructions" to Screen.Instructions,
            "High  Scores" to Screen.HighScores,
            "Achievements" to Screen.Achievements(-1)
        ).mapIndexed { index, (label, targetScreen) ->
            val ymax = logoStartY + GameData.LOGO.size + 4 * (index + 1)
            drawButton(display, label, 0.0, 0.0, display.columns.toDouble(), ymax.toDouble())
            val leftCol = (display.columns / 2.0 - label.length / 2.0).roundToInt()
            HitTarget(
                hitLocs = (-1..label.length).flatMap { dx ->
                    (-1..1).map { dy -> GridPoint(leftCol + dx, ymax - 3 + dy) }
                }.toSet(),
                nextScreen = targetScreen
            )
        }
    }

    /**
     * Exact port of `drawButton(display, button, origin, xmax, ymax)` in `app.js`.
     */
    private fun drawButton(
        display: AsciiDisplay,
        button: String,
        originX: Double,
        originY: Double,
        xmax: Double,
        ymax: Double
    ) {
        (0 until button.length + 2).forEach { x ->
            (0 until 3).forEach { y ->
                display.draw(
                    originX + xmax / 2.0 - button.length / 2.0 + x - 1.0,
                    originY + ymax - 4.0 + y,
                    when {
                        x == 0 && y == 0 -> '\u256d'
                        x == 0 && y == 2 -> '\u2570'
                        x == 0 -> '\u2502'
                        x == button.length + 1 && y == 0 -> '\u256e'
                        x == button.length + 1 && y == 2 -> '\u256f'
                        x == button.length + 1 -> '\u2502'
                        y == 0 || y == 2 -> '\u2500'
                        else -> null
                    }
                )
            }
        }
        display.drawText(
            originX + xmax / 2.0 - button.length / 2.0 + button.indexOfFirst { it != ' ' }.coerceAtLeast(0),
            originY + ymax - 3.0,
            button.trim()
        )
    }

    private fun renderInstructions(display: AsciiDisplay) {
        val marginX = ((display.columns - 26) / 2).coerceAtLeast(1)
        val maxW = (display.columns - marginX * 2).coerceAtLeast(24)
        INSTRUCTION_LINES.fold(2) { y, line ->
            y + display.drawText(marginX, y, "\u2022 $line", maxW) + 1
        }
    }

    private fun renderAchievements(display: AsciiDisplay, selectedIndex: Int): List<HitTarget> {
        val marginX = ((display.columns - 26) / 2).coerceAtLeast(0)
        val (visible, hidden) = GameData.achievements
            .withIndex()
            .filter { !it.value.retired }
            .partition { it.value.deps.isEmpty() }

        val (finalY, itemTargets) = visible.fold(2 to emptyList<HitTarget>()) { (y, acc), (i, ach) ->
            display.draw(marginX + 2, y, GameData.LOCK_GLYPH, "gray")
            display.drawText(marginX + 4, y, "%c{gray}${ach.name}%c{}")
            val rowAdvance = if (i == selectedIndex) {
                display.drawText(marginX + 4, y + 1, "%c{gray}${ach.todoMsg}%c{}", 22) + 2
            } else {
                1
            }
            val target = HitTarget(
                hitLocs = ach.name.indices.map { offset -> GridPoint(marginX + 4 + offset, y) }.toSet(),
                nextScreen = Screen.Achievements(if (i != selectedIndex) i else -1)
            )
            (y + rowAdvance) to (acc + target)
        }

        if (hidden.isNotEmpty()) {
            display.drawText(marginX + 4, finalY + 1, "%c{gray}+${hidden.size} more still hidden%c{}", 22)
        }
        drawButton(display, "Done", 0.0, 0.0, display.columns.toDouble(), display.rows.toDouble())
        val doneTarget = HitTarget(
            hitLocs = (-1..4).flatMap { dx ->
                (-1..1).map { dy -> GridPoint(display.columns / 2 - 2 + dx, display.rows - 3 + dy) }
            }.toSet(),
            nextScreen = Screen.Title
        )
        return itemTargets + doneTarget
    }

    private fun renderHighScores(display: AsciiDisplay) {
        display.drawText(
            ((display.columns - 24) / 2).coerceAtLeast(1),
            display.rows / 2 - 1,
            "No high scores recorded yet. Tap anywhere to return.",
            24
        )
    }

    /**
     * Renders the initial Dungeon Level 1 HUD with status bar, health/XP meters,
     * starting room, and centered 6+1 corpse rack with starting 'a', 'a' tiles.
     */
    private fun renderDungeonPreview(display: AsciiDisplay) {
        val cols = display.columns
        val rows = display.rows

        // Top status bar (y = 0)
        display.drawText(0, 0, "D: 1")
        display.drawText((cols - 1) / 2, 0, "0")
        display.drawText(cols - 4, 0, "L: 1")

        // Welcome message (y = 1)
        display.drawText(0, 1, "> Welcome to Spellcast! Tap to return to title.")

        // Left health meter & right XP meter
        val meterHeight = (rows - 6).coerceAtLeast(4)
        drawMeter(display, 0, 2, meterHeight, 0, 50, 50, GameData.healthIcon.glyph, GameData.healthIcon.color)
        drawMeter(display, cols - 1, 2, meterHeight, 0, 2, 0, GameData.levelIcon.glyph, GameData.levelIcon.color)

        // Centered room preview
        (-4..4).forEach { dx ->
            (-3..3).forEach { dy ->
                val ch = when {
                    dx == -4 && dy == -3 -> '\u2554'
                    dx == 4 && dy == -3 -> '\u2557'
                    dx == -4 && dy == 3 -> '\u255a'
                    dx == 4 && dy == 3 -> '\u255d'
                    dx == -4 || dx == 4 -> '\u2551'
                    dy == -3 || dy == 3 -> '\u2550'
                    dx == 0 && dy == 0 -> '@'
                    dx == 2 && dy == 0 -> '\u261f'
                    dx == -2 && dy == -1 -> 'a'
                    else -> '\u00b7'
                }
                display.draw(
                    cols / 2 + dx,
                    rows / 2 + dy,
                    ch,
                    when (ch) {
                        '@' -> "#f080ff"
                        'a' -> "darkred"
                        else -> "white"
                    },
                    if (dx == 1 && dy == 0) "pink" else "black"
                )
            }
        }

        // Bottom centered 26-column rack assembly (6 inventory slots + 1 floor slot)
        val rackOriginX = ((cols - 26) / 2).coerceAtLeast(0)
        (0 until 20).forEach { dx ->
            display.draw(
                rackOriginX + dx,
                rows - 1,
                when (dx) {
                    0 -> '\u2514'
                    19 -> '\u2518'
                    else -> '\u2500'
                },
                "tan"
            )
        }
        drawRackTile(display, rackOriginX + 2.0, (rows - 2).toDouble(), 'a', "tan")
        drawRackTile(display, rackOriginX + 5.0, (rows - 2).toDouble(), 'a', "tan")

        (21 until 26).forEach { dx ->
            display.draw(
                rackOriginX + dx,
                rows - 1,
                when (dx) {
                    21 -> '\u2514'
                    25 -> '\u2518'
                    else -> '\u2500'
                },
                "gray"
            )
        }
    }

    private fun drawRackTile(
        display: AsciiDisplay,
        x: Double,
        y: Double,
        glyph: Char,
        color: String
    ) {
        display.drawText(x - 0.5, y, "%b{$color}%c{black}$glyph")
        GameData.values[glyph]
            ?.takeIf { it in 1..9 }
            ?.let { value ->
                display.drawText(x + 0.25, y, "%b{$color}%c{black}${(0x2080 + value).toChar()}")
            }
    }

    private fun drawMeter(
        display: AsciiDisplay,
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
        val totalEighths = (((value - rangeLow).coerceIn(0, range).toDouble() / range) * dy * 8.0).roundToInt()
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

    private fun renderDrivingRestrictionOverlay(display: AsciiDisplay) {
        display.clear()
        val msg = "Vehicle In Motion: Gameplay Suspended"
        display.drawText(
            ((display.columns - msg.length) / 2).coerceAtLeast(1),
            display.rows / 2,
            "%c{yellow}$msg%c{}",
            display.columns - 2
        )
    }

    override fun dispose() {
        gfx?.let { ctx ->
            ctx.display.dispose()
            ctx.batch.dispose()
        }
        gfx = null
    }

    companion object {
        private val INSTRUCTION_LINES: List<String> = listOf(
            "Fight monsters",
            "Use their corpses to cast spells to score points",
            "Scoring points makes  you more powerful",
            "Start with the spell \"AA\" (a kind of lava)",
            "Spells cast on a shrine (like %b{${GameData.shrines[2].color}} %b{}) are worth more",
            "7-corpse spells get a bonus",
            "Cast the spell of \"YENDOR\" to win",
            "Source: github.com/travis-scholtens/spellcast"
        )
    }
}
