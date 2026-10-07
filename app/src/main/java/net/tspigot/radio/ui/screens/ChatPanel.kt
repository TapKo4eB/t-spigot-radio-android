package net.tspigot.radio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.tspigot.radio.R
import net.tspigot.radio.data.ChatMessage
import net.tspigot.radio.data.ChatMessageKind
import net.tspigot.radio.ui.viewmodel.ChatUiState

private val TIME_CODE_COLOR = Color(0xFF888888)

/**
 * One chat line. Kept as its own composable so the annotated string is built
 * once per message (and only rebuilt if the message or its color changes),
 * instead of on every recomposition/scroll-in.
 */
@Composable
private fun ChatMessageRow(msg: ChatMessage) {
    val messageColor = when (msg.kind) {
        ChatMessageKind.Normal -> MaterialTheme.colorScheme.onSurface
        ChatMessageKind.Like -> Color(0xFF88ff88)
        ChatMessageKind.System -> Color(0xff99AAAA)
    }

    val displayText = remember(msg.id, messageColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = TIME_CODE_COLOR)) {
                append(msg.timeCode)
            }
            append(" ")
            withStyle(SpanStyle(color = messageColor)) {
                append(msg.text)
            }
        }
    }

    // Selection is scoped per message: a single SelectionContainer around the
    // whole LazyColumn makes every row register with one shared registrar,
    // which is expensive. The trade-off is that a drag-selection can no
    // longer span several messages.
    SelectionContainer {
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}

@Composable
fun ChatPanel(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messages = state.messages
    val input = state.input

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // The list uses reverseLayout = true, so index 0 is the NEWEST message and
    // it sits at the bottom. "At the bottom" is therefore simply "first
    // visible item is index 0 with no scroll offset" - no need to walk
    // visibleItemsInfo.
    val isAtBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
        }
    }

    var stickToBottom by remember { mutableStateOf(true) }
    var newMessageCount by remember { mutableStateOf(0) }
    var userDragging by remember { mutableStateOf(false) }

    fun scrollToNewest() {
        coroutineScope.launch {
            listState.animateScrollToItem(0)
        }
    }

    // Track whether the finger is currently dragging the list.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> userDragging = true
                is DragInteraction.Stop,
                is DragInteraction.Cancel -> userDragging = false

                else -> Unit
            }
        }
    }

    // The user pulled the list away from the bottom by hand: stop auto-following.
    // (Checked while dragging rather than at drag start, because at drag start
    // the list is still at the bottom.)
    LaunchedEffect(listState) {
        snapshotFlow { userDragging && !isAtBottom }.collect { leftBottomByDrag ->
            if (leftBottomByDrag) stickToBottom = false
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            stickToBottom = true
            newMessageCount = 0
        }
    }

    // Keyed on the newest message id, not on messages.size: once the list is
    // capped at MAX_MESSAGES the size stops changing, but the newest id still does.
    val newestId = messages.lastOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null && stickToBottom) {
            // One-item hop with reverseLayout, never a long scroll.
            listState.animateScrollToItem(0)
        }
    }

    // Socket consumption belongs to the ViewModel. Only unread/scroll state
    // belongs to this panel; counters continue changing when the list is capped.
    var lastReceivedCount by remember { mutableStateOf(state.receivedCount) }
    var lastResetCount by remember { mutableStateOf(state.resetCount) }
    LaunchedEffect(state.receivedCount, state.resetCount) {
        if (state.resetCount != lastResetCount) {
            stickToBottom = true
            newMessageCount = 0
            listState.scrollToItem(0)
        } else if (!stickToBottom) {
            newMessageCount += (state.receivedCount - lastReceivedCount).toInt()
        }
        lastReceivedCount = state.receivedCount
        lastResetCount = state.resetCount
    }

    // Display order for reverseLayout: newest first. asReversed() is an O(1) view.
    val displayMessages = remember(messages) { messages.asReversed() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.connected) "Chat" else "Chat (offline)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(end = 6.dp)
            )
            if (!state.connected) {
                Icon(
                    painter = painterResource(R.drawable.refresh),
                    contentDescription = "Refresh",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable {
                            onReconnect()
                        }
                )
            }

        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = listState,
                reverseLayout = true
            ) {
                items(
                    items = displayMessages,
                    key = { it.id },
                    contentType = { it.kind }
                ) { msg ->
                    ChatMessageRow(msg)
                }
            }

            if (newMessageCount > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clickable {
                            newMessageCount = 0
                            stickToBottom = true
                            scrollToNewest()
                        },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    tonalElevation = 4.dp
                ) {
                    Text(
                        text = if (newMessageCount == 1) {
                            "1 new message"
                        } else {
                            "$newMessageCount new messages"
                        },
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // Also require !stickToBottom so the button doesn't flash for the
            // instant between a new message arriving and the auto-scroll landing.
            if (!isAtBottom && !stickToBottom) {
                FloatingActionButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    onClick = {
                        stickToBottom = true
                        newMessageCount = 0
                        scrollToNewest()
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.arrow_down),
                        contentDescription = "Scroll to bottom"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            // Keyboard is about to come up: make sure we're
                            // pinned to the latest messages once the panel
                            // reflows upward.
                            stickToBottom = true
                            scrollToNewest()
                        }
                    },
                value = input,
                onValueChange = onInputChange,
                singleLine = true,
                label = { Text("Say something") }
            )

            IconButton(
                enabled = state.connected && input.isNotBlank(),
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10)),

                onClick = {
                    stickToBottom = true

                    onSend()
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.send),
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}