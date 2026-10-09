package net.tspigot.radio.widget

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.tspigot.radio.R
import net.tspigot.radio.service.PlaybackService
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RadioWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    companion object {
        private val PLAYING_KEY = booleanPreferencesKey("is_playing")

        /** Call from anywhere (e.g. the service) to change the icon. */
        suspend fun setPlaying(context: Context, playing: Boolean) {
            val widget = RadioWidget()
            GlanceAppWidgetManager(context)
                .getGlanceIds(RadioWidget::class.java)
                .forEach { id ->
                    updateAppWidgetState(context, id) { prefs ->
                        prefs[PLAYING_KEY] = playing
                    }
                    widget.update(context, id)
                }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val isPlaying = currentState(key = PLAYING_KEY) ?: false

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(
                        if (isPlaying) R.drawable.pause else R.drawable.play
                    ),
                    contentDescription = if (isPlaying) "Stop" else "Play",
                    modifier = GlanceModifier
                        .size(48.dp)
                        .clickable(actionRunCallback<PlayStopAction>())
                )
            }
        }
    }
}

class PlayStopAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // The receiver context can't bind to services; the application context can.
        val appContext = context.applicationContext

        withContext(Dispatchers.Main) {
            val token = SessionToken(
                appContext,
                ComponentName(appContext, PlaybackService::class.java)
            )
            val controller = MediaController.Builder(appContext, token)
                .buildAsync()
                .awaitResult(appContext)

            try {
                if (controller.playWhenReady && controller.playbackState != Player.STATE_IDLE) {
                    controller.pause()
                } else {
                    // Reconnect to the live stream instead of resuming stale buffered audio.
                    controller.stop()
                    controller.prepare()
                    controller.play()
                }
            } finally {
                controller.release()
            }
        }
    }
}

private suspend fun <T> ListenableFuture<T>.awaitResult(context: Context): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(Futures.getDone(this))
                } catch (e: ExecutionException) {
                    cont.resumeWithException(e.cause ?: e)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
        cont.invokeOnCancellation { cancel(false) }
    }