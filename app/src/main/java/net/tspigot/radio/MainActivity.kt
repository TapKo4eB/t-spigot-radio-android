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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds

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

private suspend fun fetchSlogan(): String = withContext(Dispatchers.IO) {
    val connection = URL("https://radio.tspigot.net/api/slogan").openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/plain, application/json")
        connection.setRequestProperty("User-Agent", AppConfig.userAgent)

        val response = connection.inputStream.bufferedReader().use { it.readText().trim() }
        if (response.isBlank()) return@withContext ""

        return@withContext response

    } catch (_: Exception) {
        ""
    } finally {
        connection.disconnect()
    }
}

// Parses the "MULTI:title|artist;;title|artist" encoding produced by PlaybackService
// into a list of (title, artist) pairs for every OTHER track.
private fun parseOtherTracks(description: String): List<Pair<String, String>> {
    if (!description.startsWith("MULTI:")) return emptyList()

    return description.substring(6)
        .split(";;")
        .filter { it.isNotBlank() }
        .mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) parts[0] to parts[1] else null
        }
}

@Composable
fun PlayerScreen(
    controller: MediaController?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var userWantsPlaying by remember { mutableStateOf(false) }
    var mainTitle by remember { mutableStateOf("t spigot radio") }
    var mainArtist by remember { mutableStateOf("only real music") }
    var otherTracks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var sloganText by remember { mutableStateOf("") }
    val sloganAlpha = remember { Animatable(0f) }

    // Fixes incorrect button behavior after hide and start
    LaunchedEffect(controller) {
        userWantsPlaying = controller?.isPlaying == true
    }

    LaunchedEffect(Unit) {
        sloganText = ""
        sloganAlpha.snapTo(0f)

        while (isActive) {
            val newSlogan = fetchSlogan()

            sloganAlpha.animateTo(0f, animationSpec = tween(durationMillis = 3500))
            sloganText = newSlogan
            sloganAlpha.animateTo(1f, animationSpec = tween(durationMillis = 3500))

            delay(90.seconds)
        }
    }

    DisposableEffect(controller) {
        if (controller == null) {
            onDispose { }
        } else {
            mainTitle = controller.mediaMetadata.title?.toString() ?: "t spigot radio"
            mainArtist = controller.mediaMetadata.artist?.toString() ?: "only real music"
            otherTracks = parseOtherTracks(controller.mediaMetadata.description?.toString() ?: "")

            val listener = object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    mainTitle = mediaMetadata.title?.toString() ?: "t spigot radio"
                    mainArtist = mediaMetadata.artist?.toString() ?: "only real music"
                    otherTracks = parseOtherTracks(mediaMetadata.description?.toString() ?: "")
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
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = sloganText,
            color = Color(0xFF888844),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
                .padding(horizontal = 16.dp)
                .then(Modifier)
                .graphicsLayer(alpha = sloganAlpha.value)
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val fullText = buildString {
                append(mainTitle)
                if (mainArtist.isNotBlank()) {
                    append("\nby $mainArtist")
                }

                if (otherTracks.isNotEmpty()) {
                    append("\nwith")
                    otherTracks.forEach { (otherTitle, otherArtist) ->
                        append("\n$otherTitle")
                        if (otherArtist.isNotBlank()) {
                            append("\nby $otherArtist")
                        }
                    }
                }
            }

            Text(text = fullText)

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