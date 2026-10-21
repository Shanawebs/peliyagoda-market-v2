package lk.peliyagoda.marketpro.data

import android.content.Context
import java.util.UUID

/**
 * Stable random ID for DEV device enrollment. Not Android serial/IMEI/phone number.
 * Enrollment is still SERVER-SIDE and requires an authorized Supabase Auth user.
 */
object CloudDeviceIdentity {
    private const val PREFS = "marketpro_cloud_device"
    private const val KEY = "device_uuid"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let {
            if (runCatching { UUID.fromString(it) }.isSuccess) return it
        }
        val generated = UUID.randomUUID().toString()
        check(prefs.edit().putString(KEY, generated).commit()) {
            "Could not persist device identity safely"
        }
        return generated
    }
}
