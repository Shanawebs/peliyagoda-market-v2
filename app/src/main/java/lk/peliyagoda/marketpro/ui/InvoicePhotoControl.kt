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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
        while (bounds.outWidth / factor > maxPixels * 2 ||
               bounds.outHeight / factor > maxPixels * 2) {
            factor *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = factor
        })
    }
}

@Composable
fun InvoicePhotoControl(
    businessDate: String,
    itemId: Long,
    itemName: String,
    message: (String) -> Unit
) {
    val context = LocalContext.current
    var refresh by remember(businessDate, itemId) { mutableIntStateOf(0) }
    var photoDialog by remember(businessDate, itemId) { mutableStateOf(false) }
    var fullPreview by remember(businessDate, itemId) { mutableStateOf<File?>(null) }
    var pendingPath by rememberSaveable(businessDate, itemId) { mutableStateOf("") }

    val photos = remember(context, businessDate, itemId, refresh) {
        InvoicePhotoStore.photos(context, businessDate, itemId)
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPath.takeIf { it.isNotBlank() }?.let(::File)
        pendingPath = ""
        if (ok && file != null && file.exists() && file.length() > 0L) {
            refresh++
            photoDialog = true
            message("Full-resolution invoice photo saved")
        } else {
            // Camera cancellations must not leave empty placeholder JPEG files.
            file?.takeIf { it.length() == 0L }?.delete()
            message("Photo was not captured")
        }
    }

    fun captureOriginal() {
        runCatching {
            val file = InvoicePhotoStore.newPhoto(context, businessDate, itemId)
            pendingPath = file.absolutePath
            camera.launch(InvoicePhotoStore.cameraUri(context, file))
        }.onFailure {
            pendingPath.takeIf { it.isNotBlank() }?.let(::File)
                ?.takeIf { it.exists() && it.length() == 0L }?.delete()
            pendingPath = ""
            message("Could not open the camera")
        }
    }

    // Never draw images inside the purchase card, only the compact camera action.
    IconButton(onClick = {
        if (photos.isEmpty()) captureOriginal() else photoDialog = true
    }) {
        Icon(
            if (photos.isEmpty()) Icons.Default.CameraAlt else Icons.Default.PhotoLibrary,
            contentDescription = "Invoice photos (${photos.size}) for $itemName",
            tint = ProColors.teal
        )
    }

    if (photoDialog) {
        AlertDialog(
            onDismissRequest = { photoDialog = false },
            title = { Text("Invoice photos • $itemName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Original photos are saved on this device.",
                        color = ProColors.muted,
                        style = MaterialTheme.typography.bodySmall)
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(photos, key = { it.absolutePath }) { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { fullPreview = file }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val thumb = remember(file.absolutePath) {
                                    InvoicePhotoStore.preview(file, 160)
                                }
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = "Invoice thumbnail",
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
                                    Text("${file.length() / 1024} KB • Tap to preview",
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
                                Text(" + Capture another photo")
                            }
                        }
                    }
                    Text("Note: JSON backup does not include invoice images.",
                        color = ProColors.muted,
                        style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { photoDialog = false }) { Text("Close") }
            }
        )
    }

    fullPreview?.let { file ->
        AlertDialog(
            onDismissRequest = { fullPreview = null },
            title = { Text("Invoice photo") },
            text = {
                val image = remember(file.absolutePath) {
                    InvoicePhotoStore.preview(file, 1400)
                }
                if (image == null) {
                    Text("Unable to display the image")
                } else {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "Captured invoice photo",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { fullPreview = null }) { Text("Back to photos") }
            }
        )
    }
}
