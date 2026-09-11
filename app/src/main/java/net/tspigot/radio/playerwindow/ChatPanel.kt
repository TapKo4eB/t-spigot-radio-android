package net.tspigot.radio.playerwindow

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import androidx.compose.ui.focus.onFocusChanged

private val VALID_COMMANDS = setOf("like", "name", "say")

private val TIME_CODE_COLOR = Color(0xFF888888)

private val timeCodeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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

/**
 * Formats a "timestamp" field (epoch seconds, e.g. `1788954990`) into a
 * local-time "HH:mm" string. Falls back to "--:--" if the timestamp is
 * missing/invalid (0 or negative) so a malformed message never crashes
 * the row.
 */
private fun formatTimeCode(timestampSeconds: Long): String {
    if (timestampSeconds <= 0L) return "--:--"

    return try {
        Instant.ofEpochSecond(timestampSeconds)
            .atZone(ZoneId.systemDefault())
            .format(timeCodeFormatter)
    } catch (_: Exception) {
        "--:--"
    }
}

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

/**
 * Result of splitting a "/command args..." string into its parts.
 * Returns null if there's nothing after the leading slash at all
 * (e.g. input was just "/" or "/   ").
 */
internal data class ParsedCommand(
    val command: String,
    val argsText: String
)

internal fun parseSlashCommand(text: String): ParsedCommand? {
    val withoutSlash = text.removePrefix("/").trim()
    if (withoutSlash.isEmpty()) return null

    val parts = withoutSlash.split(Regex("\\s+"), limit = 2)
    val command = parts[0].lowercase()
    val argsText = parts.getOrNull(1)?.trim().orEmpty()

    return ParsedCommand(command, argsText)
}

internal data class OutgoingPayload(
    val json: String
)

/**
 * Builds the payload for a plain message or a generic "command" type
 * (e.g. "/like"). Note: "/say" is intentionally NOT handled here -
 * it's special-cased in the send handler below, since it must be
 * emitted as a "message" payload rather than a "command" payload.
 */
internal fun buildOutgoingPayload(input: String): OutgoingPayload? {
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

    val parsed = parseSlashCommand(text) ?: return null

    val args = JSONArray()
    if (parsed.argsText.isNotEmpty()) {
        args.put(parsed.argsText)
    }

    return OutgoingPayload(
        JSONObject()
            .put("type", "command")
            .put("command", parsed.command)
            .put("args", args)
            .toString()
    )
}

@Composable
fun ChatPanel(
    connectionState: ChatConnectionState,
    modifier: Modifier = Modifier
) {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }

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
    var newMessageCount by remember { mutableStateOf(0) }

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
            newMessageCount = 0
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
                connectionState.connected = true
                connectionState.socket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val sizeBefore = messages.size

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

                    val addedCount = messages.size - sizeBefore
                    if (addedCount > 0 && !stickToBottom) {
                        newMessageCount += addedCount
                    }

                    while (messages.size > 1000) {
                        messages.removeAt(0)
                    }
                } catch (_: Exception) {
                    Log.w("ChatPanel", "failed to parse incoming message: ${text.take(200)}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connectionState.connected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectionState.connected = false
                connectionState.socket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectionState.connected = false
                connectionState.socket = null
            }
        }

        val ws = ChatSocketClient.connect(listener)

        onDispose {
            connectionState.connected = false
            connectionState.socket = null
            ws.close(1000, "bye")
        }
    }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .imePadding()
        ) {
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (connectionState.connected) "Chat" else "Chat (offline)",
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

                    val displayText = buildAnnotatedString {
                        withStyle(SpanStyle(color = TIME_CODE_COLOR)) {
                            append(formatTimeCode(msg.timestamp))
                        }
                        append(" ")
                        withStyle(SpanStyle(color = messageColor)) {
                            append(msg.text)
                        }
                    }

                    Text(
                        text = displayText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            if (newMessageCount > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clickable {
                            newMessageCount = 0
                            stickToBottom = true
                            coroutineScope.launch {
                                if (messages.isNotEmpty()) {
                                    listState.animateScrollToItem(messages.size - 1)
                                }
                            }
                        },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    tonalElevation = 4.dp
                ) {
                    Text(
                        text = if (newMessageCount == 1) {
                            "1 new message"
                        } else {
                            "$newMessageCount new messages"
                        },
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            if (!isAtBottom) {
                FloatingActionButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    onClick = {
                        stickToBottom = true
                        newMessageCount = 0
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
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged
                    { focusState ->
                        if (focusState.isFocused) {
                            // Keyboard is about to come up: make sure we're
                            // pinned to the latest messages once the panel
                            // reflows upward.
                            stickToBottom = true
                            coroutineScope.launch {
                                if (messages.isNotEmpty()) {
                                    listState.animateScrollToItem(messages.size - 1)
                                }
                            }
                        }
                    },
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                label = { Text("Say something") }
            )

            IconButton(
                enabled = connectionState.connected && input.isNotBlank(),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10)),

                onClick = {
                    stickToBottom = true

                    val text = input.trim()

                    if (text.startsWith("/")) {
                        val parsed = parseSlashCommand(text)
                        val cmd = parsed?.command.orEmpty()
                        val argsText = parsed?.argsText.orEmpty()

                        if (parsed == null || cmd !in VALID_COMMANDS) {
                            messages.add(
                                ChatMessage(
                                    id = "local_${System.currentTimeMillis()}",
                                    kind = ChatMessageKind.System,
                                    text = "Unknown command: /$cmd",
                                    timestamp = Instant.now().epochSecond
                                )
                            )
                            input = ""
                            return@IconButton
                        }

                        if (cmd == "name" && argsText.isEmpty()) {
                            messages.add(
                                ChatMessage(
                                    id = "local_${System.currentTimeMillis()}",
                                    kind = ChatMessageKind.System,
                                    text = "Usage: /name <username>",
                                    timestamp = Instant.now().epochSecond
                                )
                            )
                            input = ""
                            return@IconButton
                        }

                        if (cmd == "say") {
                            // /say is handled entirely client-side: its
                            // argument is sent as a plain "message" payload,
                            // never wrapped as a "command".
                            if (argsText.isEmpty()) {
                                messages.add(
                                    ChatMessage(
                                        id = "local_${System.currentTimeMillis()}",
                                        kind = ChatMessageKind.System,
                                        text = "Usage: /say <message>",
                                        timestamp = Instant.now().epochSecond
                                    )
                                )
                            } else {
                                val sayPayload = JSONObject()
                                    .put("type", "message")
                                    .put("text", argsText)
                                    .toString()
                                connectionState.socket?.send(sayPayload)
                            }
                            input = ""
                            return@IconButton
                        }
                    }

                    val payload = buildOutgoingPayload(input)
                    if (payload != null) {
                        connectionState.socket?.send(payload.json)
                        input = ""
                    }
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.send),
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}