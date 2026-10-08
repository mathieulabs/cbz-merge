package com.cbzmerge.app

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A CBZ opened for reading. Close it when done. */
class CbzFile(val zip: ZipFile, private val fd: ParcelFileDescriptor?, private val tempFile: File?) : Closeable {
    val pages: List<ZipEntry> by lazy { Cbz.pages(zip) }

    fun read(entry: ZipEntry): ByteArray = zip.getInputStream(entry).use { it.readBytes() }

    override fun close() {
        runCatching { zip.close() }
        runCatching { fd?.close() }
        tempFile?.delete()
    }
}

object Cbz {
    /** Name of the cover image the app puts first in the archive. */
    const val COVER_PREFIX = "0000_cover"

    private val imageExt = Regex("\\.(jpe?g|png|webp|gif|avif|bmp|jxl|heic)$", RegexOption.IGNORE_CASE)

    fun isImage(e: ZipEntry): Boolean {
        if (e.isDirectory || e.name.contains("__MACOSX")) return false
        val leaf = e.name.substringAfterLast('/')
        return !leaf.startsWith(".") && imageExt.containsMatchIn(leaf)
    }

    fun isCover(e: ZipEntry) = isImage(e) &&
        e.name.substringAfterLast('/').startsWith(COVER_PREFIX, ignoreCase = true)

    /** Pages in reading order, without the cover added by this app. */
    fun pages(zip: ZipFile): List<ZipEntry> = zip.entries().toList()
        .filter { isImage(it) && !isCover(it) }
        .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }

    fun cover(zip: ZipFile): ZipEntry? = zip.entries().toList().firstOrNull(::isCover)

    fun extension(e: ZipEntry) = e.name.substringAfterLast('.').lowercase().let { if (it == "jpeg") "jpg" else it }

    /**
     * Opens a CBZ from a content Uri.
     * Files on the phone are read in place through their file descriptor.
     * Anything else (cloud files, etc.) is copied to the cache first.
     */
    fun open(context: Context, uri: Uri): CbzFile {
        val resolver = context.contentResolver
        try {
            val fd = resolver.openFileDescriptor(uri, "r")
            if (fd != null) {
                try {
                    return CbzFile(ZipFile(File("/proc/self/fd/${fd.fd}")), fd, null)
                } catch (e: Exception) {
                    fd.close()
                }
            }
        } catch (_: Exception) {
        }
        val temp = File.createTempFile("cbz", ".tmp", context.cacheDir)
        try {
            resolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            return CbzFile(ZipFile(temp), null, temp)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /** Counts pages, or 0 if the file can't be read. */
    fun pageCount(context: Context, uri: Uri): Int =
        runCatching { open(context, uri).use { it.pages.size } }.getOrDefault(0)
}

/** Adds an uncompressed entry. Images are already compressed, so this is faster and loses nothing. */
fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
    val crc = CRC32().apply { update(bytes) }
    putNextEntry(ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        this.crc = crc.value
    })
    write(bytes)
    closeEntry()
}
