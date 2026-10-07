package net.tspigot.radio.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.tspigot.radio.ui.screens.PlayerScreen
import net.tspigot.radio.ui.theme.TSpigotRadioTheme
import net.tspigot.radio.ui.viewmodel.ChatViewModel
import net.tspigot.radio.ui.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        setContent {
            val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
            val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
            TSpigotRadioTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PlayerScreen(
                        state = playerState,
                        chatState = chatState,
                        onTogglePlayback = playerViewModel::togglePlayback,
                        onLike = { playerViewModel.likeCurrentTrack(chatViewModel::sendLike) },
                        onChatInputChange = chatViewModel::updateInput,
                        onSendChat = chatViewModel::sendInput,
                        onReconnectChat = chatViewModel::reconnect
                    )
                }
            }
        }
    }
}
