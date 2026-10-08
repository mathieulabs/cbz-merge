package com.cbzmerge.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** Where a cover comes from. [label] is shown in the UI. */
sealed interface CoverSource {
    val label: String

    /** An image picked from the gallery, already copied into the app's storage. */
    data class Image(val uri: Uri, val ext: String, override val label: String = "Image from gallery") : CoverSource

    /** An image inside a CBZ: one of its pages, or the cover of an earlier merge. */
    data class Page(val chapter: Chapter, val entry: String, override val label: String) : CoverSource
}

object Covers {
    /** Bytes and extension of the cover, ready to be stored in a CBZ. */
    fun load(context: Context, src: CoverSource): Pair<ByteArray, String> = when (src) {
        is CoverSource.Image -> {
            val bytes = context.contentResolver.openInputStream(src.uri)!!.use { it.readBytes() }
            cropTallImage(bytes, src.ext)
        }
        is CoverSource.Page -> Cbz.open(context, src.chapter.uri).use { cbz ->
            val e = cbz.zip.getEntry(src.entry) ?: error("Cover image not found.")
            val bytes = cbz.read(e)
            // A cover from an earlier merge is already cropped
            if (Cbz.isCover(e)) bytes to Cbz.extension(e) else cropTallImage(bytes, Cbz.extension(e))
        }
    }

    /**
     * Webtoon pages are long vertical strips. As a cover, only the top part
     * is kept, at a 2:3 ratio. Other images are returned unchanged.
     */
    private fun cropTallImage(bytes: ByteArray, ext: String): Pair<ByteArray, String> {
        val (w, h) = size(bytes) ?: return bytes to ext
        if (h <= w * 2) return bytes to ext
        var sample = 1
        while (w / sample > 1600) sample *= 2
        val bmp = decodeTop(bytes, w, sample) ?: return bytes to ext
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        return out.toByteArray() to "jpg"
    }

    /** True if Android can decode this image file. */
    fun isReadableImage(file: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    /** Small bitmap for the UI. Tall images show their top part. */
    fun thumbnail(bytes: ByteArray, targetWidth: Int = 240): Bitmap? {
        val (w, h) = size(bytes) ?: return null
        var sample = 1
        while (w / (sample * 2) >= targetWidth) sample *= 2
        return if (h > w * 3 / 2) {
            decodeTop(bytes, w, sample)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    fun thumbnail(context: Context, src: CoverSource): Bitmap? =
        try {
            thumbnail(load(context, src).first)
        } catch (e: Throwable) {
            null
        }

    fun thumbnail(cbz: CbzFile, entry: String): Bitmap? =
        try {
            cbz.zip.getEntry(entry)?.let { thumbnail(cbz.read(it)) }
        } catch (e: Throwable) {
            null
        }

    private fun size(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /** Decodes the top 2:3 part of an image. */
    @Suppress("DEPRECATION") // newInstance(byte[], int, int) needs API 31
    private fun decodeTop(bytes: ByteArray, width: Int, sample: Int): Bitmap? =
        try {
            val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            decoder.decodeRegion(Rect(0, 0, width, width * 3 / 2), opts).also { decoder.recycle() }
        } catch (e: Throwable) {
            null
        }
}

/**
 * ComicInfo.xml is read by Komga, Kavita, Mihon and others.
 * The app uses it to mark the first image as the front cover.
 */
object ComicInfo {
    const val FILE_NAME = "ComicInfo.xml"

    private const val COVER_PAGES = "  <Pages>\n    <Page Image=\"0\" Type=\"FrontCover\" />\n  </Pages>\n"

    fun create(series: String?, pageCount: Int, hasCover: Boolean): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<ComicInfo xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
        if (!series.isNullOrBlank()) append("  <Series>${escape(series)}</Series>\n")
        append("  <PageCount>$pageCount</PageCount>\n")
        if (hasCover) append(COVER_PAGES)
        append("</ComicInfo>\n")
    }

    /** Updates an existing file, keeping its other fields (title, writer...). */
    fun withCover(existing: String, pageCount: Int): String {
        val cleaned = existing
            .replace(Regex("<Pages\\s*/>|<Pages>[\\s\\S]*?</Pages>"), "")
            .replace(Regex("<PageCount>[\\s\\S]*?</PageCount>"), "")
        val end = cleaned.lastIndexOf("</ComicInfo>")
        if (end < 0) return create(null, pageCount, hasCover = true)
        val block = "  <PageCount>$pageCount</PageCount>\n$COVER_PAGES"
        return (cleaned.substring(0, end) + block + cleaned.substring(end)).replace(Regex("\n\\s*\n"), "\n")
    }

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
