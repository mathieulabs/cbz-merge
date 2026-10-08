package com.cbzmerge.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cbzmerge.app.MainViewModel
import com.cbzmerge.app.MergeStatus
import com.cbzmerge.app.R

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) vm.addFiles(it)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) vm.addFolder(it)
    }
    val pickOutFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) vm.chooseOutFolder(it)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (it != null) vm.pickCoverImage(it, forEditScreen = vm.editingCover)
    }
    val pickCbzToEdit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) vm.openEditTarget(it)
    }
    val openGallery = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    var showPagePicker by remember { mutableStateOf(false) }

    // Ask once for notifications so the merge progress shows while the app is in the background
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.merge() }
    val startMerge = {
        val needsAsking = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsking) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.merge()
    }
    var panelOpen by rememberSaveable { mutableStateOf(true) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    val hasChapters = vm.chapters.isNotEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (hasChapters && panelOpen) {
                MergePanel(vm, onPickOutFolder = { pickOutFolder.launch(null) }, onMerge = startMerge, onHide = { panelOpen = false })
            }
        },
        floatingActionButton = {
            if (hasChapters && !panelOpen) MergePill(vm) { panelOpen = true }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (hasChapters) {
                MergeScreen(
                    vm,
                    extraBottomSpace = !panelOpen,
                    onAddFolder = { pickFolder.launch(null) },
                    onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onCoverFromGallery = openGallery,
                    onCoverFromPage = { showPagePicker = true }
                )
            } else {
                EmptyState(
                    onAddFolder = { pickFolder.launch(null) },
                    onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onEditCover = { vm.editingCover = true }
                )
            }
        }
    }

    if (vm.editingCover) {
        EditCoverScreen(
            vm,
            onClose = { vm.editingCover = false },
            onPickCbz = { pickCbzToEdit.launch(arrayOf("*/*")) },
            onCoverFromGallery = openGallery,
            onCoverFromPage = { showPagePicker = true }
        )
    }

    if (showPagePicker) {
        val chapters = if (vm.editingCover) listOfNotNull(vm.editTarget) else vm.chapters.toList()
        if (chapters.isEmpty()) {
            showPagePicker = false
        } else {
            PagePickerDialog(
                chapters,
                onPick = { page ->
                    if (vm.editingCover) vm.editNewCover = page else vm.cover = page
                    showPagePicker = false
                },
                onDismiss = { showPagePicker = false }
            )
        }
    }

    vm.update?.let { update ->
        AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            title = { Text("Update available") },
            text = { Text("Version ${update.version} is out.") },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.page)))
                    vm.dismissUpdate()
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Later") } }
        )
    }

    val done = vm.status as? MergeStatus.Done
    if (done != null && done.oldVersions.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = vm::keepOldVersions,
            title = { Text("Replace the old version?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This folder already has a merge of this series:")
                    done.oldVersions.forEach { Text(it.name, fontWeight = FontWeight.Medium) }
                    Text("Your original chapters won't be touched.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = vm::deleteOldVersions) { Text("Delete old") } },
            dismissButton = { TextButton(onClick = vm::keepOldVersions) { Text("Keep both") } }
        )
    }
}

@Composable
private fun EmptyState(onAddFolder: () -> Unit, onAddFiles: () -> Unit, onEditCover: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource(R.drawable.ic_launcher_fg), null, Modifier.size(88.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Merge chapters into one CBZ",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a folder of chapters or select the files. Your originals are never modified.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAddFolder, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Choose a folder") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onAddFiles, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Select files") }
        TextButton(onClick = onEditCover, modifier = Modifier.padding(top = 6.dp)) {
            Text("Change the cover of an existing CBZ")
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Got new chapters? Add your current merge plus the new chapters, then merge again.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
