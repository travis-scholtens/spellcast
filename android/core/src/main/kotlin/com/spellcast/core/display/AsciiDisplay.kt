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
            ((x * 100.0).roundToInt()).toLong(),
            ((y * 100.0).roundToInt()).toLong()
        )
    }

    data class CellData(
        val x: Double,
        val y: Double,
        val glyphs: List<Char>,
        val fgColors: List<Color>,
        val bgColor: Color
    )

    var columns: Int = 26
        private set
    var rows: Int = 26
        private set

    var cellWidthPx: Float = 24f
        private set
    var cellHeightPx: Float = 38f
        private set
    var viewportOriginX: Float = 0f
        private set
    var viewportOriginY: Float = 0f
        private set

    private var screenWidthPx: Int = 1024
    private var screenHeightPx: Int = 768

    private val cells = LinkedHashMap<CellCoord, CellData>()
    private var fontGenerator: FreeTypeFontGenerator? = null
    private var bitmapFont: BitmapFont? = null
    private var whitePixelTexture: Texture? = null

    fun init() {
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
            setColor(Color.WHITE)
            fill()
        }
        whitePixelTexture = Texture(pixmap)
        pixmap.dispose()

        fontGenerator = FreeTypeFontGenerator(Gdx.files.internal(fontAssetPath))
        rebuildGridAndFont(Gdx.graphics.width, Gdx.graphics.height)
    }

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        rebuildGridAndFont(width, height)
    }

    private fun rebuildGridAndFont(width: Int, height: Int) {
        screenWidthPx = width
        screenHeightPx = height

        val density = Gdx.graphics.density.coerceAtLeast(1f)
        val targetCellW = 24f * density
        val targetCellH = 38f * density

        columns = max(26, floor(width / targetCellW).toInt())
        rows = max(26, floor(height / targetCellH).toInt())

        // Compute integer pixel cell height so block glyphs tile seamlessly without subpixel gaps
        val maxCellH = height.toFloat() / rows
        val maxCellW = width.toFloat() / columns
        val rawCellH = minOf(maxCellH, maxCellW / 0.6f)
        val fontPixelHeight = rawCellH.toInt().coerceIn(16, 96)

        bitmapFont?.dispose()
        val parameter = FreeTypeFontGenerator.FreeTypeFontParameter().apply {
            size = fontPixelHeight
            color = Color.WHITE
            mono = false
            characters = buildSupportedCharacterString()
            minFilter = Texture.TextureFilter.Nearest
            magFilter = Texture.TextureFilter.Nearest
        }
        val font = fontGenerator?.generateFont(parameter)
        bitmapFont = font

        val fullBlock = font?.data?.getGlyph('\u2588')
        cellHeightPx = (fullBlock?.height ?: fontPixelHeight).toFloat()
        cellWidthPx = (fullBlock?.width ?: (fontPixelHeight * 0.6f).roundToInt()).toFloat()

        val totalGridW = columns * cellWidthPx
        val totalGridH = rows * cellHeightPx
        viewportOriginX = floor((width - totalGridW) / 2f)
        viewportOriginY = floor((height - totalGridH) / 2f)
    }

    private fun buildSupportedCharacterString(): String {
        val sb = StringBuilder(512)
        for (c in 32..126) sb.append(c.toChar())
        sb.append('\u00B7')
        sb.append('\u2022')
        for (c in 0x2080..0x2089) sb.append(c.toChar())
        for (c in 0x2500..0x25FF) sb.append(c.toChar())
        for (c in listOf(
            0x2610, 0x2611, 0x2612, 0x2613, 0x261D, 0x261F, 0x2620,
            0x2660, 0x2661, 0x2662, 0x2663, 0x2664, 0x2665, 0x2666, 0x2667,
            0x26A1, 0x2713, 0x2717
        )) {
            sb.append(c.toChar())
        }
        sb.append(GameData.LOCK_GLYPH)
        sb.append(GameData.UNLOCK_GLYPH)
        return sb.toString()
    }

    fun clear() {
        cells.clear()
    }

    fun draw(
        x: Double,
        y: Double,
        glyph: Char?,
        fg: String? = "white",
        bg: String? = "black"
    ) {
        val glyphList = if (glyph == null || glyph == '\u0000' || glyph == ' ') {
            emptyList()
        } else {
            listOf(glyph)
        }
        val fgColor = SpellcastColors.resolve(fg, Color.WHITE)
        val bgColor = SpellcastColors.resolve(bg, Color.BLACK)
        cells[CellCoord(x, y)] = CellData(
            x = x,
            y = y,
            glyphs = glyphList,
            fgColors = listOf(fgColor),
            bgColor = bgColor
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
    ) {
        val ch = when {
            glyphStr.isEmpty() -> null
            glyphStr == "\uD83D\uDD12" -> GameData.LOCK_GLYPH
            glyphStr == "\uD83D\uDD13" -> GameData.UNLOCK_GLYPH
            else -> glyphStr[0]
        }
        draw(x, y, ch, fg, bg)
    }

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
    ): Int {
        data class StyledChar(val ch: Char, val fg: String, val bg: String)
        data class WordToken(val chars: List<StyledChar>, val isNewline: Boolean = false)

        var currentFg = "white"
        var currentBg = "black"
        val tokens = mutableListOf<WordToken>()
        var currentWord = mutableListOf<StyledChar>()

        var i = 0
        while (i < text.length) {
            if (text[i] == '%' && i + 2 < text.length && (text[i + 1] == 'c' || text[i + 1] == 'b') && text[i + 2] == '{') {
                val closeIdx = text.indexOf('}', i + 3)
                if (closeIdx != -1) {
                    val tagType = text[i + 1]
                    val value = text.substring(i + 3, closeIdx)
                    if (tagType == 'c') {
                        currentFg = value.ifBlank { "white" }
                    } else {
                        currentBg = value.ifBlank { "black" }
                    }
                    i = closeIdx + 1
                    continue
                }
            }
            val ch = text[i]
            if (ch == '\n') {
                if (currentWord.isNotEmpty()) {
                    tokens.add(WordToken(currentWord))
                    currentWord = mutableListOf()
                }
                tokens.add(WordToken(emptyList(), isNewline = true))
            } else if (ch == ' ') {
                if (currentWord.isNotEmpty()) {
                    tokens.add(WordToken(currentWord))
                    currentWord = mutableListOf()
                }
                tokens.add(WordToken(listOf(StyledChar(' ', currentFg, currentBg))))
            } else {
                currentWord.add(StyledChar(ch, currentFg, currentBg))
            }
            i++
        }
        if (currentWord.isNotEmpty()) {
            tokens.add(WordToken(currentWord))
        }

        var cx = x
        var cy = y
        var lines = 1
        var lineStart = true

        for (token in tokens) {
            if (token.isNewline) {
                cx = x
                cy += 1.0
                lines++
                lineStart = true
                continue
            }
            val isSpace = token.chars.size == 1 && token.chars[0].ch == ' '
            if (isSpace && lineStart && cx > x) {
                continue
            }
            if (!isSpace && cx > x && (cx - x) + token.chars.size > maxWidth) {
                cx = x
                cy += 1.0
                lines++
                lineStart = true
            }
            for (sc in token.chars) {
                draw(cx, cy, sc.ch, sc.fg, sc.bg)
                cx += 1.0
            }
            if (!isSpace) {
                lineStart = false
            }
        }
        return lines
    }

    fun drawText(x: Int, y: Int, text: String, maxWidth: Int = (columns - x).coerceAtLeast(1)): Int =
        drawText(x.toDouble(), y.toDouble(), text, maxWidth)

    /**
     * Converts touch screen coordinates (origin top-left) into grid (col, row), or (-1, -1) if outside.
     */
    fun eventToPosition(screenX: Int, screenY: Int): Pair<Int, Int> {
        val localX = screenX - viewportOriginX
        val localY = screenY - viewportOriginY
        if (localX < 0f || localY < 0f) return -1 to -1
        val col = floor(localX / cellWidthPx).toInt()
        val row = floor(localY / cellHeightPx).toInt()
        if (col !in 0 until columns || row !in 0 until rows) return -1 to -1
        return col to row
    }

    fun render(batch: SpriteBatch) {
        val whiteTex = whitePixelTexture ?: return
        val font = bitmapFont ?: return
        val fullBlock = font.data.getGlyph('\u2588')
        val baseOffsetX = fullBlock?.xoffset ?: 0
        val baseOffsetY = fullBlock?.yoffset ?: 0

        // Convert top-down grid coordinates (row 0 = top) to LibGDX bottom-up Y coordinates
        val topY = screenHeightPx - viewportOriginY

        // Pass 1: Draw cell backgrounds
        for (cell in cells.values) {
            if (cell.bgColor.r > 0f || cell.bgColor.g > 0f || cell.bgColor.b > 0f) {
                val px = floor(viewportOriginX + (cell.x.toFloat() * cellWidthPx))
                val py = floor(topY - ((cell.y.toFloat() + 1f) * cellHeightPx))
                batch.color = cell.bgColor
                batch.draw(whiteTex, px, py, cellWidthPx, cellHeightPx)
            }
        }

        // Pass 2: Draw cell foreground glyphs anchored to the exact 600x1000 Em-box
        for (cell in cells.values) {
            val cellLeftX = floor(viewportOriginX + (cell.x.toFloat() * cellWidthPx))
            val cellBottomY = floor(topY - ((cell.y.toFloat() + 1f) * cellHeightPx))

            for (idx in cell.glyphs.indices) {
                val ch = cell.glyphs[idx]
                val glyph = font.data.getGlyph(ch) ?: continue
                if (glyph.width <= 0 || glyph.height <= 0) continue

                val fg = cell.fgColors.getOrElse(idx) { Color.WHITE }
                batch.color = fg
                val tex = font.regions[glyph.page].texture
                val gx = cellLeftX + (glyph.xoffset - baseOffsetX)
                val gy = cellBottomY + (glyph.yoffset - baseOffsetY)
                batch.draw(
                    tex,
                    gx,
                    gy,
                    glyph.width.toFloat(),
                    glyph.height.toFloat(),
                    glyph.u,
                    glyph.v,
                    glyph.u2,
                    glyph.v2
                )
            }
        }
        batch.color = Color.WHITE
    }

    fun dispose() {
        bitmapFont?.dispose()
        fontGenerator?.dispose()
        whitePixelTexture?.dispose()
    }
}
