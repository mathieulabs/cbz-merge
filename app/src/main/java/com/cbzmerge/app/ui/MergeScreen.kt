package com.cbzmerge.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cbzmerge.app.Chapter
import com.cbzmerge.app.MainViewModel
import com.cbzmerge.app.MergeStatus
import com.cbzmerge.app.Naming
import com.cbzmerge.app.Quality

/** Cover, output settings and the chapter list, in one scrolling column. */
@Composable
fun MergeScreen(
    vm: MainViewModel,
    extraBottomSpace: Boolean,
    onAddFolder: () -> Unit,
    onAddFiles: () -> Unit,
    onCoverFromGallery: () -> Unit,
    onCoverFromPage: () -> Unit
) {
    val working = vm.status is MergeStatus.Working
    var showMaxPages by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showRange by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(listState, firstIndex = 1, onMove = vm::moveTo)

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (extraBottomSpace) 96.dp else 16.dp)
    ) {
        item(key = "header") {
            CoverCard(vm, enabled = !working, onGallery = onCoverFromGallery, onPage = onCoverFromPage)
            Spacer(Modifier.height(12.dp))
            OutputCard(vm, enabled = !working, onMaxPages = { showMaxPages = true }, onQuality = { showQuality = true })
            Spacer(Modifier.height(20.dp))
            ChaptersHeader(vm, enabled = !working, onSelectRange = { showRange = true })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddButton("Folder", !working, onAddFolder, Modifier.weight(1f))
                AddButton("Files", !working, onAddFiles, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        itemsIndexed(vm.chapters, key = { _, c -> c.uri.toString() }) { i, chapter ->
            val first = i == 0
            val last = i == vm.chapters.lastIndex
            val shape = RoundedCornerShape(
                topStart = if (first) 16.dp else 0.dp, topEnd = if (first) 16.dp else 0.dp,
                bottomStart = if (last) 16.dp else 0.dp, bottomEnd = if (last) 16.dp else 0.dp
            )
            val key = chapter.uri.toString()
            val dragged = reorder.draggedKey == key
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = if (dragged) 8.dp else 0.dp,
                modifier = if (dragged) Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offset } else Modifier.animateItem()
            ) {
                Column {
                    if (!first && chapter.uri in vm.fileBreaks) {
                        FileBreakBanner(enabled = !working) { vm.toggleFileBreak(chapter.uri) }
                    }
                    ChapterRow(i, chapter, vm, enabled = !working, handle = Modifier.dragHandle(reorder, key))
                    if (!last && vm.chapters[i + 1].uri !in vm.fileBreaks) {
                        HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (showMaxPages) {
        MaxPagesDialog(vm.maxPages, onDismiss = { showMaxPages = false }) {
            vm.changeMaxPages(it)
            showMaxPages = false
        }
    }
    if (showQuality) {
        val originalBytes = vm.plan.sumOf { it.bytes }
        QualityDialog(
            vm.quality, vm.imageFormat,
            estimate = { q -> vm.estimate(originalBytes, q) },
            hasFiles = originalBytes > 0,
            onFormat = vm::changeImageFormat,
            onDismiss = { showQuality = false }
        ) {
            vm.changeQuality(it)
            showQuality = false
        }
    }
    if (showRange) {
        RangeDialog(vm.chapters.size, onDismiss = { showRange = false }) { from, to ->
            vm.selectRange(from, to)
            showRange = false
        }
    }
}

/** Series name, split and quality settings, and a preview of the files to create. */
@Composable
private fun OutputCard(vm: MainViewModel, enabled: Boolean, onMaxPages: () -> Unit, onQuality: () -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            OutlinedTextField(
                value = vm.seriesName,
                onValueChange = vm::renameSeries,
                label = { Text("Series name") },
                singleLine = true,
                enabled = enabled,
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(4.dp))
            SettingRow("Max pages per file", if (vm.maxPages == 0) "No limit" else formatCount(vm.maxPages), enabled, onMaxPages)
            if (vm.seasonStarts > 0) {
                SwitchRow(
                    "Split by season", "${vm.seasonStarts + 1} seasons found: one file each, cut by pages if too big",
                    vm.splitBySeason, enabled, vm::changeSplitBySeason
                )
            }
            val qualityText = if (vm.quality == Quality.ORIGINAL) vm.quality.title else "${vm.quality.title} · ${vm.imageFormat.title}"
            SettingRow("Image quality", qualityText, enabled, onQuality)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            PlanPreview(vm, enabled)
        }
    }
}

@Composable
private fun PlanPreview(vm: MainViewModel, enabled: Boolean) {
    val plan = vm.plan
    var renamingKey by remember { mutableStateOf<Uri?>(null) }
    var showAll by remember { mutableStateOf(false) }
    val original = plan.sumOf { it.bytes }
    val estimated = vm.estimate(original)
    val summary = buildString {
        append(
            when (plan.size) {
                0 -> "No chapters selected"
                1 -> "Creates 1 file"
                else -> "Creates ${plan.size} files"
            }
        )
        if (plan.isNotEmpty()) {
            when {
                vm.quality == Quality.ORIGINAL -> append(" · ${formatSize(original)}")
                estimated == null -> append(" · estimating size…")
                else -> append(" · about ${formatSize(estimated)} (from ${formatSize(original)})")
            }
        }
        if (vm.countingPages) append(" · counting pages…")
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (plan.isNotEmpty()) {
            Text("Tap a file to rename it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (if (showAll) plan else plan.take(8)).forEach { output ->
            val size = vm.estimate(output.bytes)?.let { (if (vm.quality == Quality.ORIGINAL) " · " else " · ~") + formatSize(it) } ?: ""
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { renamingKey = output.key }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    output.fileName, Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium
                )
                Text(
                    "${formatCount(output.pages)} p$size",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        if (plan.size > 8 && !showAll) {
            Text(
                "Show all ${plan.size} files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { showAll = true }.padding(vertical = 4.dp)
            )
        }
    }

    val target = plan.firstOrNull { it.key == renamingKey }
    if (target != null) {
        RenameFileDialog(
            current = Naming.baseName(target.fileName),
            renamed = target.renamed,
            onDismiss = { renamingKey = null },
            onReset = {
                vm.resetOutputName(target.key)
                renamingKey = null
            }
        ) {
            vm.renameOutput(target.key, it)
            renamingKey = null
        }
    }
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChaptersHeader(vm: MainViewModel, enabled: Boolean, onSelectRange: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val selected = vm.chapters.filter { it.uri !in vm.excluded }
    val pages = selected.sumOf { vm.pageCounts[it.uri] ?: 0 }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Chapters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${selected.size} of ${vm.chapters.size} selected · ${formatCount(pages)} pages",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = enabled) { Icon(Icons.Default.MoreVert, "Chapter options") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val close = { menuOpen = false }
                MenuItem("Select all", close, vm::selectAll)
                MenuItem("Select none", close, vm::selectNone)
                MenuItem("Select a range…", close, onSelectRange)
                HorizontalDivider()
                MenuItem("Sort by name", close, vm::sortByName)
                if (vm.fileBreaks.isNotEmpty()) MenuItem("Remove file breaks", close, vm::clearFileBreaks)
                MenuItem("Change the cover of a CBZ…", close) { vm.editingCover = true }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Clear list", color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; vm.clear() }
                )
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = { close(); action() })
}

@Composable
private fun AddButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = ButtonShape) {
        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

/** Shown above a chapter that starts a new file. */
@Composable
private fun FileBreakBanner(enabled: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "New file starts here", Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold
        )
        TextButton(onClick = onRemove, enabled = enabled) { Text("Remove", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun ChapterRow(index: Int, chapter: Chapter, vm: MainViewModel, enabled: Boolean, handle: Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val included = chapter.uri !in vm.excluded
    val pages = vm.pageCounts[chapter.uri]
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox) { vm.toggle(chapter.uri) }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = included, onCheckedChange = { vm.toggle(chapter.uri) }, enabled = enabled)
        Row(Modifier.weight(1f).alpha(if (included) 1f else 0.45f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${index + 1}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(34.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(chapter.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(
                        chapter.group.substringAfterLast('/').ifBlank { null },
                        if (pages == null) "…" else "${formatCount(pages)} pages",
                        formatSize(chapter.size)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (enabled) {
            Box(handle.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Menu, "Drag to reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                Icon(Icons.Default.MoreVert, "Options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (index > 0) {
                    DropdownMenuItem(
                        text = { Text(if (chapter.uri in vm.fileBreaks) "Remove file break" else "Start a new file here") },
                        onClick = { menuOpen = false; vm.toggleFileBreak(chapter.uri) }
                    )
                }
                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0, onClick = { menuOpen = false; vm.move(index, -1) })
                DropdownMenuItem(
                    text = { Text("Move down") }, enabled = index < vm.chapters.lastIndex,
                    onClick = { menuOpen = false; vm.move(index, 1) }
                )
                DropdownMenuItem(
                    text = { Text("Remove from list", color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; vm.remove(index) }
                )
            }
        }
    }
}

/** Bottom panel: output folder, progress and the merge button. Swipe down or tap the arrow to hide it. */
@Composable
fun MergePanel(vm: MainViewModel, onPickOutFolder: () -> Unit, onMerge: () -> Unit, onHide: () -> Unit) {
    val status = vm.status
    val working = status is MergeStatus.Working
    // Swipe down to hide
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val hideDistance = with(LocalDensity.current) { 72.dp.toPx() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier
            .graphicsLayer { translationY = dragOffset }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> dragOffset = (dragOffset + delta).coerceAtLeast(0f) },
                onDragStopped = {
                    if (dragOffset > hideDistance) onHide()
                    dragOffset = 0f
                }
            )
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(end = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Save to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (vm.outFolder == null) "No folder chosen" else vm.outFolderName,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onPickOutFolder, enabled = !working) { Text(if (vm.outFolder == null) "Choose" else "Change") }
                IconButton(onClick = onHide) { Icon(Icons.Default.KeyboardArrowDown, "Hide panel") }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusLine(vm)
                if (!working) {
                    val files = vm.plan.size
                    Button(
                        onClick = { if (vm.outFolder == null) onPickOutFolder() else onMerge() },
                        enabled = vm.outFolder == null || files > 0,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            when {
                                vm.outFolder == null -> "Choose output folder"
                                files > 1 -> "Merge into $files files"
                                else -> "Merge"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(vm: MainViewModel) {
    when (val status = vm.status) {
        is MergeStatus.Working -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::cancelMerge) { Text("Cancel") }
            }
        }
        is MergeStatus.Done -> {
            val files = status.files
            val what = if (files.size == 1) "Saved ${files[0].fileName}" else "Saved ${files.size} files"
            Text(
                "$what · ${formatCount(files.sumOf { it.pages })} pages · ${formatSize(files.sumOf { it.bytes })}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        is MergeStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        MergeStatus.Idle -> if (vm.outFolder == null) {
            Text(
                "Pick a separate folder for your merges. The app remembers it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Small floating button shown when the panel is hidden. */
@Composable
fun MergePill(vm: MainViewModel, onOpen: () -> Unit) {
    val label = when (val status = vm.status) {
        is MergeStatus.Working -> "Merging · ${(status.progress * 100).toInt()}%"
        is MergeStatus.Done -> "Done"
        is MergeStatus.Failed -> "Merge failed"
        MergeStatus.Idle -> if (vm.plan.size > 1) "Merge · ${vm.plan.size} files" else "Merge"
    }
    ExtendedFloatingActionButton(
        onClick = onOpen,
        icon = { Icon(Icons.Default.KeyboardArrowUp, null) },
        text = { Text(label, fontWeight = FontWeight.SemiBold) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    )
}
