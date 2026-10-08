package com.cbzmerge.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cbzmerge.app.Cbz
import com.cbzmerge.app.CbzFile
import com.cbzmerge.app.Chapter
import com.cbzmerge.app.CoverEditStatus
import com.cbzmerge.app.CoverSource
import com.cbzmerge.app.Covers
import com.cbzmerge.app.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Limits how many images are decoded at once, to keep memory low. */
private val decodeSlots = Semaphore(3)

private val ThumbShape = RoundedCornerShape(8.dp)

@Composable
fun CoverThumb(source: CoverSource?, width: Dp, placeholder: String = "") {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, source) {
        value = null
        if (source != null) {
            value = withContext(Dispatchers.IO) {
                decodeSlots.withPermit { Covers.thumbnail(context, source)?.asImageBitmap() }
            }
        }
    }
    Box(
        Modifier.width(width).aspectRatio(2f / 3f).clip(ThumbShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ThumbShape),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        when {
            image != null -> Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            source != null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else -> Text(
                placeholder,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

/** Cover choice on the merge screen. */
@Composable
fun CoverCard(vm: MainViewModel, enabled: Boolean, onGallery: () -> Unit, onPage: () -> Unit) {
    val cover = vm.effectiveCover
    val files = vm.plan.size
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CoverThumb(cover, 56.dp, "Page 1")
            Column(Modifier.weight(1f)) {
                Text(
                    if (files > 1) "Cover · on all $files files" else "Cover",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    cover?.label ?: "First page (default)",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Gallery", enabled, onGallery)
                    SmallButton("Pick a page", enabled, onPage)
                }
            }
            if (vm.cover != null) {
                IconButton(onClick = { vm.cover = null }, enabled = enabled) {
                    Icon(Icons.Default.Close, "Use default cover", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.height(32.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

/** Full screen grid of pages to pick a cover from, one chapter at a time. */
@Composable
fun PagePickerDialog(chapters: List<Chapter>, onPick: (CoverSource.Page) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var chapterIndex by remember { mutableIntStateOf(0) }
    var cbz by remember { mutableStateOf<CbzFile?>(null) }
    var pages by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(chapterIndex) {
        pages = null
        error = null
        val previous = cbz
        cbz = null
        withContext(Dispatchers.IO) { previous?.close() }
        try {
            val opened = withContext(Dispatchers.IO) { Cbz.open(context, chapters[chapterIndex].uri) }
            cbz = opened
            pages = withContext(Dispatchers.IO) { opened.pages.map { it.name } }
        } catch (e: Exception) {
            error = "Couldn't open this chapter."
        }
    }
    DisposableEffect(Unit) { onDispose { cbz?.close() } }

    FullScreenDialog("Choose a cover", onClose = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (chapters.size > 1) {
                Surface(shape = ButtonShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                        IconButton(onClick = { chapterIndex-- }, enabled = chapterIndex > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous chapter")
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Chapter ${chapterIndex + 1} of ${chapters.size}", style = MaterialTheme.typography.labelLarge)
                            Text(
                                chapters[chapterIndex].name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { chapterIndex++ }, enabled = chapterIndex < chapters.lastIndex) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next chapter")
                        }
                    }
                }
            }
            Text(
                "Tall webtoon pages are cropped to the top.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp)
            )
            val file = cbz
            val names = pages
            when {
                error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                file == null || names == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyVerticalGrid(
                    GridCells.Adaptive(100.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    itemsIndexed(names, key = { _, name -> "$chapterIndex/$name" }) { i, name ->
                        PageThumb(file, name, i + 1) {
                            onPick(CoverSource.Page(chapters[chapterIndex], name, "Page ${i + 1}, chapter ${chapterIndex + 1}"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageThumb(cbz: CbzFile, entry: String, number: Int, onClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, cbz, entry) {
        value = withContext(Dispatchers.IO) {
            decodeSlots.withPermit { Covers.thumbnail(cbz, entry)?.asImageBitmap() }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(ThumbShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val image = bitmap
            if (image != null) {
                Image(image, "Page $number", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        Text("$number", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Replaces the cover of a CBZ that was already merged. */
@Composable
fun EditCoverScreen(
    vm: MainViewModel,
    onClose: () -> Unit,
    onPickCbz: () -> Unit,
    onCoverFromGallery: () -> Unit,
    onCoverFromPage: () -> Unit
) {
    val status = vm.editStatus
    val working = status is CoverEditStatus.Working
    val target = vm.editTarget

    FullScreenDialog("Change a cover", onClose = { if (!working) onClose() }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Change the cover of a CBZ you already merged. The pages stay as they are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("File", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                target?.name ?: "No file chosen",
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = onPickCbz, enabled = !working) { Text(if (target == null) "Choose" else "Change") }
                    }
                }
                if (target != null) {
                    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LabeledThumb(vm.editCurrentCover, "Current")
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                LabeledThumb(vm.editNewCover, "New", placeholder = "Not chosen")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onCoverFromGallery, enabled = !working, modifier = Modifier.weight(1f), shape = ButtonShape) {
                                    Text("Gallery")
                                }
                                OutlinedButton(onClick = onCoverFromPage, enabled = !working, modifier = Modifier.weight(1f), shape = ButtonShape) {
                                    Text("Pick a page")
                                }
                            }
                        }
                    }
                }
                when (status) {
                    is CoverEditStatus.Working -> LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                    CoverEditStatus.Saved -> Text("Cover saved.", color = MaterialTheme.colorScheme.primary)
                    is CoverEditStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
                    CoverEditStatus.Idle -> Unit
                }
            }
            Button(
                onClick = vm::saveEditedCover,
                enabled = vm.editNewCover != null && !working,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).height(52.dp)
            ) { Text("Save cover", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun LabeledThumb(source: CoverSource?, label: String, placeholder: String = "") {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CoverThumb(source, 110.dp, placeholder)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A dialog that covers the whole screen, with a close button and a title. */
@Composable
private fun FullScreenDialog(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
