package net.tspigot.radio

import android.content.Context
import androidx.core.content.edit

object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_CHAT_NAME = "chat_name"
    private const val KEY_BOOKMARK_ON_LIKE = "bookmark_on_like"

    fun getChatName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHAT_NAME, "") ?: ""

    fun setChatName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_CHAT_NAME, name.trim()) }
    }
    fun getBookmarkOnLike(context: Context): Boolean {
       val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BOOKMARK_ON_LIKE, true) // default on
    }

    fun setBookmarkOnLike(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_BOOKMARK_ON_LIKE, enabled) }
    }
}