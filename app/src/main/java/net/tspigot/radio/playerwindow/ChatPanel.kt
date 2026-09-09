package net.tspigot.radio.playerwindow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.tspigot.radio.AppConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val VALID_COMMANDS = setOf("like", "name")

enum class ChatMessageKind {
    Normal,
    Like,
    System
}

data class ChatMessage(
    val id: String,
    val kind: ChatMessageKind,
    val text: String,
    val timestamp: Long
)

private object ChatSocketClient {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun connect(listener: WebSocketListener): WebSocket {
        val request = Request.Builder()
            .url("wss://radio.tspigot.net/chat")
            .header("User-Agent", AppConfig.userAgent)
            .build()

        return client.newWebSocket(request, listener)
    }
}

private fun parseChatMessage(json: String): ChatMessage? {
    return try {
        val obj = JSONObject(json)
        val type = obj.optString("type")

        when (type) {
            "message" -> ChatMessage(
                id = obj.optString("id"),
                kind = ChatMessageKind.Normal,
                text = "${obj.optString("username", "anon")}: ${obj.optString("text", "")}",
                timestamp = obj.optLong("timestamp", 0L)
            )

            "like" -> {
                val username = obj.optString("username", "anon")
                val track = obj.optJSONObject("track")
                val song = track?.optString("title", "Unknown song") ?: "Unknown song"
                val author = track?.optString("artist", "Unknown author") ?: "Unknown author"

                ChatMessage(
                    id = obj.optString("id"),
                    kind = ChatMessageKind.Like,
                    text = "$username liked $song by $author",
                    timestamp = obj.optLong("timestamp", 0L)
                )
            }

            "system" -> ChatMessage(
                id = obj.optString("id"),
                kind = ChatMessageKind.System,
                text = obj.optString("text", ""),
                timestamp = obj.optLong("timestamp", 0L)
            )

            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private data class OutgoingPayload(
    val json: String
)

private fun buildOutgoingPayload(input: String): OutgoingPayload? {
    val text = input.trim()
    if (text.isEmpty()) return null

    if (!text.startsWith("/")) {
        return OutgoingPayload(
            JSONObject()
                .put("type", "message")
                .put("text", text)
                .toString()
        )
    }

    val withoutSlash = text.removePrefix("/").trim()
    if (withoutSlash.isEmpty()) return null

    val parts = withoutSlash.split(Regex("\\s+"), limit = 2)
    val command = parts[0].lowercase()
    val argsText = parts.getOrNull(1)?.trim().orEmpty()

    val args = JSONArray()
    if (argsText.isNotEmpty()) {
        args.put(argsText)
    }

    return OutgoingPayload(
        JSONObject()
            .put("type", "command")
            .put("command", command)
            .put("args", args)
            .toString()
    )
}

@Composable
fun ChatPanel(
    modifier: Modifier = Modifier
) {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var socket by remember { mutableStateOf<WebSocket?>(null) }

    DisposableEffect(Unit) {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                socket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseChatMessage(text)?.let { msg ->
                    messages.add(msg)
                    if (messages.size > 100) {
                        messages.removeAt(0)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                socket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                socket = null
            }
        }

        val ws = ChatSocketClient.connect(listener)

        onDispose {
            connected = false
            socket = null
            ws.close(1000, "bye")
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Divider()
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (connected) "Chat" else "Chat (offline)",
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                val messageColor = when (msg.kind) {
                    ChatMessageKind.Normal -> MaterialTheme.colorScheme.onSurface
                    ChatMessageKind.Like -> Color(0xFF88ff88)
                    ChatMessageKind.System -> Color(0xff99AAAA)
                }

                Text(
                    text = msg.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = messageColor,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                label = { Text("Message") }
            )

            Button(
                enabled = connected && input.isNotBlank(),
                onClick = {
                    val text = input.trim()

                    // Check if it's a command and if that command is unknown
                    if (text.startsWith("/")) {
                        val cmd = text.removePrefix("/").trim().split(Regex("\\s+"))[0].lowercase()
                        if (cmd !in VALID_COMMANDS) {
                            messages.add(
                                ChatMessage(
                                    id = "local_${System.currentTimeMillis()}",
                                    kind = ChatMessageKind.System,
                                    text = "Unknown command: /$cmd",
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                            input = ""
                            return@Button
                        }
                    }

                    val payload = buildOutgoingPayload(input)
                    if (payload != null) {
                        socket?.send(payload.json)
                        input = ""
                    }
                }
            ) {
                Text("Send")
            }
        }
    }
}