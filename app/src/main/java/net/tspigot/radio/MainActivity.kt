package net.tspigot.radio

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import net.tspigot.radio.ui.theme.TSpigotRadioTheme

class MainActivity : ComponentActivity() {

    private lateinit var controllerFuture: ListenableFuture<MediaController>

    private var mediaController by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

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
                val controller = controllerFuture.get()
                mediaController = controller

                if (controller.mediaItemCount == 0) {
                    val stationUrl =
                        "https://radio.tspigot.net/radio/radio.mp3"

                    val mediaItem = MediaItem.Builder()
                        .setUri(stationUrl)
                        .setMediaId("station_1")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("My Radio Station")
                                .setArtist("Live Radio")
                                .build()
                        )
                        .build()

                    controller.setMediaItem(mediaItem)
                    controller.prepare()

                    // Only call play here if playback has never started.
                    controller.play()
                }
            },
            ContextCompat.getMainExecutor(this)
        )

        setContent {
            TSpigotRadioTheme {
                PlayerScreen(controller = mediaController)
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

@Composable
fun PlayerScreen(
    controller: MediaController?,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember {
        mutableStateOf(controller?.isPlaying == true)
    }

    DisposableEffect(controller) {
        if (controller == null) {
            onDispose { }
        } else {
            // Immediately synchronize the UI with the current player state.
            isPlaying = controller.isPlaying

            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    isPlaying = isPlayingNow
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        isPlaying = false
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
        Button(
            modifier = modifier,
            enabled = controller != null,
            onClick = {
                controller?.let {
                    if (it.isPlaying) {
                        it.pause()
                    } else {
                        it.play()
                    }
                }
            }
        ) {
            Text(
                text = when {
                    controller == null -> "Connecting..."
                    isPlaying -> "Pause"
                    else -> "Play"
                }
            )
        }
    }
}