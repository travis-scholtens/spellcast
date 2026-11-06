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
import kotlin.math.floor
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

    private enum class ScreenMode {
        TITLE,
        INSTRUCTIONS,
        ACHIEVEMENTS,
        HIGH_SCORES,
        DUNGEON
    }

    private data class GridPoint(val x: Int, val y: Int)
    private data class TitleButton(
        val text: String,
        val hitLocs: List<GridPoint>,
        val action: () -> Unit
    )

    private lateinit var batch: SpriteBatch
    private lateinit var camera: OrthographicCamera
    private lateinit var display: AsciiDisplay

    private var screenMode: ScreenMode = ScreenMode.TITLE
    private var isDrivingRestricted: Boolean = false
    private var selectedAchievementIndex: Int = -1
    private val titleButtons = mutableListOf<TitleButton>()
    private val achievementButtons = mutableListOf<TitleButton>()

    private val inputProcessor = object : InputAdapter() {
        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (isDrivingRestricted) return true
            val (col, row) = display.eventToPosition(screenX, screenY)
            if (col < 0 || row < 0) return false
            handleGridClick(col, row)
            return true
        }
    }

    override fun create() {
        Gdx.graphics.isContinuousRendering = false

        batch = SpriteBatch()
        camera = OrthographicCamera()
        camera.setToOrtho(false, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())

        display = AsciiDisplay("SpellyFreeMono.ttf")
        display.init()

        Gdx.input.inputProcessor = inputProcessor
        showTitle()
        Gdx.graphics.requestRendering()
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        camera.setToOrtho(false, width.toFloat(), height.toFloat())
        camera.update()
        batch.projectionMatrix = camera.combined
        display.resize(width, height)
        redrawActiveScreen()
        Gdx.graphics.requestRendering()
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        camera.update()
        batch.projectionMatrix = camera.combined
        batch.begin()
        display.render(batch)
        batch.end()
    }

    fun setDrivingRestricted(restricted: Boolean) {
        if (isDrivingRestricted == restricted) return
        isDrivingRestricted = restricted
        Gdx.app?.postRunnable {
            Gdx.input.inputProcessor = if (restricted) null else inputProcessor
            redrawActiveScreen()
            Gdx.graphics.requestRendering()
        }
    }

    private fun redrawActiveScreen() {
        if (isDrivingRestricted) {
            drawDrivingRestrictionOverlay()
            return
        }
        when (screenMode) {
            ScreenMode.TITLE -> showTitle()
            ScreenMode.INSTRUCTIONS -> showTutorial()
            ScreenMode.ACHIEVEMENTS -> showAchievements(selectedAchievementIndex)
            ScreenMode.HIGH_SCORES -> showHighScores()
            ScreenMode.DUNGEON -> showDungeonPreview()
        }
    }

    private fun handleGridClick(col: Int, row: Int) {
        when (screenMode) {
            ScreenMode.TITLE -> {
                for (btn in titleButtons) {
                    if (btn.hitLocs.any { it.x == col && it.y == row }) {
                        btn.action()
                        Gdx.graphics.requestRendering()
                        return
                    }
                }
            }
            ScreenMode.INSTRUCTIONS, ScreenMode.HIGH_SCORES, ScreenMode.DUNGEON -> {
                showTitle()
                Gdx.graphics.requestRendering()
            }
            ScreenMode.ACHIEVEMENTS -> {
                for (btn in achievementButtons) {
                    if (btn.hitLocs.any { it.x == col && it.y == row }) {
                        btn.action()
                        Gdx.graphics.requestRendering()
                        return
                    }
                }
            }
        }
    }

    /**
     * Exact port of `Game.drawLogo(y)` and `Game.showTitle()` from `app.js`,
     * centered horizontally on display-adaptive grids (`columns >= 26`).
     */
    fun showTitle() {
        screenMode = ScreenMode.TITLE
        display.clear()
        titleButtons.clear()

        val cols = display.columns
        val rows = display.rows
        val logo = GameData.LOGO
        val logoWidth = 24
        val logoOriginX = ((cols - logoWidth) / 2).coerceAtLeast(1)
        val verticalOffset = ((rows - 26) / 2).coerceAtLeast(0)
        val logoStartY = 2 + verticalOffset

        for (i in logo.indices) {
            val line = logo[i]
            for (j in line.indices) {
                val ch = line[j]
                if (ch != ' ') {
                    val color = SpellcastColors.logoColor(
                        j.toDouble() / line.length.coerceAtLeast(1),
                        i.toDouble() / logo.size
                    )
                    display.draw(logoOriginX + j, logoStartY + i, ch, color, "black")
                }
            }
        }

        val spacing = 4
        val firstButtonY = logoStartY + logo.size + spacing

        val defs = listOf(
            "  New Game  " to { showDungeonPreview() },
            "Instructions" to { showTutorial() },
            "High  Scores" to { showHighScores() },
            "Achievements" to { showAchievements(-1) }
        )

        for (i in defs.indices) {
            val (label, action) = defs[i]
            val ymax = firstButtonY + spacing * i
            drawButton(label, 0.0, 0.0, cols.toDouble(), ymax.toDouble())
            val locs = mutableListOf<GridPoint>()
            val leftCol = (cols / 2.0 - label.length / 2.0).roundToInt()
            for (x in -1..label.length) {
                for (dy in -1..1) {
                    locs.add(GridPoint(leftCol + x, ymax - 3 + dy))
                }
            }
            titleButtons.add(TitleButton(label, locs, action))
        }
    }

    /**
     * Exact port of `drawButton(display, button, origin, xmax, ymax)` in `app.js`.
     */
    private fun drawButton(
        button: String,
        originX: Double,
        originY: Double,
        xmax: Double,
        ymax: Double
    ) {
        for (x in 0 until button.length + 2) {
            for (y in 0 until 3) {
                val glyph: Char? = when {
                    x == 0 -> when (y) {
                        0 -> '\u256d'
                        2 -> '\u2570'
                        else -> '\u2502'
                    }
                    x == button.length + 1 -> when (y) {
                        0 -> '\u256e'
                        2 -> '\u256f'
                        else -> '\u2502'
                    }
                    y == 0 || y == 2 -> '\u2500'
                    else -> null
                }
                display.draw(
                    originX + xmax / 2.0 - button.length / 2.0 + x - 1.0,
                    originY + ymax - 4.0 + y,
                    glyph
                )
            }
        }
        val firstNonSpace = button.indexOfFirst { it != ' ' }.coerceAtLeast(0)
        display.drawText(
            originX + xmax / 2.0 - button.length / 2.0 + firstNonSpace,
            originY + ymax - 3.0,
            button.trim()
        )
    }

    private fun showTutorial() {
        screenMode = ScreenMode.INSTRUCTIONS
        display.clear()
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

        val marginX = ((display.columns - 26) / 2).coerceAtLeast(1)
        val maxW = (display.columns - marginX * 2).coerceAtLeast(24)
        var y = 2
        for (line in lines) {
            y += display.drawText(marginX, y, line, maxW) + 1
        }
    }

    private fun showAchievements(index: Int) {
        screenMode = ScreenMode.ACHIEVEMENTS
        selectedAchievementIndex = index
        display.clear()
        achievementButtons.clear()

        val marginX = ((display.columns - 26) / 2).coerceAtLeast(0)
        var y = 2
        var hidden = 0

        for (i in GameData.achievements.indices) {
            val ach = GameData.achievements[i]
            if (ach.retired) continue
            if (ach.deps.isNotEmpty()) {
                hidden++
                continue
            }
            display.draw(marginX + 2, y, GameData.LOCK_GLYPH, "gray")
            display.drawText(marginX + 4, y, "%c{gray}${ach.name}%c{}")
            val nextIdx = if (i != index) i else -1
            val locs = ach.name.indices.map { offset -> GridPoint(marginX + 4 + offset, y) }
            achievementButtons.add(TitleButton(ach.name, locs) { showAchievements(nextIdx) })
            y += if (i == index) {
                display.drawText(marginX + 4, y + 1, "%c{gray}${ach.todoMsg}%c{}", 22) + 2
            } else {
                1
            }
        }
        if (hidden > 0) {
            display.drawText(marginX + 4, y + 1, "%c{gray}+$hidden more still hidden%c{}", 22)
        }
        drawButton("Done", 0.0, 0.0, display.columns.toDouble(), display.rows.toDouble())
        val doneLocs = mutableListOf<GridPoint>()
        val leftCol = display.columns / 2 - 2
        for (dx in -1..4) {
            for (dy in -1..1) {
                doneLocs.add(GridPoint(leftCol + dx, display.rows - 3 + dy))
            }
        }
        achievementButtons.add(TitleButton("Done", doneLocs) { showTitle() })
    }

    private fun showHighScores() {
        screenMode = ScreenMode.HIGH_SCORES
        display.clear()
        val msg = "No high scores recorded yet. Tap anywhere to return."
        val x = ((display.columns - 24) / 2).coerceAtLeast(1)
        display.drawText(x, display.rows / 2 - 1, msg, 24)
    }

    /**
     * Renders the initial Dungeon Level 1 HUD with status bar, health/XP meters,
     * starting room, and centered 6+1 corpse rack with starting 'a', 'a' tiles.
     */
    private fun showDungeonPreview() {
        screenMode = ScreenMode.DUNGEON
        display.clear()

        val cols = display.columns
        val rows = display.rows

        // Top status bar (y = 0)
        display.drawText(0, 0, "D: 1")
        val scoreText = "0"
        display.drawText((cols - scoreText.length) / 2, 0, scoreText)
        val levelText = "L: 1"
        display.drawText(cols - levelText.length, 0, levelText)

        // Welcome message (y = 1)
        display.drawText(0, 1, "> Welcome to Spellcast! Tap to return to title.")

        // Left health meter & right XP meter
        val meterTop = 2
        val meterHeight = (rows - 3 - 1 - 2).coerceAtLeast(4)
        drawMeter(0, meterTop, meterHeight, 0, 50, 50, GameData.healthIcon.glyph, GameData.healthIcon.color)
        drawMeter(cols - 1, meterTop, meterHeight, 0, 2, 0, GameData.levelIcon.glyph, GameData.levelIcon.color)

        // Simple centered room preview
        val cx = cols / 2
        val cy = rows / 2
        for (dx in -4..4) {
            for (dy in -3..3) {
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
                val fg = when (ch) {
                    '@' -> "#f080ff"
                    'a' -> "darkred"
                    else -> "white"
                }
                val bg = if (dx == 1 && dy == 0) "pink" else "black"
                display.draw(cx + dx, cy + dy, ch, fg, bg)
            }
        }

        // Bottom centered 26-column rack assembly (6 inventory slots + 1 floor slot)
        val rackOriginX = ((cols - 26) / 2).coerceAtLeast(0)
        for (dx in 0 until 20) {
            val glyph = when (dx) {
                0 -> '\u2514'
                19 -> '\u2518'
                else -> '\u2500'
            }
            display.draw(rackOriginX + dx, rows - 1, glyph, "tan")
        }
        // Draw starting 'a', 'a' corpses in slots 0 and 1
        drawRackTile(rackOriginX + 2.0, (rows - 2).toDouble(), 'a', "tan")
        drawRackTile(rackOriginX + 5.0, (rows - 2).toDouble(), 'a', "tan")

        for (dx in 21 until 26) {
            val glyph = when (dx) {
                21 -> '\u2514'
                25 -> '\u2518'
                else -> '\u2500'
            }
            display.draw(rackOriginX + dx, rows - 1, glyph, "gray")
        }
    }

    private fun drawRackTile(x: Double, y: Double, glyph: Char, color: String) {
        val value = GameData.values[glyph] ?: 0
        val ax = x - 0.5
        display.drawText(ax, y, "%b{$color}%c{black}$glyph")
        if (value in 1..9) {
            val subChar = (0x2080 + value).toChar()
            display.drawText(ax + 0.75, y, "%b{$color}%c{black}$subChar")
        }
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
        val remainder = totalEighths % 8
        val fullCells = (totalEighths - remainder) / 8

        var i = 0
        while (i < fullCells && i <= dy) {
            display.draw(x, y + dy - i, '\u2588', color, "darkgray")
            i++
        }
        if (i <= dy) {
            val remChar = if (remainder == 0) null else (0x2580 + remainder).toChar()
            display.draw(x, y + dy - i, remChar, color, "darkgray")
            i++
        }
        for (j in i until dy) {
            display.draw(x, y + dy - j, '\u2588', "darkgray")
        }
        display.draw(x, y, glyph, color)
    }

    private fun drawDrivingRestrictionOverlay() {
        display.clear()
        val msg = "Vehicle In Motion: Gameplay Suspended"
        val cx = ((display.columns - msg.length) / 2).coerceAtLeast(1)
        val cy = display.rows / 2
        display.drawText(cx, cy, "%c{yellow}$msg%c{}", display.columns - 2)
    }

    override fun dispose() {
        display.dispose()
        batch.dispose()
    }
}
