package com.example.lightplayer

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri,
) {
    fun toItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                // обкладинку для сповіщення вантажить ThumbLoader у сервісі
                .setArtworkUri(uri)
                .build()
        )
        .build()
}

object Library {
    /** Усі треки з /storage/emulated/0/Music/ разом з підпапками. */
    fun load(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        )
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            proj,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Music/%"),
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val artist = c.getString(iArtist)
                out += Track(
                    id = id,
                    title = c.getString(iTitle) ?: "Без назви",
                    artist = if (artist.isNullOrBlank() || artist == "<unknown>") "Невідомий виконавець" else artist,
                    durationMs = c.getLong(iDur),
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                )
            }
        }
        return out
    }
}

/** Крихітний кеш мініатюр з жорстким лімітом пам'яті (3 МБ). */
object Art {
    private val cache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val misses = HashSet<String>()

    fun peek(t: Track?, px: Int): Bitmap? = t?.let { cache.get("${it.id}_$px") }

    fun get(ctx: Context, t: Track, px: Int): Bitmap? {
        val key = "${t.id}_$px"
        cache.get(key)?.let { return it }
        if (synchronized(misses) { key in misses }) return null
        val bmp = try {
            ctx.contentResolver.loadThumbnail(t.uri, Size(px, px), null)
        } catch (e: Exception) {
            null
        }
        if (bmp != null) cache.put(key, bmp) else synchronized(misses) { misses += key }
        return bmp
    }
}
