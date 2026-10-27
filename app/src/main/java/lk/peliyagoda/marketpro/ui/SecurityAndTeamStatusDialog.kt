package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.BuildConfig

/** Cloud account, phone-linking and two-phone sync status only. */
@Composable
fun SecurityAndTeamStatusDialog(onDismiss: () -> Unit) {
    var showTeamSetup by remember { mutableStateOf(false) }

    if (!showTeamSetup) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color.White,
            title = { Text("Security & Team Sync") },
            text = {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 490.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Owner & Purchasing Team",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Owner phone එකෙන් Team phone request එක Approve/Unlink කළ හැකියි. " +
                            "Login වීම පමණක් Cloud access permission එකක් නොවේ; " +
                            "active session සහ linked physical phone එක server එක verify කරයි."
                    )
                    Text(
                        "Team අද Sri Lanka business date එකේ entries create/correct කරන්න පුළුවන්. " +
                            "පරණ දින Team phone එකෙන් edit කරන්න බැහැ. Ownerට පරණ දින corrections කළ හැකියි."
                    )
                    Text(
                        "Local edit එක Room Database + Offline Outbox එකට මුලින් save වෙනවා. " +
                            "Server ACK එක verify වුණාට පස්සේ විතරයි Outbox operation එක clear වෙන්නේ."
                    )
                    Text(
                        if (
                            !BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
                            BuildConfig.MARKETPRO_STAGING_QA_FINANCE_RPC &&
                            BuildConfig.MARKETPRO_TWO_PHONE_AUTO_SYNC
                        ) {
                            "Staging v4.1.0: Phone Linking, Auto Upload, Safe Room Merge සහ " +
                                "foreground near-live pull enabled for real two-phone QA."
                        } else {
                            "Cloud two-phone writer is not active in this build."
                        },
                        color = ProColors.teal
                    )
                    Text(
                        "Local Restore PIN සහ JSON Backup settings දැන් Security & Backup කොටසෙයි."
                    )
                    Button(onClick = { showTeamSetup = true }) {
                        Text("Team Login, Phone Linking & Sync")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        )
    }

    if (showTeamSetup) {
        TeamAccessSetupDialog(onDismiss = { showTeamSetup = false })
    }
}
