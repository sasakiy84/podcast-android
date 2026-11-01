package io.github.sasakiy84.podcast

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

@Composable
internal fun CollapsibleDescription(text: String): Unit {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        if (expanded) {
            ContentText(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { expanded = false }) { Text("折りたたむ") }
        } else {
            ContentText(text, style = MaterialTheme.typography.bodyMedium, maxLines = 5,
                onTextLayout = { overflow = it.hasVisualOverflow }, modifier = Modifier.fillMaxWidth().clickable(enabled = overflow) { expanded = true })
            if (overflow) TextButton(onClick = { expanded = true }) { Text("全文を表示") }
        }
    }
}
