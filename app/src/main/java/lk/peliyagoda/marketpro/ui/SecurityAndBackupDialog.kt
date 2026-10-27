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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.security.RestorePinGuard

/** Local backup/restore security only. Cloud phone permissions live elsewhere. */
@Composable
fun SecurityAndBackupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val guard = remember(context) { RestorePinGuard(context) }
    var showPinPrompt by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("Security & Backup") },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Local 6-Digit Restore PIN: " +
                        if (guard.isConfigured()) "Configured" else "Not Set",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "මේ PIN එක Android Keystore මඟින් ආරක්ෂා කරයි. " +
                        "Restore Backup (JSON) ක්‍රියාවලියට මෙය අවශ්‍යයි."
                )
                Text(
                    "PIN එක Cloud Owner/Team login හෝ Phone Linking permission එකක් නොවේ. " +
                        "Cloud permissions server එකෙන් වෙනම verify කරයි."
                )
                Text(
                    "Backup restore කිරීමට පෙර අලුත් JSON backup එකක් save කරගන්න. " +
                        "Invoice photos JSON backup එකට ඇතුළත් නොවේ."
                )
                Button(onClick = { showPinPrompt = true }) {
                    Text(
                        if (guard.isConfigured()) "Verify 6-Digit Local PIN"
                        else "Set 6-Digit Local PIN"
                    )
                }
                if (result.isNotBlank()) {
                    Text(result, color = ProColors.teal)
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
                result = "Local Restore PIN verified."
            }
        )
    }
}
