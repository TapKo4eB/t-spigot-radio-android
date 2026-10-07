package net.tspigot.radio.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tspigot.radio.AppConfig
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

internal object RadioApi {
    suspend fun slogan(): String = request("/api/slogan", "text/plain, application/json") {
        inputStream.bufferedReader().use { it.readText().trim() }
    }.orEmpty()

    suspend fun backgroundImage(): Bitmap? {
        val name = request("/api/image", "text/plain") {
            inputStream.bufferedReader().use { it.readText().trim() }
        }.orEmpty()
        if (name.isBlank()) return null
        return request("/images/$name", "image/*") {
            inputStream.use { BitmapFactory.decodeStream(it) }
        }
    }

    suspend fun history(): List<NowPlaying>? = request("/api/history", "application/json") {
        val body = inputStream.bufferedReader().use { it.readText() }
        val array = JSONArray(body)
        List(array.length()) { NowPlaying.fromJson(array.getJSONObject(it)) }
    }

    private suspend fun <T> request(
        path: String,
        accept: String,
        read: HttpURLConnection.() -> T
    ): T? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("https://radio.tspigot.net$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", AppConfig.userAgent)
            connection.read()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
