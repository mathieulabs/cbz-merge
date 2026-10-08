package com.cbzmerge.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** A file written by [Merger.merge]. */
data class Written(val fileName: String, val pages: Int, val bytes: Long)

object Merger {
    /** Pages in flight at once: enough to keep every core busy while pages are written. */
    private val PIPELINE_SIZE = Runtime.getRuntime().availableProcessors().coerceIn(2, 8) * 2

    /** Keeps decoded bitmaps within half of the app's memory, whatever the page sizes. */
    private val memory = MemoryBudget(Runtime.getRuntime().maxMemory() / 2)

    private class PendingPage(val chapter: Int, val page: Int, val result: Deferred<Pair<ByteArray, String>>)

    /**
     * Writes one merged CBZ into [outFolder].
     *
     * Pages are renamed "0001_0001.jpg" (chapter_page) so every reader keeps the order.
     * The file is written as "<name>.part" and renamed at the end, so a failed or
     * cancelled merge never leaves a broken file or removes an existing one.
     *
     * [onPage] is called after each page with the index of the chapter being read.
     */
    suspend fun merge(
        context: Context,
        outFolder: Uri,
        fileName: String,
        chapters: List<Chapter>,
        cover: Pair<ByteArray, String>?,
        quality: Quality,
        format: ImageFormat,
        onPage: (chapterIndex: Int) -> Unit
    ): Written {
        val resolver = context.contentResolver
        val partName = "$fileName.part"
        Saf.listFiles(resolver, outFolder).filter { it.name == partName }.forEach { Saf.delete(resolver, it.uri) }

        var outUri = DocumentsContract.createDocument(resolver, Saf.folderUri(outFolder), "application/octet-stream", partName)
            ?: error("Couldn't create the file in the output folder.")

        var pages = 0
        var finished = false
        try {
            resolver.openOutputStream(outUri, "w")!!.buffered(1 shl 16).use { out ->
                ZipOutputStream(out).use { zip ->
                    cover?.let { (bytes, ext) -> zip.putStored("${Cbz.COVER_PREFIX}.$ext", bytes) }

                    coroutineScope {
                        // Pipeline: pages are read in order, re-encoded on all cores at once,
                        // and written in order as soon as the oldest one is ready.
                        val pending = ArrayDeque<PendingPage>()
                        suspend fun writeOldest() {
                            val page = pending.removeFirst()
                            val (bytes, ext) = page.result.await()
                            zip.putStored(String.format(Locale.ROOT, "%04d_%04d.%s", page.chapter + 1, page.page + 1, ext), bytes)
                            pages++
                            onPage(page.chapter)
                        }

                        chapters.forEachIndexed { ci, chapter ->
                            ensureActive()
                            val cbz = try {
                                Cbz.open(context, chapter.uri)
                            } catch (e: Exception) {
                                error("\"${chapter.name}\" isn't a valid CBZ.")
                            }
                            cbz.use {
                                if (cbz.pages.isEmpty()) error("No images in \"${chapter.name}\".")
                                cbz.pages.forEachIndexed { pi, entry ->
                                    ensureActive()
                                    val bytes = cbz.read(entry)
                                    val ext = Cbz.extension(entry)
                                    val result = if (quality == Quality.ORIGINAL) {
                                        CompletableDeferred(bytes to ext)
                                    } else {
                                        async(Dispatchers.Default) {
                                            memory.reserve(quality.memoryNeeded(bytes)) { quality.encode(bytes, ext, format) }
                                        }
                                    }
                                    pending.addLast(PendingPage(ci, pi, result))
                                    while (pending.size >= PIPELINE_SIZE) writeOldest()
                                }
                            }
                        }
                        while (pending.isNotEmpty()) writeOldest()
                    }

                    val imageCount = pages + if (cover != null) 1 else 0
                    val info = ComicInfo.create(Naming.seriesOfMerged(fileName), imageCount, hasCover = cover != null)
                    zip.putStored(ComicInfo.FILE_NAME, info.toByteArray())
                }
            }
            finished = true
        } finally {
            if (!finished) Saf.delete(resolver, outUri)
        }

        Saf.listFiles(resolver, outFolder).filter { it.name == fileName }.forEach { Saf.delete(resolver, it.uri) }
        runCatching { DocumentsContract.renameDocument(resolver, outUri, fileName) }.getOrNull()?.let { outUri = it }
        val (finalName, size) = Saf.nameAndSize(resolver, outUri)
        return Written(finalName, pages, size)
    }

    /**
     * Earlier merges of the same series that overlap the new files,
     * e.g. "Title ch.1-45" once "Title ch.1-52" exists.
     */
    fun findOldVersions(context: Context, outFolder: Uri, written: List<Written>): List<DocEntry> {
        val newNames = written.map { it.fileName }.toSet()
        val newRanges = written.mapNotNull { w ->
            Naming.seriesOfMerged(w.fileName)?.let { it to Naming.mergedRange(w.fileName) }
        }
        if (newRanges.isEmpty()) return emptyList()

        return Saf.listFiles(context.contentResolver, outFolder).filter { f ->
            if (f.name in newNames || !Naming.isCbz(f.name)) return@filter false
            val series = Naming.seriesOfMerged(f.name) ?: return@filter false
            val range = Naming.mergedRange(f.name)
            newRanges.any { (s, r) ->
                s == series && (r == null || range == null || (range.start <= r.endInclusive && r.start <= range.endInclusive))
            }
        }
    }

    /** Replaces the cover of an existing CBZ. Pages and other files are copied unchanged. */
    suspend fun replaceCover(context: Context, target: Chapter, cover: CoverSource, onProgress: (Float) -> Unit) {
        val resolver = context.contentResolver
        val (coverBytes, coverExt) = Covers.load(context, cover)
        val temp = File.createTempFile("cover", ".cbz", context.cacheDir)
        try {
            Cbz.open(context, target.uri).use { cbz ->
                val entries = cbz.zip.entries().toList()
                val isInfo = { e: ZipEntry -> e.name.equals(ComicInfo.FILE_NAME, ignoreCase = true) }
                val keep = entries.filter { !Cbz.isCover(it) && !isInfo(it) }
                val oldInfo = entries.firstOrNull(isInfo)?.let { String(cbz.read(it)) }
                val imageCount = keep.count(Cbz::isImage) + 1

                ZipOutputStream(temp.outputStream().buffered(1 shl 16)).use { zip ->
                    zip.putStored("${Cbz.COVER_PREFIX}.$coverExt", coverBytes)
                    keep.forEachIndexed { i, e ->
                        coroutineContext.ensureActive()
                        if (e.isDirectory) {
                            zip.putNextEntry(ZipEntry(e.name))
                            zip.closeEntry()
                        } else {
                            zip.putStored(e.name, cbz.read(e))
                        }
                        onProgress((i + 1f) / keep.size * 0.8f)
                    }
                    val info = oldInfo?.let { ComicInfo.withCover(it, imageCount) }
                        ?: ComicInfo.create(Naming.seriesOfMerged(target.name), imageCount, hasCover = true)
                    zip.putStored(ComicInfo.FILE_NAME, info.toByteArray())
                }
            }
            // The new archive is complete: now overwrite the original
            val out: OutputStream = runCatching { resolver.openOutputStream(target.uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(target.uri, "w")
                ?: error("Couldn't write to this file.")
            out.use { o -> temp.inputStream().use { it.copyTo(o, 1 shl 16) } }
            onProgress(1f)
        } finally {
            temp.delete()
        }
    }
}
