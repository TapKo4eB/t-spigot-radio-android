package net.tspigot.radio.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.WebSocket

class ChatConnectionState {
    var connected by mutableStateOf(false)
    var socket by mutableStateOf<WebSocket?>(null)
    @Volatile
    var sentName: String? = null
}