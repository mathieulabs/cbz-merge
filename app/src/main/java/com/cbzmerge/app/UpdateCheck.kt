package com.cbzmerge.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Asks GitHub whether a newer release than the installed version exists. */
object UpdateCheck {
    private const val REPO = "mathieulabs/cbz-merge"

    data class Update(val version: String, val page: String)

    /** Returns the newer release, or null when up to date or when anything goes wrong (offline, rate limit...). */
    suspend fun latest(context: Context): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return@runCatching null
            val connection = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode != 200) return@runCatching null
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val version = json.getString("tag_name").removePrefix("v")
                val page = json.getString("html_url")
                // Only ever send the user to this project's own releases
                if (!page.startsWith("https://github.com/$REPO/")) return@runCatching null
                if (isNewer(version, current)) Update(version, page) else null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** Compares versions like "1.10" and "1.9" number by number. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
