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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The Local PIN is required ONLY when restoring a JSON backup. Verification
 * is part of Restore itself, not a standalone permission for Cloud or staff.
 */
@Composable
internal fun SecurityAndBackupDialog(
    pinConfigured:Boolean,
    onBackup:()->Unit,
    onRestore:()->Unit,
    onDismiss:()->Unit
) {
    AlertDialog(
        onDismissRequest=onDismiss,
        title={ Text("Security & Backup",fontWeight=FontWeight.Bold) },
        text={
            Column(
                modifier=Modifier.fillMaxWidth().heightIn(max=420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(12.dp)
            ){
                Text("දත්ත Backup සහ Restore කිරීම මෙතැනින් කළ හැකියි.",
                    style=MaterialTheme.typography.bodyLarge)
                Text("Restore Backup (JSON) සඳහා පමණක් 6-Digit Local PIN එක අවශ්‍යයි. " +
                    "මෙම PIN එක Owner Login, Team Login හෝ Cloud Phone Approval " +
                    "සඳහා භාවිත නොවේ.")
                Text("Restore PIN: "+if(pinConfigured) "Configured" else
                    "Not Set — First Restore සඳහා PIN සකසන්න.")
                Text("Restore කිරීමට පෙර අලුත් Backup එකක් Save කරගන්න. " +
                    "Cloud Sync නොවූ දත්ත සහ Invoice Photos ආරක්ෂිතව තබාගන්න.",
                    style=MaterialTheme.typography.bodySmall)
                Button(onClick=onBackup,modifier=Modifier.fillMaxWidth()) {
                    Text("Backup All Records (JSON)")
                }
                Button(onClick=onRestore,modifier=Modifier.fillMaxWidth()) {
                    Text("Restore Backup (JSON) — PIN Required")
                }
            }
        },
        confirmButton={TextButton(onClick=onDismiss){Text("Close")}}
    )
}
