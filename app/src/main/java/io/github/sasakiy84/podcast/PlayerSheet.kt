@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.sasakiy84.podcast

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop

/** One persistent Material sheet: the mini-player and full player share drag anchors. */
@Composable
internal fun PlayerHost(episode: Episode?, state: PodcastUiState, action: (PodcastAction) -> Unit, expanded: Boolean, setExpanded: (Boolean) -> Unit,
    navigation: @Composable () -> Unit, queues: () -> Unit, membership: () -> Unit, openPodcast: () -> Unit, content: @Composable (androidx.compose.ui.unit.Dp) -> Unit): Unit {
    if (episode == null) { content(0.dp); return }
    val sheet = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    val scaffold = rememberBottomSheetScaffoldState(bottomSheetState = sheet)
    var navigationHeight by remember { mutableIntStateOf(80) }
    var miniHeight by remember { mutableIntStateOf(68) }
    var height by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val peek = with(density) { (navigationHeight + miniHeight).toDp() }
    LaunchedEffect(expanded) { if (expanded) sheet.expand() else sheet.partialExpand() }
    val update by rememberUpdatedState(setExpanded)
    LaunchedEffect(sheet) { snapshotFlow { sheet.currentValue }.drop(1).collect { update(it == SheetValue.Expanded) } }
    val progress by remember { derivedStateOf {
        if (height <= 0 || !sheet.hasExpandedState) return@derivedStateOf 0f
        val offset = try { sheet.requireOffset() } catch (_: IllegalStateException) { height.toFloat() }
        if (offset <= 0f && sheet.currentValue == SheetValue.PartiallyExpanded && sheet.targetValue == SheetValue.PartiallyExpanded) return@derivedStateOf 0f
        val collapsed = (height - navigationHeight - miniHeight).coerceAtLeast(1).toFloat()
        (1f - offset / collapsed).coerceIn(0f, 1f)
    } }
    PlayerSystemBars(progress > 0.5f)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = progress > 0.001f) { setExpanded(false); scope.launch { sheet.partialExpand() } }
    Box(Modifier.fillMaxSize()) {
    BottomSheetScaffold(modifier = Modifier.fillMaxSize().onSizeChanged { height = it.height }, scaffoldState = scaffold,
        sheetPeekHeight = peek, sheetShape = RectangleShape, sheetDragHandle = null, sheetMaxWidth = androidx.compose.ui.unit.Dp.Unspecified,
        sheetContainerColor = MaterialTheme.colorScheme.surface, sheetContent = {
            Box(Modifier.fillMaxHeight()) {
                if (progress > 0.001f) PodcastTheme(dark = true) {
                    Surface(Modifier.fillMaxSize().alpha(progress)) { PlayerPage(episode, state, action, { setExpanded(false) }, membership, queues, openPodcast) }
                }
                if (progress < 0.999f) Column(Modifier.fillMaxWidth().alpha(1f - progress)) {
                    Box(Modifier.onSizeChanged { miniHeight = it.height }) { MiniPlayer(episode, state, action, queues) { setExpanded(true) } }
                }
            }
        }) { content(peek) }
    if (progress < 0.999f) Box(Modifier.align(Alignment.BottomCenter).alpha(1f - progress).onSizeChanged { navigationHeight = it.height }) { navigation() }
    }
}

@Composable
private fun PlayerSystemBars(expanded: Boolean): Unit {
    val view = androidx.compose.ui.platform.LocalView.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val activity = remember(view) {
        generateSequence(view.context) { current -> (current as? android.content.ContextWrapper)?.baseContext?.takeIf { it !== current } }
            .filterIsInstance<android.app.Activity>().firstOrNull()
    }
    DisposableEffect(activity, view, expanded, dark) {
        val controller = activity?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !expanded && !dark
        controller?.isAppearanceLightNavigationBars = !expanded && !dark
        onDispose {
            if (oldStatus != null) controller?.isAppearanceLightStatusBars = oldStatus
            if (oldNavigation != null) controller?.isAppearanceLightNavigationBars = oldNavigation
        }
    }
}
