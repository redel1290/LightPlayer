package com.example.lightplayer

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

@Immutable
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri,
    /** Підпапка відносно Music, напр. "Rock/Metal"; "" для кореня. */
    val folder: String,
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
    /** Усі треки з /storage/emulated/0/Music/ разом з підпапками (приховані папки з крапкою пропускаються). */
    fun load(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH,
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
            val iPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val rel = c.getString(iPath) ?: ""
                val sub = (if (rel.regionMatches(0, "Music/", 0, 6, ignoreCase = true)) rel.substring(6) else rel)
                    .trim('/')
                if (sub.isNotEmpty() && sub.split('/').any { it.startsWith(".") }) continue

                val id = c.getLong(iId)
                val artist = c.getString(iArtist)
                out += Track(
                    id = id,
                    title = c.getString(iTitle) ?: "Без назви",
                    artist = if (artist.isNullOrBlank() || artist == "<unknown>") "Невідомий виконавець" else artist,
                    durationMs = c.getLong(iDur),
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    folder = sub,
                )
            }
        }
        return out
    }
}

/** Крихітний кеш мініатюр з жорстким лімітом пам'яті (3 МБ). */
object Art {
    /** Лише 2 потоки на обкладинки, щоб не душити UI при скролі. */
    val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    private val cache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val misses = HashSet<String>()
    private val accents = HashMap<Long, Int>()

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

    /** Яскравий акцентний колір з обкладинки (ARGB) або null, якщо обкладинки нема / вона сіра. */
    fun accent(ctx: Context, t: Track): Int? {
        synchronized(accents) { accents[t.id]?.let { return if (it == 0) null else it } }
        var result = 0
        try {
            val src = get(ctx, t, 96)
            if (src != null) {
                val sw = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
                if (sw != null) {
                    val small = Bitmap.createScaledBitmap(sw, 16, 16, true)
                    val px = IntArray(256)
                    small.getPixels(px, 0, 16, 0, 0, 16, 16)
                    val hsv = FloatArray(3)
                    var best = 0f
                    var bestHsv: FloatArray? = null
                    for (p in px) {
                        AColor.colorToHSV(p, hsv)
                        val w = hsv[1] * hsv[2]
                        if (w > best) {
                            best = w
                            bestHsv = hsv.copyOf()
                        }
                    }
                    val h = bestHsv
                    if (h != null && best > 0.08f) {
                        h[1] = h[1].coerceIn(0.45f, 0.9f)
                        h[2] = h[2].coerceIn(0.6f, 0.95f)
                        result = AColor.HSVToColor(h)
                    }
                }
            }
        } catch (e: Exception) {
            result = 0
        }
        synchronized(accents) { accents[t.id] = result }
        return if (result == 0) null else result
    }
}
