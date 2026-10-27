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

/**
 * Shows accurate protection status. Local PIN guards restore; it is NOT an
 * Android OS uninstall interceptor and cannot grant server staff permissions.
 */
@Composable
fun SecurityAndTeamStatusDialog(onDismiss: () -> Unit) {
    var showTeamSetup by remember { mutableStateOf(false) }
    if (!showTeamSetup) AlertDialog(
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
                    "Two-Phone Rules: Purchasing staff can enter and correct " +
                        "TODAY's entries as often as needed without owner approval. " +
                        "Previous days are READ-ONLY for staff. Only the Owner " +
                        "can correct previous days."
                )
                Text(
                    "Sri Lanka business-day cutoff: 12:00 midnight (Asia/Colombo). " +
                        "This rule must be verified and enforced by the Cloud server, " +
                        "not the phone clock."
                )
                Text(
                    if (BuildConfig.MARKETPRO_LOCAL_PREVIEW)
                        "This is an offline preview. Cloud Phone Linking is unavailable."
                    else
                        "Phone Linking and Safe Cloud Download available for Owner / Team. " +
                        "Automatic financial uploads are not yet active."
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
                Button(onClick = { showTeamSetup = true }) {
                    Text("Account & Phone — Cloud Sync")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
    if (showTeamSetup) {
        TeamAccessSetupDialog(onDismiss = { showTeamSetup = false })
    }
}
