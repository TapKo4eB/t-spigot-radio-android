package net.tspigot.radio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.tspigot.radio.AppConfig
import net.tspigot.radio.data.NowPlaying
import net.tspigot.radio.data.NowPlayingResponse
import net.tspigot.radio.ui.activities.MainActivity
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds
import androidx.core.content.edit
import net.tspigot.radio.R

class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var trackJob: Job? = null

    companion object {
        private val MAIN_TYPES = setOf("FULL_TRACK", "AMBIENCE", "BED", "COMMERCIAL", "BUMP")
        private val OTHER_TYPES = setOf("VOICE", "ENV", "RHYTHM")
        const val ACTION_PLAY_STOP = "PLAY_STOP"
        private const val PREFS = "radio_prefs"
        private const val KEY_IS_PLAYING = "is_playing"

        fun isPlaying(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_IS_PLAYING, false)
        }
    }

    override fun onCreate() {
        super.onCreate()

        startForeground(1, createNotification())

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()


        val customUserAgent = AppConfig.userAgent

        // Create HttpDataSource factory with custom user agent
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(customUserAgent)

        // Create MediaSourceFactory with custom HttpDataSource
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
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
                delay(10.seconds)
            }
        }
    }

    private fun createNotification(): Notification {
        val channelId = "radio_playback"

        val channel = NotificationChannel(
            channelId,
            "Radio Playback",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("t spigot radio")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
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

        // Encode all OTHER tracks into the description field so the UI can
        // reconstruct the full "with X, with Y, ..." list.
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

            connection.setRequestProperty(
                "User-Agent",
                AppConfig.userAgent
            )

            if (connection.responseCode !in 200..299) return@withContext NowPlayingResponse()

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(response)

            if (jsonArray.length() == 0) return@withContext NowPlayingResponse()

            val tracks = mutableListOf<NowPlaying>()

            // The API can return any number of tracks now, so read all of them.
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
        if (player?.isPlaying != true) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_STOP -> togglePlayStop()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun setPlayingState(playing: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_IS_PLAYING, playing)
            }
    }

    private fun togglePlayStop() {
        val p = player ?: return
        if (p.isPlaying) {
            p.stop()
            setPlayingState(false)
        } else {
            p.play()
            setPlayingState(true)
        }
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