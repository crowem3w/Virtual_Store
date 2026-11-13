package org.example.test

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.example.test.world.WorldInfo
import java.text.DateFormat
import java.util.Date

/** Home screen: the saved worlds, plus a button that starts a new live scan. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldListScreen(
    worlds: List<WorldInfo>,
    hasCamera: Boolean,
    onNew: () -> Unit,
    onOpen: (WorldInfo) -> Unit,
    onRename: (WorldInfo, String) -> Unit,
    onDelete: (WorldInfo) -> Unit,
    onStore: () -> Unit,
) {
    var renaming by remember { mutableStateOf<WorldInfo?>(null) }
    var deleting by remember { mutableStateOf<WorldInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Worlds") },
                actions = { TextButton(onClick = onStore) { Text("Store (Wi-Fi)") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { if (hasCamera) onNew() }) {
                Text(if (hasCamera) "New world" else "Camera permission needed")
            }
        },
    ) { padding ->
        if (worlds.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No worlds yet. Tap New world, then walk around slowly. The app builds a 3D model of what the camera sees and saves it here.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(worlds, key = { it.id }) { w ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val thumb = remember(w.id, w.updatedMs) {
                                if (w.thumbnail.exists()) {
                                    BitmapFactory.decodeFile(w.thumbnail.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })
                                } else null
                            }
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(88.dp).clip(MaterialTheme.shapes.medium),
                                )
                            } else {
                                Box(Modifier.size(88.dp))
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(w.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${w.points} points · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(w.updatedMs)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row {
                                    TextButton(onClick = { onOpen(w) }, enabled = w.points > 0) { Text("Open") }
                                    TextButton(onClick = { renaming = w }) { Text("Rename") }
                                    TextButton(onClick = { deleting = w }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { w ->
        var text by remember(w.id) { mutableStateOf(w.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename world") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(w, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { w ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${w.name}?") },
            text = { Text("This removes the world and its keyframes from the phone.") },
            confirmButton = { TextButton(onClick = { onDelete(w); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
