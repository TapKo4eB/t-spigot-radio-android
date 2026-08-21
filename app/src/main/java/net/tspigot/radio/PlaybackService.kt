package net.tspigot.radio

import NowPlaying
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var trackJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val stationUrl = "https://radio.tspigot.net/radio/radio.mp3"

        val initialItem = MediaItem.Builder()
            .setUri(stationUrl)
            .setMediaId("station_1")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("My Radio Station")
                    .setArtist("Live Radio")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

        player!!.setMediaItem(initialItem)
        player!!.prepare()

        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )


        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(pendingIntent)
            .build()

        trackJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            while (isActive) {
                val track = fetchCurrentTrack()
                if (track != null) {
                    updateMetadata(track)
                }
                delay(10_000L)
            }
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    private fun updateMetadata(track: NowPlaying) {
        val player = player ?: return
        val currentItem = player.currentMediaItem ?: return

        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(
                currentItem.mediaMetadata.buildUpon()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .build()
            )
            .build()

        player.replaceMediaItem(0, updatedItem)
    }

    private suspend fun fetchCurrentTrack(): NowPlaying? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
            connection = URL("https://radio.tspigot.net/api/nowPlaying")
                .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000

            if (connection.responseCode !in 200..299) return@withContext null

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val tracks = JSONArray(response)

            if (tracks.length() == 0) return@withContext null

            val firstTrack = tracks.getJSONObject(0)

            NowPlaying(
                title = firstTrack.optString("title", "Unknown track"),
                artist = firstTrack.optString("artist", "Unknown artist")
            )
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the radio playing if the app is removed from Recents.
        if (player?.isPlaying != true) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }

        mediaSession = null
        player = null

        super.onDestroy()
    }
}