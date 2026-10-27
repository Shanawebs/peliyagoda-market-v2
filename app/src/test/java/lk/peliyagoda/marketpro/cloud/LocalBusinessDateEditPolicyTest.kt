package lk.peliyagoda.marketpro.cloud

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LocalBusinessDateEditPolicyTest {
    private val today=LocalDate.parse("2026-10-27")
    @Test fun teamCanEditTodayRepeatedlyAcrossAnyNumberOfPhones() {
        repeat(100) {
            assertTrue(LocalBusinessDateEditPolicy.mayEdit(
                TeamRole.PURCHASING_STAFF,today,today))
        }
    }
    @Test fun teamMayBrowseButNeverEditOldOrFutureDays() {
        for (day in listOf("2026-10-26","2026-10-01","2025-01-01","2026-10-28")) {
            assertFalse(LocalBusinessDateEditPolicy.mayEdit(
                TeamRole.PURCHASING_STAFF,LocalDate.parse(day),today))
        }
    }
    @Test fun ownerCanEditAnyPastOrCurrentButNotFutureDate() {
        for (day in listOf("2025-01-01","2026-10-26","2026-10-27")) {
            assertTrue(LocalBusinessDateEditPolicy.mayEdit(TeamRole.OWNER,
                LocalDate.parse(day),today))
        }
        assertFalse(LocalBusinessDateEditPolicy.mayEdit(TeamRole.OWNER,
            LocalDate.parse("2026-10-28"),today))
    }
    @Test fun noLoginAndViewOnlyCannotEdit() {
        assertFalse(LocalBusinessDateEditPolicy.mayEdit(null,today,today))
        assertFalse(LocalBusinessDateEditPolicy.mayEdit(TeamRole.VIEWER,today,today))
    }
    @Test fun midnightChangeRechecksTheBusinessDay() {
        val earlier=LocalDate.parse("2026-10-26")
        assertTrue(LocalBusinessDateEditPolicy.mayEdit(TeamRole.PURCHASING_STAFF,
            earlier,earlier))
        assertFalse(LocalBusinessDateEditPolicy.mayEdit(TeamRole.PURCHASING_STAFF,
            earlier,today))
    }
}
