package net.tspigot.radio

import android.app.Application
import android.os.Build

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}

object AppConfig {
    val androidVersion = Build.VERSION.RELEASE
    val userAgent = "tSpigotRadioAndroid/${BuildConfig.VERSION_NAME} (Android $androidVersion) (https://github.com/TapKo4eB/t-spigot-radio-android)"
}