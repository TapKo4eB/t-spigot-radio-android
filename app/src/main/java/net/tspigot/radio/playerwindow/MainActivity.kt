package net.tspigot.radio.playerwindow

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import net.tspigot.radio.PlaybackService
import net.tspigot.radio.ui.theme.TSpigotRadioTheme

class MainActivity : ComponentActivity() {

    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        val sessionToken = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )

        controllerFuture = MediaController.Builder(
            this,
            sessionToken
        ).buildAsync()

        controllerFuture.addListener(
            {
                mediaController = controllerFuture.get()
            },
            ContextCompat.getMainExecutor(this)
        )

        setContent {
            TSpigotRadioTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PlayerScreen(controller = mediaController)
                }
            }
        }
    }

    override fun onDestroy() {
        if (::controllerFuture.isInitialized) {
            MediaController.releaseFuture(controllerFuture)
        }
        super.onDestroy()
    }
}