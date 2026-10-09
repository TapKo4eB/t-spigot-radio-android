package net.tspigot.radio.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.unit.dp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import net.tspigot.radio.R
import net.tspigot.radio.service.PlaybackService

class RadioWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isPlaying = PlaybackService.isPlaying(context) // see below

        provideContent {
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
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_STOP
        }
        context.startForegroundService(intent)
    }
}