package net.tspigot.radio.data

data class NowPlayingResponse(
    val tracks: List<NowPlaying> = emptyList()
)