package io.github.sasakiy84.podcast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshableContent(enabled: Boolean, isRefreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit): Unit {
    if (enabled) androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier, content = content)
    else androidx.compose.foundation.layout.Box(modifier, content = content)
}
