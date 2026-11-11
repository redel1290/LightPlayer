package com.example.lightplayer

import android.content.Context
import androidx.media3.common.MediaItem
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Файл queue.txt у приватному сховищі додатка.
 *
 * Перший рядок (фіксовані 64 байти, перезаписується на місці):
 *   cur=0000012 pos=0000123456 sh=0 rp=0
 *   cur = номер треку, що грає зараз (1-based, відповідає нумерації нижче)
 *   pos = позиція в мс, sh = shuffle, rp = repeat
 * Далі по рядку на трек:  номер|id|назва
 *
 * Черга пишеться лише коли змінюється, а перший рядок оновлюється дешево.
 */
object QueueStore {
    private const val HEADER_LEN = 64

    class Saved(
        val cur: Int,
        val pos: Long,
        val shuffle: Boolean,
        val repeat: Int,
        val ids: List<Long>,
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "queue.txt")

    fun exists(ctx: Context) = file(ctx).exists()

    private fun header(cur: Int, pos: Long, shuffle: Boolean, repeat: Int): String =
        String.format(Locale.US, "cur=%07d pos=%010d sh=%d rp=%d", cur, pos, if (shuffle) 1 else 0, repeat)
            .padEnd(HEADER_LEN - 1) + "\n"

    fun writeAll(ctx: Context, items: List<MediaItem>, cur: Int, pos: Long, shuffle: Boolean, repeat: Int) {
        val tmp = File(ctx.filesDir, "queue.tmp")
        tmp.bufferedWriter().use { w ->
            w.write(header(cur, pos, shuffle, repeat))
            for (i in items.indices) {
                val item = items[i]
                val title = (item.mediaMetadata.title ?: "").toString().replace('\n', ' ')
                w.write("${i + 1}|${item.mediaId}|$title\n")
            }
        }
        tmp.renameTo(file(ctx))
    }

    fun writeHeader(ctx: Context, cur: Int, pos: Long, shuffle: Boolean, repeat: Int) {
        val f = file(ctx)
        if (!f.exists()) return
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(cur, pos, shuffle, repeat).toByteArray(Charsets.US_ASCII))
        }
    }

    fun read(ctx: Context): Saved? {
        return try {
            val f = file(ctx)
            if (!f.exists()) return null
            f.bufferedReader().use { r ->
                val head = r.readLine() ?: return null
                val kv = HashMap<String, String>()
                for (part in head.trim().split(' ')) {
                    val i = part.indexOf('=')
                    if (i > 0) kv[part.substring(0, i)] = part.substring(i + 1)
                }
                val ids = ArrayList<Long>()
                while (true) {
                    val line = r.readLine() ?: break
                    val p = line.split('|', limit = 3)
                    if (p.size >= 2) p[1].toLongOrNull()?.let { ids += it }
                }
                Saved(
                    cur = kv["cur"]?.toIntOrNull() ?: 0,
                    pos = kv["pos"]?.toLongOrNull() ?: 0L,
                    shuffle = kv["sh"] == "1",
                    repeat = kv["rp"]?.toIntOrNull() ?: 0,
                    ids = ids,
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
