package net.tspigot.radio

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import net.tspigot.radio.ui.theme.TSpigotRadioTheme
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.PlaybackException

class MainActivity : ComponentActivity() {

    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        val sessionToken = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )

        controllerFuture = MediaController.Builder(
            this,
            sessionToken
        ).buildAsync()

        controllerFuture.addListener(
            {
                mediaController = controllerFuture.get()
            },
            ContextCompat.getMainExecutor(this)
        )

        setContent {
            TSpigotRadioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PlayerScreen(controller = mediaController)
                }
            }
        }
    }

    override fun onDestroy() {
        if (::controllerFuture.isInitialized) {
            MediaController.releaseFuture(controllerFuture)
        }
        super.onDestroy()
    }
}

// Helper function to check if device has internet connectivity
private fun hasNetworkConnectivity(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
fun PlayerScreen(
    controller: MediaController?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var userWantsPlaying by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("t spigot radio") }
    var artist by remember { mutableStateOf("only real music") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller) {
        if (controller == null) {
            onDispose { }
        } else {
            title = controller.mediaMetadata.title?.toString() ?: "t spigot radio"
            artist = controller.mediaMetadata.artist?.toString() ?: "only real music"

            val listener = object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    title = mediaMetadata.title?.toString() ?: "t spigot radio"
                    artist = mediaMetadata.artist?.toString() ?: "only real music"
                }

                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    if (isPlayingNow) {
                        statusMessage = null
                    } else if (userWantsPlaying) {
                        val hasNetwork = hasNetworkConnectivity(context)
                        statusMessage = if (hasNetwork) "Reconnecting..." else "No network available"
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING && userWantsPlaying) {
                        val hasNetwork = hasNetworkConnectivity(context)
                        statusMessage = if (hasNetwork) "Reconnecting..." else "No network available"
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (userWantsPlaying) {
                        val hasNetwork = hasNetworkConnectivity(context)
                        statusMessage = if (hasNetwork) "Reconnecting..." else "No network available"
                    }
                }
            }

            controller.addListener(listener)

            onDispose {
                controller.removeListener(listener)
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val artistLine = if (artist.isBlank()) "" else "\nby $artist"

            Text(text = "$title$artistLine")

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                modifier = modifier,
                enabled = controller != null,
                onClick = {
                    controller?.let {
                        if (userWantsPlaying) {
                            it.pause()
                            userWantsPlaying = false
                            statusMessage = null
                        } else {
                            statusMessage = null
                            it.play()
                            userWantsPlaying = true
                        }
                    }
                }
            ) {
                Text(
                    text = when {
                        controller == null -> "Connecting..."
                        userWantsPlaying -> "Pause"
                        else -> "Play"
                    }
                )
            }

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = statusMessage!!)
            }
        }
    }
}