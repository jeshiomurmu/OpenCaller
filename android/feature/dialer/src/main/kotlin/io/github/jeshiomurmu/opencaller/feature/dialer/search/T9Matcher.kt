package io.github.jeshiomurmu.opencaller.feature.dialer.search

import java.text.Normalizer

/** How a suggestion matched what was typed; lower ranks first. */
enum class T9MatchKind {
    /** The typed digits spell the start of the name ("726" → "Ram Kumar"). */
    NAME_START,

    /** They spell the start of a later word ("586" → "Ram Kumar"), possibly running on into the next ones. */
    WORD_START,

    /** They are the initials ("75" → "Ram Kumar"). */
    INITIALS,

    /** The number starts with them. */
    NUMBER_START,

    /** The number contains them. */
    NUMBER_CONTAINS,
}

/**
 * Something the dial pad can suggest: a contact's number or a number from the call log.
 *
 * @property name the contact's name or the name Android cached in the call log, `null` for an unknown number.
 * @property dialString what is dialled when the suggestion is picked.
 */
data class T9Candidate(
    val name: String?,
    val dialString: String,
)

/** A [T9Candidate] that matched, with how it matched. */
data class T9Match(
    val candidate: T9Candidate,
    val kind: T9MatchKind,
)

/**
 * T9 search for the dial pad (docs/prd.md F-CM-01, TC-DIAL-002): the typed digits are matched against names spelled
 * on the phone keypad (ITU E.161: 2 = ABC … 9 = WXYZ) and against the digits of numbers. Accents are ignored
 * ("José" is 5673). Pure and synchronous; it runs over the contacts and call log already in memory.
 */
class T9Matcher {
    /** How [candidate] matches [typed] (the dial pad input), or `null`. Only the digits of [typed] count. */
    fun match(
        typed: String,
        candidate: T9Candidate,
    ): T9MatchKind? {
        val digits = typed.filter(::isDigit)
        if (digits.isEmpty()) return null
        return candidate.name?.let { nameMatch(digits, it) } ?: numberMatch(digits, candidate.dialString)
    }

    /**
     * The candidates matching [typed], best first: by [T9MatchKind], then in the order given (callers pass recent
     * calls first), at most [limit], one per number (its best match).
     */
    fun search(
        typed: String,
        candidates: List<T9Candidate>,
        limit: Int = DEFAULT_LIMIT,
    ): List<T9Match> =
        candidates
            .asSequence()
            .mapNotNull { candidate -> match(typed, candidate)?.let { T9Match(candidate, it) } }
            .sortedBy { it.kind.ordinal }
            .distinctBy { it.candidate.dialString.filter(::isDigit) }
            .take(limit)
            .toList()

    private fun nameMatch(
        digits: String,
        name: String,
    ): T9MatchKind? {
        val words = words(name).map(::spell)
        if (words.isEmpty()) return null
        val wordStart = words.indices.firstOrNull { start -> words.drop(start).joinToString("").startsWith(digits) }
        return when {
            wordStart == 0 -> T9MatchKind.NAME_START
            wordStart != null -> T9MatchKind.WORD_START
            digits.length >= MIN_INITIALS && words.joinToString("") { it.take(1) }.startsWith(digits) -> T9MatchKind.INITIALS
            else -> null
        }
    }

    private fun numberMatch(
        digits: String,
        dialString: String,
    ): T9MatchKind? {
        val numberDigits = dialString.filter(::isDigit)
        return when {
            numberDigits.startsWith(digits) -> T9MatchKind.NUMBER_START
            numberDigits.contains(digits) -> T9MatchKind.NUMBER_CONTAINS
            else -> null
        }
    }

    /** Public for tests: the keypad digits that spell [word] (letters and digits; anything else is dropped). */
    fun spell(word: String): String = word.mapNotNull(::keyOf).joinToString("")

    // Lower-case words without accents: "José-María O'Neil" → [jose, maria, o, neil].
    private fun words(name: String): List<String> =
        Normalizer
            .normalize(name, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .split(NOT_ALPHANUMERIC)
            .filter(String::isNotEmpty)

    private fun keyOf(c: Char): Char? =
        when (c.lowercaseChar()) {
            in '0'..'9' -> c
            in 'a'..'c' -> '2'
            in 'd'..'f' -> '3'
            in 'g'..'i' -> '4'
            in 'j'..'l' -> '5'
            in 'm'..'o' -> '6'
            in 'p'..'s' -> '7'
            in 't'..'v' -> '8'
            in 'w'..'z' -> '9'
            else -> null
        }

    private companion object {
        const val DEFAULT_LIMIT = 20

        // Initials need two letters; a single digit would match half the address book.
        const val MIN_INITIALS = 2
        val COMBINING_MARKS = Regex("""\p{M}+""")
        val NOT_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
    }
}

private fun isDigit(c: Char): Boolean = c in '0'..'9'
