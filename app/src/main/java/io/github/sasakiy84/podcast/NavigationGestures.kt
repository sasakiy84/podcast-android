package io.github.sasakiy84.podcast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/** Only dismiss after a downward drag that the inner list cannot consume. */
@Composable
internal fun Modifier.dismissPlayerOnSwipe(close: () -> Unit): Modifier {
    val current = rememberUpdatedState(close)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val connection = remember(threshold) {
        object : NestedScrollConnection {
            var distance = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (consumed.y < 0 || available.y < 0) distance = 0f
                    else distance += available.y.coerceAtLeast(0f)
                    if (distance >= threshold) { distance = 0f; current.value() }
                }
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity { distance = 0f; return Velocity.Zero }
        }
    }
    return nestedScroll(connection)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshableContent(enabled: Boolean, isRefreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit): Unit {
    if (enabled) androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier, content = content)
    else androidx.compose.foundation.layout.Box(modifier, content = content)
}
