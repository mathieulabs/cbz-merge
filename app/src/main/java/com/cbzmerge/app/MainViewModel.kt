package com.cbzmerge.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface CoverEditStatus {
    data object Idle : CoverEditStatus
    data class Working(val progress: Float) : CoverEditStatus
    data object Saved : CoverEditStatus
    data class Failed(val message: String) : CoverEditStatus
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val resolver get() = context.contentResolver
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** A newer release on GitHub, if there is one. */
    var update by mutableStateOf<UpdateCheck.Update?>(null)
        private set

    init {
        viewModelScope.launch { update = UpdateCheck.latest(context) }
    }

    fun dismissUpdate() {
        update = null
    }

    // Chapters and how they are grouped into files

    val chapters = mutableStateListOf<Chapter>()
    val pageCounts = mutableStateMapOf<Uri, Int>()

    /** Unchecked chapters, left out of the merge. */
    var excluded by mutableStateOf(setOf<Uri>())
        private set

    /** Chapters that start a new file (e.g. the first chapter of a season). */
    var fileBreaks by mutableStateOf(setOf<Uri>())
        private set

    /** Each output file is named "<seriesName> ch.X-Y". */
    var seriesName by mutableStateOf("")
        private set
    private var seriesNameEdited = false
    /** Name of the first folder added, used as the default series name. */
    private var folderName: String? = null

    var maxPages by mutableStateOf(prefs.getInt(KEY_MAX_PAGES, 0))
        private set
    var quality by mutableStateOf(readQuality())
        private set
    var imageFormat by mutableStateOf(readImageFormat())
        private set

    /** Breaks the user removed by hand, so adding more chapters doesn't bring them back. */
    private var dismissedBreaks = setOf<Uri>()

    /** Start a new file at each season (where chapter numbers start over). Can be turned off. */
    var splitBySeason by mutableStateOf(prefs.getBoolean(KEY_SPLIT_SEASONS, true))
        private set
    /** Breaks added automatically for seasons, so they can be taken back when the option is turned off. */
    private var seasonBreaks = setOf<Uri>()
    /** How many times the numbering starts over in the list. */
    val seasonStarts by derivedStateOf {
        chapters.zipWithNext().count { (a, b) -> Naming.startsNewSeason(a.name, b.name) }
    }

    /** File names typed by the user, keyed by the first chapter of each file. */
    private val customNames = mutableStateMapOf<Uri, String>()

    val plan by derivedStateOf {
        Planner.plan(seriesName, chapters.toList(), excluded, fileBreaks, pageCounts, maxPages, customNames.toMap())
    }
    val countingPages by derivedStateOf { chapters.any { it.uri !in pageCounts } }

    /** Output size relative to the original for each quality, measured on sample pages. */
    var sizeRatios by mutableStateOf<Map<Quality, Double>>(emptyMap())
        private set

    // Output folder, kept across launches

    var outFolder by mutableStateOf<Uri?>(null)
        private set
    var outFolderName by mutableStateOf("")
        private set

    // Cover

    var cover by mutableStateOf<CoverSource?>(null)
    /** Cover of an earlier merge found in the list, used when no cover is picked. */
    var previousCover by mutableStateOf<CoverSource.Page?>(null)
        private set
    val effectiveCover get() = cover ?: previousCover

    // "Change the cover of a CBZ" screen

    var editingCover by mutableStateOf(false)
    var editTarget by mutableStateOf<Chapter?>(null)
        private set
    var editCurrentCover by mutableStateOf<CoverSource.Page?>(null)
        private set
    var editNewCover by mutableStateOf<CoverSource?>(null)
    var editStatus by mutableStateOf<CoverEditStatus>(CoverEditStatus.Idle)
        private set

    /** Mirrors [MergeRunner.status], which lives outside this screen. */
    var status by mutableStateOf(MergeRunner.status.value)
        private set
    /** One-off message shown in a snackbar. */
    var message by mutableStateOf<String?>(null)

    private var countJob: Job? = null
    private var estimateJob: Job? = null
    private var coverScanJob: Job? = null
    private val coverEntryCache = java.util.concurrent.ConcurrentHashMap<Uri, String>()

    init {
        viewModelScope.launch {
            MergeRunner.status.collect {
                status = it
                // Size estimates are paused during a merge; finish them afterwards
                if (it !is MergeStatus.Working && sizeRatios.isEmpty() && chapters.isNotEmpty()) estimateSizes()
            }
        }
        prefs.getString(KEY_OUT_FOLDER, null)?.let { saved ->
            val uri = Uri.parse(saved)
            if (resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) {
                outFolder = uri
                outFolderName = Saf.folderName(resolver, uri)
            }
        }
    }

    // Settings

    fun chooseOutFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
        outFolder?.takeIf { it != uri }?.let { old -> runCatching { resolver.releasePersistableUriPermission(old, flags) } }
        outFolder = uri
        outFolderName = Saf.folderName(resolver, uri)
        prefs.edit().putString(KEY_OUT_FOLDER, uri.toString()).apply()
    }

    fun changeMaxPages(n: Int) {
        maxPages = n.coerceAtLeast(0)
        prefs.edit().putInt(KEY_MAX_PAGES, maxPages).apply()
    }

    fun changeQuality(q: Quality) {
        quality = q
        prefs.edit().putString(KEY_QUALITY, q.name).apply()
    }

    fun changeImageFormat(format: ImageFormat) {
        if (format == imageFormat) return
        imageFormat = format
        prefs.edit().putString(KEY_FORMAT, format.name).apply()
        sizeRatios = emptyMap()
        estimateSizes()
    }

    fun renameOutput(key: Uri, name: String) {
        if (name.isBlank()) customNames.remove(key) else customNames[key] = name.trim()
    }

    fun resetOutputName(key: Uri) {
        customNames.remove(key)
    }

    fun renameSeries(name: String) {
        seriesName = name
        seriesNameEdited = true
    }

    /** Estimated size of [bytes] of original images once encoded with [q], or null while measuring. */
    fun estimate(bytes: Long, q: Quality = quality): Long? =
        if (q == Quality.ORIGINAL) bytes else sizeRatios[q]?.let { (bytes * it).toLong() }

    // Chapter list

    fun addFiles(uris: List<Uri>) = viewModelScope.launch {
        val found = withContext(Dispatchers.IO) {
            uris.map { uri -> Saf.nameAndSize(resolver, uri).let { (name, size) -> Chapter(uri, name, size) } }
        }
        addChapters(found)
        breakAtSeasonStarts()
    }

    /**
     * Adds every CBZ in a folder and its subfolders.
     * When chapters come from several subfolders (e.g. one per season), each one starts a new file.
     */
    fun addFolder(tree: Uri) = viewModelScope.launch {
        val (name, found) = withContext(Dispatchers.IO) {
            val entries = runCatching { Saf.listFilesDeep(resolver, tree) }.getOrDefault(emptyList())
            Saf.folderName(resolver, tree) to entries.map { Chapter(it.uri, it.name, it.size, it.folder) }
        }
        if (chapters.isEmpty()) folderName = name
        addChapters(found)
        breakAtEachGroup()
        breakAtSeasonStarts()
    }

    /** Adds files shared to the app from another app. */
    fun addShared(uris: List<Uri>) {
        if (uris.isNotEmpty()) addFiles(uris)
    }

    private fun breakAtEachGroup() {
        val firsts = chapters.groupBy { it.group }.values.map { it.first() }
        if (firsts.size < 2) return
        fileBreaks = fileBreaks + firsts.filter { it != chapters.first() }.map { it.uri }
    }

    /** Starts a new file where the chapter numbers start over (the first chapter of a new season). */
    private fun breakAtSeasonStarts() {
        if (!splitBySeason) return
        val added = chapters.zipWithNext()
            .filter { (a, b) -> Naming.startsNewSeason(a.name, b.name) }
            .map { it.second.uri }
            .filter { it !in dismissedBreaks && it !in fileBreaks }
        fileBreaks = fileBreaks + added
        seasonBreaks = seasonBreaks + added
    }

    fun changeSplitBySeason(on: Boolean) {
        splitBySeason = on
        prefs.edit().putBoolean(KEY_SPLIT_SEASONS, on).apply()
        if (on) {
            dismissedBreaks = emptySet()
            breakAtSeasonStarts()
        } else {
            fileBreaks = fileBreaks - seasonBreaks
            seasonBreaks = emptySet()
        }
    }

    private fun addChapters(found: List<Chapter>) {
        val cbz = found.filter { Naming.isCbz(it.name) }
        val known = chapters.map { Triple(it.group, it.name, it.size) }.toSet()
        val fresh = cbz.filter { Triple(it.group, it.name, it.size) !in known }
        chapters.addAll(fresh)
        sortByName()
        val skipped = found.size - cbz.size
        message = when {
            found.isEmpty() -> "No files found."
            fresh.isEmpty() && cbz.isNotEmpty() -> "These chapters are already in the list."
            skipped > 0 -> "Added ${fresh.size}, skipped $skipped (not .cbz)."
            else -> null
        }
        MergeRunner.reset()
    }

    fun sortByName() {
        val sorted = chapters.sortedWith { a, b ->
            NaturalOrder.compare(a.group, b.group).takeIf { it != 0 } ?: NaturalOrder.compare(a.name, b.name)
        }
        chapters.clear()
        chapters.addAll(sorted)
        onChaptersChanged()
    }

    fun move(index: Int, delta: Int) {
        val other = index + delta
        if (index !in chapters.indices || other !in chapters.indices) return
        chapters[index] = chapters[other].also { chapters[other] = chapters[index] }
        onChaptersChanged()
    }

    /** Moves a chapter to another position (drag and drop). */
    fun moveTo(from: Int, to: Int) {
        if (from !in chapters.indices || to !in chapters.indices || from == to) return
        chapters.add(to, chapters.removeAt(from))
        onChaptersChanged()
    }

    fun remove(index: Int) {
        if (index !in chapters.indices) return
        val uri = chapters.removeAt(index).uri
        excluded = excluded - uri
        fileBreaks = fileBreaks - uri
        onChaptersChanged()
    }

    fun clear() {
        chapters.clear()
        excluded = emptySet()
        fileBreaks = emptySet()
        dismissedBreaks = emptySet()
        seasonBreaks = emptySet()
        customNames.clear()
        cover = null
        folderName = null
        seriesNameEdited = false
        MergeRunner.reset()
        onChaptersChanged()
    }

    fun toggle(uri: Uri) {
        excluded = if (uri in excluded) excluded - uri else excluded + uri
    }

    fun selectAll() {
        excluded = emptySet()
    }

    fun selectNone() {
        excluded = chapters.map { it.uri }.toSet()
    }

    /** Keeps only list positions [from]..[to] (1-based, inclusive) checked. */
    fun selectRange(from: Int, to: Int) {
        val range = (minOf(from, to) - 1)..(maxOf(from, to) - 1)
        excluded = chapters.filterIndexed { i, _ -> i !in range }.map { it.uri }.toSet()
    }

    fun toggleFileBreak(uri: Uri) {
        if (uri in fileBreaks) {
            fileBreaks = fileBreaks - uri
            dismissedBreaks = dismissedBreaks + uri
        } else {
            fileBreaks = fileBreaks + uri
            dismissedBreaks = dismissedBreaks - uri
        }
    }

    fun clearFileBreaks() {
        dismissedBreaks = dismissedBreaks + fileBreaks
        fileBreaks = emptySet()
    }

    private fun onChaptersChanged() {
        if (!seriesNameEdited) {
            seriesName = if (chapters.isEmpty()) "" else folderName ?: Naming.seriesName(chapters.map { it.name })
        }
        (cover as? CoverSource.Page)?.let { c -> if (chapters.none { it.uri == c.chapter.uri }) cover = null }
        findPreviousCover()
        countPages()
        estimateSizes()
    }

    /** Page counts are needed to split files; they are read in the background. */
    private fun countPages() {
        if (countJob?.isActive == true) return
        countJob = viewModelScope.launch {
            while (true) {
                val next = chapters.firstOrNull { it.uri !in pageCounts } ?: break
                pageCounts[next.uri] = withContext(Dispatchers.IO) { Cbz.pageCount(context, next.uri) }
            }
        }
    }

    /** Re-encodes two pages from up to 6 chapters with each preset to estimate the output size. */
    private fun estimateSizes() {
        val list = chapters.toList()
        val format = imageFormat
        estimateJob?.cancel()
        if (list.isEmpty()) {
            sizeRatios = emptyMap()
            return
        }
        // Don't compete with a running merge for the CPU
        if (MergeRunner.isRunning) return
        estimateJob = viewModelScope.launch {
            delay(500)
            sizeRatios = withContext(Dispatchers.IO) {
                val qualities = Quality.entries - Quality.ORIGINAL
                val encoded = qualities.associateWith { 0L }.toMutableMap()
                var original = 0L
                val n = minOf(6, list.size)
                for (chapter in (0 until n).map { list[it * list.size / n] }) {
                    ensureActive()
                    runCatching {
                        Cbz.open(context, chapter.uri).use { cbz ->
                            val pages = cbz.pages
                            if (pages.isEmpty()) return@use
                            for (entry in listOf(pages[pages.size / 3], pages[pages.size * 2 / 3]).distinct()) {
                                val bytes = cbz.read(entry)
                                val ext = Cbz.extension(entry)
                                original += bytes.size
                                qualities
                                    .map { q -> async(Dispatchers.Default) { q to q.encode(bytes, ext, format).first.size } }
                                    .awaitAll()
                                    .forEach { (q, size) -> encoded[q] = encoded.getValue(q) + size }
                            }
                        }
                    }
                }
                if (original == 0L) emptyMap() else encoded.mapValues { it.value.toDouble() / original }
            }
        }
    }

    /** If the list contains an earlier merge, its cover is reused by default. */
    private fun findPreviousCover() {
        val list = chapters.toList()
        coverScanJob?.cancel()
        coverScanJob = viewModelScope.launch {
            previousCover = withContext(Dispatchers.IO) {
                list.firstNotNullOfOrNull { chapter ->
                    if (Naming.seriesOfMerged(chapter.name) == null) return@firstNotNullOfOrNull null
                    val entry = coverEntryCache.getOrPut(chapter.uri) {
                        runCatching { Cbz.open(context, chapter.uri).use { Cbz.cover(it.zip)?.name } }.getOrNull() ?: ""
                    }
                    if (entry.isEmpty()) null
                    else CoverSource.Page(chapter, entry, "From ${Naming.baseName(chapter.name)}")
                }
            }
        }
    }

    // Covers

    /**
     * Copies a picked image into the app's storage right away: the picker's
     * access can expire, and an unreadable file is caught here.
     */
    fun pickCoverImage(uri: Uri, forEditScreen: Boolean) = viewModelScope.launch {
        val picked = withContext(Dispatchers.IO) {
            try {
                val ext = when (resolver.getType(uri)) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    "image/avif" -> "avif"
                    "image/heic", "image/heif" -> "heic"
                    else -> "jpg"
                }
                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(4)?.forEach { it.delete() }
                val file = File(dir, "cover_${System.currentTimeMillis()}.$ext")
                resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                if (Covers.isReadableImage(file)) CoverSource.Image(Uri.fromFile(file), ext)
                else null.also { file.delete() }
            } catch (e: Throwable) {
                null
            }
        }
        when {
            picked == null -> message = "Couldn't read that image. Try another one."
            forEditScreen -> editNewCover = picked
            else -> cover = picked
        }
    }

    fun openEditTarget(uri: Uri) = viewModelScope.launch {
        editNewCover = null
        editStatus = CoverEditStatus.Idle
        val (name, size) = withContext(Dispatchers.IO) { Saf.nameAndSize(resolver, uri) }
        val target = Chapter(uri, name, size)
        editTarget = target
        editCurrentCover = null
        editCurrentCover = withContext(Dispatchers.IO) { currentCover(target) }
        if (editCurrentCover == null) editStatus = CoverEditStatus.Failed("This file isn't a readable CBZ.")
    }

    private fun currentCover(target: Chapter): CoverSource.Page? = runCatching {
        Cbz.open(context, target.uri).use { cbz ->
            (Cbz.cover(cbz.zip) ?: cbz.pages.firstOrNull())?.let { CoverSource.Page(target, it.name, "Current cover") }
        }
    }.getOrNull()

    fun saveEditedCover() {
        val target = editTarget ?: return
        val newCover = editNewCover ?: return
        if (editStatus is CoverEditStatus.Working) return
        viewModelScope.launch {
            editStatus = CoverEditStatus.Working(0f)
            editStatus = try {
                withContext(Dispatchers.IO) {
                    Merger.replaceCover(context, target, newCover) { editStatus = CoverEditStatus.Working(it) }
                }
                coverEntryCache.remove(target.uri)
                editNewCover = null
                editCurrentCover = withContext(Dispatchers.IO) { currentCover(target) }
                CoverEditStatus.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                CoverEditStatus.Failed("This image is too large for the phone's memory.")
            } catch (e: SecurityException) {
                CoverEditStatus.Failed("Android won't let the app edit this file. Copy it to a folder on the phone and try again.")
            } catch (e: Exception) {
                CoverEditStatus.Failed(e.message ?: "Couldn't change the cover.")
            }
        }
    }

    // Merge

    /** Counts any missing pages, then hands the work to [MergeRunner]. */
    fun merge() {
        val folder = outFolder ?: return
        if (plan.isEmpty() || MergeRunner.isRunning) return
        estimateJob?.cancel()
        viewModelScope.launch {
            countJob?.join()
            val missing = chapters.filter { it.uri !in pageCounts }
            pageCounts.putAll(withContext(Dispatchers.IO) { missing.associate { it.uri to Cbz.pageCount(context, it.uri) } })
            MergeRunner.start(context, MergeRequest(folder, plan, effectiveCover, quality, imageFormat))
        }
    }

    fun cancelMerge() = MergeRunner.cancel()

    fun deleteOldVersions() {
        val done = status as? MergeStatus.Done ?: return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { done.oldVersions.count { Saf.delete(resolver, it.uri) } }
            message = if (deleted == done.oldVersions.size) "Old version deleted." else "Deleted $deleted of ${done.oldVersions.size} files."
            MergeRunner.clearOldVersions()
        }
    }

    fun keepOldVersions() = MergeRunner.clearOldVersions()

    private fun readQuality() =
        runCatching { Quality.valueOf(prefs.getString(KEY_QUALITY, null)!!) }.getOrDefault(Quality.ORIGINAL)

    private fun readImageFormat() =
        runCatching { ImageFormat.valueOf(prefs.getString(KEY_FORMAT, null)!!) }.getOrDefault(ImageFormat.WEBP)

    private companion object {
        const val KEY_OUT_FOLDER = "out_folder"
        const val KEY_MAX_PAGES = "max_pages"
        const val KEY_QUALITY = "quality"
        const val KEY_FORMAT = "image_format"
        const val KEY_SPLIT_SEASONS = "split_seasons"
    }
}
