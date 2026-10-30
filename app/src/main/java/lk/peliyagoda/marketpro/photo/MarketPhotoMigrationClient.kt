package lk.peliyagoda.marketpro.photo

import android.content.Context
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.cloud.CloudOwnerAuth
import lk.peliyagoda.marketpro.cloud.MarketProActiveRole
import lk.peliyagoda.marketpro.cloud.TeamRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Temporary, owner-only Box to R2 migration client.
 * Never contains Box or R2 credentials. The server verifies each original,
 * compares SHA-256 after R2 download and conditionally switches metadata.
 */
internal class MarketPhotoMigrationClient(private val app: Context) {
    private val endpoint = URL(
        BuildConfig.MARKETPRO_SUPABASE_URL +
            "/functions/v1/marketpro-box-to-r2-migrate-qa"
    )

    private suspend fun invoke(mode: String, photoId: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            check(!BuildConfig.MARKETPRO_LOCAL_PREVIEW)
            check(MarketProActiveRole.current(app) == TeamRole.OWNER)
            check(endpoint.protocol == "https")
            check(endpoint.host == BuildConfig.MARKETPRO_SUPABASE_HOST)
            val token = CloudOwnerAuth(app).accessTokenForOwner(
                BuildConfig.MARKETPRO_OWNER_USER_ID
            )
            val request = JSONObject()
                .put("mode", mode)
                .put("confirm", "COPY_ONLY_NO_DELETE")
            if (photoId != null) request.put("photo_id", photoId)
            val connection = endpoint.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 110_000
                connection.setRequestProperty("apikey", BuildConfig.MARKETPRO_SUPABASE_PUBLISHABLE_KEY)
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = request.toString().toByteArray(Charsets.UTF_8)
                connection.outputStream.use { it.write(bytes) }
                val response = (if (connection.responseCode in 200..299)
                    connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText().take(16000) } ?: ""
                val result = JSONObject(response)
                if (connection.responseCode !in 200..299 ||
                    !result.optBoolean("ok")) {
                    error("Migration server: " + result.optString("code", "UNAVAILABLE"))
                }
                result
            } finally {
                connection.disconnect()
            }
        }

    suspend fun migrateAll(onProgress: suspend (Int, Int) -> Unit): Pair<Int, Int> {
        val dryRun = invoke("dry_run")
        val entries = dryRun.optJSONArray("photos") ?: error("Invalid migration list")
        val total = entries.length()
        var completed = 0
        for (index in 0 until total) {
            val photoId = entries.getJSONObject(index).getString("photo_id")
            val result = invoke("copy_one", photoId)
            check(result.optString("code") == "COPIED_AND_VERIFIED" ||
                result.optString("code") == "NO_BOX_PHOTO")
            completed++
            onProgress(completed, total)
        }
        return completed to total
    }
}
