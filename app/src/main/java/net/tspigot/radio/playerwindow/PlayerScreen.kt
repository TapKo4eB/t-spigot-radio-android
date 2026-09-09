package net.tspigot.radio.playerwindow

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.tspigot.radio.AppConfig
import net.tspigot.radio.R
import kotlin.time.Duration.Companion.seconds

private fun hasNetworkConnectivity(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private suspend fun fetchSlogan(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val connection = java.net.URL("https://radio.tspigot.net/api/slogan").openConnection() as java.net.HttpURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/plain, application/json")
        connection.setRequestProperty("User-Agent", AppConfig.userAgent)

        val response = connection.inputStream.bufferedReader().use { it.readText().trim() }
        if (response.isBlank()) return@withContext ""

        response
    } catch (_: Exception) {
        ""
    } finally {
        connection.disconnect()
    }
}

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

    // Shared connection state: ChatPanel owns the socket lifecycle,
    // but this button also needs to send over it.
    val chatConnection = remember { ChatConnectionState() }
    var isFavorited by remember { mutableStateOf(false) }

    // Reset the favorite state whenever the *main* track changes.
    // otherTracks is intentionally excluded from this key.
    LaunchedEffect(mainTitle, mainArtist) {
        isFavorited = false
    }

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

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
        ) {
            Text(
                text = sloganText,
                color = Color(0xFF888844),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .padding(horizontal = 16.dp)
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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

                    IconButton(
                        enabled = chatConnection.connected,
                        onClick = {
                            // Always re-send "/like" on every press, even if
                            // already favorited — the action stays the same.
                            val payload = buildOutgoingPayload("/like")
                            val sent = payload != null &&
                                    chatConnection.socket?.send(payload.json) == true

                            if (sent) {
                                isFavorited = true
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isFavorited) {
                                    R.drawable.favorite_filled
                                } else {
                                    R.drawable.favorite
                                }
                            ),
                            contentDescription = if (isFavorited) "Liked" else "Like"
                        )
                    }
                }

                if (statusMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = statusMessage!!)
                }
            }
        }

        ChatPanel(
            connectionState = chatConnection,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
        )
    }
}