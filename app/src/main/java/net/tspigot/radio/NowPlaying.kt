package net.tspigot.radio

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
)
