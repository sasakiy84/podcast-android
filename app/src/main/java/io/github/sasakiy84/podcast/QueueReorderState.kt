package io.github.sasakiy84.podcast

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Local drag preview; committing moves one ID relative to an anchor in the current DB order. */
@Stable
internal class QueueReorderState(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    initial: List<String>,
) {
    var order by mutableStateOf(initial)
        private set

    var draggedId by mutableStateOf<String?>(null)
        private set

    private var origin by mutableFloatStateOf(0f)
    private var distance by mutableFloatStateOf(0f)
    private var itemHeight = 0
    private var scrolling: Job? = null

    fun reconcile(ids: List<String>): Unit {
        if (draggedId == null) order = ids
        else if (draggedId !in ids) cancel(ids)
        else order = order.filter { it in ids } + ids.filter { it !in order }
    }

    fun start(id: String): Unit {
        val info =
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "episode:$id" } ?: return
        draggedId = id
        origin = info.offset.toFloat()
        itemHeight = info.size
        distance = 0f
    }

    fun offset(id: String): Float =
        if (draggedId == id) {
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "episode:$id" }
            if (item == null) 0f else origin + distance - item.offset
        } else 0f

    fun drag(delta: Float): Unit {
        distance += delta
        movePreview()
        if (scrolling?.isActive != true && scrollAmount() != 0f)
            scrolling =
                scope.launch {
                    while (draggedId != null) {
                        val amount = scrollAmount()
                        if (amount == 0f) break
                        withFrameNanos {}
                        if (list.scrollBy(amount) == 0f) break
                        movePreview()
                    }
                }
    }

    private fun scrollAmount(): Float {
        if (draggedId == null) return 0f
        val info = list.layoutInfo
        val edge = (itemHeight * .4f).coerceAtLeast(32f)
        val center = origin + distance + itemHeight / 2f
        val maximum = (itemHeight * .15f).coerceAtLeast(6f)
        return when {
            center < info.viewportStartOffset + edge && list.canScrollBackward ->
                -((info.viewportStartOffset + edge - center) / 5).coerceIn(2f, maximum)
            center > info.viewportEndOffset - edge && list.canScrollForward ->
                ((center - info.viewportEndOffset + edge) / 5).coerceIn(2f, maximum)
            else -> 0f
        }
    }

    private fun movePreview(): Unit {
        val id = draggedId ?: return
        val from = order.indexOf(id)
        if (from < 0) return
        val center = origin + distance + itemHeight / 2f
        val current =
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "episode:$id" } ?: return
        val others =
            list.layoutInfo.visibleItemsInfo.filter {
                it.key is String &&
                    (it.key as String).startsWith("episode:") &&
                    it.key != "episode:$id"
            }
        val target =
            if (center > current.offset + current.size / 2f) {
                others.lastOrNull {
                    it.offset + it.size / 2f < center &&
                        order.indexOf((it.key as String).removePrefix("episode:")) > from
                }
            } else {
                others.firstOrNull {
                    it.offset + it.size / 2f > center &&
                        order.indexOf((it.key as String).removePrefix("episode:")) in 0 until from
                }
            } ?: return
        val to = order.indexOf((target.key as String).removePrefix("episode:"))
        order =
            order.toMutableList().apply {
                removeAt(from)
                add(to, id)
            }
    }

    fun finish(commit: (String, String?) -> Unit): Unit {
        val id = draggedId ?: return
        val before = order.getOrNull(order.indexOf(id) + 1)
        stop()
        commit(id, before)
    }

    fun cancel(ids: List<String>): Unit {
        stop()
        order = ids
    }

    fun stop(): Unit {
        scrolling?.cancel()
        scrolling = null
        draggedId = null
        distance = 0f
    }
}
