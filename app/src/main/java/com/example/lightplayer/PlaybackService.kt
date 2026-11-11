package com.example.lightplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * Плеєр живе тут, а не в Activity. Поки грає музика, сервіс працює
 * як foreground (зі сповіщенням), тому не помирає після змахування з "останніх".
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var executor: ListeningExecutorService? = null
    private var exo: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Пише стан у queue.txt (див. QueueStore): номер треку, що грає, позицію і чергу.
     * full = true переписує всю чергу, інакше оновлюється лише перший рядок.
     */
    private fun saveState(full: Boolean = false) {
        val p = exo ?: return
        val n = p.mediaItemCount
        if (n == 0) return
        val cur = p.currentMediaItemIndex + 1
        try {
            if (full || !QueueStore.exists(this)) {
                val items = List(n) { p.getMediaItemAt(it) }
                QueueStore.writeAll(this, items, cur, p.currentPosition, p.shuffleModeEnabled, p.repeatMode)
            } else {
                QueueStore.writeHeader(this, cur, p.currentPosition, p.shuffleModeEnabled, p.repeatMode)
            }
        } catch (e: Exception) {
            // запис стану не має ламати відтворення
        }
    }

    // Страховка на випадок, якщо систему вб'є процес: пишемо позицію кожні 15 с під час гри
    private val ticker = object : Runnable {
        override fun run() {
            saveState()
            handler.postDelayed(this, 15_000)
        }
    }

    private val stateListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                saveState(full = true)
            } else if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                saveState()
            }
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                handler.removeCallbacks(ticker)
                if (player.isPlaying) handler.postDelayed(ticker, 15_000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Тільки аудіо-рендерер: без відео/тексту/метаданих, менше пам'яті
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener))
        }

        // Малий буфер: файли локальні, великий буфер не потрібен
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 20_000, 500, 1_000)
            .build()

        val player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // автоматична робота з аудіо-фокусом
            )
            .setHandleAudioBecomingNoisy(true) // пауза при відключенні навушників
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo = player
        player.addListener(stateListener)

        val ex = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
        executor = ex

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setBitmapLoader(ThumbLoader(this, ex))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveState()
        super.onTaskRemoved(rootIntent) // Media3 сам зупиняє сервіс, якщо музика не грає
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        saveState()
        exo?.removeListener(stateListener)
        exo = null
        session?.run {
            player.release()
            release()
        }
        session = null
        executor?.shutdown()
        super.onDestroy()
    }
}

/** Вантажить обкладинку для сповіщення прямо з файлу (без кешу, 1 картинка). */
@OptIn(UnstableApi::class)
private class ThumbLoader(
    private val ctx: Context,
    private val ex: ListeningExecutorService,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String) = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = ex.submit<Bitmap> {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("decode failed")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = ex.submit<Bitmap> {
        ctx.contentResolver.loadThumbnail(uri, Size(512, 512), null)
    }
}
