package net.tspigot.radio.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import net.tspigot.radio.ui.activities.BookmarksActivity
import net.tspigot.radio.ui.activities.HistoryActivity
import net.tspigot.radio.R
import net.tspigot.radio.ui.viewmodel.PlayerUiState
import net.tspigot.radio.ui.viewmodel.ChatUiState
import net.tspigot.radio.util.SongColors
import net.tspigot.radio.util.parseSongTitle
import net.tspigot.radio.ui.activities.SettingsActivity

private const val IMAGE_FADE_DURATION_MS = 5000

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
    (fadeIn(tween(3000))
            + slideInVertically(tween(3000))
    { height -> height / 4 })
        .togetherWith(fadeOut(tween(2000))
                + slideOutVertically(tween(2000))
        { height -> -height / 4 })

@Composable
private fun BoxScope.FloatingBookmarkIcon(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f,
            animationSpec = tween(durationMillis = 1200, easing = LinearOutSlowInEasing))
        onFinished()
    }

    Icon(
        painter = painterResource(id = R.drawable.bookmark_add),
        contentDescription = null,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .offset(y = (-8f - progress.value * 40f).dp)
            .graphicsLayer(alpha = 1f - progress.value)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    chatState: ChatUiState,
    onTogglePlayback: () -> Unit,
    onLike: () -> Boolean,
    onChatInputChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onReconnectChat: () -> Unit,
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

    val userWantsPlaying = state.userWantsPlaying
    val mainTitle = state.mainTitle
    val mainArtist = state.mainArtist
    val otherTracks = state.otherTracks
    val statusMessage = state.statusMessage
    val isFavorited = state.isFavorited

    val sloganAlpha = remember { Animatable(0f) }

    // Keep crossfade animation state local; image fetching belongs to the ViewModel.
    var backgroundImage by remember { mutableStateOf<Bitmap?>(null) }
    val backgroundImageAlpha = remember { Animatable(0f) }

    var floatingBookmarkIds by remember { mutableStateOf<List<Long>>(emptyList()) }

    LaunchedEffect(state.slogan) {
        sloganAlpha.animateTo(0f, animationSpec = tween(durationMillis = 3500))
        sloganText = state.slogan
        sloganAlpha.animateTo(1f, animationSpec = tween(durationMillis = 3500))
    }

    LaunchedEffect(state.backgroundImage) {
        state.backgroundImage?.let { image ->
            if (backgroundImage != null) {
                backgroundImageAlpha.animateTo(0f, animationSpec = tween(IMAGE_FADE_DURATION_MS))
            }
            backgroundImage = image
            backgroundImageAlpha.animateTo(0.5f, animationSpec = tween(IMAGE_FADE_DURATION_MS))
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
            backgroundImage?.let { image ->
                val bitmap = remember(image) { image.asImageBitmap() }
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
                        enabled = state.connected,
                        onClick = onTogglePlayback
                    ) {
                        Text(
                            text = when {
                                !state.connected -> "Connecting..."
                                userWantsPlaying -> "Pause"
                                else -> "Play"
                            }
                        )
                    }

                    Box {
                        IconButton(
                            enabled = chatState.connected,
                            onClick = {
                                if (onLike()) {
                                    floatingBookmarkIds = floatingBookmarkIds + System.nanoTime()
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

                        floatingBookmarkIds.forEach { id ->
                            key(id) {
                                FloatingBookmarkIcon(onFinished = { floatingBookmarkIds = floatingBookmarkIds - id })
                            }
                        }
                    }
                }

                if (statusMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = statusMessage)
                }
            }
        }

        ChatPanel(
            state = chatState,
            onInputChange = onChatInputChange,
            onSend = onSendChat,
            onReconnect = onReconnectChat,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
        )
    }
}
