package com.metronomekiller.app.metronome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A vertical scroll wheel (like an alarm-clock picker) that snaps to one item.
 *
 * LEARN: it's a LazyColumn (a scrolling list that only draws visible rows) with
 * padding of one row above and below, so the first and last items can sit in
 * the middle. The snap fling behaviour makes it stop centred on an item.
 */
@Composable
fun <T> Wheel(
    items: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
) {
    val rowHeight = 44.dp
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = items.indexOf(selected).coerceAtLeast(0))
    val fling = rememberSnapFlingBehavior(listState, SnapPosition.Center)
    val scope = rememberCoroutineScope()
    val select by rememberUpdatedState(onSelected)

    // LEARN: derivedStateOf recomputes only when the scroll position changes,
    // and only triggers a redraw when the *result* changes.
    val centered by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: 0
        }
    }

    // Report the choice once a scroll settles — but only scrolls that actually
    // happened, so merely opening the sheet never overwrites a custom value.
    var scrolled by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) scrolled = true
        else if (scrolled) {
            scrolled = false
            items.getOrNull(centered)?.let { if (it != selected) select(it) }
        }
    }
    // Follow outside changes (e.g. tapping a preset) by scrolling to the new value.
    LaunchedEffect(selected) {
        val i = items.indexOf(selected)
        if (i >= 0 && i != centered && !listState.isScrollInProgress) listState.animateScrollToItem(i)
    }

    Box(modifier.height(rowHeight * 3), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = rowHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(items) { i, item ->
                val isCenter = i == centered
                Box(
                    Modifier.fillMaxWidth().height(rowHeight).clickable { scope.launch { listState.animateScrollToItem(i) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(item),
                        fontSize = if (isCenter) 30.sp else 18.sp,
                        fontWeight = if (isCenter) FontWeight.Medium else FontWeight.Normal,
                        color = if (isCenter) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Thin lines marking the selected row
        HorizontalDivider(Modifier.align(Alignment.Center).padding(bottom = rowHeight), color = MaterialTheme.colorScheme.outline)
        HorizontalDivider(Modifier.align(Alignment.Center).padding(top = rowHeight), color = MaterialTheme.colorScheme.outline)
    }
}
