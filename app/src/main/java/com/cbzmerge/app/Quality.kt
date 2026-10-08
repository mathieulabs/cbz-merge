package com.cbzmerge.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import java.io.ByteArrayOutputStream

/** Format used when pages are re-encoded. */
enum class ImageFormat(val title: String, val detail: String, val ext: String) {
    WEBP("WebP", "Smaller files, slower to merge. Works in most readers.", "webp"),
    JPEG("JPEG", "About 3x faster to merge, files a bit bigger. Works everywhere.", "jpg");

    @Suppress("DEPRECATION") // WEBP is lossy when quality < 100; WEBP_LOSSY needs API 30
    val compressFormat: Bitmap.CompressFormat
        get() = when {
            this == JPEG -> Bitmap.CompressFormat.JPEG
            Build.VERSION.SDK_INT >= 30 -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.WEBP
        }
}

/** Size presets for the merged files. */
enum class Quality(val title: String, val detail: String, val level: Int, val maxWidth: Int?) {
    ORIGINAL("Original", "Images are copied as they are", 0, null),
    HIGH("High", "Full size, quality 85", 85, null),
    BALANCED("Balanced", "Up to 1080 px wide, quality 75", 75, 1080),
    SMALL("Small", "Up to 800 px wide, quality 65", 65, 800),
    SMALLEST("Smallest", "Up to 720 px wide, quality 50", 50, 720);

    /**
     * Re-encodes one page. The original is kept when it's already smaller,
     * when it can't be decoded, or when it's a GIF (may be animated).
     */
    fun encode(bytes: ByteArray, ext: String, format: ImageFormat): Pair<ByteArray, String> {
        if (this == ORIGINAL || ext == "gif") return bytes to ext
        return try {
            val plan = decodePlan(bytes) ?: return bytes to ext
            val target = plan.targetWidth
            val options = BitmapFactory.Options().apply {
                inSampleSize = plan.sample
                inPreferredConfig = plan.config
            }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return bytes to ext
            if (bmp.width > target) {
                val scaled = Bitmap.createScaledBitmap(bmp, target, (bmp.height.toLong() * target / bmp.width).toInt(), true)
                if (scaled != bmp) bmp.recycle()
                bmp = scaled
            }
            if (bmp.hasAlpha()) {
                // No transparency in the output: put the page on white
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.RGB_565)
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bmp, 0f, 0f, null)
                }
                bmp.recycle()
                bmp = flat
            }
            // WebP can't go above 16383 px: very long strips fall back to JPEG
            val usedFormat = if (format == ImageFormat.WEBP && maxOf(bmp.width, bmp.height) > 16383) ImageFormat.JPEG else format
            val out = ByteArrayOutputStream(bytes.size / 2)
            val ok = bmp.compress(usedFormat.compressFormat, level, out)
            bmp.recycle()
            val result = out.toByteArray()
            if (ok && result.isNotEmpty() && result.size < bytes.size) result to usedFormat.ext else bytes to ext
        } catch (e: OutOfMemoryError) {
            bytes to ext
        } catch (e: Exception) {
            bytes to ext
        }
    }

    /** Rough memory needed to re-encode this page (decoded bitmap, scaled copy, output). */
    fun memoryNeeded(bytes: ByteArray): Long {
        if (this == ORIGINAL) return 0
        val plan = decodePlan(bytes) ?: return bytes.size.toLong()
        val bytesPerPixel = if (plan.config == Bitmap.Config.RGB_565) 2 else 4
        return plan.pixels * bytesPerPixel * 2 + bytes.size
    }

    private class DecodePlan(val sample: Int, val targetWidth: Int, val pixels: Long, val config: Bitmap.Config)

    private fun decodePlan(bytes: ByteArray): DecodePlan? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val target = minOf(w, maxWidth ?: w)
        var sample = 1
        while (w / (sample * 2) >= target) sample *= 2
        val pixels = (w.toLong() / sample) * (h.toLong() / sample)
        // Long webtoon strips: half the memory
        val config = if (pixels > 20_000_000) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        return DecodePlan(sample, target, pixels, config)
    }
}
