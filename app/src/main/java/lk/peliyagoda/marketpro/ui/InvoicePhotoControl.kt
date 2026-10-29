package lk.peliyagoda.marketpro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import lk.peliyagoda.marketpro.data.MarketDatabase
import lk.peliyagoda.marketpro.photo.MarketPhotoLocalStore
import lk.peliyagoda.marketpro.photo.MarketPhotoGatewayClient
import lk.peliyagoda.marketpro.photo.MarketPhotoSyncScheduler
import lk.peliyagoda.marketpro.photo.MarketPhotoIdentity
import lk.peliyagoda.marketpro.cloud.MarketProActiveRole
import java.io.File
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Original images are written by the device camera directly into filesDir,
 * without re-encoding, resizing, compression or re-saving inside this app.
 * Private file storage is lost on uninstall and is NOT part of JSON backups.
 */
internal object InvoicePhotoStore {
    private fun folder(context: Context, day: String, itemId: Long): File {
        require(LocalDate.parse(day).year in 2000..2100 && itemId > 0)
        return File(File(File(context.filesDir, "invoices"), day), "item-$itemId")
    }

    fun photos(context: Context, day: String, itemId: Long): List<File> {
        val dir = folder(context, day, itemId)
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("jpg", true) && it.length() > 0L }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun newPhoto(context: Context, day: String, itemId: Long): File {
        val dir = folder(context, day, itemId)
        check(dir.exists() || dir.mkdirs()) { "Cannot create invoice photo folder" }
        return File.createTempFile("invoice_${System.currentTimeMillis()}_", ".jpg", dir)
    }

    fun cameraUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.invoiceprovider", file)

    /** Decode only a display-sized preview in RAM, without modifying the original JPEG. */
    fun preview(file: File, maxPixels: Int = 1024): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var factor = 1
        // Decode only screen-sized pixels; the saved camera original is unchanged.
        while (bounds.outWidth / factor > maxPixels * 3 / 2 ||
               bounds.outHeight / factor > maxPixels * 3 / 2) {
            factor *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = factor
            // Camera invoices do not need a 32-bit alpha channel for preview.
            // Halves preview bitmap RAM without re-encoding original JPEGs.
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }
}

@Composable
fun InvoicePhotoControl(
    businessDate: String,
    itemId: Long,
    itemName: String,
    onPhotoSaved: () -> Unit = {},
    message: (String) -> Unit
) {
    val context = LocalContext.current
    val photoScope = rememberCoroutineScope()
    val photoStore = remember(context) {
        MarketPhotoLocalStore(context.applicationContext, MarketDatabase.get(context))
    }
    var refresh by remember(businessDate, itemId) { mutableIntStateOf(0) }
    var photoDialog by remember(businessDate, itemId) { mutableStateOf(false) }
    var cloudBrowseOpen by remember(businessDate) { mutableStateOf(false) }
    var fullPreview by remember(businessDate, itemId) { mutableStateOf<File?>(null) }
    var pendingPath by rememberSaveable(businessDate, itemId) { mutableStateOf("") }

    // Per-card directory reads are expensive while fling-scrolling, even
    // when the preview dialog is closed. Run the I/O outside the UI thread.
    val photos by produceState<List<File>?>(
        initialValue = null, context, businessDate, itemId, refresh
    ) {
        value = withContext(Dispatchers.IO) {
            InvoicePhotoStore.photos(context, businessDate, itemId)
        }
    }

    var cloudPhotos by remember(businessDate,itemId) {
        mutableStateOf<List<MarketPhotoGatewayClient.CloudPhoto>>(emptyList())
    }
    var loadingCloud by remember(businessDate,itemId) { mutableStateOf(false) }
    var cloudNotice by remember(businessDate,itemId) { mutableStateOf("") }
    val gateway=remember(context) {MarketPhotoGatewayClient(context.applicationContext)}

    // The cloud item's canonical UUID comes from the existing Cloud identity journal.
    // Never substitute local Room itemId as a Box photo filter.
    LaunchedEffect(photoDialog,businessDate,itemId,refresh) {
        if(photoDialog){
            loadingCloud=true
            cloudNotice=""
            val result=runCatching{
                val uuid=MarketDatabase.get(context).cloudDao()
                    .cloudId("item",itemId.toString())
                if(uuid==null) {
                    cloudNotice = "Item not linked to Cloud ID yet. Use View Cloud Photos for this date."
                    emptyList()
                } else gateway.list(uuid,businessDate)
            }
            result.onSuccess {
                cloudPhotos=it
                if(it.isEmpty() && cloudNotice.isBlank())
                    cloudNotice="No Box photos linked to this item/date. Browse the date gallery if needed."
            }
                .onFailure {
                    cloudPhotos=emptyList()
                    cloudNotice="Cloud Photos Unavailable. Local Originals Are Still Safe."
                }
            loadingCloud=false
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPath.takeIf { it.isNotBlank() }?.let(::File)
        pendingPath = ""
        if (ok && file != null && file.exists() && file.length() > 0L) {
            // Persist the completion-photo timestamp on the original file,
            // so the 1-minute delay survives navigation and app restarts.
            file.setLastModified(System.currentTimeMillis())
            refresh++
            photoDialog = true
            onPhotoSaved()
            message("Full-Resolution Original Photo Saved")
            photoScope.launch {
                runCatching {
                    photoStore.registerCapturedOriginal(businessDate, itemId, file)
                }.onSuccess { result ->
                    MarketPhotoSyncScheduler.onPhotoSaved(context)
                    if (result.gallerySaved) {
                        message("Original And Gallery Copy Saved")
                    } else {
                        message("Original Saved; Gallery Copy Unavailable")
                    }
                }.onFailure {
                    message("Original Photo Saved; Gallery Registration Pending")
                }
            }
        } else {
            // Camera cancellations must not leave empty placeholder JPEG files.
            file?.takeIf { it.length() == 0L }?.delete()
            message("Photo Was Not Captured")
        }
    }

    // Lazy idempotent registration of older local photos; no global disk scan.
    LaunchedEffect(photoDialog, businessDate, itemId) {
        if (photoDialog) {
            withContext(Dispatchers.IO) {
                InvoicePhotoStore.photos(context, businessDate, itemId).forEach { original ->
                    runCatching {
                        photoStore.registerCapturedOriginal(businessDate, itemId, original)
                    }
                }
            }
        }
    }

    fun captureOriginal() {
        runCatching {
            MarketProActiveRole.requireBusinessDateEditable(context.applicationContext,businessDate)
            val file = InvoicePhotoStore.newPhoto(context, businessDate, itemId)
            pendingPath = file.absolutePath
            camera.launch(InvoicePhotoStore.cameraUri(context, file))
        }.onFailure {
            pendingPath.takeIf { it.isNotBlank() }?.let(::File)
                ?.takeIf { it.exists() && it.length() == 0L }?.delete()
            pendingPath = ""
            message("Photo Capture Not Allowed Or Camera Unavailable")
        }
    }

    // Never draw images inside the purchase card, only the compact camera action.
    IconButton(
        enabled = photos != null,
        onClick = {
            photoDialog = true
        }
    ) {
        Icon(
            if (photos.isNullOrEmpty()) Icons.Default.CameraAlt else Icons.Default.PhotoLibrary,
            contentDescription = "Invoice Photos (${photos?.size ?: 0}) For $itemName",
            tint = ProColors.teal
        )
    }

    if (photoDialog) {
        AlertDialog(
            onDismissRequest = { photoDialog = false },
            title = { Text("Invoice Photos • $itemName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Original Photos Are Saved On This Device.",
                        color = ProColors.muted,
                        style = MaterialTheme.typography.bodySmall)
                    if (loadingCloud) Text("Checking Cloud Photos...")
                    if (cloudNotice.isNotBlank()) Text(cloudNotice)
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(cloudPhotos.filterNot { remote ->
                            photos.orEmpty().any { local ->
                                runCatching {
                                    MarketPhotoIdentity.id(local,businessDate,itemId)==remote.id
                                }.getOrDefault(false)
                            }
                        }, key = { it.id }) { entry ->
                            val downloadScope=rememberCoroutineScope()
                            OutlinedButton(
                                modifier=Modifier.fillMaxWidth(),
                                onClick={
                                    downloadScope.launch {
                                        runCatching{gateway.download(entry)}
                                            .onSuccess {fullPreview=it}
                                            .onFailure {message("Could Not Load Box Photo")}
                                    }
                                }
                            ){
                                Icon(Icons.Default.PhotoLibrary,contentDescription=null)
                                Text(" Box Photo • ${entry.capturedAt.take(16)}")
                            }
                        }
                        items(photos.orEmpty(), key = { it.absolutePath }) { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { fullPreview = file }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val thumb by produceState<Bitmap?>(
                                    initialValue = null, file.absolutePath
                                ) {
                                    value = withContext(Dispatchers.IO) {
                                        InvoicePhotoStore.preview(file, 160)
                                    }
                                }
                                val thumbnail = thumb
                                if (thumbnail != null) {
                                    Image(
                                        bitmap = thumbnail.asImageBitmap(),
                                        contentDescription = "Invoice Thumbnail",
                                        modifier = Modifier.size(62.dp)
                                            .clip(RoundedCornerShape(9.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(Icons.Default.PhotoLibrary,
                                        contentDescription = null,
                                        modifier = Modifier.size(62.dp))
                                }
                                Column {
                                    Text(file.nameWithoutExtension.take(34),
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("${file.length() / 1024} KB • Tap To Preview",
                                        color = ProColors.muted,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        item {
                            OutlinedButton(
                                onClick = { photoDialog = false; captureOriginal() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Text(" + Capture Another Photo")
                            }
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            photoDialog = false
                            cloudBrowseOpen = true
                        }
                    ) { Text("View All Cloud Photos For This Date") }
                    Text("Note: JSON Backup Does Not Include Invoice Images.",
                        color = ProColors.muted,
                        style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { photoDialog = false }) { Text("Close") }
            }
        )
    }

    if (cloudBrowseOpen) {
        CloudPhotoGalleryDialog(
            initialDate = businessDate,
            onDismiss = { cloudBrowseOpen = false }
        )
    }

    fullPreview?.let { file ->
        AlertDialog(
            onDismissRequest = { fullPreview = null },
            title = { Text("Invoice Photo") },
            text = {
                val image by produceState<Bitmap?>(
                    initialValue = null, file.absolutePath
                ) {
                    value = withContext(Dispatchers.IO) {
                        InvoicePhotoStore.preview(file, 1400)
                    }
                }
                val previewBitmap = image
                if (previewBitmap == null) {
                    Text("Loading Invoice Preview…")
                } else {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Captured Invoice Photo",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { fullPreview = null }) { Text("Back To Photos") }
            }
        )
    }
}
