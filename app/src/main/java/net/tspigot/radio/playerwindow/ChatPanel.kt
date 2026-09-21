package net.tspigot.radio.playerwindow

import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.tspigot.radio.AppConfig
import net.tspigot.radio.AppSettings
import net.tspigot.radio.R
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private const val MAX_MESSAGES = 1000

private val VALID_COMMANDS = setOf("like", "name", "say")

private val TIME_CODE_COLOR = Color(0xFF888888)

private val timeCodeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Source of unique fallback ids (missing/blank server ids, local messages). */
private val idCounter = AtomicLong()

private fun syntheticId(prefix: String): String = "${prefix}_${idCounter.incrementAndGet()}"


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
) {
    /**
     * Computed once, when the message is created (for server messages that is
     * on the OkHttp thread, not during composition). Not part of equals/hashCode.
     * Note: the "Nd" day prefix is fixed at creation time and is not refreshed
     * after midnight.
     */
    val timeCode: String = formatTimeCode(timestamp)
}

private fun localSystemMessage(text: String) = ChatMessage(
    id = syntheticId("local"),
    kind = ChatMessageKind.System,
    text = text,
    timestamp = Instant.now().epochSecond
)

/**
 * Formats a "timestamp" field (epoch seconds, e.g. `1788954990`) into a
 * local-time "HH:mm" string. Falls back to "--:--" if the timestamp is
 * missing/invalid (0 or negative) so a malformed message never crashes
 * the row.
 *
 * If the message's local calendar date is before today's local calendar
 * date, the result is prefixed with how many days ago that date was,
 * e.g. "1d 12:24" for yesterday, "792d 12:24" for 792 days ago.
 */
private fun formatTimeCode(timestampSeconds: Long): String {
    if (timestampSeconds <= 0L) return "--:--"

    return try {
        val zone = ZoneId.systemDefault()
        val messageDateTime = Instant.ofEpochSecond(timestampSeconds).atZone(zone)
        val timePart = messageDateTime.format(timeCodeFormatter)

        val messageDate = messageDateTime.toLocalDate()
        val today = LocalDate.now(zone)
        val daysAgo = ChronoUnit.DAYS.between(messageDate, today)

        if (daysAgo > 0) {
            "${daysAgo}d $timePart"
        } else {
            timePart
        }
    } catch (_: Exception) {
        "--:--"
    }
}

/** A unit of work handed from the socket thread to the UI thread. */
internal class Incoming(
    val messages: List<ChatMessage>,
    /** If true, the current list is discarded before [messages] are added. */
    val clear: Boolean
)

internal class BatchResult(
    val cleared: Boolean,
    val addedCount: Int
)

/**
 * Holds the chat message list.
 *
 * Threading model: any thread may call [post] / [reset] (they only push into a
 * channel). A single consumer on the UI side calls [applyNextBatch], which
 * drains everything that has queued up, merges/dedupes/trims it on a
 * background dispatcher, and publishes ONE new immutable list. So a burst of
 * messages (or a big history dump) costs one recomposition, not hundreds, and
 * Compose state is only ever written from the main thread.
 *
 * To keep the messages across screen rotation/navigation, create this in a
 * ViewModel (or other longer-lived holder) and pass it to [ChatPanel].
 */
@Stable
class ChatMessageStore {
    var messages: List<ChatMessage> by mutableStateOf(emptyList())
        private set

    private val incoming = Channel<Incoming>(Channel.UNLIMITED)

    /** Appends messages. Safe to call from any thread. */
    fun post(newMessages: List<ChatMessage>) {
        if (newMessages.isNotEmpty()) {
            incoming.trySend(Incoming(newMessages, clear = false))
        }
    }

    /** Clears the list. Safe to call from any thread. */
    fun reset() {
        incoming.trySend(Incoming(emptyList(), clear = true))
    }

    internal suspend fun applyNextBatch(): BatchResult {
        val batch = ArrayList<Incoming>()
        batch.add(incoming.receive())
        while (true) {
            val next = incoming.tryReceive().getOrNull() ?: break
            batch.add(next)
        }

        var cleared = false
        var added = 0
        for (b in batch) {
            if (b.clear) {
                cleared = true
                added = 0
            } else {
                added += b.messages.size
            }
        }

        val current = messages
        val merged = withContext(Dispatchers.Default) {
            val working = ArrayList<ChatMessage>(current.size + added)
            working.addAll(current)
            for (b in batch) {
                if (b.clear) working.clear()
                working.addAll(b.messages)
            }
            // LazyColumn crashes on duplicate keys, so drop repeated ids
            // (e.g. a live message that is also present in the history dump).
            working.distinctBy { it.id }.takeLast(MAX_MESSAGES)
        }

        messages = merged
        return BatchResult(cleared, added)
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
        // Missing/blank ids would collide as LazyColumn keys and crash the list.
        val id = obj.optString("id").ifBlank { syntheticId("gen") }
        val timestamp = obj.optLong("timestamp", 0L)

        when (type) {
            "message" -> ChatMessage(
                id = id,
                kind = ChatMessageKind.Normal,
                text = "${obj.optString(
                    "username", 
                    "anon")}: ${obj.optString("text", "")}",
                timestamp = timestamp
            )

            "like" -> {
                val username = obj.optString("username", "anon")
                val track = obj.optJSONObject("track")
                val song = track?.optString("title", "Unknown song") ?: "Unknown song"
                val author = track?.optString("artist", "Unknown author") ?: "Unknown author"

                ChatMessage(
                    id = id,
                    kind = ChatMessageKind.Like,
                    text = "$username liked $song by $author",
                    timestamp = timestamp
                )
            }

            "system" -> ChatMessage(
                id = id,
                kind = ChatMessageKind.System,
                text = obj.optString("text", ""),
                timestamp = timestamp
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
                Log.w(
                    "ChatPanel",
                    "history[$i] failed to parse (unknown type or bad fields), skipping")
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

/**
 * Sends a user-originated payload (message, /like, /say, ...). If no /name has
 * been sent on this connection yet and a chat name is set, sends /name first.
 * Returns true if the payload itself was sent.
 */
internal fun ChatConnectionState.sendUserPayload(context: Context, payloadJson: String): Boolean {
    val ws = socket ?: return false

    val name = AppSettings.getChatName(context)
    if (name.isNotBlank() && name != sentName) {
        val namePayload = buildOutgoingPayload("/name $name")
        if (namePayload != null && ws.send(namePayload.json)) {
            sentName = name
        }
    }

    return ws.send(payloadJson)
}

/**
 * One chat line. Kept as its own composable so the annotated string is built
 * once per message (and only rebuilt if the message or its color changes),
 * instead of on every recomposition/scroll-in.
 */
@Composable
private fun ChatMessageRow(msg: ChatMessage) {
    val messageColor = when (msg.kind) {
        ChatMessageKind.Normal -> MaterialTheme.colorScheme.onSurface
        ChatMessageKind.Like -> Color(0xFF88ff88)
        ChatMessageKind.System -> Color(0xff99AAAA)
    }

    val displayText = remember(msg.id, messageColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = TIME_CODE_COLOR)) {
                append(msg.timeCode)
            }
            append(" ")
            withStyle(SpanStyle(color = messageColor)) {
                append(msg.text)
            }
        }
    }

    // Selection is scoped per message: a single SelectionContainer around the
    // whole LazyColumn makes every row register with one shared registrar,
    // which is expensive. The trade-off is that a drag-selection can no
    // longer span several messages.
    SelectionContainer {
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}

@Composable
fun ChatPanel(
    connectionState: ChatConnectionState,
    modifier: Modifier = Modifier,
    store: ChatMessageStore = remember { ChatMessageStore() }
) {
    val context = LocalContext.current

    val messages = store.messages
    var input by remember { mutableStateOf("") }

    var reconnectTrigger by remember { mutableStateOf(0) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // The list uses reverseLayout = true, so index 0 is the NEWEST message and
    // it sits at the bottom. "At the bottom" is therefore simply "first
    // visible item is index 0 with no scroll offset" - no need to walk
    // visibleItemsInfo.
    val isAtBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
        }
    }

    var stickToBottom by remember { mutableStateOf(true) }
    var newMessageCount by remember { mutableStateOf(0) }
    var userDragging by remember { mutableStateOf(false) }

    fun scrollToNewest() {
        coroutineScope.launch {
            listState.animateScrollToItem(0)
        }
    }

    // Track whether the finger is currently dragging the list.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> userDragging = true
                is DragInteraction.Stop,
                is DragInteraction.Cancel -> userDragging = false

                else -> Unit
            }
        }
    }

    // The user pulled the list away from the bottom by hand: stop auto-following.
    // (Checked while dragging rather than at drag start, because at drag start
    // the list is still at the bottom.)
    LaunchedEffect(listState) {
        snapshotFlow { userDragging && !isAtBottom }.collect { leftBottomByDrag ->
            if (leftBottomByDrag) stickToBottom = false
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            stickToBottom = true
            newMessageCount = 0
        }
    }

    // Keyed on the newest message id, not on messages.size: once the list is
    // capped at MAX_MESSAGES the size stops changing, but the newest id still does.
    val newestId = messages.lastOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null && stickToBottom) {
            // One-item hop with reverseLayout, never a long scroll.
            listState.animateScrollToItem(0)
        }
    }

    // Single consumer for everything the socket (and local commands) produce.
    // Each iteration publishes one batched update on the main thread.
    LaunchedEffect(store) {
        while (true) {
            val result = store.applyNextBatch()
            if (result.cleared) {
                stickToBottom = true
                newMessageCount = 0
                listState.scrollToItem(0)
            } else if (result.addedCount > 0 && !stickToBottom) {
                newMessageCount += result.addedCount
            }
        }
    }

    DisposableEffect(reconnectTrigger) {
        var pendingReconnectJob: Job? = null
        // Callbacks from a socket that has already been disposed must not
        // touch shared connection state or schedule reconnects.
        val disposed = AtomicBoolean(false)

        fun scheduleReconnect() {
            if (disposed.get()) return
            pendingReconnectJob?.cancel()
            pendingReconnectJob = coroutineScope.launch {
                delay(10.seconds)
                reconnectTrigger++
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (disposed.get()) return

                store.reset()

                connectionState.sentName = null
                connectionState.connected = true
                connectionState.socket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (disposed.get()) return

                try {
                    val json = JSONObject(text)

                    if (json.has("history")) {
                        val historyArray = json.optJSONArray("history")
                        if (historyArray != null) {
                            store.post(parseHistoryMessages(historyArray))
                        } else {
                            Log.w("ChatPanel", "'history' field present but not an array")
                        }
                    } else {
                        parseChatMessage(json)?.let { msg ->
                            store.post(listOf(msg))
                        }
                    }
                } catch (_: Exception) {
                    Log.w(
                        "ChatPanel",
                        "failed to parse incoming message: ${text.take(200)}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (disposed.get()) return
                connectionState.connected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (disposed.get()) return
                connectionState.connected = false
                connectionState.socket = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (disposed.get()) return
                connectionState.connected = false
                connectionState.socket = null
                scheduleReconnect()
            }
        }

        val ws = ChatSocketClient.connect(listener)

        onDispose {
            disposed.set(true)
            pendingReconnectJob?.cancel()
            connectionState.connected = false
            connectionState.socket = null
            ws.close(1000, "bye")
        }
    }

    // Display order for reverseLayout: newest first. asReversed() is an O(1) view.
    val displayMessages = remember(messages) { messages.asReversed() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (connectionState.connected) "Chat" else "Chat (offline)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(end = 6.dp)
            )
            if (!connectionState.connected) {
                Icon(
                    painter = painterResource(R.drawable.refresh),
                    contentDescription = "Refresh",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable {
                            reconnectTrigger++
                        }
                )
            }

        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = listState,
                reverseLayout = true
            ) {
                items(
                    items = displayMessages,
                    key = { it.id },
                    contentType = { it.kind }
                ) { msg ->
                    ChatMessageRow(msg)
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
                            scrollToNewest()
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

            // Also require !stickToBottom so the button doesn't flash for the
            // instant between a new message arriving and the auto-scroll landing.
            if (!isAtBottom && !stickToBottom) {
                FloatingActionButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    onClick = {
                        stickToBottom = true
                        newMessageCount = 0
                        scrollToNewest()
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            // Keyboard is about to come up: make sure we're
                            // pinned to the latest messages once the panel
                            // reflows upward.
                            stickToBottom = true
                            scrollToNewest()
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
                            store.post(listOf(
                                localSystemMessage("Unknown command: /$cmd")))
                            input = ""
                            return@IconButton
                        }

                        if (cmd == "name" && argsText.isEmpty()) {
                            store.post(listOf(
                                localSystemMessage("Usage: /name <username>")))
                            input = ""
                            return@IconButton
                        }

                        if (cmd == "name") {
                            AppSettings.setChatName(context, argsText)

                            val payload = buildOutgoingPayload(text)
                            if (payload != null &&
                                connectionState.socket?.send(payload.json) == true) {
                                connectionState.sentName = argsText
                            }
                            input = ""
                            return@IconButton
                        }

                        if (cmd == "say") {
                            // /say is handled entirely client-side: its
                            // argument is sent as a plain "message" payload,
                            // never wrapped as a "command".
                            if (argsText.isEmpty()) {
                                store.post(listOf(
                                    localSystemMessage("Usage: /say <message>")))
                            } else {
                                val sayPayload = JSONObject()
                                    .put("type", "message")
                                    .put("text", argsText)
                                    .toString()
                                connectionState.sendUserPayload(context, sayPayload)
                            }
                            input = ""
                            return@IconButton
                        }
                    }

                    val payload = buildOutgoingPayload(input)
                    if (payload != null) {
                        connectionState.sendUserPayload(context, payload.json)
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