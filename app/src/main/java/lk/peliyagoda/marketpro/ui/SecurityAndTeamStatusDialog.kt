package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.security.RestorePinGuard

/**
 * Shows accurate protection status. Local PIN guards restore; it is NOT an
 * Android OS uninstall interceptor and cannot grant server staff permissions.
 */
@Composable
fun SecurityAndTeamStatusDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val guard = remember(context) { RestorePinGuard(context) }
    var showPinPrompt by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("Security & Team Sync") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 490.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Local 6-Digit Restore PIN: " +
                        if (guard.isConfigured()) "Configured" else "Not Set",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "PIN is protected using Android Keystore. It is required for " +
                        "Restore Backup and limits repeated incorrect attempts."
                )
                Text(
                    "Other secure owner actions (delete, staff access, revoke phones) " +
                        "will require server-side Owner authentication in the " +
                        "two-phone release. PIN alone never grants Cloud rights."
                )
                Text(
                    "Ordinary Android apps CANNOT require a custom PIN when the " +
                        "system uninstalls an app. A company-managed Device Owner " +
                        "configuration can block uninstall on the purchasing phone."
                )
                Text(
                    "Team phone → Owner phone download / correction: NOT ACTIVE " +
                        "in this offline UI preview. Do NOT share Owner login " +
                        "with purchasing staff."
                )
                Text(
                    "Sync-pending data and local invoice photos can be lost on " +
                        "uninstall or clear storage. Keep the existing Cloud phone " +
                        "and make independent local-photo backups."
                )
                if (BuildConfig.MARKETPRO_LOCAL_PREVIEW) {
                    Text(
                        "Preview Mode: No Internet permission. Current Supabase " +
                            "writer, Cloud Dashboard and old app are unaffected.",
                        color = ProColors.teal
                    )
                }
                Button(onClick = { showPinPrompt = true }) {
                    Text(if (guard.isConfigured())
                        "Verify 6-Digit Local PIN" else "Set 6-Digit Local PIN")
                }
                if (lastResult.isNotEmpty()) {
                    Text(lastResult, color = ProColors.teal)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
    if (showPinPrompt) {
        RestorePinDialog(
            guard = guard,
            onDismiss = { showPinPrompt = false },
            onAuthorized = {
                showPinPrompt = false
                lastResult = "Local PIN verified. This does NOT enable OS uninstall blocking."
            }
        )
    }
}
