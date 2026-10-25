package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.cloud.CloudOwnerAuth
import lk.peliyagoda.marketpro.cloud.CloudSyncConfig
import lk.peliyagoda.marketpro.cloud.OwnerTotpMfaPolicy
import kotlinx.coroutines.launch

/**
 * Owner ONLY Supabase Auth second factor.
 * Both seed and one-time code live exclusively in Compose volatile memory.
 * A locally successful verification NEVER grants Staff writer permission.
 * Server key-approval independently demands signed AAL2 and TOTP in AMR
 * within 300 sec of SERVER time, plus Owner's current auth.sessions row.
 */
@Composable
internal fun OwnerTotpMfaPanel() {
    val context=LocalContext.current.applicationContext
    val client=remember(context) {CloudOwnerAuth(context)}
    val scope=rememberCoroutineScope()
    val enabled=OwnerTotpMfaPolicy.canCallAuth(
        BuildConfig.MARKETPRO_LOCAL_PREVIEW,
        BuildConfig.MARKETPRO_OWNER_TOTP_MFA_AUTH_DEPLOYED,
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
        BuildConfig.MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED
    )
    // No rememberSaveable or persistent state: no screenshots/logging of OTP.
    var verifiedFactors by remember {
        mutableStateOf<List<CloudOwnerAuth.TotpFactor>>(emptyList())
    }
    var setup by remember {mutableStateOf<CloudOwnerAuth.TotpEnrollment?>(null)}
    var code by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf("")}
    val factor=setup?.factorId ?: verifiedFactors.firstOrNull()?.id

    // Prevent revealing the setup seed in screenshots and Recent Apps previews.
    // Restore a prior FLAG_SECURE state on leaving this short-lived setup step.
    val activity=generateSequence(context as Context) {
        (it as? ContextWrapper)?.baseContext
    }.filterIsInstance<Activity>().firstOrNull()
    val window=activity?.window
    val setupVisible=setup!=null && enabled
    DisposableEffect(window,setupVisible){
        val flag=WindowManager.LayoutParams.FLAG_SECURE
        val prior=window!=null && (window.attributes.flags and flag)!=0
        if(setupVisible) window?.addFlags(flag)
        onDispose {
            if(setupVisible && !prior)window?.clearFlags(flag)
        }
    }

    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Owner — Authenticator Security (MFA)",
            style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.Bold)
        Text("Before approving a Staff Phone key, verify a fresh 6-digit " +
            "code from your Authenticator app on the Owner account.",
            style=MaterialTheme.typography.bodySmall)
        if(!enabled){
            Text("Security Preview: Authenticator setup and verification " +
                "are disabled. No Owner passwords, MFA codes or secrets collected.",
                style=MaterialTheme.typography.bodySmall)
            Button(onClick={},enabled=false,
                modifier=Modifier.fillMaxWidth()) {
                Text("Owner MFA — Secure Server Activation Required")
            }
        }else{
            Button(onClick={
                busy=true
                code=""
                setup=null
                status=""
                scope.launch {
                    try {
                        verifiedFactors=client.verifiedOwnerTotpFactors(
                            CloudSyncConfig.OWNER_USER_ID)
                        status=if(verifiedFactors.isEmpty())
                            "No verified Authenticator found. Set up a factor first."
                        else "Authenticator found. Enter a fresh 6-digit code."
                    }catch(_:Exception){
                        status="Unable to confirm verified Owner Authenticator factors."
                    }finally{busy=false}
                }
            },enabled=!busy,modifier=Modifier.fillMaxWidth()){
                Text("Check Owner Authenticator")
            }
            if(verifiedFactors.isEmpty()){
                Button(onClick={
                    setup=null
                    code=""
                    status=""
                    busy=true
                    scope.launch {
                        try {
                            setup=client.beginOwnerTotpEnrollment(
                                CloudSyncConfig.OWNER_USER_ID)
                            status="Add this temporary key manually to your " +
                                "Authenticator, then verify its new code."
                        }catch(_:Exception){
                            status="Authenticator enrollment not accepted. " +
                                "Verify your Owner Cloud login."
                        }finally{busy=false}
                    }
                },enabled=!busy,modifier=Modifier.fillMaxWidth()){
                    Text("Set Up Owner Authenticator")
                }
            }
            if(setup!=null){
                Text("Temporary Setup Secret — Owner Only",
                    fontWeight=FontWeight.Bold)
                Text(setup!!.manualSecret)
                Text("Open an Authenticator app and add a time-based " +
                    "account manually with this secret. Keep the secret " +
                    "private, never screenshot or share it. " +
                    "It disappears from this screen after verification.",
                    style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={
                    val stale=setup
                    setup=null
                    code=""
                    status=""
                    busy=true
                    scope.launch {
                        try {
                            if(stale!=null && client.cancelPendingOwnerTotpEnrollment(
                                CloudSyncConfig.OWNER_USER_ID,stale.factorId)) {
                                status="Unverified Authenticator setup cancelled. " +
                                    "No Staff permissions changed."
                            }
                        }catch(_:Exception) {
                            status="Setup secret hidden, but cancellation is " +
                                "not confirmed by Supabase Auth. " +
                                "Check pending factors before creating more."
                        }finally{busy=false}
                    }
                },enabled=!busy){
                    Text("Cancel Pending Authenticator Setup")
                }
            }
            if(factor!=null) {
                OutlinedTextField(
                    value=code,
                    onValueChange={code=it.filter(Char::isDigit).take(6)},
                    label={Text("Current 6-Digit Authenticator Code")},
                    visualTransformation=PasswordVisualTransformation(),
                    singleLine=true,enabled=!busy,
                    modifier=Modifier.fillMaxWidth()
                )
                Button(onClick={
                    val selected=factor
                    val otp=code
                    if(!OwnerTotpMfaPolicy.validOtp(otp))return@Button
                    busy=true
                    code=""
                    status=""
                    scope.launch{
                        try{
                            val challenge=client.beginOwnerTotpChallenge(
                                CloudSyncConfig.OWNER_USER_ID,selected)
                            val upgraded=client.verifyOwnerTotpAndUpgradeSession(
                                CloudSyncConfig.OWNER_USER_ID,
                                selected,challenge,otp)
                            setup=null
                            verifiedFactors=emptyList()
                            status=if(upgraded)
                                "Supabase Auth verified your TOTP and securely " +
                                    "saved a new AAL2 Owner session. " +
                                    "Complete fingerprint approval promptly; " +
                                    "Server checks its own 5-minute MFA limit."
                            else "Authenticator verification not confirmed."
                        }catch(_:Exception){
                            status="Code not verified or session expired. " +
                                "Check Authenticator and Owner login, then retry."
                        }finally{busy=false}
                    }
                },enabled=!busy && OwnerTotpMfaPolicy.validOtp(code),
                    modifier=Modifier.fillMaxWidth()){
                    Text("Verify Owner MFA — Upgrade Secure Session")
                }
            }
        }
        if(status.isNotBlank())Text(status,
            style=MaterialTheme.typography.bodySmall)
    }
}
