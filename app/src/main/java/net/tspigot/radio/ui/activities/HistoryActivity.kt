package net.tspigot.radio.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.tspigot.radio.AppConfig
import net.tspigot.radio.data.BookmarkStore
import net.tspigot.radio.data.NowPlaying
import net.tspigot.radio.R
import net.tspigot.radio.ui.TimedToastHost
import net.tspigot.radio.ui.rememberTimedToastController
import net.tspigot.radio.ui.theme.TSpigotRadioTheme
import net.tspigot.radio.util.HistoryLine
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

private const val HISTORY_REFRESH_SECONDS = 10L
private val HISTORY_ALLOWED_TYPES = setOf("FULL_TRACK", "BED", "AMBIENCE", "VOICE", "COMMERCIAL")
class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TSpigotRadioTheme {
                HistoryScreen(onBack = { finish() })
            }
        }
    }
}

private suspend fun fetchHistory(): List<NowPlaying>? = withContext(Dispatchers.IO) {
    val connection = URL("https://radio.tspigot.net/api/history").openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", AppConfig.userAgent)

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val array = JSONArray(body)
        List(array.length()) { NowPlaying.fromJson(array.getJSONObject(it)) }
    } catch (_: Exception) {
        null // keep showing the previous list on failure
    } finally {
        connection.disconnect()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    var entries by remember { mutableStateOf<List<NowPlaying>>(emptyList()) }
    var showAll by rememberSaveable { mutableStateOf(false) }

    val visibleEntries = remember(entries, showAll) {
        if (showAll) entries else entries.filter { it.type in HISTORY_ALLOWED_TYPES }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val listState = rememberLazyListState()

    val context = LocalContext.current
    var menuExpandedFor by remember { mutableStateOf<String?>(null) }

    val toast = rememberTimedToastController()

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                fetchHistory()?.let { newEntries ->
                    val wasAtTop = listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0

                    entries = newEntries

                    if (wasAtTop) {
                        withFrameNanos { }
                        listState.animateScrollToItem(0)
                    }
                }
                delay(HISTORY_REFRESH_SECONDS.seconds)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAll = !showAll }) {
                        Icon(
                            painter = painterResource(
                                if (showAll)
                                    R.drawable.expand_content
                                else
                                    R.drawable.collapse_content
                            ),
                            contentDescription = if (showAll)
                                "Show only main track types"
                            else
                                "Show all track types"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(visibleEntries, key = { "${it.id}-${it.lastPlayEpoch}" }) { entry ->
                        val entryKey = "${entry.id}-${entry.lastPlayEpoch}"

                        Row(
                            modifier = Modifier.fillMaxWidth().animateItem(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HistoryLine(entry, timeFormat, Modifier.weight(1f))

                            Box {
                                IconButton(onClick = { menuExpandedFor = entryKey }) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = "More options"
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpandedFor == entryKey,
                                    onDismissRequest = { menuExpandedFor = null }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Bookmark") },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(R.drawable.bookmark_add),
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            menuExpandedFor = null
                                            val added = BookmarkStore.addBookmark(context, entry)
                                            toast.show(if (added) "Song bookmarked" else "Song already bookmarked")
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                TimedToastHost(
                    controller = toast,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                )
            }
        }
    }
}