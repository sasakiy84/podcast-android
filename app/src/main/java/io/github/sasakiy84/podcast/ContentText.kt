package io.github.sasakiy84.podcast

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

@Composable
internal fun ContentText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge): Unit {
    val color = MaterialTheme.colorScheme.primary
    val annotated = remember(text, color) {
        buildAnnotatedString {
            var offset = 0
            Regex("https?://[^\\s<>\\\"\\u3000]+").findAll(text).forEach { match ->
                var url = match.value.trimEnd('.', ',', ';', '。', '、', '）', '」', '』')
                while (url.endsWith(")") && url.count { it == ')' } > url.count { it == '(' }) url = url.dropLast(1)
                while (url.endsWith("]") && url.count { it == ']' } > url.count { it == '[' }) url = url.dropLast(1)
                if (absoluteUrl(url, url).isNotBlank()) {
                    append(text.substring(offset, match.range.first))
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(style = SpanStyle(color = color, textDecoration = TextDecoration.Underline)))) { append(url) }
                    offset = match.range.first + url.length
                }
            }
            append(text.substring(offset))
        }
    }
    SelectionContainer { Text(annotated, modifier, style = style) }
}

@Composable
internal fun CopyableText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge): Unit {
    SelectionContainer { Text(text, modifier, style = style) }
}
