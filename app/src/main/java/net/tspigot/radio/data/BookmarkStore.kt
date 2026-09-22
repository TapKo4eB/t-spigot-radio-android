package net.tspigot.radio.data

import android.content.Context
import org.json.JSONArray

object BookmarkStore {
    private const val PREFS_NAME = "bookmarks"
    private const val KEY_BOOKMARKS = "bookmarked_songs"

    fun addBookmark(context: Context, track: NowPlaying): Boolean {
        val current = getBookmarks(context).toMutableList()

        val alreadyBookmarked = current.any { it.bookmarkKey() == track.bookmarkKey() }
        if (alreadyBookmarked) return false

        current.add(0, track)
        saveBookmarks(context, current)
        return true
    }

    fun getBookmarks(context: Context): List<NowPlaying> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { NowPlaying.fromJson(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveBookmarks(context: Context, tracks: List<NowPlaying>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        tracks.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply()
    }
}