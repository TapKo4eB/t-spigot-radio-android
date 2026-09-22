package net.tspigot.radio.data

import org.json.JSONObject

data class NowPlaying(
    val id: Int? = null,
    val album: String? = null,
    val artist: String? = null,
    val artistId: Int? = null,
    val axes: List<Axis>? = null,
    val duration: Double? = null,
    val lastPlayEpoch: Long? = null,
    val path: String? = null,
    val title: String? = null,
    val type: String? = null,
    val untagged: Boolean? = null,
    val fadeIn: Double? = null,
    val fadeOut: Double? = null,
    val eqLoFreq: Double? = null,
    val eqLoGain: Double? = null,
    val eqHiFreq: Double? = null,
    val eqHiGain: Double? = null,
    val musicKey: String? = null,
    val musicBpm: Int? = null,
    val year: Int? = null,
    val country: String? = null,
    val valence: Int? = null
) {
    fun bookmarkKey(): String = "$title-$artist-$lastPlayEpoch"
    fun toJson(): JSONObject = JSONObject().apply {
        id?.let { put("id", it) }
        album?.let { put("album", it) }
        artist?.let { put("artist", it) }
        artistId?.let { put("artistId", it) }
        duration?.let { put("duration", it) }
        lastPlayEpoch?.let { put("lastPlayEpoch", it) }
        path?.let { put("path", it) }
        title?.let { put("title", it) }
        type?.let { put("type", it) }
        year?.let { put("year", it) }
        country?.let { put("country", it) }
    }
    companion object {
        fun fromJson(o: JSONObject): NowPlaying = NowPlaying(
            id = if (o.has("id")) o.optInt("id") else null,
            album = o.optString("album").ifEmpty { null },
            artist = o.optString("artist").ifEmpty { null },
            artistId = if (o.has("artistId")) o.optInt("artistId") else null,
            duration = if (o.has("duration")) o.optDouble("duration") else null,
            lastPlayEpoch = if (o.has("lastPlayEpoch")) o.optLong("lastPlayEpoch") else null,
            path = o.optString("path").ifEmpty { null },
            title = o.optString("title").ifEmpty { null },
            type = o.optString("type").ifEmpty { null },
            year = if (o.has("year")) o.optInt("year") else null,
            country = o.optString("country").ifEmpty { null }
        )
    }

}