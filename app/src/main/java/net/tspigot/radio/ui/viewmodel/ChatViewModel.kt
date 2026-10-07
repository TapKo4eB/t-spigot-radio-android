package net.tspigot.radio.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.tspigot.radio.AppSettings
import net.tspigot.radio.data.ChatMessage
import net.tspigot.radio.data.ChatMessageStore
import net.tspigot.radio.data.ChatSocketClient
import net.tspigot.radio.data.VALID_COMMANDS
import net.tspigot.radio.data.buildOutgoingPayload
import net.tspigot.radio.data.localSystemMessage
import net.tspigot.radio.data.parseChatMessage
import net.tspigot.radio.data.parseHistoryMessages
import net.tspigot.radio.data.parseSlashCommand
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connected: Boolean = false,
    val input: String = "",
    val receivedCount: Long = 0,
    val resetCount: Long = 0
)

class ChatViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ChatUiState(input = savedStateHandle["input"] ?: ""))
    val uiState = _uiState.asStateFlow()
    private val store = ChatMessageStore()
    private var socket: WebSocket? = null
    private var sentName: String? = null
    private var reconnectJob: Job? = null
    private var generation = 0
    private var cleared = false

    init {
        // Exactly one consumer survives configuration changes with this ViewModel.
        viewModelScope.launch {
            while (true) {
                val batch = store.applyNextBatch()
                _uiState.update {
                    it.copy(
                        messages = store.messages,
                        receivedCount = it.receivedCount + batch.addedCount,
                        resetCount = it.resetCount + if (batch.cleared) 1 else 0
                    )
                }
            }
        }
        reconnect()
    }

    fun updateInput(input: String) {
        savedStateHandle["input"] = input
        _uiState.update { it.copy(input = input) }
    }

    fun sendInput() {
        val text = _uiState.value.input.trim()
        if (!_uiState.value.connected || text.isEmpty()) return
        if (text.startsWith("/")) {
            val parsed = parseSlashCommand(text)
            val command = parsed?.command.orEmpty()
            val args = parsed?.argsText.orEmpty()
            if (parsed == null || command !in VALID_COMMANDS) {
                store.post(listOf(localSystemMessage("Unknown command: /$command")))
                updateInput("")
                return
            }
            if (command == "name") {
                if (args.isEmpty()) {
                    store.post(listOf(localSystemMessage("Usage: /name <username>")))
                } else {
                    AppSettings.setChatName(getApplication(), args)
                    val payload = buildOutgoingPayload(text)
                    if (payload != null && socket?.send(payload.json) == true) sentName = args
                }
                updateInput("")
                return
            }
            if (command == "say") {
                if (args.isEmpty()) {
                    store.post(listOf(localSystemMessage("Usage: /say <message>")))
                    updateInput("")
                } else if (sendUserPayload(JSONObject().put("type", "message").put("text", args).toString())) {
                    updateInput("")
                }
                return
            }
        }
        val payload = buildOutgoingPayload(text)
        if (payload != null && sendUserPayload(payload.json)) updateInput("")
    }

    fun sendLike(): Boolean = buildOutgoingPayload("/like")?.let {
        sendUserPayload(it.json)
    } ?: false

    private fun sendUserPayload(json: String): Boolean {
        if (!_uiState.value.connected) return false
        val ws = socket ?: return false
        val name = AppSettings.getChatName(getApplication())
        if (name.isNotBlank() && name != sentName) {
            val payload = buildOutgoingPayload("/name $name")
            if (payload != null && ws.send(payload.json)) sentName = name
        }
        return ws.send(json)
    }

    fun reconnect() {
        if (cleared) return
        reconnectJob?.cancel()
        reconnectJob = null
        val currentGeneration = ++generation
        socket?.close(1000, "reconnect")
        socket = null
        sentName = null
        _uiState.update { it.copy(connected = false) }

        // OkHttp callbacks arrive on its worker threads. Publish state on Main
        // and ignore callbacks belonging to any replaced or released socket.
        fun publish(action: () -> Unit) {
            viewModelScope.launch {
                if (!cleared && generation == currentGeneration) action()
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = publish {
                store.reset()
                _uiState.update { it.copy(connected = true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val messages = if (json.has("history")) {
                        json.optJSONArray("history")?.let { parseHistoryMessages(it) }.orEmpty()
                    } else listOfNotNull(parseChatMessage(json))
                    publish { store.post(messages) }
                } catch (_: Exception) {
                    Log.w("ChatViewModel", "Failed to parse incoming chat message")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = publish {
                _uiState.update { it.copy(connected = false) }
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = publish {
                connectionLost()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = publish {
                connectionLost()
            }
        }
        socket = ChatSocketClient.connect(listener)
    }

    private fun connectionLost() {
        ++generation
        socket = null
        sentName = null
        _uiState.update { it.copy(connected = false) }
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(10.seconds)
            // Don't cancel this job from inside reconnect().
            reconnectJob = null
            reconnect()
        }
    }

    override fun onCleared() {
        cleared = true
        ++generation
        reconnectJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
        super.onCleared()
    }
}
