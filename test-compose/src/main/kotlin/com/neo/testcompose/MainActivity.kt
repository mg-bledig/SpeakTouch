package com.neo.testcompose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent.getIntExtra("case", 0).coerceIn(cases.indices)
        setContent {
            var selected by remember { mutableIntStateOf(initial) }
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                BasicText("Case ${selected + 1}: ${cases[selected]}")
                Spacer(Modifier.height(24.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    key(selected) { Fixture(selected) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BasicText("Previous case", Modifier.clickable(role = Role.Button) {
                        selected = (selected + cases.size - 1) % cases.size
                    }.padding(16.dp))
                    BasicText("Next case", Modifier.clickable(role = Role.Button) {
                        selected = (selected + 1) % cases.size
                    }.padding(16.dp))
                }
            }
        }
    }
}

private val cases = listOf(
    "Unmerged comparison", "Merged passive children", "Nested passive container",
    "Merged parent with own text", "Independent actionable child",
    "Invisible merged group", "Merged group inside collection"
)

@Composable
private fun Fixture(case: Int) {
    when (case) {
        0 -> Column {
            BasicText("Download")
            BasicText("Loading")
        }
        1 -> Column(Modifier.semantics(mergeDescendants = true) {}) {
            BasicText("Download")
            BasicText("Loading")
        }
        2 -> Column(Modifier.semantics(mergeDescendants = true) {}) {
            Column {
                BasicText("Download")
                BasicText("Loading")
            }
        }
        3 -> Column(Modifier.semantics(mergeDescendants = true) {
            text = AnnotatedString("Transfer")
        }) {
            BasicText("Download")
            BasicText("Loading")
        }
        4 -> {
            var retries by remember { mutableIntStateOf(0) }
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                BasicText("Download")
                BasicText("Loading")
                BasicText("Retry $retries", Modifier.clickable(role = Role.Button) { retries++ }.padding(16.dp))
            }
        }
        5 -> Column(Modifier.alpha(0f).semantics(mergeDescendants = true) { hideFromAccessibility() }) {
            BasicText("Download")
            BasicText("Loading")
        }
        6 -> LazyColumn(Modifier.fillMaxWidth().semantics { collectionInfo = CollectionInfo(3, 1) }) {
            item { BasicText("Before group") }
            item {
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    BasicText("Download")
                    BasicText("Loading")
                }
            }
            item { BasicText("After group") }
        }
    }
}
