package net.tspigot.radio.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.tspigot.radio.data.BookmarkStore
import net.tspigot.radio.data.NowPlaying

data class BookmarksUiState(
    val entries: List<NowPlaying> = emptyList(),
    val selectedKeys: Set<String> = emptySet()
)

class BookmarksViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(BookmarksUiState(
        entries = BookmarkStore.getBookmarks(application),
        selectedKeys = savedStateHandle.get<ArrayList<String>>("selectedKeys")?.toSet().orEmpty()
    ))
    val uiState = _uiState.asStateFlow()

    fun refresh() {
        val entries = BookmarkStore.getBookmarks(getApplication())
        val validKeys = entries.map { it.bookmarkKey() }.toSet()
        _uiState.update { it.copy(entries = entries) }
        select(_uiState.value.selectedKeys.intersect(validKeys))
    }

    fun select(keys: Set<String>) {
        savedStateHandle["selectedKeys"] = ArrayList(keys)
        _uiState.update { it.copy(selectedKeys = keys) }
    }

    fun remove(toRemove: List<NowPlaying>): List<IndexedValue<NowPlaying>> {
        val keys = toRemove.map { it.bookmarkKey() }.toSet()
        val current = _uiState.value
        val removed = current.entries.withIndex().filter { it.value.bookmarkKey() in keys }
        persist(current.entries.filterNot { it.bookmarkKey() in keys })
        select(current.selectedKeys - keys)
        return removed
    }

    fun undoRemoval(removed: List<IndexedValue<NowPlaying>>) {
        val restored = _uiState.value.entries.toMutableList()
        val keys = restored.map { it.bookmarkKey() }.toMutableSet()
        removed.sortedBy { it.index }.forEach { (index, track) ->
            if (keys.add(track.bookmarkKey())) {
                restored.add(index.coerceIn(0, restored.size), track)
            }
        }
        persist(restored)
    }

    private fun persist(entries: List<NowPlaying>) {
        BookmarkStore.saveBookmarks(getApplication(), entries)
        _uiState.update { it.copy(entries = entries) }
    }
}
