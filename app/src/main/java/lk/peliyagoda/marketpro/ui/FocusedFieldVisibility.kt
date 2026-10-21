package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * V2.21: the parent LazyColumn must own the scroll correction, not only
 * the child TextField. In V2.20 the latter could run before the IME resized
 * the viewport and a last/near-last item remained obscured.
 *
 * Listen to IME inset updates, wait for the keyboard animation to settle, then
 * place the item inside the resized list. Only scroll when a field is focused
 * AND the keyboard is actually open. A negative offset reserves space for the
 * pinned category filters on Market; Kitchens has no pinned category filter.
 */
@Composable
internal fun FocusedListKeyboardScroll(
    listState: LazyListState,
    focusedIndex: Int?,
    topClearance: Dp = 12.dp
) {
    val density = LocalDensity.current
    LaunchedEffect(listState, focusedIndex, topClearance, density) {
        if (focusedIndex == null) return@LaunchedEffect
        snapshotFlow { WindowInsets.ime.getBottom(density) }
            .distinctUntilChanged()
            .collectLatest { keyboardHeight ->
                if (keyboardHeight > with(density) { 90.dp.roundToPx() }) {
                    // collectLatest cancels this wait while IME frames keep changing.
                    delay(240L)
                    if (focusedIndex < listState.layoutInfo.totalItemsCount) {
                        listState.animateScrollToItem(
                            focusedIndex,
                            scrollOffset = -with(density) { topClearance.roundToPx() }
                        )
                    }
                }
            }
    }
}

/**
 * Field-level secondary protection: requests the exact focused input after
 * its parent scrolls, especially important for Additional Purchase editors
 * inside cards taller than the visible keyboard-adjusted viewport.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.keepFocusedFieldVisible(
    topClearance: Dp = 14.dp
): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val keyboardHeight = WindowInsets.ime.getBottom(density)
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(focused, keyboardHeight, size, topClearance) {
        if (focused && size.width > 0 && size.height > 0) {
            // Run after keyboard + parent LazyColumn correction; avoid races.
            delay(if (keyboardHeight > 0) 440L else 50L)
            val top = with(density) { topClearance.toPx() }
            val bottom = with(density) { 22.dp.toPx() }
            requester.bringIntoView(
                Rect(
                    left = 0f,
                    top = -top,
                    right = size.width.toFloat(),
                    bottom = size.height.toFloat() + bottom
                )
            )
        }
    }

    return this
        .bringIntoViewRequester(requester)
        .onSizeChanged { size = it }
        .onFocusChanged { focused = it.hasFocus }
}
