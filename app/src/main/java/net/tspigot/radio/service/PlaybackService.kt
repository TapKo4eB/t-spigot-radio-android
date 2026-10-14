package net.tspigot.radio.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.tspigot.radio.AppConfig
import net.tspigot.radio.data.NowPlaying
import net.tspigot.radio.data.NowPlayingResponse
import net.tspigot.radio.ui.activities.MainActivity
import net.tspigot.radio.widget.RadioWidget
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds

class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var trackJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private val MAIN_TYPES = setOf("FULL_TRACK", "AMBIENCE", "BED", "COMMERCIAL", "BUMP")
        private val OTHER_TYPES = setOf("VOICE", "ENV", "RHYTHM")

    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(AppConfig.userAgent)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exo

        val initialItem = MediaItem.Builder()
            .setUri("https://radio.tspigot.net/radio/radio.mp3")
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

        exo.setMediaItem(initialItem)
        exo.prepare()

        val widgetSync = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = sync()
            override fun onPlaybackStateChanged(playbackState: Int) = sync()

            private fun sync() {
                val playing = exo.playWhenReady && exo.playbackState != Player.STATE_IDLE
                serviceScope.launch { RadioWidget.setPlaying(this@PlaybackService, playing) }
            }
        }
        exo.addListener(widgetSync)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(pendingIntent)
            .build()

        trackJob = serviceScope.launch {
            while (isActive) {
                val response = fetchCurrentTracks()
                if (response.tracks.isNotEmpty()) {
                    updateMetadata(response)
                }
                delay(10.seconds)
            }
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    private fun updateMetadata(response: NowPlayingResponse) {
        val player = player ?: return
        val currentItem = player.currentMediaItem ?: return

        // Only the first MAIN track is ever shown, per spec.
        val mainTrack = response.tracks.firstOrNull { it.type in MAIN_TYPES } ?: return

        // Every OTHER track is shown, in order, after the main track.
        val otherTracks = response.tracks.filter { it.type in OTHER_TYPES }

        val title = mainTrack.title ?: "Unknown track"
        val artist = mainTrack.artist ?: "Unknown artist"

        val description = if (otherTracks.isNotEmpty()) {
            "MULTI:" + otherTracks.joinToString(";;") { track ->
                val otherTitle = track.title ?: "Unknown track"
                val otherArtist = track.artist ?: "Unknown artist"
                "$otherTitle|$otherArtist"
            }
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
            connection.setRequestProperty("User-Agent", AppConfig.userAgent)

            if (connection.responseCode !in 200..299) return@withContext NowPlayingResponse()

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(response)

            if (jsonArray.length() == 0) return@withContext NowPlayingResponse()

            val tracks = mutableListOf<NowPlaying>()
            for (i in 0 until jsonArray.length()) {
                val trackJson = jsonArray.getJSONObject(i)
                tracks.add(
                    NowPlaying(
                        title = trackJson.optString("title", "Unknown track"),
                        artist = trackJson.optString("artist", "Unknown artist"),
                        type = trackJson.optString("type").takeIf { it.isNotBlank() }
                    )
                )
            }

            NowPlayingResponse(tracks = tracks)
        } catch (_: Exception) {
            NowPlayingResponse()
        } finally {
            connection?.disconnect()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the radio playing if the app is removed from Recents.
        if (player?.playWhenReady != true) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }


    override fun onDestroy() {
        trackJob?.cancel()
        serviceScope.cancel()

        // The service scope is cancelled, so use the app context on a separate scope.
        val appContext = applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            RadioWidget.setPlaying(appContext, false)
        }

        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null

        super.onDestroy()
    }
}