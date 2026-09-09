package net.tspigot.radio.playerwindow

import android.util.Log
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.tspigot.radio.AppConfig
import net.tspigot.radio.R
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

private fun parseChatMessage(obj: JSONObject): ChatMessage? {
    return try {
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

private fun parseHistoryMessages(historyArray: JSONArray): List<ChatMessage> {
    val result = ArrayList<ChatMessage>(historyArray.length())

    for (i in 0 until historyArray.length()) {
        try {
            // optJSONObject returns null instead of throwing if the
            // element isn't a JSON object (e.g. null, a string, a number).
            val item = historyArray.optJSONObject(i)
            if (item == null) {
                Log.w("ChatPanel", "history[$i] is not a JSON object, skipping")
                continue
            }

            val msg = parseChatMessage(item)
            if (msg == null) {
                Log.w("ChatPanel", "history[$i] failed to parse (unknown type or bad fields), skipping")
                continue
            }

            result.add(msg)
        } catch (e: Exception) {
            // Belt-and-braces: catch anything unexpected per-item so the
            // loop itself can never abort partway through.
            Log.w("ChatPanel", "history[$i] threw while parsing, skipping", e)
        }
    }

    return result
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

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // True while the list is scrolled all the way to the last item.
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (layoutInfo.totalItemsCount == 0 || visibleItems.isEmpty()) {
                true
            } else {
                val lastVisible = visibleItems.last()
                lastVisible.index == layoutInfo.totalItemsCount - 1 &&
                        (lastVisible.offset + lastVisible.size) <= layoutInfo.viewportEndOffset
            }
        }
    }

    var stickToBottom by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && !isAtBottom) {
                stickToBottom = false
            }
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            stickToBottom = true
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && stickToBottom) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    DisposableEffect(Unit) {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                socket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("history")) {
                        val historyArray = json.optJSONArray("history")
                        if (historyArray != null) {
                            messages.addAll(parseHistoryMessages(historyArray))
                        } else {
                            Log.w("ChatPanel", "'history' field present but not an array")
                        }
                    } else {
                        parseChatMessage(json)?.let { msg ->
                            messages.add(msg)
                        }
                    }

                    while (messages.size > 1000) {
                        messages.removeAt(0)
                    }
                } catch (_: Exception) {
                    // Only reachable now for JSON that fails to parse at all
                    // (e.g. a truncated/corrupted frame), not for per-item issues.
                    Log.w("ChatPanel", "failed to parse incoming message: ${text.take(200)}")
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = listState
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

            if (!isAtBottom) {
                FloatingActionButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    onClick = {
                        stickToBottom = true
                        coroutineScope.launch {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(messages.size - 1)
                            }
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.arrow_down),
                        contentDescription = "Scroll to bottom"
                    )
                }
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
                    stickToBottom = true

                    val text = input.trim()

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

