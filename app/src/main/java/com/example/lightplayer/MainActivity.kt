package com.example.lightplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var future: ListenableFuture<MediaController>? = null
    private val controller = mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(controller.value)
            }
        }
    }

    // Підключаємось до сервісу лише поки екран видно, потім відпускаємо.
    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            if (!f.isCancelled) runCatching { controller.value = f.get() }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller.value = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }
}

private fun audioPermission() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun App(player: Player?) {
    val ctx = LocalContext.current

    fun hasAudio() =
        ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasAudio()) }
    val perms = remember {
        buildList {
            add(audioPermission())
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasAudio()
    }
    LaunchedEffect(Unit) {
        if (perms.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }) {
            launcher.launch(perms)
        }
    }

    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(granted) {
        if (granted) {
            tracks = withContext(Dispatchers.IO) { Library.load(ctx) }
            loaded = true
        }
    }

    var query by remember { mutableStateOf("") }
    val filtered = remember(tracks, query) {
        if (query.isBlank()) tracks
        else tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    }

    // --- стан плеєра ---
    var isPlaying by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var duration by remember { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        fun sync() {
            isPlaying = player.isPlaying
            currentId = player.currentMediaItem?.mediaId
            shuffle = player.shuffleModeEnabled
            repeat = player.repeatMode
            duration = player.duration.coerceAtLeast(0L)
        }
        sync()
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = sync()
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    val playAt = remember(filtered, player) {
        { index: Int ->
            player?.run {
                setMediaItems(filtered.map { it.toItem() }, index, 0L)
                prepare()
                play()
            }
            Unit
        }
    }

    val current = remember(currentId, tracks) { tracks.firstOrNull { it.id.toString() == currentId } }

    Scaffold(
        bottomBar = {
            if (player != null && current != null) {
                PlayerPanel(player, current, isPlaying, shuffle, repeat, duration)
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Пошук") }
                )
                IconButton(onClick = {
                    // Дозволяє грати у фоні без агресивного вбивства від батареї
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    }
                }) { Text("🔋", fontSize = 22.sp) }
            }

            when {
                !granted -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { launcher.launch(perms) }) { Text("Надати доступ до музики") }
                }
                loaded && filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("У /Music нічого не знайдено")
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(filtered, key = { _, t -> t.id }, contentType = { _, _ -> 0 }) { index, t ->
                        TrackRow(t, index, t.id.toString() == currentId, playAt)
                    }
                }
            }
        }
    }
}

@Composable
fun Cover(track: Track?, px: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(Art.peek(track, px), track?.id) {
        value = if (track == null) null
        else withContext(Art.dispatcher) { Art.get(ctx, track, px) }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("♪", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TrackRow(t: Track, index: Int, selected: Boolean, onPlay: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPlay(index) }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(t, 96, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(fmt(t.durationMs), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PlayerPanel(
    player: Player,
    track: Track,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Int,
    duration: Long,
) {
    var position by remember { mutableLongStateOf(player.currentPosition) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(player, track.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                position = player.currentPosition
                delay(if (player.isPlaying) 500 else 1000)
            }
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = duration.coerceAtLeast(1L).toFloat()
    val shown = (dragging ?: position.toFloat()).coerceIn(0f, max)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(track, 384, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, color = inactive
                    )
                }
            }
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..max,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(shown.toLong()), style = MaterialTheme.typography.bodySmall)
                Text(fmt(duration), style = MaterialTheme.typography.bodySmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { player.shuffleModeEnabled = !shuffle }) {
                    Text("🔀", fontSize = 22.sp, color = if (shuffle) active else inactive)
                }
                IconButton(onClick = { player.seekToPrevious() }) { Text("⏮", fontSize = 26.sp) }
                IconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier.size(56.dp)
                ) { Text(if (isPlaying) "⏸" else "▶", fontSize = 34.sp) }
                IconButton(onClick = { player.seekToNext() }) { Text("⏭", fontSize = 26.sp) }
                IconButton(onClick = {
                    player.repeatMode = when (repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Text(
                        if (repeat == Player.REPEAT_MODE_ONE) "🔂" else "🔁",
                        fontSize = 22.sp,
                        color = if (repeat == Player.REPEAT_MODE_OFF) inactive else active
                    )
                }
            }
        }
    }
}
