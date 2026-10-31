package io.github.sasakiy84.podcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PodcastApp() }
    }
}

@Composable
fun PodcastApp(): Unit {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFA2D0BB))
    } else {
        lightColorScheme(primary = Color(0xFF315A4B))
    }
    MaterialTheme(colorScheme = colors) {
        var count by rememberSaveable { mutableIntStateOf(0) }
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.welcome), style = MaterialTheme.typography.bodyLarge)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.counter_label), style = MaterialTheme.typography.titleMedium)
                        Text(count.toString(), style = MaterialTheme.typography.displayLarge)
                        Button(onClick = { if (count < Int.MAX_VALUE) count++ }) {
                            Text(stringResource(R.string.increment))
                        }
                        TextButton(onClick = { count = 0 }, enabled = count > 0) {
                            Text(stringResource(R.string.reset))
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, locale = "ja", widthDp = 400, heightDp = 800)
@Composable
private fun PodcastPreview(): Unit { PodcastApp() }
