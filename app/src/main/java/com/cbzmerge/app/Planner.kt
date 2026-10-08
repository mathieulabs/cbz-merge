package com.cbzmerge.app

import android.net.Uri

/** One file to create. */
data class OutputPlan(
    val fileName: String,
    val chapters: List<Chapter>,
    val pages: Int,
    val bytes: Long,
    /** True when the user typed this name instead of using the generated one. */
    val renamed: Boolean = false
) {
    /** Identifies this file while the plan changes: a file is known by its first chapter. */
    val key: Uri get() = chapters.first().uri
}

object Planner {
    /**
     * Turns the chapter list into output files.
     * - unchecked chapters are skipped
     * - a file break starts a new file (e.g. one file per season)
     * - each file is cut at a chapter boundary once it would go over maxPages (0 = no limit)
     * - [names] holds names typed by the user, keyed by the first chapter of a file
     */
    fun plan(
        series: String,
        chapters: List<Chapter>,
        excluded: Set<Uri>,
        breaks: Set<Uri>,
        pageCounts: Map<Uri, Int>,
        maxPages: Int,
        names: Map<Uri, String> = emptyMap()
    ): List<OutputPlan> {
        val groups = mutableListOf<MutableList<Chapter>>()
        var current = mutableListOf<Chapter>()
        var pages = 0
        var pendingBreak = false
        for (c in chapters) {
            if (c.uri in breaks) pendingBreak = true
            if (c.uri in excluded) continue
            val n = pageCounts[c.uri] ?: 0
            val overLimit = maxPages > 0 && current.isNotEmpty() && pages + n > maxPages
            if (current.isNotEmpty() && (pendingBreak || overLimit)) {
                groups += current
                current = mutableListOf()
                pages = 0
            }
            pendingBreak = false
            current += c
            pages += n
        }
        if (current.isNotEmpty()) groups += current

        // Seasons are found from numbering restarts (56 -> 1 starts season 2), among the chapters that are kept.
        // Every chapter gets its season and its position inside it, whatever its name says.
        val kept = chapters.filter { it.uri !in excluded }
        val season = HashMap<Uri, Int>()
        val position = HashMap<Uri, Int>()
        var seasonCount = 1
        var inSeason = 0
        kept.forEachIndexed { i, c ->
            if (i > 0 && Naming.startsNewSeason(kept[i - 1].name, c.name)) {
                seasonCount++
                inSeason = 0
            }
            inSeason++
            season[c.uri] = seasonCount
            position[c.uri] = inSeason
        }
        // A season keeps its real chapter numbers only if they can be trusted:
        // all readable, never going back, and not wildly higher than the chapter count ("ch.42-1582")
        val trusted = kept.groupBy { season.getValue(it.uri) }.mapValues { (_, list) ->
            val numbers = list.map { Naming.chapterNumber(it.name, last = false)?.toDoubleOrNull() }
            val known = numbers.filterNotNull()
            known.size == numbers.size &&
                known.zipWithNext().all { (x, y) -> y >= x } &&
                known.last() - known.first() <= 2 * list.size + 20
        }

        // Chapter number shown in a file name: the real one when the season's numbers can be trusted, else its position
        fun number(c: Chapter, last: Boolean): String {
            val real = if (trusted.getValue(season.getValue(c.uri))) Naming.chapterNumber(c.name, last) else null
            return real ?: position.getValue(c.uri).toString()
        }

        val base = Naming.safeFileName(series.ifBlank { "Merged" })
        val severalFolders = chapters.map { it.group }.distinct().size > 1
        val used = mutableSetOf<String>()
        return groups.map { g ->
            // Files made from a single subfolder carry its name: "Title - Season 2 ch.1-40"
            val folder = g.map { it.group }.distinct().singleOrNull()?.substringAfterLast('/')
            val prefix = if (severalFolders && !folder.isNullOrBlank()) Naming.safeFileName("$base - $folder") else base
            val custom = names[g.first().uri]?.let { Naming.safeFileName(Naming.baseName(it)) }
            val firstSeason = season.getValue(g.first().uri)
            val lastSeason = season.getValue(g.last().uri)
            // With several seasons the name says which one: "Title S2 ch.1-40" (a subfolder name already does,
            // unless the file crosses seasons)
            fun tag(season: Int) = if (seasonCount > 1 && (prefix == base || firstSeason != lastSeason)) " S$season" else ""
            val from = number(g.first(), last = false)
            val to = number(g.last(), last = true)
            var name = custom ?: when {
                // A file that crosses seasons: "Title S1 ch.57 - S2 ch.20"
                firstSeason != lastSeason -> "$prefix${tag(firstSeason)} ch.$from -${tag(lastSeason)} ch.$to"
                from == to -> "$prefix${tag(firstSeason)} ch.$from"
                else -> "$prefix${tag(firstSeason)} ch.$from-$to"
            }
            var k = 2
            val stem = name
            while (!used.add(name.lowercase())) name = "$stem (${k++})"
            OutputPlan("$name.cbz", g, g.sumOf { pageCounts[it.uri] ?: 0 }, g.sumOf { it.size }, renamed = custom != null)
        }
    }
}
