package io.github.lamemarine.utter

/**
 * Local, rule-based text tidy-up for dictation output. No network, no model.
 */
object TextCleaner {

    const val DEFAULT_FILLERS = "um, umm, uh, uhh, uhm, er, erm, hmm, mhm"

    data class Options(
        val fillers: List<String> = emptyList(),   // empty = don't remove
        val fixCaps: Boolean = true,
        val spokenPunct: Boolean = false,
    )

    fun parseFillers(csv: String): List<String> =
        csv.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    fun apply(input: String, o: Options): String {
        var t = input
        t = stripArtifacts(t)
        if (o.spokenPunct) t = spokenPunctuation(t)
        if (o.fillers.isNotEmpty()) t = removeFillers(t, o.fillers)
        t = tidy(t).trim()
        if (o.fixCaps) t = fixCapitalisation(t)
        return t.trim()
    }

    // Whisper-style non-speech tags such as [BLANK_AUDIO] or [ Music ]
    private val artifactRe = Regex(
        "\\[\\s*(?:[A-Z_]{3,}[A-Z_ ]*|(?i:music|silence|applause|laughter|noise|inaudible))\\s*\\]"
    )

    internal fun stripArtifacts(s: String) = artifactRe.replace(s, " ")

    internal fun removeFillers(s: String, fillers: List<String>): String {
        val alt = fillers.joinToString("|") { Regex.escape(it) }
        val word = "(?<![\\w'’])(?:$alt)(?![\\w'’])"
        var t = s
        // "went, um, to" -> "went, to"
        t = Regex(",\\s*$word\\s*,", RegexOption.IGNORE_CASE).replace(t, ",")
        // filler opening a sentence: "Um, so" -> "so"
        t = Regex("(^|[.!?]\\s+|\\n)$word,?\\s*", RegexOption.IGNORE_CASE).replace(t) { it.groupValues[1] }
        // filler mid-sentence
        t = Regex("\\s*$word,?", RegexOption.IGNORE_CASE).replace(t, "")
        return t
    }

    private val spoken = listOf(
        "new paragraph" to "\n\n",
        "new line" to "\n",
        "newline" to "\n",
        "full stop" to ". ",
        "period" to ". ",
        "comma" to ", ",
        "question mark" to "? ",
        "exclamation mark" to "! ",
        "exclamation point" to "! ",
        "semicolon" to "; ",
        "colon" to ": ",
    )

    internal fun spokenPunctuation(s: String): String {
        var t = s
        for ((phrase, sym) in spoken) {
            t = Regex("[ \\t]*(?<![\\w'’])${Regex.escape(phrase)}(?![\\w'’])[.,]?[ \\t]*", RegexOption.IGNORE_CASE)
                .replace(t, Regex.escapeReplacement(sym))
        }
        return t
    }

    internal fun tidy(s: String): String {
        var t = s
        t = t.replace(Regex("[ \\t]+"), " ")
        t = t.replace(Regex(" +([,.!?;:])"), "$1")
        t = t.replace(Regex("(,\\s*)+,"), ",")
        t = t.replace(Regex(",\\s*([.!?])"), "$1")
        t = t.replace(Regex(" *\\n *"), "\n")
        t = t.replace(Regex("^[,;:]\\s*"), "")
        return t
    }

    // Not a sentence end when the full stop belongs to a common abbreviation
    private val abbrev = listOf("i\\.e", "e\\.g", "etc", "vs", "[Mm]r", "[Mm]rs", "[Mm]s", "[Dd]r", "a\\.m", "p\\.m")
        .joinToString("") { "(?<!\\b$it)" }
    private val sentenceStart = Regex("(^|$abbrev[.!?]\\s+|\\n)(\\p{Ll})")
    private val loneI = Regex("(?<![\\w.'’])i(?![\\w.])")

    internal fun fixCapitalisation(s: String): String {
        var t = sentenceStart.replace(s) { it.groupValues[1] + it.groupValues[2].uppercase() }
        t = loneI.replace(t, "I")
        return t
    }
}
