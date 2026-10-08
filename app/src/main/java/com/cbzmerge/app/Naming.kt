package com.cbzmerge.app

/** Sorts names the way people expect: "ch 2" before "ch 10", case-insensitive. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(a).map { it.value }.toList()
        val cb = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                val xs = x.trimStart('0')
                val ys = y.trimStart('0')
                if (xs.length != ys.length) xs.length - ys.length else xs.compareTo(ys)
            } else {
                x.compareTo(y, ignoreCase = true)
            }
            if (r != 0) return r
        }
        return ca.size - cb.size
    }
}

/**
 * File name helpers.
 * Merged files are named "<series> ch.<first>-<last>.cbz", which lets the app
 * recognise its own output later (to reuse a cover or replace an old version).
 */
object Naming {
    private val cbzExt = Regex("\\.(cbz|zip)$", RegexOption.IGNORE_CASE)
    private val lastNumber = Regex("(\\d+(?:\\.\\d+)?)(?!.*\\d)")
    private val markedNumber = Regex(
        "(?<!\\p{L})(?:ch|chap|chapter|chapitre|ep|episode|épisode|#|제)\\.?\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*(?:화|話)",
        RegexOption.IGNORE_CASE
    )
    private val mergedSuffix = Regex("ch\\.\\s*([\\d.]+)\\s*-\\s*([\\d.]+)$", RegexOption.IGNORE_CASE)
    private val mergedName = Regex("^(.+) ch\\.[\\d.]+-[\\d.]+$")

    // Chapter markers at the end of a name: " - 012", "_ch.5", " Episode 3", "제12화"...
    private val chapterTail = Regex(
        "(?:[\\s_\\-.#\\[(]+(?:ch|chap|chapter|chapitre|c|ep|episode|épisode|vol)\\.?)?" +
            "[\\s_\\-.#\\[(]*(?:제)?\\s*[\\d.]*\\s*(?:화|話)?[\\s_\\-.#\\])]*$",
        RegexOption.IGNORE_CASE
    )

    fun isCbz(fileName: String) = cbzExt.containsMatchIn(fileName)

    fun baseName(fileName: String) = fileName.replace(cbzExt, "")

    /** Chapter number in a file name. For a merged file, the start or end of its range. */
    fun chapterNumber(fileName: String, last: Boolean): String? {
        val base = baseName(fileName)
        mergedSuffix.find(base)?.let { return tidy(it.groupValues[if (last) 2 else 1]) }
        // A number next to a chapter marker ("ch 12", "12화") wins over any other number in the name
        val marked = markedNumber.findAll(base).lastOrNull()?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
        return (marked ?: lastNumber.find(base)?.groupValues?.get(1))?.let { tidy(it) }
    }

    /**
     * True when [next] looks like the first chapter of a new season: the numbering
     * starts over (0, 1 or 2) after a higher number, e.g. "56화" followed by "1화".
     * A decimal such as 1.5 is a bonus chapter, not a restart.
     */
    fun startsNewSeason(previous: String, next: String): Boolean {
        val before = chapterNumber(previous, last = true)?.toDoubleOrNull() ?: return false
        val after = chapterNumber(next, last = false)?.toDoubleOrNull() ?: return false
        return after < before && after <= 2.0 && after % 1.0 == 0.0
    }

    /** "0012" -> "12", "5.50" -> "5.5" */
    private fun tidy(number: String): String =
        runCatching { java.math.BigDecimal(number).stripTrailingZeros().toPlainString() }.getOrDefault(number)

    /** Guesses the series name from chapter file names (their common start, minus chapter markers). */
    fun seriesName(names: List<String>): String {
        if (names.isEmpty()) return ""
        val bases = names.map { baseName(it).replace(mergedSuffix, "").trim() }
        var prefix = bases[0]
        for (b in bases) while (prefix.isNotEmpty() && !b.startsWith(prefix)) prefix = prefix.dropLast(1)
        var s = prefix
        while (true) {
            val trimmed = s.replace(chapterTail, "").trim()
            if (trimmed == s) break
            s = trimmed
        }
        return s.ifBlank { "Merged" }
    }

    /** "Title ch.1-50.cbz" -> "Title". Null if the name doesn't look like a merged file. */
    fun seriesOfMerged(fileName: String): String? =
        mergedName.find(baseName(fileName))?.groupValues?.get(1)

    /** "Title ch.1-50.cbz" -> 1.0..50.0 */
    fun mergedRange(fileName: String): ClosedFloatingPointRange<Double>? {
        val m = mergedSuffix.find(baseName(fileName)) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val b = m.groupValues[2].toDoubleOrNull() ?: return null
        return minOf(a, b)..maxOf(a, b)
    }

    fun safeFileName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Merged" }
}
