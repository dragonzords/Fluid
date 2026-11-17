package code.name.monkey.retromusic.online

import code.name.monkey.retromusic.model.Song
import com.enes.innertube.InnerTube
import com.enes.innertube.LrcLib
import com.enes.innertube.models.SongItem
import com.enes.innertube.models.StreamInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge between the `innertube` module (online search / streaming) and Metro's local-first
 * [Song] model.
 *
 * An online song is a normal [Song] whose `data` field is `yt:<videoId>|<thumbnailUrl>`
 * instead of a file path. Everything that persists songs (queue store, playlists, ...) already
 * stores `data`, so online songs survive restarts without extra plumbing. The real stream URL is
 * only resolved when the player opens the song ([resolve]).
 */
object OnlineMusic {

    private const val PREFIX = "yt:"

    private val streamCache = ConcurrentHashMap<String, StreamInfo>()

    /** videoId -> LRC text ("" = looked up, nothing found). */
    private val lrcCache = ConcurrentHashMap<String, String>()
    private val lrcLoading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun isOnline(data: String?): Boolean = data != null && data.startsWith(PREFIX)

    fun videoId(data: String): String = data.removePrefix(PREFIX).substringBefore('|')

    fun thumbnail(data: String): String? =
        if (!isOnline(data)) null else data.substringAfter('|', "").takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ search

    suspend fun search(query: String): List<Song> {
        if (query.isBlank()) return emptyList()
        return try {
            InnerTube.searchSongs(query).map(::toSong)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun toSong(item: SongItem): Song {
        val thumb = item.thumbnailUrl?.let(::hiRes)
        return Song(
            id = stableId(item.id),
            title = item.title,
            trackNumber = 0,
            year = 0,
            duration = parseDurationMs(item.durationText),
            data = PREFIX + item.id + (if (thumb != null) "|$thumb" else ""),
            dateModified = 0,
            albumId = -1,
            albumName = item.album.orEmpty(),
            artistId = -1,
            artistName = item.artists,
            composer = null,
            albumArtist = null,
        )
    }

    /** Large positive id that can't collide with MediaStore ids (which are small). */
    private fun stableId(videoId: String): Long {
        val h = MessageDigest.getInstance("MD5").digest(videoId.toByteArray())
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (h[i].toLong() and 0xff)
        return (v and Long.MAX_VALUE) or (1L shl 62)
    }

    private fun parseDurationMs(text: String?): Long {
        if (text.isNullOrBlank()) return 0
        val parts = text.trim().split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return 0
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
    }

    private fun hiRes(url: String): String =
        url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544").replace(Regex("=s\\d+"), "=s544")

    // --------------------------------------------------------------- streaming

    /** Resolve a videoId to a direct audio stream (cached until shortly before it expires). */
    suspend fun resolve(videoId: String): StreamInfo? {
        streamCache[videoId]?.let { if (it.expiresAtMs > System.currentTimeMillis()) return it }
        val info = try {
            InnerTube.resolveStream(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        streamCache[videoId] = info
        return info
    }

    // ------------------------------------------------------------------ lyrics

    /**
     * Time-synced lyrics for an online [song] as LRC text, or null.
     * With [allowNetwork] false (main thread) only cached lyrics are returned and a background
     * fetch is started so the next call hits the cache.
     */
    fun lrc(song: Song, allowNetwork: Boolean): String? {
        val id = videoId(song.data)
        lrcCache[id]?.let { return it.ifEmpty { null } }
        if (!allowNetwork) {
            prefetchLyrics(song)
            return null
        }
        return try {
            val text = runBlocking { fetchLrc(song) }
            lrcCache[id] = text.orEmpty()
            text
        } catch (e: Exception) {
            null // network problem: don't cache, try again next time
        }
    }

    fun prefetchLyrics(song: Song) {
        val id = videoId(song.data)
        if (lrcCache.containsKey(id) || !lrcLoading.add(id)) return
        scope.launch {
            try {
                lrcCache[id] = fetchLrc(song).orEmpty()
            } catch (e: Exception) {
                // ignore, will retry later
            } finally {
                lrcLoading.remove(id)
            }
        }
    }

    private suspend fun fetchLrc(song: Song): String? {
        val seconds = (song.duration / 1000).toInt().takeIf { it > 0 }
        val lyrics = LrcLib.lyrics(song.title, song.artistName, seconds)
        val lines = lyrics?.synced?.takeIf { it.isNotEmpty() } ?: return null
        return lines.joinToString("\n") { line ->
            val totalCs = line.timeMs / 10
            val min = totalCs / 6000
            val sec = (totalCs / 100) % 60
            val cs = totalCs % 100
            "[%02d:%02d.%02d]%s".format(min, sec, cs, line.text)
        }
    }
}
