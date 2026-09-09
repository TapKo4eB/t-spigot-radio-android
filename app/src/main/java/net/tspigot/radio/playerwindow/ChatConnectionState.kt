package net.tspigot.radio.playerwindow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.WebSocket

class ChatConnectionState {
    var connected by mutableStateOf(false)
    var socket by mutableStateOf<WebSocket?>(null)
}