package net.tspigot.radio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.tspigot.radio.playerwindow.buildHistoryLine
import net.tspigot.radio.ui.TimedToastHost
import net.tspigot.radio.ui.rememberTimedToastController
import net.tspigot.radio.ui.theme.TSpigotRadioTheme
import java.text.SimpleDateFormat
import java.util.Locale

class BookmarksActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TSpigotRadioTheme {
                BookmarksScreen(onBack = { finish() })
            }
        }
    }
}

private data class UndoState(
    val message: String,
    val removed: List<IndexedValue<NowPlaying>>
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf(BookmarkStore.getBookmarks(context)) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var menuExpandedFor by remember { mutableStateOf<String?>(null) }
    var bulkMenuExpanded by remember { mutableStateOf(false) }

    var undoState by remember { mutableStateOf<UndoState?>(null) }
    var dismissJob by remember { mutableStateOf<Job?>(null) }
    val undoProgress = remember { Animatable(1f) }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val selectionMode = selectedKeys.isNotEmpty()

    fun persist(newEntries: List<NowPlaying>) {
        entries = newEntries
        BookmarkStore.saveBookmarks(context, newEntries)
    }

    val toast = rememberTimedToastController()

    fun undoRemoval(removed: List<IndexedValue<NowPlaying>>) {
        val restored = entries.toMutableList()
        removed.sortedBy { it.index }.forEach { (index, track) ->
            restored.add(index.coerceIn(0, restored.size), track)
        }
        persist(restored)
    }

    fun removeBookmarks(toRemove: List<NowPlaying>) {
        if (toRemove.isEmpty()) return

        val removedKeys = toRemove.map { it.bookmarkKey() }.toSet()
        val removedWithIndex = entries.withIndex().filter { it.value.bookmarkKey() in removedKeys }
        persist(entries.filterNot { it.bookmarkKey() in removedKeys })

        toast.show(
            message = if (toRemove.size == 1) "Bookmark removed" else "${toRemove.size} bookmarks removed",
            actionLabel = "Undo",
            onAction = { undoRemoval(removedWithIndex) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectionMode) "${selectedKeys.size} selected" else "Bookmarks") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectionMode) selectedKeys = emptySet() else onBack()
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
                                        selectedKeys = emptySet()
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
                                        selectedKeys = if (selected) selectedKeys - entryKey
                                        else selectedKeys + entryKey
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) selectedKeys = setOf(entryKey)
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

                        Text(
                            text = remember(entry) { buildHistoryLine(entry, timeFormat) },
                            modifier = Modifier.weight(1f)
                        )

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
