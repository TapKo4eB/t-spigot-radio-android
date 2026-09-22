package net.tspigot.radio.playerwindow

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.tspigot.radio.AppConfig
import net.tspigot.radio.BookmarkStore
import net.tspigot.radio.BookmarksActivity
import net.tspigot.radio.HistoryActivity
import net.tspigot.radio.NowPlaying
import net.tspigot.radio.R
import net.tspigot.radio.SettingsActivity
import kotlin.time.Duration.Companion.seconds

private const val IMAGE_FETCH_INTERVAL_SECONDS = 120L
private const val IMAGE_FADE_DURATION_MS = 5000

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

private suspend fun fetchBackgroundImageName(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val connection = java.net.URL("https://radio.tspigot.net/api/image").openConnection() as java.net.HttpURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/plain")
        connection.setRequestProperty("User-Agent", AppConfig.userAgent)

        connection.inputStream.bufferedReader().use { it.readText().trim() }
    } catch (_: Exception) {
        ""
    } finally {
        connection.disconnect()
    }
}

private suspend fun fetchBackgroundImageBitmap(fileName: String): ImageBitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (fileName.isBlank()) return@withContext null

    val connection = java.net.URL("https://radio.tspigot.net/images/$fileName").openConnection() as java.net.HttpURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", AppConfig.userAgent)

        val bytes = connection.inputStream.use { it.readBytes() }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) {
        null
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
private fun SongInfoDisplay(
    mainTitle: String,
    mainArtist: String,
    otherTracks: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    val (parsedTitle, bracketPart) = remember(mainTitle) { parseSongTitle(mainTitle) }

    Box(
        modifier = modifier
            .padding(horizontal = 24.dp)
    ) {
        SelectionContainer() {
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.animateContentSize()
            ) {
                AnimatedContent(
                    targetState = parsedTitle to bracketPart,
                    transitionSpec = { fadeTitleTransition() },
                    label = "mainTitle"
                ) { (title, bracket) ->
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                                append(title)
                            }
                            if (bracket != null) {
                                append(" ")
                                withStyle(
                                    SpanStyle(
                                        fontStyle = FontStyle.Italic, color = SongColors.Bracket
                                    )
                                ) {
                                    append(bracket)
                                }
                            }
                        },
                        fontSize = 20.sp,
                        textAlign = TextAlign.Start,
                        softWrap = true
                    )
                }

                AnimatedVisibility(
                    visible = mainArtist.isNotBlank(),
                    enter = fadeIn(tween(3000)),
                    exit = fadeOut(tween(2000))
                ) {
                    AnimatedContent(
                        targetState = mainArtist,
                        transitionSpec = { fadeTitleTransition() },
                        label = "mainArtist"
                    ) { artist ->
                        Text(
                            text = "by $artist",
                            color = SongColors.Artist,
                            textAlign = TextAlign.Start,
                            softWrap = true
                        )
                    }
                }

                AnimatedVisibility(
                    visible = otherTracks.isNotEmpty(),
                    enter = fadeIn(tween(3000)) + expandVertically(tween(3000)),
                    exit = fadeOut(tween(2000)) + shrinkVertically(tween(2000))
                ) {

                    Column {
                        Text(
                            text = "with",
                            color = Color(0xFF888888),
                            softWrap = true,
                            modifier = Modifier.padding(start = 8.dp)
                        )

                        otherTracks.forEach { (otherTitle, otherArtist) ->
                            key(otherTitle, otherArtist) {
                                AnimatedContent(
                                    targetState = otherTitle to otherArtist,
                                    transitionSpec = { fadeTitleTransition() },
                                    label = "otherTrack"
                                ) { (title, artist) ->
                                    Text(
                                        text = buildAnnotatedString {
                                            withStyle(
                                                SpanStyle(
                                                    fontWeight = FontWeight.Bold,
                                                    fontStyle = FontStyle.Italic
                                                )
                                            ) {
                                                append(title)
                                            }
                                            if (artist.isNotBlank()) {
                                                append(" ")
                                                withStyle(
                                                    SpanStyle(
                                                        fontWeight = FontWeight.Normal,
                                                        fontStyle = FontStyle.Normal,
                                                        color = SongColors.Artist
                                                    )
                                                ) {
                                                    append("by $artist")
                                                }
                                            }
                                        },
                                        fontSize = 16.sp,
                                        textAlign = TextAlign.Start,
                                        softWrap = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fadeTitleTransition(): ContentTransform =
    (fadeIn(tween(3000)) + slideInVertically(tween(3000)) { height -> height / 4 })
        .togetherWith(fadeOut(tween(2000)) + slideOutVertically(tween(2000)) { height -> -height / 4 })

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    controller: MediaController?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val focusManager = LocalFocusManager.current

    val keyboardVisible = WindowInsets.isImeVisible

    val blurRadius by animateDpAsState(
        targetValue = if (keyboardVisible) 16.dp else 0.dp,
        animationSpec = tween(durationMillis = 250),
        label = "keyboardBlur"
    )

    var sloganText by remember { mutableStateOf("") }

    val sloganBackgroundAlpha by animateFloatAsState(
        targetValue = if (keyboardVisible || sloganText.isBlank()) 0f else 0.35f,
        animationSpec = tween(durationMillis = 250),
        label = "sloganBackgroundAlpha"
    )

    var userWantsPlaying by remember { mutableStateOf(false) }
    var mainTitle by remember { mutableStateOf("t spigot radio") }
    var mainArtist by remember { mutableStateOf("only real music") }
    var otherTracks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val sloganAlpha = remember { Animatable(0f) }

    // Currently playing background image, fetched from the API every
    // IMAGE_FETCH_INTERVAL_SECONDS and crossfaded in/out over IMAGE_FADE_DURATION_MS.
    var backgroundImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val backgroundImageAlpha = remember { Animatable(0f) }

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

    LaunchedEffect(Unit) {
        while (isActive) {
            val imageName = fetchBackgroundImageName()
            if (imageName.isNotBlank()) {
                val bitmap = fetchBackgroundImageBitmap(imageName)
                if (bitmap != null) {
                    if (backgroundImage != null) {
                        backgroundImageAlpha.animateTo(0f, animationSpec = tween(IMAGE_FADE_DURATION_MS))
                    }
                    backgroundImage = bitmap
                    backgroundImageAlpha.animateTo(0.5f, animationSpec = tween(IMAGE_FADE_DURATION_MS))
                }
            }

            delay(IMAGE_FETCH_INTERVAL_SECONDS.seconds)
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
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(WindowInsets(0, 0, 0, 0))
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                })
            }
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
        ) {
            backgroundImage?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    alpha = backgroundImageAlpha.value,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .blur(blurRadius)
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 32.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.Top
            ) {

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp, start = 8.dp, end = 8.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        text = sloganText,
                        color = Color(0xFF888844),
                        modifier = Modifier
                            .blur(blurRadius)
                            .graphicsLayer(alpha = sloganAlpha.value)
                            .background(
                                color = Color.Black.copy(alpha = sloganBackgroundAlpha),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Column {
                    IconButton(
                        onClick = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.settings),
                            contentDescription = "Settings"
                        )
                    }

                    IconButton(
                        onClick = {
                            context.startActivity(
                                Intent(context, HistoryActivity::class.java)
                            )
                        }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.music_history),
                            contentDescription = "History"
                        )
                    }

                    IconButton(
                        onClick = {
                            context.startActivity(Intent(
                                context,
                                BookmarksActivity::class.java))
                        }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.bookmarks),
                            contentDescription = "Bookmarks"
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                SongInfoDisplay(
                    mainTitle = mainTitle,
                    mainArtist = mainArtist,
                    otherTracks = otherTracks
                )

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
                            val payload = buildOutgoingPayload("/like")
                            val sent = payload != null &&
                                    chatConnection.sendUserPayload(context, payload.json)

                            if (sent) {
                                if (!isFavorited) {
                                    BookmarkStore.addBookmark(
                                        context,
                                        NowPlaying(
                                            title = mainTitle,
                                            artist = mainArtist,
                                            lastPlayEpoch = System.currentTimeMillis() / 1000
                                        )
                                    )
                                }
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