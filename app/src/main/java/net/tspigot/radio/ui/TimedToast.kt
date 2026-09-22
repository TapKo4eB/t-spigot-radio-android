package net.tspigot.radio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class TimedToastData(
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

class TimedToastController internal constructor(private val scope: CoroutineScope) {
    var current by mutableStateOf<TimedToastData?>(null)
        private set
    val progress = Animatable(1f)
    private var dismissJob: Job? = null

    fun show(
        message: String,
        durationMs: Long = 5000,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        dismissJob?.cancel()
        current = TimedToastData(message, actionLabel, onAction)

        dismissJob = scope.launch {
            progress.snapTo(1f)
            progress.animateTo(0f,
                animationSpec = tween(durationMs.toInt(), easing = LinearEasing))
            current = null
        }
    }

    fun dismiss() {
        dismissJob?.cancel()
        current = null
    }
}

@Composable
fun rememberTimedToastController(): TimedToastController {
    val scope = rememberCoroutineScope()
    return remember { TimedToastController(scope) }
}

@Composable
fun TimedToastHost(
    controller: TimedToastController,
    modifier: Modifier = Modifier
) {
    val data = controller.current

    AnimatedVisibility(
        visible = data != null,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF323232),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = data?.message.orEmpty(),
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    if (data?.actionLabel != null) {
                        TextButton(onClick = {
                            data.onAction?.invoke()
                            controller.dismiss()
                        }) {
                            Text(data.actionLabel)
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = controller.progress.value.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .align(Alignment.CenterStart)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}