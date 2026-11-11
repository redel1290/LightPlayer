package com.example.lightplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var future: ListenableFuture<MediaController>? = null
    private val controller = mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppSettings(this)
        val favs = Favs(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (settings.on(Fx.DYNAMIC) && Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                App(controller.value, settings, favs)
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

class FolderItem(val name: String, val path: String, val count: Int)
class ListView(val folders: List<FolderItem>, val playable: List<Track>)

/** Який екран зараз показується (для анімації переходів). */
data class Screen(val settings: Boolean, val path: String) {
    val depth: Int
        get() = if (settings) 1000 else if (path.isEmpty()) 0 else path.count { it == '/' } + 1
}

/**
 * Вміст екрана: спершу папки (за алфавітом), потім обрані треки, потім решта.
 * При пошуку показуються збіги з усієї бібліотеки (без папок).
 */
fun buildView(tracks: List<Track>, path: String, query: String, favs: Set<Long>): ListView {
    if (query.isNotBlank()) {
        val m = tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
        return ListView(emptyList(), m.sortedByDescending { it.id in favs })
    }
    val prefix = if (path.isEmpty()) "" else "$path/"
    val counts = LinkedHashMap<String, Int>()
    val direct = ArrayList<Track>()
    for (t in tracks) {
        if (t.folder == path) {
            direct += t
        } else if (t.folder.startsWith(prefix)) {
            val seg = t.folder.substring(prefix.length).substringBefore('/')
            counts[seg] = (counts[seg] ?: 0) + 1
        }
    }
    val folders = counts.entries
        .map { FolderItem(it.key, prefix + it.key, it.value) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    // sortedByDescending стабільне: обрані нагору, решта лишається як є
    return ListView(folders, direct.sortedByDescending { it.id in favs })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(player: Player?, s: AppSettings, favs: Favs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

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
    var refreshing by remember { mutableStateOf(false) }

    suspend fun reload() {
        val list = withContext(Dispatchers.IO) { Library.load(ctx) }
        if (list != tracks) tracks = list
        loaded = true
    }

    val visible = rememberVisible()
    // Бібліотека перечитується при кожному поверненні в додаток (і при першому запуску)
    LaunchedEffect(granted, visible) {
        if (granted && visible) reload()
    }

    val onRefresh: () -> Unit = {
        scope.launch {
            refreshing = true
            try {
                Library.rescan(ctx)
                reload()
            } finally {
                refreshing = false
            }
        }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val searching = query.isNotBlank()
    val favIds = favs.ids

    BackHandler(enabled = showSettings || searching || path.isNotEmpty()) {
        when {
            showSettings -> showSettings = false
            searching -> query = ""
            else -> path = path.substringBeforeLast('/', "")
        }
    }

    // --- стан плеєра ---
    var isPlaying by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var duration by remember { mutableLongStateOf(0L) }
    var forward by remember { mutableStateOf(true) }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        var lastIdx = -1
        fun sync() {
            isPlaying = player.isPlaying
            currentId = player.currentMediaItem?.mediaId
            shuffle = player.shuffleModeEnabled
            repeat = player.repeatMode
            duration = player.duration.coerceAtLeast(0L)
            val ni = player.currentMediaItemIndex
            if (ni != lastIdx) {
                forward = ni >= lastIdx
                lastIdx = ni
            }
        }
        sync()
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = sync()
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    val current = remember(currentId, tracks) { tracks.firstOrNull { it.id.toString() == currentId } }

    // Відновлення з queue.txt: беремо номер треку, шукаємо його в бібліотеці й ставимо на паузі.
    // Якщо треку нема (або файлу нема), нічого не вибирається.
    LaunchedEffect(player, tracks) {
        if (player == null || tracks.isEmpty() || player.mediaItemCount > 0) return@LaunchedEffect
        val saved = withContext(Dispatchers.IO) { QueueStore.read(ctx) } ?: return@LaunchedEffect
        val curId = saved.ids.getOrNull(saved.cur - 1) ?: return@LaunchedEffect
        val byId = tracks.associateBy { it.id }
        if (byId[curId] == null) return@LaunchedEffect
        val queue = saved.ids.mapNotNull { byId[it] }
        val index = queue.indexOfFirst { it.id == curId }
        if (index < 0 || player.mediaItemCount > 0) return@LaunchedEffect
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeat
        player.setMediaItems(queue.map { it.toItem() }, index, saved.pos)
        player.prepare()
    }

    val onFav = remember(favs) { { id: Long -> favs.toggle(id) } }

    // --- кольори ---
    val outer = MaterialTheme.colorScheme
    val gradOn = s.on(Fx.GRADIENT)
    val custom = gradOn && s.gradCustom
    val coverOn = s.on(Fx.COVER)
    val coverAccent by produceState<Color?>(null, current?.id, coverOn) {
        value = if (current == null || !coverOn) null
        else withContext(Art.dispatcher) { Art.accent(ctx, current) }?.let { Color(it) }
    }
    // Колір, яким фарбується весь інтерфейс: власний градієнт > обкладинка > колір теми
    val override: Color? = if (custom) Color(s.grad1) else coverAccent
    val accent = override ?: outer.primary
    val themed = remember(outer, override) {
        if (override == null) outer else {
            val container = override.copy(alpha = 0.38f).compositeOver(outer.surface)
            outer.copy(
                primary = override,
                onPrimary = onColor(override),
                primaryContainer = container,
                onPrimaryContainer = outer.onSurface,
                secondaryContainer = hueShift(override, 40f).copy(alpha = 0.30f).compositeOver(outer.surface),
            )
        }
    }
    val c1 = if (custom) Color(s.grad1) else accent
    val c2 = if (custom) Color(s.grad2) else remember(accent) { hueShift(accent, 55f) }
    val fadeOn = s.on(Fx.FADE)
    val g1 = animateColorAsState(c1, tween(if (fadeOn) 800 else 0), label = "g1")
    val g2 = animateColorAsState(c2, tween(if (fadeOn) 800 else 0), label = "g2")
    val strength = s.gradStrength
    val drift = rememberDrift(gradOn && s.on(Fx.ANIMGRAD) && isPlaying && visible)

    val eqOn = s.on(Fx.EQ)
    val animOn = s.on(Fx.LISTANIM)

    MaterialTheme(colorScheme = themed) {
        Box(
            Modifier
                .fillMaxSize()
                .background(themed.background)
                .then(
                    if (gradOn) Modifier.drawBehind {
                        val h = size.height
                        val off = (drift.value - 0.5f) * h * 0.6f
                        drawRect(
                            Brush.verticalGradient(
                                listOf(
                                    g1.value.copy(alpha = strength),
                                    g2.value.copy(alpha = strength)
                                ),
                                startY = -h * 0.2f + off,
                                endY = h * 1.2f + off
                            )
                        )
                    } else Modifier
                )
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    if (player != null && current != null) {
                        AnimatedVisibility(
                            visible = !showSettings,
                            enter = if (fadeOn) slideInVertically { it } + fadeIn() else EnterTransition.None,
                            exit = if (fadeOn) slideOutVertically { it } + fadeOut() else ExitTransition.None
                        ) {
                            PlayerPanel(
                                player, current, isPlaying, shuffle, repeat, duration,
                                current.id in favIds, accent, g1, g2, strength, forward, visible, s,
                                onFav = { favs.toggle(current.id) }
                            )
                        }
                    }
                }
            ) { pad ->
                val body: @Composable (Screen) -> Unit = { sc ->
                    if (sc.settings) {
                        SettingsScreen(s) { showSettings = false }
                    } else {
                        val view = remember(tracks, sc.path, query, favIds) {
                            buildView(tracks, sc.path, query, favIds)
                        }
                        val playAt = remember(view, player) {
                            { index: Int ->
                                player?.run {
                                    setMediaItems(view.playable.map { it.toItem() }, index, 0L)
                                    prepare()
                                    play()
                                }
                                Unit
                            }
                        }
                        Column(Modifier.fillMaxSize()) {
                            if (!searching && sc.path.isNotEmpty()) {
                                FolderHeader(sc.path) { path = sc.path.substringBeforeLast('/', "") }
                            }
                            // Потягни вниз від верху списку: пересканувати Music і оновити список
                            PullToRefreshBox(
                                isRefreshing = refreshing,
                                onRefresh = onRefresh,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                when {
                                    !granted -> Box(
                                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Button(onClick = { launcher.launch(perms) }) {
                                            Text("Надати доступ до музики")
                                        }
                                    }
                                    loaded && tracks.isEmpty() -> Box(
                                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                        contentAlignment = Alignment.Center
                                    ) { Text("У /Music нічого не знайдено\nПотягни вниз, щоб оновити") }
                                    else -> LazyColumn(Modifier.fillMaxSize()) {
                                        items(view.folders, key = { "f:" + it.path }) { f ->
                                            Box(if (animOn) Modifier.animateItem() else Modifier) {
                                                FolderRow(f.name, f.count) { path = f.path }
                                            }
                                        }
                                        itemsIndexed(view.playable, key = { _, t -> t.id }) { index, t ->
                                            val selected = t.id.toString() == currentId
                                            Box(if (animOn) Modifier.animateItem() else Modifier) {
                                                TrackRow(
                                                    t, index, selected,
                                                    playing = selected && isPlaying && visible,
                                                    fav = t.id in favIds,
                                                    eq = eqOn,
                                                    bounce = animOn,
                                                    onPlay = playAt,
                                                    onFav = onFav,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Column(Modifier.padding(pad).fillMaxSize()) {
                    AnimatedVisibility(
                        visible = !showSettings,
                        enter = if (fadeOn) expandVertically() + fadeIn() else EnterTransition.None,
                        exit = if (fadeOn) shrinkVertically() + fadeOut() else ExitTransition.None
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("Пошук") }
                            )
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Filled.Settings, "Налаштування", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = {
                                // Дозволяє грати у фоні без агресивного вбивства від батареї
                                runCatching {
                                    ctx.startActivity(
                                        Intent(
                                            SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${ctx.packageName}")
                                        )
                                    )
                                }
                            }) { Text("🔋", fontSize = 22.sp) }
                        }
                    }

                    val screen = Screen(showSettings, path)
                    if (fadeOn) {
                        AnimatedContent(
                            targetState = screen,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            transitionSpec = {
                                if (targetState.depth > initialState.depth) {
                                    (slideInHorizontally(tween(340)) { it / 3 } + fadeIn(tween(340))) togetherWith
                                        (slideOutHorizontally(tween(340)) { -it / 3 } + fadeOut(tween(200)))
                                } else {
                                    (slideInHorizontally(tween(340)) { -it / 3 } + fadeIn(tween(340))) togetherWith
                                        (slideOutHorizontally(tween(340)) { it / 3 } + fadeOut(tween(200)))
                                }
                            },
                            label = "nav"
                        ) { sc -> body(sc) }
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth()) { body(screen) }
                    }
                }
            }
        }
    }
}
