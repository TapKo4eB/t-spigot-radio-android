package net.tspigot.radio.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Locale
import net.tspigot.radio.R
import net.tspigot.radio.data.NowPlaying
import net.tspigot.radio.ui.screens.TimedToastHost
import net.tspigot.radio.ui.screens.rememberTimedToastController
import net.tspigot.radio.ui.theme.TSpigotRadioTheme
import net.tspigot.radio.ui.viewmodel.HistoryUiState
import net.tspigot.radio.ui.viewmodel.HistoryViewModel
import net.tspigot.radio.util.HistoryLine

class HistoryActivity : ComponentActivity() {
    private val viewModel: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            TSpigotRadioTheme {
                HistoryScreen(
                    state = state,
                    onToggleShowAll = viewModel::toggleShowAll,
                    onBookmark = viewModel::bookmark,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startRefreshing()
    }

    override fun onStop() {
        viewModel.stopRefreshing()
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onToggleShowAll: () -> Unit,
    onBookmark: (NowPlaying) -> Boolean,
    onBack: () -> Unit
) {
    val entries = state.entries
    val showAll = state.showAll
    val visibleEntries = remember(entries, showAll) { state.visibleEntries }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val listState = rememberLazyListState()

    var menuExpandedFor by remember { mutableStateOf<String?>(null) }

    val toast = rememberTimedToastController()

    LaunchedEffect(entries) {
        val wasAtTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        if (wasAtTop && entries.isNotEmpty()) {
            withFrameNanos { }
            listState.animateScrollToItem(0)
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
                    IconButton(onClick = onToggleShowAll) {
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
                                            val added = onBookmark(entry)
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
