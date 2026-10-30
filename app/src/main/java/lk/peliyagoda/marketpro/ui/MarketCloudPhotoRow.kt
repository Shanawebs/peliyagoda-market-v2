package lk.peliyagoda.marketpro.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import lk.peliyagoda.marketpro.photo.MarketPhotoGatewayClient

/**
 * Visible cloud card mirrors local captured photos with a verified thumbnail.
 * Downloads are serialized and limited to a few visible rows per dialog.
 * Full-size JPEG originals are unchanged and SHA-256 checked before preview.
 */
@Composable
internal fun MarketCloudPhotoRow(
    photo: MarketPhotoGatewayClient.CloudPhoto,
    position: Int,
    gateway: MarketPhotoGatewayClient,
    mutex: Mutex,
    showItemName: Boolean,
    autoThumbnail: Boolean,
    onClick: () -> Unit
) {
    val thumbnail by produceState<Bitmap?>(
        initialValue = null, photo.id, photo.sha256, autoThumbnail
    ) {
        if (autoThumbnail) {
            mutex.lock()
            try {
                val verified = gateway.download(photo)
                value = withContext(Dispatchers.IO) {
                    InvoicePhotoStore.preview(verified, 160)
                }
            } catch (_: Exception) {
                // Clicking the row still provides a fresh, checksum-verified
                // full photo request; thumbnails are never mandatory.
            } finally {
                mutex.unlock()
            }
        }
    }

    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = "Verified Photo ${position + 1}",
                    modifier = Modifier.size(62.dp).clip(RoundedCornerShape(9.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = "Photo ${position + 1}",
                    modifier = Modifier.size(62.dp)
                )
            }
            Column {
                if (showItemName) {
                    Text(photo.itemName ?: "Unknown Item",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    MarketCloudPhotoLabels.title(position, photo.capturedAt),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    MarketCloudPhotoLabels.detail(photo.bytes, photo.id),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
