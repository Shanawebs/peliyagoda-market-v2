package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.security.RestorePinGuard
import lk.peliyagoda.marketpro.security.RestorePinPolicy

/**
 * Only invokes onAuthorized after a valid PIN was entered or established.
 * The caller must consume that one-time authorization before restoring data.
 */
@Composable
fun RestorePinDialog(
    guard: RestorePinGuard,
    onDismiss: () -> Unit,
    onAuthorized: () -> Unit
) {
    val firstSetup = remember(guard) { !guard.isConfigured() }
    var entered by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val safeKeyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    val transformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation()
    val complete = RestorePinPolicy.validPin(entered) &&
        (!firstSetup || RestorePinPolicy.validPin(confirm))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (firstSetup) "Set Owner Restore PIN" else "Enter Restore PIN",
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (firstSetup)
                        "පළමු වරට Restore කිරීමට පෙර Owner විසින් අංක 6ක PIN එකක් සකස් කළ යුතුය. " +
                            "එය සුරක්ෂිතව තබාගන්න. PIN එක අමතක වුණොත් App Data මකා දැමීමෙන් තොරව " +
                            "නැවත ලබාගැනීමේ පහසුකමක් නැත."
                    else
                        "Backup File එක තෝරාගැනීමට පෙර අංක 6ක PIN එක ඇතුළත් කරන්න.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = entered,
                    onValueChange = { entered = it.filter { ch -> ch in '0'..'9' }.take(6); error = "" },
                    label = { Text(if (firstSetup) "New 6-Digit PIN" else "6-Digit PIN") },
                    singleLine = true,
                    keyboardOptions = safeKeyboard,
                    visualTransformation = transformation,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (firstSetup) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it.filter { ch -> ch in '0'..'9' }.take(6); error = "" },
                        label = { Text("Confirm New PIN") },
                        singleLine = true,
                        keyboardOptions = safeKeyboard,
                        visualTransformation = transformation,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TextButton(onClick = { showPin = !showPin }) {
                    Text(if (showPin) "Hide PIN" else "Show PIN")
                }
                if (error.isNotEmpty()) {
                    Text(
                        error,
                        color = ProColors.red,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "PIN එක නිවැරදි වුණත් Restore කිරීමට පෙර Backup File එක සහ අවසාන Confirmation එක අවශ්‍යයි.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProColors.muted
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = complete,
                onClick = {
                    runCatching {
                        if (firstSetup) {
                            when {
                                entered != confirm -> error = "PIN අංක දෙක සමාන විය යුතුය."
                                guard.establish(entered, confirm) -> onAuthorized()
                                else -> error = "PIN සකස් කළ නොහැක. නැවත පරීක්ෂා කරන්න."
                            }
                        } else {
                            when (val result = guard.verify(entered)) {
                                RestorePinGuard.Check.Accepted -> onAuthorized()
                                is RestorePinGuard.Check.Rejected -> {
                                    error = "PIN අංකය වැරදියි. තවත් උත්සාහ ${result.attemptsRemaining}යි."
                                    entered = ""
                                }
                                is RestorePinGuard.Check.Locked -> {
                                    val minutes = (result.secondsRemaining + 59) / 60
                                    error = "උත්සාහයන් සීමාව ඉක්මවා ඇත. විනාඩි ${minutes}කින් නැවත උත්සාහ කරන්න."
                                    entered = ""
                                }
                                RestorePinGuard.Check.NotConfigured ->
                                    error = "PIN සකසා නොමැත. Dialog එක වසා නැවත විවෘත කරන්න."
                            }
                        }
                    }.onFailure {
                        // A missing Keystore key must never silently bypass protection.
                        error = "ආරක්ෂක PIN පරීක්ෂාව අසාර්ථකයි. Backup ඉවත් නොකර සහාය ලබාගන්න."
                        entered = ""
                    }
                }
            ) { Text(if (firstSetup) "Set PIN & Continue" else "Unlock Restore") }
        },
        shape = RoundedCornerShape(22.dp)
    )
}
