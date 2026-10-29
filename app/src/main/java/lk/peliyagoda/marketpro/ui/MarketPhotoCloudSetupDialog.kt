package lk.peliyagoda.marketpro.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lk.peliyagoda.marketpro.cloud.CloudPhoneRegistryClient
import lk.peliyagoda.marketpro.cloud.MarketProActiveRole
import lk.peliyagoda.marketpro.cloud.TeamRole
import lk.peliyagoda.marketpro.photo.MarketPhotoGatewayClient

/** Owner approves the exact Team photo key; never grants access by a device ID alone. */
@Composable
internal fun MarketPhotoCloudSetupDialog(onDismiss:()->Unit){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val role=MarketProActiveRole.current(context.applicationContext)
    val isOwner=role==TeamRole.OWNER
    val gateway=remember(context){MarketPhotoGatewayClient(context.applicationContext)}
    val registry=remember(context){CloudPhoneRegistryClient(context.applicationContext)}
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("Phone Photo Key setup is separate from Box account connection.")}
    var phones by remember{mutableStateOf<List<CloudPhoneRegistryClient.Phone>>(emptyList())}
    fun execute(action:suspend()->String){
        if(busy)return
        busy=true
        scope.launch {
            try{status=action()}catch(e:Exception){status=e.message?.take(160)?:"Photo setup failed"}
            finally{busy=false}
        }
    }
    AlertDialog(
        onDismissRequest={if(!busy)onDismiss()},
        title={Text("Photo Cloud Security")},
        text={
            Column(
                modifier=Modifier.fillMaxWidth().heightIn(max=530.dp).verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(10.dp)
            ){
                Text("Only approved Market Pro phones may view or upload Box photos.")
                if(isOwner){
                    Text("Owner: verify a fresh Authenticator code before registering or approving photo keys.")
                    OwnerTotpMfaPanel()
                }else{
                    Text("Team: register this phone's unique Photo Key. Owner must approve it.")
                }
                Button(onClick={execute{gateway.enrollThisPhone()}},
                    enabled=!busy,modifier=Modifier.fillMaxWidth()){
                    Text("Register This Phone Photo Key")
                }
                if(isOwner){
                    OutlinedButton(
                        onClick={execute{
                            phones=registry.listPhones().phones.filter{
                                it.role=="editor"&&it.enabled
                            }
                            "Found ${phones.size} Team phone(s). Approve only recognized phones."
                        }},
                        enabled=!busy,modifier=Modifier.fillMaxWidth()
                    ){Text("Review Team Photo Keys")}
                    phones.forEach {phone->
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text(phone.label)
                            OutlinedButton(
                                enabled=!busy,
                                onClick={execute{gateway.approveTeamPhotoKey(phone.id)}}
                            ){Text("Approve Photo Key")}
                        }
                    }
                }
                Text(status)
                Text("Original images stay on the capture phone. Box tokens stay on the server.")
            }
        },
        confirmButton={
            TextButton(onClick=onDismiss,enabled=!busy){Text("Close")}
        }
    )
}
