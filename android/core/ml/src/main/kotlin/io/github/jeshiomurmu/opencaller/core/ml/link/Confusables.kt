package io.github.jeshiomurmu.opencaller.core.ml.link

/**
 * Letters that look like Latin letters: a small hand-picked set in the spirit of the confusables data of Unicode
 * Technical Standard #39 (not a copy of it), covering the Cyrillic and Greek look-alikes seen in phishing domains and
 * the digits 0 and 1. [skeleton] maps a host to what it looks like, so `pаypa1` (Cyrillic а, digit 1) reads as
 * `paypal`.
 */
internal object Confusables {
    private val SINGLE: Map<Char, Char> =
        buildMap {
            // Cyrillic
            putAll(
                listOf(
                    'а' to 'a',
                    'е' to 'e',
                    'ё' to 'e',
                    'һ' to 'h',
                    'і' to 'i',
                    'ї' to 'i',
                    'ј' to 'j',
                    'к' to 'k',
                    'ӏ' to 'l',
                    'о' to 'o',
                    'р' to 'p',
                    'ԛ' to 'q',
                    'ѕ' to 's',
                    'у' to 'y',
                    'ԝ' to 'w',
                    'х' to 'x',
                    'с' to 'c',
                    'ԁ' to 'd',
                    'п' to 'n',
                ),
            )
            // Greek
            putAll(
                listOf(
                    'α' to 'a',
                    'ε' to 'e',
                    'η' to 'n',
                    'ι' to 'i',
                    'κ' to 'k',
                    'ν' to 'v',
                    'ο' to 'o',
                    'ρ' to 'p',
                    'τ' to 't',
                    'υ' to 'u',
                    'χ' to 'x',
                    'ω' to 'w',
                ),
            )
            // Latin look-alikes and digits
            putAll(listOf('ı' to 'i', 'ɑ' to 'a', 'ɡ' to 'g', 'ⅼ' to 'l', '0' to 'o', '1' to 'l'))
        }
    private val PAIRS = listOf("rn" to "m", "vv" to "w")

    /** What [host] looks like in plain lower-case Latin letters. */
    fun skeleton(host: String): String {
        val mapped = host.lowercase().map { SINGLE[it] ?: it }.joinToString("")
        return PAIRS.fold(mapped) { text, (from, to) -> text.replace(from, to) }
    }

    /** True when a label of [host] mixes Latin letters with letters of another script. */
    fun mixesScripts(host: String): Boolean =
        host.split('.', '-').any { label ->
            val scripts = label.filter(Char::isLetter).map { Character.UnicodeScript.of(it.code) }.toSet()
            Character.UnicodeScript.LATIN in scripts && scripts.size > 1
        }
}
