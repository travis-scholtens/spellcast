package com.spellcast.core.rot

/**
 * 1:1 port of ROT.FOV.PreciseShadowcasting in functional style.
 */
class PreciseShadowcasting(
    private val topology: Int = 8,
    private val lightPasses: (x: Int, y: Int) -> Boolean
) {

    private data class Arc(val num: Int, val den: Int)

    fun compute(
        x: Int,
        y: Int,
        radius: Int,
        callback: (x: Int, y: Int, r: Int, visibility: Double) -> Unit
    ) {
        callback(x, y, 0, 1.0)
        if (!lightPasses(x, y)) return

        (1..radius).fold(emptyList<Arc>() to false) { (shadows, cutoff), r ->
            if (cutoff) {
                shadows to true
            } else {
                val neighbors = getCircle(x, y, r)
                val neighborCount = neighbors.size
                neighbors.withIndex().fold(shadows to false) { (ringShadows, ringCutoff), (i, cell) ->
                    if (ringCutoff) {
                        ringShadows to true
                    } else {
                        val a1 = Arc(if (i != 0) 2 * i - 1 else 2 * neighborCount - 1, 2 * neighborCount)
                        val a2 = Arc(2 * i + 1, 2 * neighborCount)
                        val blocks = !lightPasses(cell.x, cell.y)
                        val (visibility, nextShadows) = checkVisibility(a1, a2, blocks, ringShadows)
                        if (visibility > 0.0) {
                            callback(cell.x, cell.y, r, visibility)
                        }
                        val isFullShadow = nextShadows.size == 2 &&
                            nextShadows[0].num == 0 &&
                            nextShadows[1].num == nextShadows[1].den
                        nextShadows to isFullShadow
                    }
                }
            }
        }
    }

    private fun getCircle(cx: Int, cy: Int, r: Int): List<GridVec> {
        val (dirs, countFactor) = when (topology) {
            4 -> listOf(
                RotDirs.DIRS_8[7],
                RotDirs.DIRS_8[1],
                RotDirs.DIRS_8[3],
                RotDirs.DIRS_8[5]
            ) to 1
            6 -> RotDirs.DIRS_6 to 1
            8 -> RotDirs.DIRS_4 to 2
            else -> error("Unsupported topology: $topology")
        }
        val start = if (topology == 4) GridVec(cx, cy + r) else GridVec(cx - r, cy + r)
        return dirs
            .flatMap { dir -> List(r * countFactor) { dir } }
            .runningFold(start) { pos, dir -> pos + dir }
            .dropLast(1)
    }

    private fun checkVisibility(
        a1: Arc,
        a2: Arc,
        blocks: Boolean,
        shadows: List<Arc>
    ): Pair<Double, List<Arc>> {
        if (a1.num > a2.num) {
            val (v1, s1) = checkVisibility(a1, Arc(a1.den, a1.den), blocks, shadows)
            val (v2, s2) = checkVisibility(Arc(0, 1), a2, blocks, s1)
            return ((v1 + v2) / 2.0) to s2
        }

        val index1 = shadows.indexOfFirst { old -> old.num * a1.den - a1.num * old.den >= 0 }
            .let { if (it == -1) shadows.size else it }
        val edge1 = index1 < shadows.size &&
            (shadows[index1].num * a1.den - a1.num * shadows[index1].den == 0) &&
            (index1 % 2 == 0)

        val index2 = shadows.indexOfLast { old -> a2.num * old.den - old.num * a2.den >= 0 }
        val edge2 = index2 >= 0 &&
            (a2.num * shadows[index2].den - shadows[index2].num * a2.den == 0) &&
            (index2 % 2 != 0)

        val visible = !(
            (index1 == index2 && (edge1 || edge2)) ||
                (edge1 && edge2 && index1 + 1 == index2 && index2 % 2 != 0) ||
                (index1 > index2 && index1 % 2 != 0)
            )
        if (!visible) return 0.0 to shadows

        val remove = index2 - index1 + 1
        return if (remove % 2 != 0) {
            if (index1 % 2 != 0) {
                val p = shadows[index1]
                val visibleLength = (a2.num * p.den - p.num * a2.den).toDouble() / (p.den.toDouble() * a2.den)
                val nextShadows = if (blocks) splice(shadows, index1, remove, listOf(a2)) else shadows
                val arcLength = (a2.num * a1.den - a1.num * a2.den).toDouble() / (a1.den.toDouble() * a2.den)
                (visibleLength / arcLength) to nextShadows
            } else {
                val p = shadows[index2]
                val visibleLength = (p.num * a1.den - a1.num * p.den).toDouble() / (a1.den.toDouble() * p.den)
                val nextShadows = if (blocks) splice(shadows, index1, remove, listOf(a1)) else shadows
                val arcLength = (a2.num * a1.den - a1.num * a2.den).toDouble() / (a1.den.toDouble() * a2.den)
                (visibleLength / arcLength) to nextShadows
            }
        } else {
            if (index1 % 2 != 0) {
                val p1 = shadows[index1]
                val p2 = shadows[index2]
                val visibleLength = (p2.num * p1.den - p1.num * p2.den).toDouble() / (p1.den.toDouble() * p2.den)
                val nextShadows = if (blocks) splice(shadows, index1, remove, emptyList()) else shadows
                val arcLength = (a2.num * a1.den - a1.num * a2.den).toDouble() / (a1.den.toDouble() * a2.den)
                (visibleLength / arcLength) to nextShadows
            } else {
                val nextShadows = if (blocks) splice(shadows, index1, remove, listOf(a1, a2)) else shadows
                1.0 to nextShadows
            }
        }
    }

    private fun <T> splice(
        list: List<T>,
        start: Int,
        deleteCount: Int,
        replacements: List<T>
    ): List<T> = list.take(start) + replacements + list.drop((start + deleteCount).coerceAtLeast(start))
}
