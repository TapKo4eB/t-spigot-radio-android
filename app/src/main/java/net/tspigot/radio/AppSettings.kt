package net.tspigot.radio

import android.content.Context

object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_CHAT_NAME = "chat_name"

    fun getChatName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHAT_NAME, "") ?: ""

    fun setChatName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CHAT_NAME, name.trim()).apply()
    }
}