package net.tspigot.radio

import NowPlaying
import android.content.ComponentName
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.tspigot.radio.ui.theme.TSpigotRadioTheme
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ){
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

@Composable
fun PlayerScreen(
    controller: MediaController?,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember {
        mutableStateOf(controller?.isPlaying == true)
    }

    var currentTrack by remember {
        mutableStateOf<NowPlaying?>(null)
    }

    // Poll the now-playing API every 10 seconds.
    LaunchedEffect(Unit) {
        while (true) {
            val track = fetchCurrentTrack()

            if (track != null) {
                currentTrack = track
            }

            delay(10_000L)
        }
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = currentTrack?.let {
                    "${it.title}\nby ${it.artist}"
                } ?: "Loading current track..."
            )

            Spacer(modifier = Modifier.height(16.dp))

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
}

private suspend fun fetchCurrentTrack(): NowPlaying? {
    return withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
            connection = URL(
                "https://radio.tspigot.net/api/nowPlaying"
            ).openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000

            if (connection.responseCode !in 200..299) {
                return@withContext null
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val tracks = JSONArray(response)

            if (tracks.length() == 0) {
                return@withContext null
            }

            val firstTrack = tracks.getJSONObject(0)

            NowPlaying(
                title = firstTrack.optString(
                    "title",
                    "Unknown track"
                ),
                artist = firstTrack.optString(
                    "artist",
                    "Unknown artist"
                )
            )
        } catch (exception: Exception) {
            // Keep displaying the previous title if the request fails.
            null
        } finally {
            connection?.disconnect()
        }
    }
}