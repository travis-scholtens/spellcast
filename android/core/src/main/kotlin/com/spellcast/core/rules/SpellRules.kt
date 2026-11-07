package com.spellcast.core.rules

import com.spellcast.core.model.CastSpellRecord
import com.spellcast.core.model.GameData

data class HighScoreEntry(
    val victoryColor: String?,
    val depth: Int,
    val totalScore: Int,
    val bestWord: String?,
    val balanceVersion: Int
)

/**
 * Pure functional port of Spellcast's word matching, blank substitution, spell scoring,
 * inline formatting, message wrapping, and high-score sorting rules from `app.js`.
 */
object SpellRules {

    const val GAME_VERSION = 9
    const val BALANCE_VERSION = 8
    const val RACK_SIZE = 6

    private val PAREN_REGEX = Regex("""[()]""")
    private val COLOR_TAG_REGEX = Regex("""%.?\{.*?\}""")

    fun deparenthesize(word: String): String =
        word.replace(PAREN_REGEX, "")

    fun surroundText(text: String, color: String): String =
        "%c{$color}$text%c{}"

    fun findWordWithBlank(
        word: String,
        words: Set<String>,
        history: List<String> = emptyList()
    ): String? {
        val pattern = Regex("^$word$")
        val matches = words
            .asSequence()
            .filter { it.length == word.length && pattern.matches(it) }
            .map { entry ->
                word.indices.joinToString("") { i ->
                    if (word[i] == '.') "(${entry[i]})" else "${entry[i]}"
                }
            }
            .sorted()
            .toList()

        if (matches.isEmpty()) return null
        if (history.isEmpty()) return matches.first()

        return matches.firstOrNull { deparenthesize(it) !in history }
            ?: history.firstNotNullOfOrNull { used ->
                matches.firstOrNull { deparenthesize(it) == used }
            }
    }

    fun checkWord(
        word: String,
        words: Set<String>,
        history: List<String> = emptyList()
    ): String? =
        if ('.' in word) {
            findWordWithBlank(word, words, history)
        } else if (word == "yendor" || word in words) {
            word
        } else {
            null
        }

    fun spellValue(spell: String, shrineType: Int?): Pair<Int, Boolean> {
        val shrine = shrineType?.let { GameData.shrines.getOrNull(it) }
        val bestLetterValue = spell.maxOfOrNull { GameData.values[it] ?: 0 } ?: 0
        val bestLetterIndex = spell.indexOfFirst { (GameData.values[it] ?: 0) == bestLetterValue }
        val baseScore = spell.indices.sumOf { i ->
            (GameData.values[spell[i]] ?: 0) *
                if (shrine != null && (shrine.isWholeWord || i == bestLetterIndex)) shrine.multiplier else 1
        }
        val isBingo = spell.length >= 7
        return (baseScore + if (isBingo) 50 else 0) to isBingo
    }

    fun spellValues(spellsCast: List<CastSpellRecord>): List<Int> =
        spellsCast.map { spellValue(it.tiles, it.shrineType).first }

    fun formatCastSpell(text: String, shrineType: Int?): String {
        val shrine = shrineType?.let { GameData.shrines.getOrNull(it) }
        val bestLetterValue = text.maxOfOrNull { GameData.values[it] ?: 0 } ?: 0
        val bestLetterIndex = if (bestLetterValue > 0) {
            text.indexOfFirst { (GameData.values[it] ?: 0) == bestLetterValue }
        } else {
            1.coerceAtMost(text.lastIndex.coerceAtLeast(0))
        }
        val upper = text.uppercase()
        return when {
            shrine == null -> upper
            shrine.isWholeWord -> surroundText(upper, shrine.color)
            else -> upper.substring(0, bestLetterIndex) +
                surroundText(upper[bestLetterIndex].toString(), shrine.color) +
                upper.substring(bestLetterIndex + 1)
        }
    }

    fun formatSpell(castSpell: CastSpellRecord, value: Int): String =
        "${castSpell.formatted} ($value)"

    fun formatAchievementMessage(key: String, message: String): String {
        val revealed = GameData.achievements
            .filter { !it.retired && key in it.deps }
            .map { it.name }
        val base = "Achievement unlocked: $message"
        return when (revealed.size) {
            0 -> base
            1 -> "$base\n\nNew achievement revealed: ${revealed[0]}"
            else -> "$base\n\n${revealed.size} new achievements revealed"
        }
    }

    fun renderedLength(s: String): Int =
        s.replace(COLOR_TAG_REGEX, "").length

    fun wrapMessages(messages: List<String>, width: Int): List<String> =
        messages.flatMap { msg ->
            wrapSingleMessage(listOf(">") + msg.split(" "), width, emptyList())
        }

    private tailrec fun wrapSingleMessage(
        tokens: List<String>,
        width: Int,
        acc: List<String>
    ): List<String> {
        if (tokens.isEmpty()) return acc
        val (line, remaining) = consumeLine(tokens.first(), tokens.drop(1), width)
        val nextTokens = if (remaining.isNotEmpty()) listOf(" ") + remaining else emptyList()
        return wrapSingleMessage(nextTokens, width, acc + line)
    }

    private tailrec fun consumeLine(
        current: String,
        remaining: List<String>,
        width: Int
    ): Pair<String, List<String>> =
        if (remaining.isNotEmpty() && renderedLength(current) + renderedLength(remaining.first()) + 1 < width) {
            consumeLine("$current ${remaining.first()}", remaining.drop(1), width)
        } else {
            current to remaining
        }

    fun sortScores(
        scores: List<HighScoreEntry>,
        currentBalanceVersion: Int = BALANCE_VERSION
    ): List<HighScoreEntry> =
        scores.sortedWith { a, b ->
            when {
                a.balanceVersion == currentBalanceVersion && b.balanceVersion != currentBalanceVersion -> -1
                b.balanceVersion == currentBalanceVersion && a.balanceVersion != currentBalanceVersion -> 1
                a.victoryColor != null && b.victoryColor == null -> -1
                b.victoryColor != null && a.victoryColor == null -> 1
                else -> b.totalScore.compareTo(a.totalScore)
            }
        }
}
