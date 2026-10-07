package net.tspigot.radio.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import net.tspigot.radio.AppConfig
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.util.concurrent.atomic.AtomicLong

private const val MAX_MESSAGES = 1000

internal val VALID_COMMANDS = setOf("like", "name", "say")

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

internal fun localSystemMessage(text: String) = ChatMessage(
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
 * channel). A single consumer in the ViewModel calls [applyNextBatch], which
 * drains everything that has queued up, merges/dedupes/trims it on a
 * background dispatcher, and publishes ONE new immutable list. So a burst of
 * messages (or a big history dump) costs one recomposition, not hundreds, and
 * the list is only published from the main thread.
 *
 * Owned by ChatViewModel so the consumer and messages survive activity recreation.
 */
class ChatMessageStore {
    var messages: List<ChatMessage> = emptyList()
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

internal object ChatSocketClient {
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

internal fun parseChatMessage(obj: JSONObject): ChatMessage? {
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

internal fun parseHistoryMessages(historyArray: JSONArray): List<ChatMessage> {
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
 * it's special-cased by ChatViewModel, since it must be
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
