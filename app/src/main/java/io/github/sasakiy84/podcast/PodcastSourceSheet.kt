@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sasakiy84.podcast

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
internal fun PodcastSourceSheet(podcast: Podcast, dismiss: () -> Unit): Unit {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    fun open(url: String): Unit {
        try {
            uri.openUri(url)
        } catch (_: IllegalArgumentException) {
            android.widget.Toast.makeText(
                    context,
                    "URL を開けるアプリがありません。",
                    android.widget.Toast.LENGTH_SHORT,
                )
                .show()
        }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val animate = rememberSheetAction(sheet)
    val close = { animate(dismiss) }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheet,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "購読元",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = close) {
                    Icon(AppIcons.Close, "購読元を閉じる", Modifier.size(20.dp))
                }
            }
            SelectionContainer {
                Text(podcast.feedUrl, style = MaterialTheme.typography.bodyMedium)
            }
            Row {
                TextButton(
                    onClick = {
                        context
                            .getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("RSS", podcast.feedUrl))
                    }
                ) {
                    Text("URL をコピー")
                }
                TextButton(onClick = { open(podcast.feedUrl) }) { Text("RSS を開く") }
            }
            TextButton(
                onClick = {
                    shareText(
                        context,
                        podcast.title,
                        listOf(podcast.title, podcast.feedUrl, podcast.website)
                            .filter(String::isNotBlank)
                            .distinct()
                            .joinToString("\n"),
                    )
                }
            ) {
                Icon(AppIcons.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("番組を共有")
            }
            if (podcast.website.isNotBlank())
                TextButton(onClick = { open(podcast.website) }) { Text("番組のWebサイト") }
        }
    }
}
