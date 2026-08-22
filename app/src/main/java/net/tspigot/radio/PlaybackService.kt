package net.tspigot.radio

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
                    .setTitle("t spigot radio")
                    .setArtist("only real music")
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
                val response = fetchCurrentTracks()
                if (response.tracks.isNotEmpty()) {
                    updateMetadata(response)
                }
                delay(10_000L)
            }
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    private fun updateMetadata(response: NowPlayingResponse) {
        val player = player ?: return
        val currentItem = player.currentMediaItem ?: return

        val tracks = response.tracks
        val firstTrack = tracks.getOrNull(0) ?: return

        val title = firstTrack.title ?: "Unknown track"
        val artist = firstTrack.artist ?: "Unknown artist"

        // If there's a second track, encode it in the description field
        val description = if (tracks.size >= 2) {
            val secondTrack = tracks[1]
            val secondTitle = secondTrack.title ?: "Unknown track"
            val secondArtist = secondTrack.artist ?: "Unknown artist"
            "DUAL:$secondTitle|$secondArtist"
        } else {
            ""
        }

        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(
                currentItem.mediaMetadata.buildUpon()
                    .setTitle(title)
                    .setArtist(artist)
                    .setDescription(description)
                    .build()
            )
            .build()

        player.replaceMediaItem(0, updatedItem)
    }

    private suspend fun fetchCurrentTracks(): NowPlayingResponse = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
            connection = URL("https://radio.tspigot.net/api/nowPlaying")
                .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000

            if (connection.responseCode !in 200..299) return@withContext NowPlayingResponse()

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(response)

            if (jsonArray.length() == 0) return@withContext NowPlayingResponse()

            val tracks = mutableListOf<NowPlaying>()

            // Get up to 2 tracks
            for (i in 0 until minOf(2, jsonArray.length())) {
                val trackJson = jsonArray.getJSONObject(i)
                tracks.add(
                    NowPlaying(
                        title = trackJson.optString("title", "Unknown track"),
                        artist = trackJson.optString("artist", "Unknown artist")
                    )
                )
            }

            NowPlayingResponse(tracks = tracks)
        } catch (e: Exception) {
            NowPlayingResponse()
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