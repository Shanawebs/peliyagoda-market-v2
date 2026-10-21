package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * V2.20: actively bring the focused numeric field fully into its LazyColumn
 * viewport AFTER the keyboard has animated into place.
 *
 * The Market list pins its category filter at the top. Asking for a rectangle
 * that extends ABOVE the input reserves room for that filter, avoiding the
 * focused field disappearing underneath it. The lower margin keeps the field
 * from touching the keyboard edge.
 *
 * This changes scrolling only. It does not mutate saved quantities, prices,
 * dates, market sorting, or the saved list-state when changing tabs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.keepFocusedFieldVisible(
    topClearance: Dp = 14.dp
): Modifier {
    val bringRequester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val keyboardBottomPx = WindowInsets.ime.getBottom(density)
    var focused by remember { mutableStateOf(false) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }

    // Keyboard insets update throughout the IME animation. The effect restarts
    // on every change; the final request runs after the viewport has settled.
    // A physical keyboard (0px IME) is supported by the same focus request.
    LaunchedEffect(focused, keyboardBottomPx, fieldSize, topClearance) {
        if (focused && fieldSize.width > 0 && fieldSize.height > 0) {
            delay(90L)
            val topPx = with(density) { topClearance.toPx() }
            val bottomPx = with(density) { 18.dp.toPx() }
            bringRequester.bringIntoView(
                Rect(
                    left = 0f,
                    top = -topPx,
                    right = fieldSize.width.toFloat(),
                    bottom = fieldSize.height.toFloat() + bottomPx
                )
            )
        }
    }

    return this
        .bringIntoViewRequester(bringRequester)
        .onSizeChanged { fieldSize = it }
        .onFocusChanged { focused = it.hasFocus }
}
