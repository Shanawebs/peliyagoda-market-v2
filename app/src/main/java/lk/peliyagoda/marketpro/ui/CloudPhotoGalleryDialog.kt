package lk.peliyagoda.marketpro.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lk.peliyagoda.marketpro.photo.MarketPhotoGatewayClient
import java.io.File
import java.time.LocalDate

/**
 * Read-only, signed Box photo browser. A photo belongs to a canonical cloud
 * item, regardless of either phone's local Room numeric item ID and UUID links.
 * Never clears data, writes a local purchase, or exposes Box file URLs/tokens.
 */
@Composable
internal fun CloudPhotoGalleryDialog(
    initialDate: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val gateway = remember(context) { MarketPhotoGatewayClient(context) }
    val scope = rememberCoroutineScope()
    var day by remember(initialDate) { mutableStateOf(LocalDate.parse(initialDate)) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var photos by remember(day) {
        mutableStateOf<List<MarketPhotoGatewayClient.CloudPhoto>>(emptyList())
    }
    var notice by remember(day) { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var selectedName by remember { mutableStateOf("") }

    LaunchedEffect(day, refresh) {
        loading = true
        notice = ""
        photos = emptyList()
        runCatching { gateway.listDay(day.toString()) }
            .onSuccess { rows ->
                photos = rows
                if (rows.isEmpty()) notice = "No Cloud photos for this date."
            }
            .onFailure {
                notice = "Could not retrieve Cloud photos. Verify Team login, " +
                    "approved Phone Photo Key and Internet connection."
            }
        loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Cloud Photo Gallery") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Original-quality Box photos • View only",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(enabled = !loading && !downloading,
                        onClick = { day = day.minusDays(1) }) { Text("Previous") }
                    Text(day.toString(), fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 13.dp))
                    TextButton(enabled = !loading && !downloading,
                        onClick = { day = day.plusDays(1) }) { Text("Next") }
                }
                if (loading) CircularProgressIndicator()
                if (notice.isNotBlank()) Text(notice)
                if (!loading) {
                    Text("${photos.size} Cloud photo(s)",
                        style = MaterialTheme.typography.labelMedium)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(photos, key = { it.id }) { photo ->
                        OutlinedButton(
                            enabled = !downloading,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                downloading = true
                                notice = ""
                                scope.launch {
                                    try {
                                        val file = gateway.download(photo)
                                        selectedName = photo.itemName ?: "Invoice Photo"
                                        selectedFile = file
                                    } catch (e: Exception) {
                                        notice = "Could not download this Box photo. " +
                                            "The original on the capture phone is unchanged."
                                    } finally {
                                        downloading = false
                                    }
                                }
                            }
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(photo.itemName ?: "Unknown Item",
                                    fontWeight = FontWeight.Bold)
                                Text(
                                    "${photo.capturedAt.take(16)} • ${photo.bytes / 1024} KB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                if (downloading) Text("Downloading and verifying original JPEG...")
                Button(
                    enabled = !loading && !downloading,
                    onClick = { refresh++ },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Refresh Cloud Photos") }
            }
        },
        confirmButton = {
            TextButton(enabled = !downloading,onClick = onDismiss) { Text("Close") }
        }
    )

    selectedFile?.let { file ->
        AlertDialog(
            onDismissRequest = { selectedFile = null },
            title = { Text(selectedName) },
            text = {
                val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
                    value = withContext(Dispatchers.IO) {
                        InvoicePhotoStore.preview(file, 1100)
                    }
                }
                if (bitmap == null) {
                    Text("Loading verified original photo...")
                } else {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Cloud invoice photo",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedFile = null }) { Text("Back To Gallery") }
            }
        )
    }
}
