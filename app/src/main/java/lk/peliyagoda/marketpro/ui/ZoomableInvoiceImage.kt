package lk.peliyagoda.marketpro.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** Display-only pinch zoom and pan; never alters original JPEG bytes. */
@Composable
internal fun ZoomableInvoiceImage(bitmap: Bitmap, description: String) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offsetX by remember(bitmap) { mutableFloatStateOf(0f) }
    var offsetY by remember(bitmap) { mutableFloatStateOf(0f) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = description,
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    if (newScale <= 1f) {
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        val boundX = size.width * (newScale - 1f) / 2f
                        val boundY = size.height * (newScale - 1f) / 2f
                        offsetX = (offsetX + pan.x).coerceIn(-boundX, boundX)
                        offsetY = (offsetY + pan.y).coerceIn(-boundY, boundY)
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
                clip = true
            },
        contentScale = ContentScale.Fit
    )
}
