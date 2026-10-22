package lk.peliyagoda.marketpro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.cloud.CloudOwnerAuth
import kotlinx.coroutines.launch

@Composable
fun CloudOwnerLoginDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val client = remember(context) { CloudOwnerAuth(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // Never save the password in Compose state persistence.
    var account by remember { mutableStateOf<CloudOwnerAuth.Account?>(null) }
    var working by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Checking your saved Supabase session...") }

    LaunchedEffect(Unit) {
        val restored = runCatching { client.currentAccount() }
        account = restored.getOrNull()
        status = when {
            account != null -> "Signed in. Open Cloud Sync & Backup to check enrollment, pending records and opt-in status."
            restored.isFailure -> "Could not verify saved session. Connect to the internet and sign in."
            else -> "Create or sign in to your Market Pro DEV account."
        }
        working = false
    }

    fun start(action: suspend () -> Unit) {
        working = true
        scope.launch {
            try { action() }
            catch (e: Exception) {
                status = e.message?.take(180) ?: "Authentication failed. Please try again."
            } finally {
                password = ""
                working = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Market Pro — Cloud Owner Login") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("DEV only. Login alone does not upload any records. Cloud Sync requires explicit activation. Invoice photos always remain local.")
                if (account == null) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.take(254) },
                        label = { Text("Owner email") },
                        singleLine = true,
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.take(128) },
                        label = { Text("Password (12+ characters for sign up)") },
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val enteredEmail = email
                            val enteredPassword = password
                            start {
                                client.signIn(enteredEmail, enteredPassword).also { account = it }
                                status = "Email authenticated. Check Cloud Sync & Backup for device enrollment and upload status."
                            }
                        },
                        enabled = !working && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Sign In") }
                    TextButton(
                        onClick = {
                            val enteredEmail = email
                            val enteredPassword = password
                            start {
                                client.createAccount(enteredEmail, enteredPassword)
                                status = "Check your email (including Spam) for the Supabase confirmation link. Confirm it, then return here and Sign In. If already registered, use Sign In."
                            }
                        },
                        enabled = !working && CloudOwnerAuth.validEmail(email) &&
                            password.length >= 12,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Create Owner Account / Send Verification Email") }
                } else {
                    Text("Authenticated: ${account!!.email}")
                    Text("Auth User ID: ${account!!.userId}")
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Supabase Auth User ID", account!!.userId)
                        )
                        status = "Auth User ID copied."
                    }) { Text("Copy Auth User ID") }
                    TextButton(onClick = {
                        start {
                            client.signOut()
                            account = null
                            status = "Signed out on this phone. Pending local changes are retained until you sign in again."
                        }
                    }, enabled = !working) { Text("Sign Out") }
                }
                Text(status)
                Text("You do not need to share passwords, verification links or access tokens in chat.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Close") } }
    )
}
