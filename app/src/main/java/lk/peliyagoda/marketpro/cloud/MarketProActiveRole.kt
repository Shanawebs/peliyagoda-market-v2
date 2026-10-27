package lk.peliyagoda.marketpro.cloud

import android.content.Context
import lk.peliyagoda.marketpro.BuildConfig
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Shared APK, local editing guard, never a substitute for server RLS.
 *
 * One active login mode on this installation. Both roles see the SAME UI;
 * Team can write today's business date only. Owner can correct any earlier
 * date. The authoritative cloud writer MUST recheck these rules and current
 * device revocation from the actual verified Auth session at commit time.
 *
 * Stored preferences do not confer remote privileges. They merely prevent
 * accidental offline changes to historical records in the phone's Room DB.
 */
internal object MarketProActiveRole {
    private const val PREFS = "marketpro_active_account_role_v1"
    private const val MODE = "mode"
    private const val USER = "user_id"
    private const val OWNER = "owner"
    private const val TEAM = "team"
    private val COLOMBO = ZoneId.of("Asia/Colombo")

    private fun prefs(app: Context) =
        app.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activateOwner(app: Context, verifiedUserId: String) {
        require(verifiedUserId == CloudSyncConfig.OWNER_USER_ID) {
            "Only the verified workspace Owner may select Owner role"
        }
        require(UUID.fromString(verifiedUserId).toString() == verifiedUserId)
        check(prefs(app).edit().putString(MODE, OWNER)
            .putString(USER,verifiedUserId).commit()) { "Owner login state not saved" }
    }

    fun activateTeam(app: Context, verifiedUserId: String) {
        require(verifiedUserId != CloudSyncConfig.OWNER_USER_ID)
        require(UUID.fromString(verifiedUserId).toString() == verifiedUserId)
        check(prefs(app).edit().putString(MODE, TEAM)
            .putString(USER,verifiedUserId).commit()) { "Team login state not saved" }
    }

    fun clearIfActive(app: Context, mode: String) {
        val p = prefs(app)
        if (p.getString(MODE,null)==mode) {
            p.edit().remove(MODE).remove(USER).commit()
        }
    }

    fun current(app: Context): TeamRole? {
        if (!BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE) return TeamRole.OWNER
        val p = prefs(app)
        val user=p.getString(USER,null) ?: return null
        if (runCatching { UUID.fromString(user).toString()==user }.getOrDefault(false)
            .not()) return null
        return when(p.getString(MODE,null)) {
            OWNER -> if(user==CloudSyncConfig.OWNER_USER_ID) TeamRole.OWNER else null
            TEAM -> if(user!=CloudSyncConfig.OWNER_USER_ID) TeamRole.PURCHASING_STAFF else null
            else -> null
        }
    }

    fun requireBusinessDateEditable(app: Context, date: String) {
        if (!BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE) return
        val target = LocalDate.parse(date)
        val today = LocalDate.now(COLOMBO)
        val role=current(app)
        check(LocalBusinessDateEditPolicy.mayEdit(role,target,today)) {
            when {
                role==null -> "Sign in as Owner or Team before editing records"
                target.isAfter(today) -> "Future dates cannot be edited"
                else -> "Team can edit today's records only. Earlier dates are Owner-only."
            }
        }
    }

    fun requireCatalogEditable(app: Context) {
        if (!BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE) return
        check(current(app) in setOf(TeamRole.OWNER, TeamRole.PURCHASING_STAFF)) {
            "Sign in as Owner or Team before changing the catalog"
        }
    }

    fun requireOwnerAdmin(app: Context) {
        if (!BuildConfig.MARKETPRO_REQUIRE_CLOUD_ROLE) return
        check(current(app)==TeamRole.OWNER) { "Owner account required" }
    }
}
