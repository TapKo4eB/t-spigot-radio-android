package net.tspigot.radio.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.tspigot.radio.data.BookmarkStore
import net.tspigot.radio.data.NowPlaying
import net.tspigot.radio.data.RadioApi
import kotlin.time.Duration.Companion.seconds

private val HISTORY_ALLOWED_TYPES = setOf("FULL_TRACK", "BED", "AMBIENCE", "VOICE", "COMMERCIAL")

data class HistoryUiState(
    val entries: List<NowPlaying> = emptyList(),
    val showAll: Boolean = false
) {
    val visibleEntries: List<NowPlaying>
        get() = if (showAll) entries else entries.filter { it.type in HISTORY_ALLOWED_TYPES }
}

class HistoryViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(HistoryUiState(showAll = savedStateHandle["showAll"] ?: false))
    val uiState = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    fun startRefreshing() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            while (isActive) {
                RadioApi.history()?.let { entries ->
                    _uiState.update { it.copy(entries = entries) }
                }
                delay(10.seconds)
            }
        }
    }

    fun stopRefreshing() {
        refreshJob?.cancel()
        refreshJob = null
    }

    fun toggleShowAll() {
        val showAll = !_uiState.value.showAll
        savedStateHandle["showAll"] = showAll
        _uiState.update { it.copy(showAll = showAll) }
    }

    fun bookmark(entry: NowPlaying): Boolean = BookmarkStore.addBookmark(getApplication(), entry)
}
