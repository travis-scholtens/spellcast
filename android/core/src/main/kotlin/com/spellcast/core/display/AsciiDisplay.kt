package com.spellcast.core.display

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.spellcast.core.model.GameData
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure LibGDX + gdx-freetype implementation of ROT.Display with:
 * - Adaptive display cell sizing (target 24dp x 38dp, minimum 26x26 grid)
 * - Exact Em-box alignment anchored to U+2588 full block (zero seams on box-drawing/block art)
 * - Fractional (Double, Double) cell positioning for rack tiles and centered buttons
 * - Multi-glyph stacked cells and %c{fg} / %b{bg} inline color markup + word wrapping
 */
class AsciiDisplay(private val fontAssetPath: String = "SpellyFreeMono.ttf") {

    data class CellCoord(val xBits: Long, val yBits: Long) {
        constructor(x: Double, y: Double) : this(
            (x * 100.0).roundToInt().toLong(),
            (y * 100.0).roundToInt().toLong()
        )
    }

    data class CellData(
        val x: Double,
        val y: Double,
        val glyphs: List<Char>,
        val fgColors: List<Color>,
        val bgColor: Color
    )

    data class GridMetrics(
        val screenWidthPx: Int = 1024,
        val screenHeightPx: Int = 768,
        val columns: Int = 26,
        val rows: Int = 26,
        val cellWidthPx: Float = 24f,
        val cellHeightPx: Float = 38f,
        val viewportOriginX: Float = 0f,
        val viewportOriginY: Float = 0f
    )

    private data class RenderResources(
        val whitePixelTexture: Texture,
        val fontGenerator: FreeTypeFontGenerator,
        val bitmapFont: BitmapFont
    )

    private data class StyledChar(val ch: Char, val fg: String, val bg: String)
    private data class StyleAcc(
        val fg: String = "white",
        val bg: String = "black",
        val emitted: StyledChar? = null
    )
    private data class WordToken(val chars: List<StyledChar>, val isNewline: Boolean = false)
    private data class LayoutState(
        val cx: Double,
        val cy: Double,
        val lines: Int = 1,
        val lineStart: Boolean = true
    )

    private var metrics: GridMetrics = GridMetrics()
    private var cells: Map<CellCoord, CellData> = emptyMap()
    private var resources: RenderResources? = null

    val columns: Int get() = metrics.columns
    val rows: Int get() = metrics.rows

    fun init() {
        val whiteTex = Pixmap(1, 1, Pixmap.Format.RGBA8888).let { pixmap ->
            pixmap.setColor(Color.WHITE)
            pixmap.fill()
            Texture(pixmap).also { pixmap.dispose() }
        }
        val generator = FreeTypeFontGenerator(Gdx.files.internal(fontAssetPath))
        rebuildGridAndFont(Gdx.graphics.width, Gdx.graphics.height, whiteTex, generator)
    }

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        resources?.let { res ->
            rebuildGridAndFont(width, height, res.whitePixelTexture, res.fontGenerator)
        }
    }

    private fun rebuildGridAndFont(
        width: Int,
        height: Int,
        whiteTex: Texture,
        generator: FreeTypeFontGenerator
    ) {
        val density = Gdx.graphics.density.coerceAtLeast(1f)
        val cols = max(26, floor(width / (24f * density)).toInt())
        val rws = max(26, floor(height / (38f * density)).toInt())
        val fontPixelHeight = minOf(height.toFloat() / rws, (width.toFloat() / cols) / 0.6f)
            .toInt()
            .coerceIn(16, 96)

        resources?.bitmapFont?.dispose()
        val font = generator.generateFont(
            FreeTypeFontGenerator.FreeTypeFontParameter().apply {
                size = fontPixelHeight
                color = Color.WHITE
                mono = false
                characters = SUPPORTED_CHARS
                minFilter = Texture.TextureFilter.Nearest
                magFilter = Texture.TextureFilter.Nearest
            }
        )
        val fullBlock = font.data.getGlyph('\u2588')
        val cellH = (fullBlock?.height ?: fontPixelHeight).toFloat()
        val cellW = (fullBlock?.width ?: (fontPixelHeight * 0.6f).roundToInt()).toFloat()

        resources = RenderResources(whiteTex, generator, font)
        metrics = GridMetrics(
            screenWidthPx = width,
            screenHeightPx = height,
            columns = cols,
            rows = rws,
            cellWidthPx = cellW,
            cellHeightPx = cellH,
            viewportOriginX = floor((width - cols * cellW) / 2f),
            viewportOriginY = floor((height - rws * cellH) / 2f)
        )
    }

    fun clear() {
        cells = emptyMap()
    }

    fun draw(
        x: Double,
        y: Double,
        glyph: Char?,
        fg: String? = "white",
        bg: String? = "black"
    ) {
        cells = cells + (
            CellCoord(x, y) to CellData(
                x = x,
                y = y,
                glyphs = listOfNotNull(glyph?.takeUnless { it == '\u0000' || it == ' ' }),
                fgColors = listOf(SpellcastColors.resolve(fg, Color.WHITE)),
                bgColor = SpellcastColors.resolve(bg, Color.BLACK)
            )
        )
    }

    fun draw(
        x: Int,
        y: Int,
        glyph: Char?,
        fg: String? = "white",
        bg: String? = "black"
    ) = draw(x.toDouble(), y.toDouble(), glyph, fg, bg)

    fun draw(
        x: Double,
        y: Double,
        glyphStr: String,
        fg: String? = "white",
        bg: String? = "black"
    ) = draw(
        x = x,
        y = y,
        glyph = when (glyphStr) {
            "" -> null
            "\uD83D\uDD12" -> GameData.LOCK_GLYPH
            "\uD83D\uDD13" -> GameData.UNLOCK_GLYPH
            else -> glyphStr[0]
        },
        fg = fg,
        bg = bg
    )

    fun draw(
        x: Int,
        y: Int,
        glyphStr: String,
        fg: String? = "white",
        bg: String? = "black"
    ) = draw(x.toDouble(), y.toDouble(), glyphStr, fg, bg)

    /**
     * Port of ROT.Display.drawText(x, y, text, maxWidth) supporting %c{fg} and %b{bg} inline tags
     * and word wrapping. Returns the number of lines drawn.
     */
    fun drawText(
        x: Double,
        y: Double,
        text: String,
        maxWidth: Int = (columns - x.toInt()).coerceAtLeast(1)
    ): Int =
        tokenize(text)
            .fold(LayoutState(cx = x, cy = y)) { state, token ->
                val isSpace = token.chars.singleOrNull()?.ch == ' '
                when {
                    token.isNewline -> LayoutState(
                        cx = x,
                        cy = state.cy + 1.0,
                        lines = state.lines + 1,
                        lineStart = true
                    )
                    isSpace && state.lineStart && state.cx > x -> state
                    else -> {
                        val wrapped = if (!isSpace && state.cx > x && (state.cx - x) + token.chars.size > maxWidth) {
                            LayoutState(
                                cx = x,
                                cy = state.cy + 1.0,
                                lines = state.lines + 1,
                                lineStart = true
                            )
                        } else {
                            state
                        }
                        token.chars.forEachIndexed { idx, sc ->
                            draw(wrapped.cx + idx, wrapped.cy, sc.ch, sc.fg, sc.bg)
                        }
                        wrapped.copy(
                            cx = wrapped.cx + token.chars.size,
                            lineStart = if (isSpace) wrapped.lineStart else false
                        )
                    }
                }
            }
            .lines

    fun drawText(
        x: Int,
        y: Int,
        text: String,
        maxWidth: Int = (columns - x).coerceAtLeast(1)
    ): Int = drawText(x.toDouble(), y.toDouble(), text, maxWidth)

    private fun tokenize(text: String): List<WordToken> =
        TAG_OR_CHAR_REGEX.findAll(text)
            .runningFold(StyleAcc()) { acc, match ->
                when (match.groupValues[1]) {
                    "c" -> acc.copy(fg = match.groupValues[2].ifBlank { "white" }, emitted = null)
                    "b" -> acc.copy(bg = match.groupValues[2].ifBlank { "black" }, emitted = null)
                    else -> acc.copy(emitted = StyledChar(match.groupValues[3][0], acc.fg, acc.bg))
                }
            }
            .mapNotNull { it.emitted }
            .fold(emptyList()) { tokens, sc ->
                when (sc.ch) {
                    '\n' -> tokens + WordToken(emptyList(), isNewline = true)
                    ' ' -> tokens + WordToken(listOf(sc))
                    else -> if (tokens.lastOrNull()?.let { !it.isNewline && it.chars.firstOrNull()?.ch != ' ' } == true) {
                        tokens.dropLast(1) + WordToken(tokens.last().chars + sc)
                    } else {
                        tokens + WordToken(listOf(sc))
                    }
                }
            }

    /**
     * Converts touch screen coordinates (origin top-left) into grid (col, row), or (-1, -1) if outside.
     */
    fun eventToPosition(screenX: Int, screenY: Int): Pair<Int, Int> =
        (floor((screenX - metrics.viewportOriginX) / metrics.cellWidthPx).toInt() to
            floor((screenY - metrics.viewportOriginY) / metrics.cellHeightPx).toInt())
            .takeIf { (col, row) ->
                screenX >= metrics.viewportOriginX &&
                    screenY >= metrics.viewportOriginY &&
                    col in 0 until metrics.columns &&
                    row in 0 until metrics.rows
            } ?: (-1 to -1)

    fun render(batch: SpriteBatch) {
        val res = resources ?: return
        val fullBlock = res.bitmapFont.data.getGlyph('\u2588')
        val baseOffsetX = fullBlock?.xoffset ?: 0
        val baseOffsetY = fullBlock?.yoffset ?: 0
        val topY = metrics.screenHeightPx - metrics.viewportOriginY

        // Pass 1: Draw cell backgrounds
        cells.values
            .filter { it.bgColor.r > 0f || it.bgColor.g > 0f || it.bgColor.b > 0f }
            .forEach { cell ->
                batch.color = cell.bgColor
                batch.draw(
                    res.whitePixelTexture,
                    floor(metrics.viewportOriginX + cell.x.toFloat() * metrics.cellWidthPx),
                    floor(topY - (cell.y.toFloat() + 1f) * metrics.cellHeightPx),
                    metrics.cellWidthPx,
                    metrics.cellHeightPx
                )
            }

        // Pass 2: Draw cell foreground glyphs anchored to the exact 600x1000 Em-box
        cells.values.forEach { cell ->
            val cellLeftX = floor(metrics.viewportOriginX + cell.x.toFloat() * metrics.cellWidthPx)
            val cellBottomY = floor(topY - (cell.y.toFloat() + 1f) * metrics.cellHeightPx)

            cell.glyphs.forEachIndexed { idx, ch ->
                res.bitmapFont.data.getGlyph(ch)
                    ?.takeIf { it.width > 0 && it.height > 0 }
                    ?.let { glyph ->
                        batch.color = cell.fgColors.getOrElse(idx) { Color.WHITE }
                        batch.draw(
                            res.bitmapFont.regions[glyph.page].texture,
                            cellLeftX + (glyph.xoffset - baseOffsetX),
                            cellBottomY + (glyph.yoffset - baseOffsetY),
                            glyph.width.toFloat(),
                            glyph.height.toFloat(),
                            glyph.u,
                            glyph.v,
                            glyph.u2,
                            glyph.v2
                        )
                    }
            }
        }
        batch.color = Color.WHITE
    }

    fun dispose() {
        resources?.let { res ->
            res.bitmapFont.dispose()
            res.fontGenerator.dispose()
            res.whitePixelTexture.dispose()
        }
        resources = null
    }

    companion object {
        private val TAG_OR_CHAR_REGEX = Regex("""%([cb])\{([^}]*)\}|([\s\S])""")

        private val SUPPORTED_CHARS: String = (
            (32..126).map(Int::toChar) +
                listOf('\u00B7', '\u2022') +
                (0x2080..0x2089).map(Int::toChar) +
                (0x2500..0x25FF).map(Int::toChar) +
                listOf(
                    0x2610, 0x2611, 0x2612, 0x2613, 0x261D, 0x261F, 0x2620,
                    0x2660, 0x2661, 0x2662, 0x2663, 0x2664, 0x2665, 0x2666, 0x2667,
                    0x26A1, 0x2713, 0x2717
                ).map(Int::toChar) +
                listOf(GameData.LOCK_GLYPH, GameData.UNLOCK_GLYPH)
        ).joinToString("")
    }
}
