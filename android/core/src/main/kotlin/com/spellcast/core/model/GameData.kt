package com.spellcast.core.model

data class IconDef(val glyph: Char, val color: String)

data class Shrine(
    val multiplier: Int,
    val isWholeWord: Boolean,
    val frequency: Int,
    val color: String,
    val name: String
)

data class Achievement(
    val key: String,
    val retired: Boolean,
    val name: String,
    val todoMsg: String,
    val didMsg: String,
    val doneMsg: String? = null,
    val deps: List<String> = emptyList()
)

data class FoeKind(
    val glyph: Char,
    val name: String,
    val color: String,
    val health: Int?,
    val speed: Int?,
    val value: Int,
    val frequency: Int
)

data class ExperienceLevel(
    val expThreshold: Int,
    val meanDamage: Double,
    val speed: Int
)

object GameData {
    val healthIcon = IconDef('\u2665', "lightpink")
    val levelIcon = IconDef('\u26a1', "lightgreen")

    // BMP PUA aliases generated in SpellyFreeMono.ttf for U+1F512 / U+1F513
    const val LOCK_GLYPH: Char = '\uE092'
    const val UNLOCK_GLYPH: Char = '\uE093'

    val LOGO: List<String> = listOf(
        "  ▄▄  ▖       ▗▄▘▄▞",
        "▗▘ ▀▀▀        ▝▛ ▜▘",
        "▞      ▄ ▄  ▖▖ ▌ ▐ ▗▖▗▖",
        "▌▐ ▟▛▜ ▐▘▝▌▐▘▐▖▌ ▐  ▛ ▜",
        "▌ ▀▘  ▌▐  ▌▐▖▘ ▌ ▐  ▌ ▐▖",
        "▐▄   ▗▌▐▖ ▌▐▌  ▙ ▐▖ ▌ ▐▘",
        " ▀███▀ ▜▀▞  ▜▞▝▜▘▀▛▝▀ ▐",
        "       ▐▌             ▌",
        "        ▘"
    )

    val shrines: List<Shrine> = listOf(
        Shrine(2, false, 6, "lightblue", "Biglyphica"),
        Shrine(3, false, 3, "blue", "Trirunius"),
        Shrine(2, true, 4, "pink", "Duverbia"),
        Shrine(3, true, 2, "red", "Troilogos")
    )

    val achievements: List<Achievement> = listOf(
        Achievement(
            "use_every_foe", false, "Trophy Hunter",
            "Use each type of monster in at least one spell.",
            "You have cast a spell with the corpse of each type of monster!",
            "You cast a spell with every type of monster."
        ),
        Achievement(
            "xyzzy", false, "Magic Word",
            "Use the canonical magic word.",
            "You cast XYZZY. Nothing happens.",
            "You cast XYZZY. Nothing happened."
        ),
        Achievement(
            "every_shrine", false, "Zealot",
            "Cast a spell at all fifteen shrines.",
            "You have shown your devotion to the gods by casting a spell at every shrine.",
            "You showed your devotion to the gods by casting a spell at every shrine."
        ),
        Achievement(
            "earliest_bingo", false, "Prodigy",
            "Cast a seven-corpse spell before descending the third stair.",
            "You have cast a very powerful spell at a shallow depth!",
            "You cast a very powerful spell at a shallow depth."
        ),
        Achievement(
            "victory", false, "Victory",
            "Cast YENDOR.",
            "You ascended from the depths and are a true wizard!"
        ),
        Achievement(
            "victory_no_shrines", false, "Atheist",
            "Cast YENDOR without attempting to cast a spell at any shrines.",
            "You ascended while eschewing religious pieties!",
            null, listOf("victory")
        ),
        Achievement(
            "victory_no_fizzles", false, "Perfectionist",
            "Cast YENDOR without ever attempting a nonexistant spell.",
            "You ascended without ever attempting a nonexistant spell!",
            null, listOf("victory")
        ),
        Achievement(
            "victory_use_every_corpse", false, "Productivity",
            "Cast YENDOR without letting any corpses go to waste.",
            "You ascended without wasting a corpse!",
            null, listOf("victory_no_fizzles")
        ),
        Achievement(
            "victory_shallowest", false, "Eager Beaver",
            "Cast YENDOR before descending to depth 26.",
            "You ascended before even risking a zombie encounter!",
            null, listOf("victory")
        ),
        Achievement(
            "victory_no_bingos", false, "Small Ball",
            "Cast YENDOR without casting any seven-corpse spells.",
            "You ascended without dabbling in more powerful magicks.",
            null, listOf("victory")
        ),
        Achievement(
            "victory_kill_all_seen", false, "Take No Prisoners",
            "Cast YENDOR having slain every monster you've seen.",
            "You ascended after killing every monster you saw!",
            null, listOf("victory")
        ),
        Achievement(
            "victory_clear_every_depth", false, "Cleaner",
            "Cast YENDOR having slain every monster at every visited depth.",
            "You ascended after hunting down every monster!",
            null, listOf("victory_kill_all_seen")
        ),
        Achievement(
            "victory_at_low_level", true, "Extreme Precocity",
            "Cast YENDOR while having no more than 100 experience points.",
            "You ascended with an ultra low experience level!",
            null, listOf("victory")
        ),
        Achievement(
            "victory_at_low_level_2", false, "Precocity",
            "Cast YENDOR while having no more than 350 experience points.",
            "You ascended with a very low experience level!",
            null, listOf("victory")
        ),
        Achievement(
            "victory_no_repeats", false, "Variety",
            "Cast YENDOR having never cast the same spell twice.",
            "You ascended without repeating a spell!",
            null, listOf("victory")
        )
    )

    val foeKinds: List<FoeKind> = listOf(
        FoeKind('a', "ant", "darkred", 5, 100, 1, 9),
        FoeKind('b', "bogle", "brown", 7, 104, 3, 2),
        FoeKind('c', "crawler", "yellow", 9, 108, 3, 2),
        FoeKind('d', "demon", "crimson", 11, 112, 2, 4),
        FoeKind('e', "echidna", "lawngreen", 11, 116, 1, 12),
        FoeKind('f', "faun", "cadetblue", 24, 120, 4, 2),
        FoeKind('g', "goblin", "olive", 23, 124, 2, 3),
        FoeKind('h', "harpy", "orange", 34, 128, 4, 2),
        FoeKind('i', "imp", "blue", 32, 132, 1, 9),
        FoeKind('j', "jird", "darkgoldenrod", 42, 136, 8, 1),
        FoeKind('k', "kraken", "darkseagreen", 38, 140, 5, 1),
        FoeKind('l', "lich", "magenta", 28, 144, 1, 4),
        FoeKind('m', "medusa", "darkorange", 48, 148, 3, 2),
        FoeKind('n', "nymph", "orchid", 45, 152, 1, 6),
        FoeKind('o', "orc", "indianred", 40, 156, 1, 8),
        FoeKind('p', "python", "saddlebrown", 50, 160, 3, 2),
        FoeKind('q', "quokka", "lightcoral", 56, 164, 10, 1),
        FoeKind('r', "redcap", "red", 48, 168, 1, 6),
        FoeKind('s', "sylph", "cyan", 50, 172, 1, 4),
        FoeKind('t', "troll", "orangered", 57, 176, 1, 6),
        FoeKind('u', "unicorn", "white", 62, 180, 1, 4),
        FoeKind('v', "valkyr", "hotpink", 69, 184, 4, 2),
        FoeKind('w', "wendigo", "springgreen", 75, 188, 4, 2),
        FoeKind('x', "xenopus", "lemonchiffon", 78, 192, 8, 1),
        FoeKind('y', "yeti", "peru", 90, 196, 4, 2),
        FoeKind('z', "zombie", "darkgray", 105, 200, 10, 1)
    )

    val shapeshifter = FoeKind('.', "metamorph", "gray", null, null, 0, 2)

    val glyphKind: Map<Char, FoeKind> =
        (foeKinds + shapeshifter).associateBy { it.glyph }

    val values: Map<Char, Int> =
        (foeKinds + shapeshifter).associate { it.glyph to it.value }

    val experienceLevels: List<ExperienceLevel> = listOf(
        ExperienceLevel(expThreshold = 0, meanDamage = 2.0, speed = 75),
        ExperienceLevel(expThreshold = 2, meanDamage = 2.0, speed = 75),
        ExperienceLevel(expThreshold = 7, meanDamage = 4.5, speed = 83),
        ExperienceLevel(expThreshold = 17, meanDamage = 4.5, speed = 83),
        ExperienceLevel(expThreshold = 34, meanDamage = 7.5, speed = 91),
        ExperienceLevel(expThreshold = 54, meanDamage = 7.5, speed = 91),
        ExperienceLevel(expThreshold = 76, meanDamage = 11.0, speed = 99),
        ExperienceLevel(expThreshold = 106, meanDamage = 11.0, speed = 99),
        ExperienceLevel(expThreshold = 139, meanDamage = 14.3, speed = 107),
        ExperienceLevel(expThreshold = 169, meanDamage = 17.0, speed = 107),
        ExperienceLevel(expThreshold = 202, meanDamage = 17.0, speed = 115),
        ExperienceLevel(expThreshold = 236, meanDamage = 17.1, speed = 115),
        ExperienceLevel(expThreshold = 270, meanDamage = 17.1, speed = 123),
        ExperienceLevel(expThreshold = 305, meanDamage = 17.9, speed = 123),
        ExperienceLevel(expThreshold = 339, meanDamage = 17.9, speed = 131),
        ExperienceLevel(expThreshold = 372, meanDamage = 21.6, speed = 131),
        ExperienceLevel(expThreshold = 407, meanDamage = 21.6, speed = 139),
        ExperienceLevel(expThreshold = 444, meanDamage = 22.4, speed = 139),
        ExperienceLevel(expThreshold = 482, meanDamage = 22.4, speed = 147),
        ExperienceLevel(expThreshold = 519, meanDamage = 25.2, speed = 147),
        ExperienceLevel(expThreshold = 556, meanDamage = 25.2, speed = 155),
        ExperienceLevel(expThreshold = 593, meanDamage = 28.8, speed = 155),
        ExperienceLevel(expThreshold = 633, meanDamage = 28.8, speed = 163),
        ExperienceLevel(expThreshold = 674, meanDamage = 30.9, speed = 163),
        ExperienceLevel(expThreshold = 718, meanDamage = 30.9, speed = 171),
        ExperienceLevel(expThreshold = 764, meanDamage = 33.0, speed = 171),
        ExperienceLevel(expThreshold = 813, meanDamage = 33.0, speed = 179),
        ExperienceLevel(expThreshold = 863, meanDamage = 34.2, speed = 179),
        ExperienceLevel(expThreshold = 917, meanDamage = 34.2, speed = 187),
        ExperienceLevel(expThreshold = 974, meanDamage = 38.5, speed = 187),
        ExperienceLevel(expThreshold = 1036, meanDamage = 38.5, speed = 195)
    )

    fun levelIndexFor(experience: Int): Int =
        (1 until experienceLevels.size)
            .firstOrNull { experience < experienceLevels[it].expThreshold }
            ?: experienceLevels.lastIndex
}
