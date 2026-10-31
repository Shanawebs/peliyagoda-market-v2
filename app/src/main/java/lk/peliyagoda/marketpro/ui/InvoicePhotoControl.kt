package lk.peliyagoda.marketpro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.sync.Mutex
import lk.peliyagoda.marketpro.data.MarketDatabase
import lk.peliyagoda.marketpro.photo.MarketPhotoLocalStore
import lk.peliyagoda.marketpro.photo.MarketPhotoGatewayClient
import lk.peliyagoda.marketpro.photo.MarketPhotoSyncScheduler
import lk.peliyagoda.marketpro.photo.MarketPhotoIdentity
import lk.peliyagoda.marketpro.photo.MarketPhotoCardMatchPolicy
import lk.peliyagoda.marketpro.photo.PhotoCardMatchStatus
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
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = factor
            // Camera invoices do not need a 32-bit alpha channel for preview.
            // Halves preview bitmap RAM without re-encoding original JPEGs.
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return null
        val rotation = runCatching {
            when(ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if(rotation == 0f) return decoded
        return runCatching {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if(it !== decoded) decoded.recycle() }
        }.getOrElse { decoded }
    }
}

@Composable
internal fun InvoicePhotoControl(
    businessDate: String,
    itemId: Long,
    itemName: String,
    knownCloudPhotos: List<MarketPhotoGatewayClient.CloudPhoto> = emptyList(),
    onPhotoSaved: () -> Unit = {},
    message: (String) -> Unit
) {
    val context = LocalContext.current
    val photoScope = rememberCoroutineScope()
    val photoStore = remember(context) {
        MarketPhotoLocalStore(context.applicationContext, MarketDatabase.get(context))
    }
    var refresh by remember(businessDate, itemId) { mutableIntStateOf(0) }
    var cloudRefresh by remember(businessDate, itemId) { mutableIntStateOf(0) }
    var photoDialog by remember(businessDate, itemId) { mutableStateOf(false) }
    var cloudBrowseOpen by remember(businessDate, itemId) { mutableStateOf(false) }
    var fullPreview by remember(businessDate, itemId) { mutableStateOf<File?>(null) }
    var selectedCloudPhoto by remember(businessDate, itemId) { mutableStateOf<MarketPhotoGatewayClient.CloudPhoto?>(null) }
    var deleteConfirmation by remember(businessDate, itemId) { mutableStateOf(false) }
    var deletingPhoto by remember(businessDate, itemId) { mutableStateOf(false) }
    var selectedOriginal by remember(businessDate, itemId) { mutableStateOf<File?>(null) }
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

    var cloudPhotos by remember(businessDate, itemId) {
        mutableStateOf(knownCloudPhotos)
    }
    var loadingCloud by remember(businessDate, itemId) { mutableStateOf(false) }
    var cloudNotice by remember(businessDate, itemId) { mutableStateOf("") }
    val gateway = remember(context) { MarketPhotoGatewayClient(context.applicationContext) }
    // Serialize thumbnail downloads to limit mobile bandwidth.
    val thumbnailMutex = remember(gateway) { Mutex() }

    // Photo Card uses the same authenticated server date-gallery as the menu
    // previously did. Local UUID lookup is OPTIONAL; if Room lookup fails on a
    // Samsung/Team phone, it may not block secure read-only cloud browsing.
    LaunchedEffect(photoDialog, businessDate, itemId, itemName, cloudRefresh) {
        if (photoDialog) {
            loadingCloud = true
            cloudNotice = ""
            if (knownCloudPhotos.isNotEmpty()) cloudPhotos = knownCloudPhotos
            runCatching {
                val dayPhotos = gateway.listDay(businessDate)
                val linkedId = withContext(Dispatchers.IO) {
                    runCatching {
                        MarketDatabase.get(context).cloudDao()
                            .cloudId("item", itemId.toString())
                    }.getOrNull()
                }
                MarketPhotoCardMatchPolicy.match(dayPhotos, linkedId, itemName)
            }.onSuccess { match ->
                cloudPhotos = match.photos
                cloudNotice = when {
                    match.photos.isNotEmpty() -> ""
                    match.status == PhotoCardMatchStatus.AMBIGUOUS ->
                        "More than one Cloud item uses this name. Owner must verify its identity."
                    else -> "No Cloud photos found for this item on the selected date."
                }
            }.onFailure { error ->
                // Preserve already fetched cloud metadata and all local photos.
                // Never reveal authentication secrets, request bodies or tokens.
                cloudNotice = if (cloudPhotos.isNotEmpty()) {
                    "Showing last synced Cloud photo list. Refresh was unsuccessful."
                } else {
                    val detail = when (error) {
                        is java.io.IOException ->
                            error.message?.take(110) ?: "Connection problem"
                        is org.json.JSONException -> "Unexpected Cloud response format"
                        else -> error.javaClass.simpleName.take(48)
                    }
                    "Cloud photos could not load ($detail). Local photos remain safe."
                }
            }
            loadingCloud = false
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
                    // The original JPEG remains safe even when optional
                    // Android Gallery registration fails. Worker will
                    // discover and queue it from app-private storage.
                    MarketPhotoSyncScheduler.onPhotoSaved(context)
                    message("Original Saved; Cloud Sync Will Retry")
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

    // Keep one compact photo view icon inside the existing purchase card.
    IconButton(
        enabled = photos != null,
        onClick = {
            photoDialog = true
        }
    ) {
        Icon(
            if (MarketPhotoCardMatchPolicy.hasAnyPhoto(
                photos?.size ?: 0, maxOf(knownCloudPhotos.size, cloudPhotos.size)
            )) Icons.Default.PhotoLibrary else Icons.Default.CameraAlt,
            contentDescription = "Invoice Photos (${photos?.size ?: 0} local, ${cloudPhotos.size} cloud) For $itemName",
            tint = ProColors.teal
        )
    }

    if (photoDialog) {
        AlertDialog(
            onDismissRequest = { photoDialog = false },
            title = { Text("Invoice Photos • $itemName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Item Photos • This Phone And Approved Cloud",
                        color = ProColors.muted,
                        style = MaterialTheme.typography.bodySmall)
                    if (loadingCloud) Text("Checking Cloud Photos...")
                    if (cloudNotice.isNotBlank()) Text(cloudNotice)
                    if (!loadingCloud) {
                        TextButton(onClick = { cloudRefresh++ }) {
                            Text("Refresh Cloud Photos")
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            cloudPhotos.filterNot { remote ->
                                photos.orEmpty().any { local ->
                                    runCatching {
                                        MarketPhotoIdentity.id(local,businessDate,itemId)==remote.id
                                    }.getOrDefault(false)
                                }
                            }, key = { _, photo -> photo.id }
                        ) { index, entry ->
                            MarketCloudPhotoRow(
                                photo = entry,
                                position = index,
                                gateway = gateway,
                                mutex = thumbnailMutex,
                                showItemName = false,
                                // Avoid silently downloading an entire large
                                // date's gallery over mobile data. First four
                                // visible receipts have previews; all remain
                                // tappable for individual verified downloads.
                                autoThumbnail = index < 4,
                                onClick = {
                                    photoScope.launch {
                                        runCatching { gateway.download(entry) }
                                            .onSuccess { selectedOriginal = null; selectedCloudPhoto = entry; fullPreview = it }
                                            .onFailure { message("Could Not Load Cloud Photo") }
                                    }
                                }
                            )
                        }
                        items(photos.orEmpty(), key = { it.absolutePath }) { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        selectedOriginal = file
                                        selectedCloudPhoto = cloudPhotos.firstOrNull { cloud ->
                                            runCatching { MarketPhotoIdentity.id(file,businessDate,itemId)==cloud.id }.getOrDefault(false)
                                        }
                                        fullPreview = file
                                    }
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
                    ) {
                        Text("View All Cloud Photos For This Date")
                    }
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

    // Date-wise, read-only Cloudflare R2 gallery from this card.
    // Its signed Cloud API and previous/next day navigation stay unchanged.
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
                    ZoomableInvoiceImage(previewBitmap, "Captured Invoice Photo")
                }
            },
            dismissButton = {
                TextButton(enabled = !deletingPhoto && selectedCloudPhoto != null,
                    onClick = { deleteConfirmation = true }) {
                    Text("Delete Photo", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !deletingPhoto, onClick = { fullPreview = null }) {
                    Text("Back To Photos")
                }
            }
        )
    }
    if (deleteConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!deletingPhoto) deleteConfirmation = false },
            title = { Text("Delete Invoice Photo?") },
            text = { Text("Remove this photo from Admin and Subs Cloud Galleries? Admin may delete any date; Subs may delete only their own photo from today. Permission is checked by Cloud.") },
            dismissButton = {
                TextButton(enabled = !deletingPhoto, onClick = { deleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(enabled = !deletingPhoto, onClick = {
                    val cloud = selectedCloudPhoto
                    if (cloud != null) {
                        deletingPhoto = true
                        photoScope.launch {
                            try {
                                check(gateway.deletePhoto(cloud.id))
                                val original = selectedOriginal
                                if (original != null && original.isFile) {
                                    withContext(Dispatchers.IO) {
                                        val trash = File(context.filesDir, "photo_deleted_backup")
                                        if (trash.isDirectory || trash.mkdirs()) {
                                            original.renameTo(File(trash, cloud.id + ".jpg"))
                                        }
                                    }
                                }
                                cloudPhotos = cloudPhotos.filterNot { it.id == cloud.id }
                                fullPreview = null
                                selectedCloudPhoto = null
                                selectedOriginal = null
                                refresh++
                                cloudRefresh++
                                message("Photo Removed From Cloud")
                            } catch (_: Exception) {
                                message("Delete Failed Or Permission Denied")
                            } finally {
                                deletingPhoto = false
                                deleteConfirmation = false
                            }
                        }
                    }
                }) { Text(if (deletingPhoto) "Deleting..." else "Delete Photo",
                    color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}
