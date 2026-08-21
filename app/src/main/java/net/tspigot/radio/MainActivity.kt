package net.tspigot.radio

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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import net.tspigot.radio.ui.theme.TSpigotRadioTheme

class MainActivity : ComponentActivity() {


    private var player: Player? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TSpigotRadioTheme {
                PlayerScreen()
            }
        }
    }

}

@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier
) {

    val context = LocalContext.current
    var isPlaying by remember {
        mutableStateOf(false)
    }

    val player = remember {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                val mediaItem = MediaItem.fromUri(
                    "https://radio.tspigot.net/radio/radio.mp3"
                )

                setMediaItem(mediaItem)
                prepare()

                addListener(
                    object : Player.Listener {
                        override fun onIsPlayingChanged(
                            isPlayingNow: Boolean
                        ) {
                            isPlaying = isPlayingNow
                        }

                        override fun onPlaybackStateChanged(
                            playbackState: Int
                        ) {
                            if (playbackState == Player.STATE_ENDED) {
                                isPlaying = false
                            }
                        }
                    }
                )
            }
    }
    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Button(
            modifier = modifier,
            onClick = {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
        ) {
            Text(
                text = if (isPlaying) {
                    "Stop"
                } else {
                    "Play"
                }
            )
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    TSpigotRadioTheme {
        Greeting("Android")
    }
}