package net.tspigot.radio

data class NowPlayingResponse(
    val tracks: List<NowPlaying> = emptyList()
)
