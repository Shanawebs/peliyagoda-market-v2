package lk.peliyagoda.marketpro.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.cloud.BoxOAuthConnectionClient
import lk.peliyagoda.marketpro.cloud.MarketProActiveRole
import lk.peliyagoda.marketpro.cloud.TeamRole
import kotlinx.coroutines.launch

/**
 * Existing Owner Login + Owner MFA are reused: this dialog creates NO new credentials.
 * Authorization opens in the system browser to reach the preconfigured Box callback.
 * Both buttons are explicit user actions; there is no automatic Box login or upload.
 */
@Composable
internal fun BoxCloudConnectDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val client = remember(context) { BoxOAuthConnectionClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val isOwner = MarketProActiveRole.current(context.applicationContext) == TeamRole.OWNER
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(
        "Use your existing Box storage account, not the new Developer account."
    ) }
    var connected by remember { mutableStateOf(false) }

    fun runAction(task: suspend () -> Unit) {
        if (busy || !isOwner) return
        busy = true
        scope.launch {
            try { task() }
            catch (_: Exception) {
                status = "Box connection not confirmed. Check network, Owner Login and " +
                    "recent MFA verification. No purchase records were changed."
            } finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color.White,
        title = { Text("Box Photo Cloud • Owner") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Market Pro photos will be stored in your existing Box account " +
                    "under Market Pro / Item Photos. The Box Developer login alone " +
                    "does not authorize that folder.")
                if (!isOwner) {
                    Text("First sign in as Cloud Owner from the app menu.",
                        color = ProColors.red, fontWeight = FontWeight.Bold)
                } else {
                    Text("1. Check Owner Authenticator and verify a new 6-digit code. " +
                        "2. Immediately tap Connect Box. " +
                        "3. In the browser select your OLD Box account and approve access. " +
                        "4. Return here and tap Check Box Connection.")
                    OwnerTotpMfaPanel()
                    Button(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            runAction {
                                val authorizationUrl = client.startAuthorization()
                                // Our client validates the exact trusted Box OAuth host,
                                // path, callback and CSRF state before launching browser.
                                val browserIntent = Intent(
                                    Intent.ACTION_VIEW, Uri.parse(authorizationUrl)
                                ).addCategory(Intent.CATEGORY_BROWSABLE)
                                if (context !is Activity) {
                                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(browserIntent)
                                status = "Box browser opened. Approve access using the " +
                                    "existing storage account, then return to Market Pro."
                            }
                        }
                    ) { Text(if (busy) "Checking..." else "Connect Box") }
                    OutlinedButton(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            runAction {
                                val snapshot = client.checkStatus()
                                connected = snapshot.connected
                                status = if (snapshot.connected)
                                    "Box account connected securely. " +
                                        "Photo upload is not enabled in this build yet."
                                else "Not connected yet. Complete Box approval in the browser."
                            }
                        }
                    ) { Text("Check Box Connection") }
                }
                Text(
                    if (connected) "Box Connected • Photo Sync Pending" else status,
                    fontWeight = FontWeight.SemiBold,
                    color = if (connected) ProColors.green else ProColors.muted
                )
                Text("No Box Client Secret, token, password or photo is saved " +
                    "inside this Android screen. The Photo Upload module remains OFF.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        }
    )
}
