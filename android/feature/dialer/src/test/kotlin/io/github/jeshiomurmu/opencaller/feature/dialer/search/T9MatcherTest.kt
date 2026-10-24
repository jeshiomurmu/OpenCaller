package io.github.jeshiomurmu.opencaller.feature.dialer.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TC-DIAL-002: T9 search over contacts and the call log. */
class T9MatcherTest {
    private val matcher = T9Matcher()
    private val ram = T9Candidate("Ram Kumar", "+8801712345678")
    private val sam = T9Candidate("Sam Okafor", "+2348031234567")
    private val unknown = T9Candidate(null, "+12025550143")

    @Test
    fun tcDial002DigitsSpellTheStartOfTheName() {
        assertEquals(T9MatchKind.NAME_START, matcher.match("726", ram))
        assertEquals(T9MatchKind.NAME_START, matcher.match("7", ram))
        assertEquals(T9MatchKind.NAME_START, matcher.match("72658627", ram))
        // "726" is also "SAM": both match, Ram and Sam alike.
        assertEquals(T9MatchKind.NAME_START, matcher.match("726", sam))
    }

    @Test
    fun laterWordsAndInitials() {
        assertEquals(T9MatchKind.WORD_START, matcher.match("586", ram))
        assertEquals(T9MatchKind.WORD_START, matcher.match("6523", sam))
        assertEquals(T9MatchKind.INITIALS, matcher.match("75", ram))
        assertNull(matcher.match("57", ram))
    }

    @Test
    fun accentsAndPunctuationAreIgnored() {
        val jose = T9Candidate("José-María O'Neil", "+34612345678")
        assertEquals("5673", matcher.spell("jose"))
        assertEquals(T9MatchKind.NAME_START, matcher.match("5673", jose))
        assertEquals(T9MatchKind.WORD_START, matcher.match("62742", jose))
        assertEquals(T9MatchKind.WORD_START, matcher.match("6345", jose))
    }

    @Test
    fun numbersMatchByTheirDigits() {
        assertEquals(T9MatchKind.NUMBER_START, matcher.match("1202", unknown))
        assertEquals(T9MatchKind.NUMBER_CONTAINS, matcher.match("5550143", unknown))
        // A national number typed against an international one: 01712… is inside +8801712….
        assertEquals(T9MatchKind.NUMBER_CONTAINS, matcher.match("01712", T9Candidate(null, "+8801712345678")))
        // A contact whose name doesn't match is found by its number.
        assertEquals(T9MatchKind.NUMBER_CONTAINS, matcher.match("1712", ram))
    }

    @Test
    fun onlyDigitsCount() {
        assertEquals(T9MatchKind.NAME_START, matcher.match("+726", ram))
        assertNull(matcher.match("*#", ram))
        assertNull(matcher.match("", ram))
    }

    @Test
    fun searchRanksBestFirstOncePerNumber() {
        val recent = T9Candidate(null, "+8801712345678")
        val raj = T9Candidate("Raj", "+911234567890")
        val result = matcher.search("72", listOf(recent, unknown, sam, raj, ram))
        assertEquals(listOf(sam, raj, ram), result.map { it.candidate })
        assertEquals(1, result.count { it.candidate.dialString == "+8801712345678" })

        val byNumber = matcher.search("1712", listOf(recent, ram))
        assertEquals(listOf(recent), byNumber.map { it.candidate })
        assertEquals(2, matcher.search("7", listOf(ram, sam, unknown), limit = 2).size)
    }

    @Test
    fun aNumberShowsWithItsBestMatch() {
        val fromLog = T9Candidate(null, "+8801726345678")
        val contact = T9Candidate("Ram", "+880 1726-345678")
        val result = matcher.search("726", listOf(fromLog, contact))
        assertEquals(listOf(T9Match(contact, T9MatchKind.NAME_START)), result)
    }
}
