package com.example.lightplayer

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

fun onColor(c: Color): Color = if (c.luminance() > 0.55f) Color.Black else Color.White

fun hueShift(c: Color, deg: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = (hsv[0] + deg) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// ---------------------------------------------------------------- допоміжне

/** true лише поки додаток реально на екрані: ефекти в цей час малюються, у фоні вони вимкнені. */
@Composable
fun rememberVisible(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val st by lifecycle.currentStateFlow.collectAsState()
    return st.isAtLeast(Lifecycle.State.RESUMED)
}

/** Пульсація: поки active, масштаб "дихає"; коли вимкнено, плавно повертається до 1. */
@Composable
fun rememberPulse(active: Boolean): State<Float> {
    val p = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                p.animateTo(1.07f, tween(800))
                p.animateTo(1f, tween(800))
            }
        } else {
            p.animateTo(1f, tween(250))
        }
    }
    return p.asState()
}

/** Повільний дрейф градієнта 0..1; при зупинці лишається на місці. */
@Composable
fun rememberDrift(active: Boolean): State<Float> {
    val p = remember { Animatable(0.5f) }
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                p.animateTo(1f, tween(7000, easing = LinearEasing))
                p.animateTo(0f, tween(7000, easing = LinearEasing))
            }
        }
    }
    return p.asState()
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
        Box(
            modifier.background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.primaryContainer)
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Ic.Note, null, Modifier.fillMaxSize(0.5f),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
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
                sc.snapTo(1.5f)
                sc.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 400f))
            }
        }
    }
    IconButton(onClick = onClick) {
        Icon(
            if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = null,
            tint = if (fav) Color(0xFFFF4D6D) else MaterialTheme.colorScheme.onSurfaceVariant,
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
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Ic.Folder, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                "Треків: $count", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
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
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                else Modifier
            )
            .clickable { onPlay(index) }
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(t, 96, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
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
private fun CtrlButton(icon: ImageVector, tint: Color, size: Dp = 30.dp, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, null, Modifier.size(size), tint = tint) }
}

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
    g1: State<Color>,
    g2: State<Color>,
    strength: Float,
    forward: Boolean,
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
    val glow = s.on(Fx.GLOW)
    val gradient = s.on(Fx.GRADIENT)
    val panelAlpha = (strength + 0.2f).coerceAtMost(0.95f)

    val header: @Composable (Track) -> Unit = { t ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(
                t, 384,
                Modifier.size(72.dp)
                    .graphicsLayer {
                        scaleX = pulse.value
                        scaleY = pulse.value
                    }
                    .shadow(
                        if (glow) 16.dp else 0.dp, RoundedCornerShape(14.dp),
                        ambientColor = accent, spotColor = accent
                    )
                    .clip(RoundedCornerShape(14.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                )
                Text(
                    t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = inactive
                )
            }
            Heart(fav, s.on(Fx.LISTANIM), onFav)
        }
    }

    Surface(
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .then(
                    if (gradient) Modifier.drawBehind {
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(
                                    g1.value.copy(alpha = panelAlpha),
                                    g2.value.copy(alpha = panelAlpha)
                                )
                            )
                        )
                    } else Modifier
                )
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (s.on(Fx.FADE)) {
                AnimatedContent(
                    targetState = track,
                    transitionSpec = {
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(tween(420)) { dir * it / 2 } +
                            fadeIn(tween(420)) +
                            scaleIn(animationSpec = tween(420), initialScale = 0.88f)) togetherWith
                            (slideOutHorizontally(tween(320)) { -dir * it / 2 } + fadeOut(tween(220)))
                    },
                    label = "track"
                ) { header(it) }
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
                CtrlButton(Ic.Shuffle, if (shuffle) accent else inactive, 26.dp) {
                    player.shuffleModeEnabled = !shuffle
                }
                CtrlButton(Ic.Prev, MaterialTheme.colorScheme.onSurface, 34.dp) { player.seekToPrevious() }
                Box(
                    Modifier.size(62.dp).clip(CircleShape).background(accent)
                        .clickable { if (player.isPlaying) player.pause() else player.play() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Ic.Pause else Ic.Play, null,
                        Modifier.size(36.dp), tint = onColor(accent)
                    )
                }
                CtrlButton(Ic.Next, MaterialTheme.colorScheme.onSurface, 34.dp) { player.seekToNext() }
                CtrlButton(
                    if (repeat == Player.REPEAT_MODE_ONE) Ic.RepeatOne else Ic.Repeat,
                    if (repeat == Player.REPEAT_MODE_OFF) inactive else accent, 26.dp
                ) {
                    player.repeatMode = when (repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- налаштування

private val palette = listOf(
    Color(0xFFE53935), Color(0xFFFF7043), Color(0xFFFFB300), Color(0xFFC0CA33),
    Color(0xFF43A047), Color(0xFF00ACC1), Color(0xFF1E88E5), Color(0xFF3949AB),
    Color(0xFF8E24AA), Color(0xFFEC407A), Color(0xFF26A69A), Color(0xFF546E7A),
)

private val presets = listOf(
    Triple("Океан", Color(0xFF00C6FF), Color(0xFF0072FF)),
    Triple("Захід", Color(0xFFFF9966), Color(0xFFFF5E62)),
    Triple("Неон", Color(0xFFFF00CC), Color(0xFF3333FF)),
    Triple("Ліс", Color(0xFF11998E), Color(0xFF38EF7D)),
    Triple("Лава", Color(0xFFF12711), Color(0xFFF5AF19)),
    Triple("Крига", Color(0xFF74EBD5), Color(0xFF9FACE6)),
    Triple("Фіолет", Color(0xFF8E2DE2), Color(0xFF4A00E0)),
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SwatchRow(title: String, selected: Int, onPick: (Int) -> Unit) {
    Text(
        title, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (c in palette) {
            val argb = c.toArgb()
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(c)
                    .then(
                        if (argb == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        else Modifier
                    )
                    .clickable { onPick(argb) }
            )
        }
    }
}

@Composable
fun SettingsScreen(s: AppSettings, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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

        SectionTitle("Кольори")
        FxRow(s, Fx.DYNAMIC, "Кольори з шпалер", "Тема підлаштовується під шпалери телефона (Android 12+)",
            enabled = Build.VERSION.SDK_INT >= 31)
        FxRow(s, Fx.COVER, "Колір з обкладинки", "Увесь інтерфейс (кнопки, виділення, повзунок) бере колір з обкладинки треку")

        SectionTitle("Градієнт")
        FxRow(s, Fx.GRADIENT, "Градієнтний фон", "Кольоровий фон екрана та панелі плеєра")
        FxRow(s, Fx.ANIMGRAD, "Живий градієнт", "Фон повільно «пливе», поки грає музика")
        Row(
            Modifier.fillMaxWidth().clickable { s.setCustom(!s.gradCustom) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Власні кольори градієнта", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (s.gradCustom) "Градієнт з двох вибраних кольорів"
                    else "Зараз авто: колір береться з обкладинки",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = s.gradCustom, onCheckedChange = { s.setCustom(it) })
        }
        if (s.gradCustom) {
            Text(
                "Готові теми", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for ((name, a, b) in presets) {
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(Brush.horizontalGradient(listOf(a, b)))
                            .clickable { s.setColors(a.toArgb(), b.toArgb()) }
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    ) { Text(name, color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
            SwatchRow("Колір зверху", s.grad1) { s.setColors(it, s.grad2) }
            SwatchRow("Колір знизу", s.grad2) { s.setColors(s.grad1, it) }
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.horizontalGradient(listOf(Color(s.grad1), Color(s.grad2))))
            )
        }
        Text(
            "Яскравість градієнта", style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        Slider(
            value = s.gradStrength,
            onValueChange = { s.setStrength(it, persist = false) },
            onValueChangeFinished = { s.setStrength(s.gradStrength, persist = true) },
            valueRange = 0.15f..0.9f,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SectionTitle("Анімації")
        FxRow(s, Fx.EQ, "Еквалайзер", "Анімовані смужки біля треку, що грає")
        FxRow(s, Fx.PULSE, "Пульсація обкладинки", "Обкладинка в плеєрі «дихає», поки грає музика")
        FxRow(s, Fx.GLOW, "Сяйво обкладинки", "Кольорове світіння навколо обкладинки в плеєрі")
        FxRow(s, Fx.LISTANIM, "Анімації списку", "Плавне переміщення рядків і відскок серця")
        FxRow(s, Fx.FADE, "Плавні переходи", "Слайд між треками, папками та екранами, плавна зміна кольорів")
        Spacer(Modifier.height(28.dp))
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
