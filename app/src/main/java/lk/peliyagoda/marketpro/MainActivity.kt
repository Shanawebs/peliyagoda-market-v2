package lk.peliyagoda.marketpro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import lk.peliyagoda.marketpro.data.MarketDatabase
import lk.peliyagoda.marketpro.data.MarketRepository
import lk.peliyagoda.marketpro.ui.MarketProApp
import lk.peliyagoda.marketpro.ui.MarketViewModel
import lk.peliyagoda.marketpro.update.MarketProAutoUpdater

/**
 * v4.3.1 recovery: no database access or Compose startup when the previous
 * app process crashed. Never clears Room, photos, credentials, or app data.
 */
class MainActivity : ComponentActivity() {
    private val model: MarketViewModel by viewModels {
        MarketViewModel.factory(
            MarketRepository(MarketDatabase.get(applicationContext), applicationContext),
            applicationContext
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MarketProCrashGuard.install(applicationContext)
        enableEdgeToEdge()
        // A prior fatal error may be Room- or UI-related. Do not create the
        // ViewModel/Database in recovery mode; preserve the original files.
        if (MarketProCrashGuard.hasCrash(applicationContext)) {
            runCatching {
                WorkManager.getInstance(applicationContext)
                    .cancelUniqueWork("marketpro_photo_once")
                WorkManager.getInstance(applicationContext)
                    .cancelUniqueWork("marketpro_photo_retry")
            }
            showRecoveryDialog()
            return
        }
        setContent { MarketProApp(model) }
    }

    private fun showRecoveryDialog() {
        val error = MarketProCrashGuard.crashDescription(applicationContext)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Market Pro • Safe Recovery")
            .setMessage(
                "The last startup ended unexpectedly. Existing local purchases, " +
                    "photos and database have NOT been deleted.\n\n" +
                    "Cause (diagnostic only):\n" + error.take(1100) +
                    "\n\nChoose Check For Updates to install a verified newer build " +
                    "without uninstalling, or copy this diagnostic for support."
            )
            .setPositiveButton("Check For Updates", null)
            .setNeutralButton("Copy Crash Details", null)
            .setNegativeButton("Try App Again", null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val button = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false
                lifecycleScope.launch {
                    val result = MarketProAutoUpdater.check(this@MainActivity, force = true)
                    button.isEnabled = true
                    when (result) {
                        is MarketProAutoUpdater.Outcome.Downloaded -> {
                            runCatching {
                                MarketProAutoUpdater.installWithAndroidApproval(
                                    this@MainActivity, result.ready
                                )
                            }.onFailure {
                                showRecoveryMessage(
                                    "Installer unavailable: " + it.javaClass.simpleName
                                )
                            }
                        }
                        is MarketProAutoUpdater.Outcome.NoUpdate ->
                            showRecoveryMessage("No newer signed update is published yet.")
                        is MarketProAutoUpdater.Outcome.Failure ->
                            showRecoveryMessage(
                                "Update check failed. Try Wi-Fi, without clearing app data."
                            )
                    }
                }
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val service = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                service.setPrimaryClip(
                    ClipData.newPlainText("Market Pro Crash Diagnostic", error)
                )
                showRecoveryMessage("Diagnostic copied. No photos or credentials included.")
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                MarketProCrashGuard.clear(applicationContext)
                dialog.dismiss()
                recreate()
            }
        }
        dialog.show()
    }

    private fun showRecoveryMessage(text: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Market Pro Recovery")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }
}

internal object MarketProCrashGuard {
    private const val PREFS = "marketpro_crash_recovery_431"
    private const val MARKER = "last_crash"
    @Volatile private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val top = generateSequence(throwable) { it.cause }
                    .take(3)
                    .joinToString("\nCaused by: ") { cause ->
                        cause.javaClass.name + "\n" +
                            cause.stackTrace
                                .filter { it.className.startsWith("lk.peliyagoda.marketpro") }
                                .take(6)
                                .joinToString("\n") { frame ->
                                    frame.className + ":" + frame.methodName +
                                        ":" + frame.lineNumber
                                }
                    }.take(1600)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(MARKER, top.ifBlank { throwable.javaClass.name })
                    .commit()
            }
            if (previous != null) previous.uncaughtException(thread, throwable)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
        installed = true
    }

    fun hasCrash(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains(MARKER)

    fun crashDescription(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MARKER, "Unknown crash") ?: "Unknown crash"

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(MARKER).commit()
    }
}
