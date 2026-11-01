package io.github.jeshiomurmu.opencaller.core.ml.transaction

import io.github.jeshiomurmu.opencaller.core.ml.SourceAssets
import io.github.jeshiomurmu.opencaller.core.ml.TestMl
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.SenderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TransactionParserTest {
    private val parser = TestMl.transactionParser()

    @Test
    fun theShippedTemplatesAllCompile() {
        val banks = BankTemplates.parse(SourceAssets.read(BankTemplates.ASSET))
        assertTrue(banks.size >= 15)
        assertEquals(setOf("BD", "IN", "US"), banks.map { it.country }.toSet())
        assertTrue(banks.all { bank -> bank.templates.isNotEmpty() && bank.templates.all { "(?<amount>" in it.pattern.pattern } })
    }

    @Test
    fun aBankTemplateGivesAmountCardDigitsAndMerchant() {
        val found =
            parser.parse(
                "Rs.1,250.50 spent on HDFC Bank Card x4421 at Metro Fuels on 12-Oct-26. Avl bal: Rs.10,000",
                SenderId("VM-HDFCBK-T"),
            )!!
        assertEquals(BigDecimal("1250.50"), found.amount)
        assertEquals("INR", found.currency)
        assertEquals(Direction.DEBIT, found.direction)
        assertEquals("4421", found.last4)
        assertEquals("Metro Fuels", found.merchant)
        assertEquals("HDFC Bank", found.bank)
    }

    @Test
    fun aCreditWithoutAMerchant() {
        val found =
            parser.parse(
                "BDT 5,000 has been credited to your account **1234 on 01-Nov-26. Available balance BDT 9,000.",
                SenderId("BRAC BANK"),
            )!!
        assertEquals(BigDecimal("5000.00"), found.amount)
        assertEquals(Direction.CREDIT, found.direction)
        assertEquals("1234", found.last4)
        assertNull(found.merchant)
    }

    @Test
    fun anUnknownBankFallsBackToTheGenericRules() {
        val found = parser.parse("Your a/c XX7788 is debited for INR 499.00 at Lotus Pharmacy on 3 Nov.", SenderId("AX-NEWBNK-T"))!!
        assertEquals(BigDecimal("499.00"), found.amount)
        assertEquals("INR", found.currency)
        assertEquals("7788", found.last4)
        assertEquals("Lotus Pharmacy", found.merchant)
        assertNull(found.bank)
    }

    @Test
    fun messagesFromPeopleAndMessagesWithoutAMoveAreNotTransactions() {
        assertNull(parser.parse("I sent you $20 for the tickets", PhoneNumber("+15550192")))
        assertNull(parser.parse("Get 30GB for Tk 299, valid 30 days.", SenderId("Robi")))
        assertEquals(
            "TransactionInfo(currency=INR, direction=DEBIT, bank=null)",
            parser.parse("Rs.10 debited", SenderId("ABC"))!!.toString(),
        )
    }
}
