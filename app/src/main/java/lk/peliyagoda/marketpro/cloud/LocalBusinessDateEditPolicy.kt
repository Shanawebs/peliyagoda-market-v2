package lk.peliyagoda.marketpro.cloud

import java.time.LocalDate

/** UI/Room offline guard, not a server authorization source. */
internal object LocalBusinessDateEditPolicy {
    fun mayEdit(role:TeamRole?, selected:LocalDate, todayInColombo:LocalDate):Boolean {
        if(selected.isAfter(todayInColombo))return false
        return when(role) {
            TeamRole.OWNER -> true
            TeamRole.PURCHASING_STAFF -> selected==todayInColombo
            else -> false
        }
    }
}
