package net.tspigot.radio.util

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import net.tspigot.radio.data.NowPlaying
import java.text.SimpleDateFormat
import java.util.Date

object SongColors {
    val Timestamp = Color(0xFF888888)
    val Artist = Color(0xFFB0B0B0)
    val Bracket = Color(0xFF6A806A)
}

object SongStyles {
    val Title = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
    val Artist = SpanStyle(fontWeight = FontWeight.Normal, fontStyle = FontStyle.Normal, color = SongColors.Artist)
    val Bracket = SpanStyle(fontStyle = FontStyle.Italic, color = SongColors.Bracket)
    val Timestamp = SpanStyle(color = SongColors.Timestamp)
}

/**
 * Splits a song title into the main part and a trailing "(...)" annotation, if present.
 * "Put Everything Together by PLUS  (1972 Poland)" ->
 *   ("Put Everything Together by PLUS", "(1972 Poland)")
 */
fun parseSongTitle(title: String): Pair<String, String?> {
    val regex = Regex("""\s*(\([^()]*\))\s*$""")
    val match = regex.find(title)
    return if (match != null) {
        val bracketPart = match.groupValues[1]
        val mainPart = title.substring(0, match.range.first).trimEnd()
        mainPart to bracketPart
    } else {
        title to null
    }
}

@Composable
fun HistoryLine(track: NowPlaying, timeFormat: SimpleDateFormat, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = buildTitleLine(track, timeFormat))
        if (!track.artist.isNullOrBlank()) {
            Text(text = buildArtistLine(track))
        }
    }
}

fun buildTitleLine(track: NowPlaying, timeFormat: SimpleDateFormat): AnnotatedString =
    buildAnnotatedString {
        track.lastPlayEpoch?.let { epochSeconds ->
            withStyle(SongStyles.Timestamp) { append(timeFormat.format(Date(epochSeconds * 1000))) }
            append("  ")
        }
        val (title, bracket) = parseSongTitle(track.title.orEmpty())
        withStyle(SongStyles.Title) { append(title) }
        if (bracket != null) {
            append(" ")
            withStyle(SongStyles.Bracket) { append(bracket) }
        }
    }

fun buildArtistLine(track: NowPlaying): AnnotatedString =
    buildAnnotatedString {
        withStyle(SongStyles.Artist) { append("by ${track.artist}") }
    }