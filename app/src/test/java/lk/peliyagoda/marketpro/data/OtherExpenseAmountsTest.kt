package lk.peliyagoda.marketpro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherExpenseAmountsTest {
    @Test fun originalFinancialExample() {
        val operating = OtherExpenseAmounts(
            transportCents=15000, laborChargeCents=20000,
            mealsDrinksCents=10000, othersCents=5000,
            remarks="Delivery vehicle and market helpers"
        )
        assertEquals(50000L, operating.totalCents)
        assertEquals(-82000L, cashBalanceAfterOtherExpenses(
            cashReceivedCents=50000L,
            purchaseExpensesCents=82000L,
            otherExpensesCents=50000L
        ))
    }

    @Test fun eachFieldCanUpdateWithoutOverwritingOtherFieldsOrRemarks() {
        val a = OtherExpenseAmounts(transportCents=2000, remarks="Morning trip")
        val b = a.withAmount(OtherExpenseField.LABOR_CHARGE,3500)
            .withAmount(OtherExpenseField.MEALS_AND_DRINKS,1000)
            .withAmount(OtherExpenseField.OTHERS,500)
        assertEquals(7000L,b.totalCents)
        assertEquals("Morning trip",b.remarks)
        assertEquals(2000L,b.transportCents)
        assertEquals(3500L,b.laborChargeCents)
        assertEquals(1000L,b.mealsDrinksCents)
        assertEquals(500L,b.othersCents)
    }

    @Test fun blankFieldsDefaultToZeroAndRemarksAreNotMoney() {
        val amounts=OtherExpenseAmounts(remarks="No additional expenses")
        assertEquals(0L,amounts.totalCents)
        assertEquals(60000L,cashBalanceAfterOtherExpenses(80000L,20000L,amounts.totalCents))
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsNegativeExpense() { OtherExpenseAmounts(transportCents=-1) }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsHugeExpense() { OtherExpenseAmounts().withAmount(OtherExpenseField.OTHERS,10_000_000_000L) }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsHugeRemark() { OtherExpenseAmounts(remarks="A".repeat(501)) }

    @Test fun remarkCanBeSinhalaAndBoundary500() {
        assertTrue(OtherExpenseAmounts(remarks="ප්‍රවාහන වියදම්").remarks.isNotEmpty())
        assertEquals(500,OtherExpenseAmounts(remarks="x".repeat(500)).remarks.length)
    }
}
