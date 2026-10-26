package com.example.lightplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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

    // onTaskRemoved не перевизначаємо: Media3 сам зупиняє сервіс,
    // якщо музика НЕ грає, і лишає його, якщо грає.

    override fun onDestroy() {
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
