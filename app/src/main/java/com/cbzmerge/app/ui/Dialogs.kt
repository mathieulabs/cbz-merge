package com.cbzmerge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cbzmerge.app.ImageFormat
import com.cbzmerge.app.Quality

private val MaxPagesPresets = listOf(0, 1000, 2000, 3000, 5000)
private const val CUSTOM = -1

@Composable
fun MaxPagesDialog(current: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    var choice by remember { mutableIntStateOf(if (current in MaxPagesPresets) current else CUSTOM) }
    var custom by remember { mutableStateOf(if (current in MaxPagesPresets) "" else current.toString()) }
    val customValue = custom.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Max pages per file") },
        text = {
            Column {
                Hint("Files are cut between chapters, never in the middle of one.")
                Spacer(Modifier.height(8.dp))
                MaxPagesPresets.forEach { n ->
                    RadioLine(if (n == 0) "No limit" else formatCount(n), choice == n) { choice = n }
                }
                RadioLine("Custom", choice == CUSTOM) { choice = CUSTOM }
                if (choice == CUSTOM) {
                    NumberField(custom, "Pages", Modifier.fillMaxWidth().padding(top = 4.dp)) { custom = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSelect(if (choice == CUSTOM) customValue else choice) },
                enabled = choice != CUSTOM || customValue > 0
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun QualityDialog(
    current: Quality,
    format: ImageFormat,
    estimate: (Quality) -> Long?,
    hasFiles: Boolean,
    onFormat: (ImageFormat) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (Quality) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Image quality") },
        text = {
            Column {
                Hint("Smaller presets shrink files a lot. Merging takes longer when pages are re-encoded.")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageFormat.entries.forEach { f ->
                        FilterChip(
                            selected = f == format,
                            onClick = { onFormat(f) },
                            label = { Text(f.title) }
                        )
                    }
                }
                Hint(format.detail)
                Spacer(Modifier.height(8.dp))
                Quality.entries.forEach { q ->
                    val size = estimate(q)
                    val sizeText = when {
                        !hasFiles -> ""
                        size == null -> " · estimating…"
                        q == Quality.ORIGINAL -> " · ${formatSize(size)}"
                        else -> " · about ${formatSize(size)}"
                    }
                    RadioLine(q.title, q == current, q.detail + sizeText) { onSelect(q) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun RangeDialog(count: Int, onDismiss: () -> Unit, onSelect: (from: Int, to: Int) -> Unit) {
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf(count.toString()) }
    val a = from.toIntOrNull() ?: 0
    val b = to.toIntOrNull() ?: 0
    val valid = a in 1..count && b in 1..count

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select a range") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Keeps only these positions in the list checked.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(from, "From", Modifier.weight(1f)) { from = it }
                    NumberField(to, "To", Modifier.weight(1f)) { to = it }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(a, b) }, enabled = valid) { Text("Select") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NumberField(value: String, label: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(6)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun RadioLine(label: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) Hint(detail)
        }
    }
}

@Composable
fun RenameFileDialog(
    current: String,
    renamed: Boolean,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename file") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("File name") },
                    suffix = { Text(".cbz") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Hint("Keep \"ch.1-50\" at the end if you want the app to recognise this file later.")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (renamed) TextButton(onClick = onReset) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
