package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.BuildConfig

/**
 * Honest V3.6.8 offline preview, no fake success and no destructive action.
 * This is deliberately not an actionable replacement flow until the actual
 * owner-authenticated server functions + UI secure transport are deployed.
 */
@Composable
internal fun TeamPhoneManagementPreview() {
    val deployed = !BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED &&
        BuildConfig.MARKETPRO_TEAM_PHONE_TRANSFER_RPC_DEPLOYED

    Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
        Text("Team Phone Management",
            style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        Text("Owner Can Remove An Old Staff Phone And Pair A New One",
            style=MaterialTheme.typography.bodyMedium)
        Text("1. Disable the old Staff Phone immediately from Cloud.",
            style=MaterialTheme.typography.bodySmall)
        Text("2. Invite a new Phone with its own ID and a 30-minute code.",
            style=MaterialTheme.typography.bodySmall)
        Text("3. Staff signs in on the new Phone and accepts the invitation.",
            style=MaterialTheme.typography.bodySmall)
        Text("4. Owner activates the new Device as Read-Only first. " +
            "Writing requires a separate approved Server rollout.",
            style=MaterialTheme.typography.bodySmall)
        Text(
            "IMPORTANT: Upload or securely back up any UNSYNCED purchases " +
                "and invoice photos on the old phone BEFORE retiring it. " +
                "Only Cloud-synced data can be downloaded to the new phone.",
            style=MaterialTheme.typography.bodySmall,
            color=Color(0xFF9A4634),
            fontWeight=FontWeight.Bold
        )
        Text("Old Staff App access is rejected by the Server after revocation, " +
            "even if the phone retains an unexpired Login Token. " +
            "Data already on a lost device cannot be remotely erased.",
            style=MaterialTheme.typography.bodySmall)
        Button(
            onClick={},
            enabled=false,
            modifier=Modifier.fillMaxWidth()
        ){ Text("Disable Old Staff Phone — Server Activation Required") }
        Button(
            onClick={},
            enabled=false,
            modifier=Modifier.fillMaxWidth()
        ){ Text("Replace Team Phone — Server Activation Required") }
        Text(
            if(deployed)
                "Network gate configured; real Owner phone management UI " +
                    "must be verified before these controls are enabled."
            else
                "Development Preview: Both actions are disabled. " +
                    "Live V3.3.7 and current team data are unchanged.",
            style=MaterialTheme.typography.bodySmall,color=ProColors.teal
        )
    }
}
