package net.tspigot.radio.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.tspigot.radio.AppSettings

data class SettingsUiState(
    val chatName: String = "",
    val bookmarkOnLike: Boolean = true
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        val app = getApplication<Application>()
        _uiState.value = SettingsUiState(
            chatName = AppSettings.getChatName(app),
            bookmarkOnLike = AppSettings.getBookmarkOnLike(app)
        )
    }

    fun setChatName(name: String) {
        AppSettings.setChatName(getApplication(), name)
        _uiState.update { it.copy(chatName = name.trim()) }
    }

    fun setBookmarkOnLike(enabled: Boolean) {
        AppSettings.setBookmarkOnLike(getApplication(), enabled)
        _uiState.update { it.copy(bookmarkOnLike = enabled) }
    }
}
