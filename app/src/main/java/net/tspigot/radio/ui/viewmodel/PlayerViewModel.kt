package net.tspigot.radio.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.tspigot.radio.AppSettings
import net.tspigot.radio.data.BookmarkStore
import net.tspigot.radio.data.NowPlaying
import net.tspigot.radio.data.RadioApi
import net.tspigot.radio.service.PlaybackService
import kotlin.time.Duration.Companion.seconds

data class PlayerUiState(
    val connected: Boolean = false,
    val userWantsPlaying: Boolean = false,
    val mainTitle: String = "t spigot radio",
    val mainArtist: String = "only real music",
    val otherTracks: List<Pair<String, String>> = emptyList(),
    val statusMessage: String? = null,
    val slogan: String = "",
    val backgroundImage: Bitmap? = null,
    val isFavorited: Boolean = false
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState = _uiState.asStateFlow()

    private var controller: MediaController? = null
    private var cleared = false
    private var changingPlayback = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!cleared && !changingPlayback) updatePlayerState(player)
        }
    }
    private val controllerFuture = MediaController.Builder(
        application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java))
    ).buildAsync()

    init {
        controllerFuture.addListener({
            if (!cleared) {
                try {
                    val connectedController = controllerFuture.get()
                    controller = connectedController
                    connectedController.addListener(listener)
                    updatePlayerState(connectedController)
                } catch (_: Exception) {
                    _uiState.update { it.copy(statusMessage = "Unable to connect to playback service") }
                }
            }
        }, ContextCompat.getMainExecutor(application))

        viewModelScope.launch {
            while (isActive) {
                val slogan = RadioApi.slogan()
                _uiState.update { it.copy(slogan = slogan) }
                delay(90.seconds)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                RadioApi.backgroundImage()?.let { bitmap ->
                    _uiState.update { it.copy(backgroundImage = bitmap) }
                }
                delay(120.seconds)
            }
        }
    }

    fun togglePlayback() {
        val player = controller ?: return
        val wantsPlaying = !_uiState.value.userWantsPlaying
        changingPlayback = true
        try {
            // Preserve the live-stream reset: Play must never resume an old buffer.
            player.playWhenReady = false
            player.stop()
            player.seekToDefaultPosition()
            if (wantsPlaying) {
                player.prepare()
                player.play()
            }
        } finally {
            changingPlayback = false
            updatePlayerState(player)
        }
    }

    /** Returns whether a new bookmark was added, so the UI can animate it. */
    fun likeCurrentTrack(sendLike: () -> Boolean): Boolean {
        val current = _uiState.value
        if (current.isFavorited || !sendLike()) return false
        val app = getApplication<Application>()
        val added = AppSettings.getBookmarkOnLike(app) && BookmarkStore.addBookmark(
            app,
            NowPlaying(
                title = current.mainTitle,
                artist = current.mainArtist,
                lastPlayEpoch = System.currentTimeMillis() / 1000
            )
        )
        _uiState.update { it.copy(isFavorited = true) }
        return added
    }

    private fun updatePlayerState(player: Player) {
        val metadata = player.mediaMetadata
        val title = metadata.title?.toString() ?: "t spigot radio"
        val artist = metadata.artist?.toString() ?: "only real music"
        val description = metadata.description?.toString().orEmpty()
        val otherTracks = if (description.startsWith("MULTI:")) {
            description.substring(6).split(";;").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size == 2) parts[0] to parts[1] else null
            }
        } else emptyList()
        val wantsPlaying = player.playWhenReady
        val status = if (wantsPlaying && !player.isPlaying) {
            val cm = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val connected = network != null && cm.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            if (connected) "Reconnecting..." else "No network available"
        } else null
        _uiState.update {
            it.copy(
                connected = true,
                userWantsPlaying = wantsPlaying,
                mainTitle = title,
                mainArtist = artist,
                otherTracks = otherTracks,
                statusMessage = status,
                isFavorited = it.isFavorited && title == it.mainTitle && artist == it.mainArtist
            )
        }
    }

    override fun onCleared() {
        cleared = true
        controller?.removeListener(listener)
        controller = null
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}
