package net.tspigot.radio.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import net.tspigot.radio.ui.viewmodel.BookmarksUiState
import net.tspigot.radio.ui.viewmodel.BookmarksViewModel
import net.tspigot.radio.util.HistoryLine

class BookmarksActivity : ComponentActivity() {
    private val viewModel: BookmarksViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            TSpigotRadioTheme {
                BookmarksScreen(
                    state = state,
                    onSelect = viewModel::select,
                    onRemove = viewModel::remove,
                    onUndoRemoval = viewModel::undoRemoval,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen(
    state: BookmarksUiState,
    onSelect: (Set<String>) -> Unit,
    onRemove: (List<NowPlaying>) -> List<IndexedValue<NowPlaying>>,
    onUndoRemoval: (List<IndexedValue<NowPlaying>>) -> Unit,
    onBack: () -> Unit
) {
    val entries = state.entries
    val selectedKeys = state.selectedKeys
    var menuExpandedFor by remember { mutableStateOf<String?>(null) }
    var bulkMenuExpanded by remember { mutableStateOf(false) }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val selectionMode = selectedKeys.isNotEmpty()

    val toast = rememberTimedToastController()

    fun removeBookmarks(toRemove: List<NowPlaying>) {
        val removed = onRemove(toRemove)
        if (removed.isEmpty()) return
        toast.show(
            message = if (removed.size == 1) "Bookmark removed" else "${removed.size} bookmarks removed",
            actionLabel = "Undo",
            onAction = { onUndoRemoval(removed) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectionMode) "${selectedKeys.size} selected" else "Bookmarks") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectionMode) onSelect(emptySet()) else onBack()
                    }) {
                        Icon(
                            painter = painterResource(
                                if (selectionMode) R.drawable.close else R.drawable.arrow_back
                            ),
                            contentDescription = if (selectionMode) "Cancel selection" else "Back"
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        Box {
                            IconButton(onClick = { bulkMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.more_horiz),
                                    contentDescription = "More options"
                                )
                            }
                            DropdownMenu(
                                expanded = bulkMenuExpanded,
                                onDismissRequest = { bulkMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Remove") },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.bookmark_remove),
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        bulkMenuExpanded = false
                                        val toRemove = entries.filter { it.bookmarkKey() in selectedKeys }
                                        onSelect(emptySet())
                                        removeBookmarks(toRemove)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(entries, key = { it.bookmarkKey() }) { entry ->
                    val entryKey = entry.bookmarkKey()
                    val selected = entryKey in selectedKeys

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        onSelect(
                                            if (selected) selectedKeys - entryKey
                                            else selectedKeys + entryKey
                                        )
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) onSelect(setOf(entryKey))
                                }
                            )
                            .background(if (selected) Color(0xFF2A2A2A) else Color.Transparent)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AnimatedVisibility(visible = selectionMode) {
                            Icon(
                                painter = painterResource(
                                    if (selected) R.drawable.select_check_box
                                    else R.drawable.check_box_outline_blank
                                ),
                                contentDescription = if (selected) "Selected" else "Not selected",
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp)
                            )
                        }

                        HistoryLine(entry, timeFormat, Modifier.weight(1f))


                        if (!selectionMode) {
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
                                        text = { Text("Remove") },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(R.drawable.bookmark_remove),
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            menuExpandedFor = null
                                            removeBookmarks(listOf(entry))
                                        }
                                    )
                                }
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
