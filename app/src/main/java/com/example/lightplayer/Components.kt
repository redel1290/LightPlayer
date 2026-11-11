package com.example.lightplayer

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ---------------------------------------------------------------- допоміжне

/** true лише поки додаток реально на екрані: ефекти в цей час малюються, у фоні вони вимкнені. */
@Composable
fun rememberVisible(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val st by lifecycle.currentStateFlow.collectAsState()
    return st.isAtLeast(Lifecycle.State.RESUMED)
}

/** Значення масштабу пульсації; коли active = false, анімація не працює взагалі. */
@Composable
fun rememberPulse(active: Boolean): State<Float> {
    val idle = remember { mutableFloatStateOf(1f) }
    if (!active) return idle
    val t = rememberInfiniteTransition(label = "pulse")
    return t.animateFloat(1f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
}

// ---------------------------------------------------------------- обкладинка

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

// ---------------------------------------------------------------- еквалайзер

@Composable
fun Equalizer(color: Color, active: Boolean, modifier: Modifier = Modifier) {
    if (active) {
        val t = rememberInfiniteTransition(label = "eq")
        val a = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(430), RepeatMode.Reverse), label = "a")
        val b = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(610), RepeatMode.Reverse), label = "b")
        val c = t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "c")
        // значення читаються тільки під час малювання, без перекомпонування
        Canvas(modifier) { drawBars(color, a.value, b.value, c.value) }
    } else {
        Canvas(modifier) { drawBars(color, 0.5f, 0.8f, 0.35f) }
    }
}

private fun DrawScope.drawBars(color: Color, a: Float, b: Float, c: Float) {
    val w = size.width / 5f
    val hs = floatArrayOf(a, b, c)
    for (i in 0..2) {
        val h = size.height * hs[i]
        drawRect(color, Offset(i * 2 * w, size.height - h), Size(w, h))
    }
}

// ---------------------------------------------------------------- серце

@Composable
fun Heart(fav: Boolean, bounce: Boolean, onClick: () -> Unit) {
    val sc = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(fav) }
    LaunchedEffect(fav) {
        if (fav != seen) {
            seen = fav
            if (bounce && fav) {
                sc.snapTo(1.4f)
                sc.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 400f))
            }
        }
    }
    IconButton(onClick = onClick) {
        Text(
            if (fav) "♥" else "♡",
            fontSize = 22.sp,
            color = if (fav) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer {
                scaleX = sc.value
                scaleY = sc.value
            }
        )
    }
}

// ---------------------------------------------------------------- рядки списку

@Composable
fun FolderHeader(path: String, onUp: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onUp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("‹", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(
            "Music/$path", maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
fun FolderRow(name: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) { Text("📁", fontSize = 24.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                "Треків: $count", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
fun TrackRow(
    t: Track,
    index: Int,
    selected: Boolean,
    playing: Boolean,
    fav: Boolean,
    eq: Boolean,
    bounce: Boolean,
    onPlay: (Int) -> Unit,
    onFav: (Long) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onPlay(index) }
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
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
        if (eq && selected) {
            Spacer(Modifier.width(8.dp))
            Equalizer(MaterialTheme.colorScheme.primary, playing, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(fmt(t.durationMs), style = MaterialTheme.typography.bodySmall)
        Heart(fav, bounce) { onFav(t.id) }
    }
}

// ---------------------------------------------------------------- панель плеєра

@Composable
fun PlayerPanel(
    player: Player,
    track: Track,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Int,
    duration: Long,
    fav: Boolean,
    accent: Color,
    tint: State<Color>,
    visible: Boolean,
    s: AppSettings,
    onFav: () -> Unit,
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
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    val pulse = rememberPulse(s.on(Fx.PULSE) && isPlaying && visible)
    val gradient = s.on(Fx.GRADIENT)

    val header: @Composable (Track) -> Unit = { t ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(
                t, 384,
                Modifier.size(64.dp)
                    .graphicsLayer {
                        scaleX = pulse.value
                        scaleY = pulse.value
                    }
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = inactive
                )
            }
            Heart(fav, s.on(Fx.LISTANIM), onFav)
        }
    }

    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .then(
                    if (gradient) Modifier.drawBehind {
                        drawRect(
                            Brush.verticalGradient(listOf(tint.value.copy(alpha = 0.30f), Color.Transparent))
                        )
                    } else Modifier
                )
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (s.on(Fx.FADE)) {
                Crossfade(targetState = track, animationSpec = tween(350), label = "track") { header(it) }
            } else {
                header(track)
            }
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..max,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
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
                    Text("🔀", fontSize = 22.sp, color = if (shuffle) accent else inactive)
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
                        color = if (repeat == Player.REPEAT_MODE_OFF) inactive else accent
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- налаштування

@Composable
fun SettingsScreen(s: AppSettings, pad: PaddingValues, onBack: () -> Unit) {
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Налаштування", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Ефекти малюються лише поки додаток на екрані. У фоні та при вимкненому екрані " +
                "вони повністю зупиняються і не навантажують телефон.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        FxRow(s, Fx.DYNAMIC, "Кольори з шпалер", "Тема підлаштовується під шпалери телефона (Android 12+)",
            enabled = Build.VERSION.SDK_INT >= 31)
        FxRow(s, Fx.COVER, "Колір з обкладинки", "Акцентний колір береться з обкладинки поточного треку")
        FxRow(s, Fx.GRADIENT, "Градієнтний фон", "М'яке кольорове підсвічування екрана та панелі плеєра")
        FxRow(s, Fx.EQ, "Еквалайзер", "Анімовані смужки біля треку, що грає")
        FxRow(s, Fx.PULSE, "Пульсація обкладинки", "Обкладинка в плеєрі ледь пульсує, поки грає музика")
        FxRow(s, Fx.LISTANIM, "Анімації списку", "Плавне переміщення рядків і відскок серця")
        FxRow(s, Fx.FADE, "Плавні переходи", "Згасання між треками та плавна зміна кольорів")
    }
}

@Composable
private fun FxRow(s: AppSettings, key: String, title: String, subtitle: String, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { s.set(key, !s.on(key)) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = s.on(key) && enabled, onCheckedChange = { s.set(key, it) }, enabled = enabled)
    }
}
