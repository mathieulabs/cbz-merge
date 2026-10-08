package com.cbzmerge.app.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drag and drop for a LazyColumn. Items are dragged by a handle.
 * [firstIndex] is the list index of the first movable item (items before it, like headers, never move).
 * [onMove] receives positions among the movable items.
 */
class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val firstIndex: Int,
    private val onMove: (from: Int, to: Int) -> Unit
) {
    var draggedKey by mutableStateOf<Any?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    // Index the dragged item should have once the list has been laid out again after a move
    private var expectedIndex = -1

    fun start(key: Any) {
        draggedKey = key
        offset = 0f
        expectedIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.index ?: -1
    }

    fun drag(delta: Float) {
        val key = draggedKey ?: return
        offset += delta
        val info = listState.layoutInfo
        val current = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        if (current.index != expectedIndex) return // last move not laid out yet

        val center = (current.offset + offset + current.size / 2f).toInt()
        val target = info.visibleItemsInfo.firstOrNull {
            it.key != key && it.index >= firstIndex && center in it.offset..(it.offset + it.size)
        }
        if (target != null) {
            onMove(current.index - firstIndex, target.index - firstIndex)
            offset += current.offset - target.offset
            expectedIndex = target.index
        }

        // Scroll when the item gets close to the top or bottom edge
        val top = current.offset + offset
        val edge = 120f
        val step = when {
            top + current.size > info.viewportEndOffset - edge -> 24f
            top < info.viewportStartOffset + edge -> -24f
            else -> 0f
        }
        if (step != 0f) scope.launch { offset += listState.scrollBy(step) }
    }

    fun end() {
        draggedKey = null
        offset = 0f
        expectedIndex = -1
    }
}

@Composable
fun rememberReorderState(listState: LazyListState, firstIndex: Int, onMove: (Int, Int) -> Unit): ReorderState {
    val scope = rememberCoroutineScope()
    return remember(listState) { ReorderState(listState, scope, firstIndex, onMove) }
}

/** Makes this element a drag handle for the item with [key]. */
fun Modifier.dragHandle(state: ReorderState, key: Any): Modifier = pointerInput(key) {
    detectDragGestures(
        onDragStart = { state.start(key) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
        onDrag = { change, amount ->
            change.consume()
            state.drag(amount.y)
        }
    )
}
