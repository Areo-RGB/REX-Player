package xyz.mpv.rex.ui.browser.timers

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Environment
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.mpv.rex.R
import xyz.mpv.rex.preferences.preference.collectAsState as collectPrefAsState
import xyz.mpv.rex.utils.media.MediaUtils
import xyz.mpv.rex.utils.media.TimerClips
import java.io.File

/**
 * Process-wide timer engine, so a running timer survives tab switches. Collapsed version of
 * timer-machine's TimerMachine/TaskManager: flattened phases, RUNNING/PAUSED, drift-free countdown
 * against elapsedRealtime, beeps in the last 3 s and on every phase change.
 */
object TimerRunner {
  data class State(
    val timer: TimerClips.Timer,
    val phases: List<TimerClips.Phase>,
    val index: Int,
    val remainingMs: Long,
    val running: Boolean,
  ) {
    val phase get() = phases[index]
  }

  private val _state = MutableStateFlow<State?>(null)
  val state = _state.asStateFlow()

  /** True while the mpv player is acting as the timer view (portrait, crop, follows the steps). */
  @Volatile var playerMode = false

  /** Clip the player should show: the next-up clip during rest, else the current one. */
  fun clipFor(s: State): String? =
    (if (s.phase.isRest) s.phases.getOrNull(s.index + 1)?.clipPath else s.phase.clipPath)
      ?.takeIf { File(it).isFile }

  fun openInPlayer(context: android.content.Context) {
    val clip = _state.value?.let(::clipFor) ?: return
    playerMode = true
    MediaUtils.playFile(clip, context, "timers")
  }

  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var job: Job? = null
  private var endAt = 0L
  private var lastBeepSecond = -1L
  private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() }

  fun start(timer: TimerClips.Timer) {
    val phases = TimerClips.phases(timer)
    if (phases.isEmpty()) return
    _state.value = State(timer, phases, 0, phases[0].seconds * 1000L, false)
    resume()
    beep(long = true)
  }

  fun toggle() = if (_state.value?.running == true) pause() else resume()

  fun pause() {
    val s = _state.value ?: return
    if (!s.running) return
    job?.cancel()
    _state.value = s.copy(running = false, remainingMs = (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
  }

  fun resume() {
    val s = _state.value ?: return
    endAt = SystemClock.elapsedRealtime() + s.remainingMs
    _state.value = s.copy(running = true)
    job?.cancel()
    job = scope.launch {
      while (isActive) {
        val left = endAt - SystemClock.elapsedRealtime()
        if (left <= 0) {
          jump(+1, keepRunning = true)
          return@launch
        }
        val sec = (left + 999) / 1000
        if (sec <= 3 && sec != lastBeepSecond) {
          lastBeepSecond = sec
          beep(long = false)
        }
        _state.value = _state.value?.copy(remainingMs = left)
        delay(100)
      }
    }
  }

  fun jump(delta: Int, keepRunning: Boolean = _state.value?.running == true) {
    val s = _state.value ?: return
    job?.cancel()
    val next = s.index + delta
    if (next !in s.phases.indices) {
      if (next >= s.phases.size) beep(long = true)
      if (next >= s.phases.size) stop() else _state.value = s.copy(index = 0, remainingMs = s.phases[0].seconds * 1000L)
      return
    }
    lastBeepSecond = -1
    _state.value = s.copy(index = next, remainingMs = s.phases[next].seconds * 1000L, running = false)
    if (keepRunning) {
      beep(long = true)
      resume()
    }
  }

  fun stop() {
    job?.cancel()
    _state.value = null
  }

  private fun beep(long: Boolean) {
    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, if (long) 400 else 120)
  }
}

private fun formatSeconds(total: Long): String = "%d:%02d".format(total / 60, total % 60)

@Composable
fun TimersScreen() {
  val running by TimerRunner.state.collectAsState()
  var editing by remember { mutableStateOf<TimerClips.Timer?>(null) }
  var reload by remember { mutableIntStateOf(0) }

  when {
    editing != null -> TimerEditor(
      initial = editing!!,
      onClose = { saved ->
        editing = null
        if (saved) reload++
      },
    )
    running != null -> TimerRunView(running!!)
    else -> TimerList(reload = reload, onEdit = { editing = it }, onChanged = { reload++ })
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerList(
  reload: Int,
  onEdit: (TimerClips.Timer) -> Unit,
  onChanged: () -> Unit,
) {
  val hasAccess = remember { Environment.isExternalStorageManager() }
  var timers by remember { mutableStateOf<List<TimerClips.Timer>?>(null) }
  var toDelete by remember { mutableStateOf<TimerClips.Timer?>(null) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val browserPreferences = org.koin.compose.koinInject<xyz.mpv.rex.preferences.BrowserPreferences>()
  val playerDefault by browserPreferences.timerPlayerView.collectPrefAsState()
  var showSettings by remember { mutableStateOf(false) }

  if (showSettings) {
    AlertDialog(
      onDismissRequest = { showSettings = false },
      title = { Text("Timer settings") },
      text = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("Open timers in video player")
            Text(
              "Full-screen portrait player with timer overlay. Off: split screen with the timer.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.outline,
            )
          }
          Switch(playerDefault, { browserPreferences.timerPlayerView.set(it) })
        }
      },
      confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } },
    )
  }

  LaunchedEffect(reload) {
    timers = if (hasAccess) withContext(Dispatchers.IO) { TimerClips.scanTimers() } else emptyList()
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            stringResource(R.string.timers),
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
          )
        },
        actions = {
          IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Timer settings") }
        },
      )
    },
    floatingActionButton = {
      val newTimerLabel = stringResource(R.string.timer_new)
      if (hasAccess) {
        ExtendedFloatingActionButton(
          onClick = {
            onEdit(TimerClips.Timer(TimerClips.newTimerFile("New timer"), "New timer", 30, 15, 1, emptyList()))
          },
          icon = { Icon(Icons.Filled.Add, null) },
          text = { Text(newTimerLabel) },
          // material3 1.5 alpha drops the extended FAB's label from accessibility: set it explicitly
          modifier = Modifier.padding(bottom = xyz.mpv.rex.ui.browser.LocalNavigationBarHeight.current)
            .semantics { contentDescription = newTimerLabel },
        )
      }
    },
  ) { padding ->
    val list = timers
    when {
      list == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
      list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), Alignment.Center) {
        Text(stringResource(R.string.timer_none), color = MaterialTheme.colorScheme.outline)
      }
      else -> LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        itemsIndexed(list, key = { _, t -> t.file.path }) { _, timer ->
          TimerCard(
            timer = timer,
            onStart = {
              TimerRunner.start(timer)
              if (playerDefault) TimerRunner.openInPlayer(context)
            },
            onEdit = { onEdit(timer) },
            onDuplicate = {
              scope.launch(Dispatchers.IO) {
                var n = 2
                var f = File(timer.dir, "timer-copy.json")
                while (f.exists()) f = File(timer.dir, "timer-copy-${n++}.json")
                runCatching { TimerClips.save(timer.copy(file = f, name = timer.name + " (copy)")) }
                withContext(Dispatchers.Main) { onChanged() }
              }
            },
            onDelete = { toDelete = timer },
          )
        }
      }
    }
  }

  toDelete?.let { timer ->
    AlertDialog(
      onDismissRequest = { toDelete = null },
      title = { Text("Delete \"${timer.name}\"?") },
      text = { Text("Deletes ${timer.file.name}. Video clips in the folder are kept.") },
      confirmButton = {
        TextButton(onClick = {
          timer.file.delete()
          toDelete = null
          onChanged()
        }) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun TimerCard(
  timer: TimerClips.Timer,
  onStart: () -> Unit,
  onEdit: () -> Unit,
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(
    onClick = onStart,
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(timer.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
          "${timer.steps.size} steps · work ${timer.work}s · rest ${timer.rest}s · " +
            formatSeconds(TimerClips.totalSeconds(timer).toLong()) +
            if (timer.file.name != "timer.json") " · ${timer.file.name}" else "",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
        )
      }
      IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit") }
      IconButton(onClick = onDuplicate) { Icon(Icons.Filled.ContentCopy, "Duplicate") }
      IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
      FilledIconButton(onClick = onStart) { Icon(Icons.Filled.PlayArrow, "Start") }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerRunView(state: TimerRunner.State) {
  val view = LocalView.current
  DisposableEffect(Unit) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }
  BackHandler { TimerRunner.stop() }

  val phase = state.phase
  val next = state.phases.getOrNull(state.index + 1)

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(state.timer.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = TimerRunner::stop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Stop") } },
      )
    },
  ) { padding ->
    // Rest screen: next-up clip as a square preview, split with the timer
    val previewClip = if (phase.isRest) next?.clipPath?.takeIf { File(it).isFile } else null
    androidx.compose.foundation.layout.BoxWithConstraints(
      Modifier.fillMaxSize().padding(padding).padding(bottom = xyz.mpv.rex.ui.browser.LocalNavigationBarHeight.current).padding(24.dp),
    ) {
      val landscape = maxWidth > maxHeight
      when {
        previewClip == null -> TimerPanel(state, Modifier.fillMaxSize(), ringSize = 260.dp)
        landscape -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
          SquareClip(previewClip, next!!, Modifier.weight(1f))
          Spacer(Modifier.width(24.dp))
          TimerPanel(state, Modifier.weight(1f), ringSize = 220.dp)
        }
        else -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
          SquareClip(previewClip, next!!, Modifier.weight(1f, fill = false))
          Spacer(Modifier.height(24.dp))
          TimerPanel(state, Modifier.fillMaxWidth(), ringSize = 200.dp)
        }
      }
    }
  }
}

@Composable
private fun TimerPanel(state: TimerRunner.State, modifier: Modifier, ringSize: androidx.compose.ui.unit.Dp) {
  val context = LocalContext.current
  val phase = state.phase
  val color = if (phase.isRest) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
  val secondsLeft = (state.remainingMs + 999) / 1000
  val progress = (state.remainingMs / (phase.seconds * 1000f)).coerceIn(0f, 1f)
  val next = state.phases.getOrNull(state.index + 1)
  Column(
    modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      if (phase.isRest) "REST" else "WORK",
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.ExtraBold,
      color = color,
    )
    Text(phase.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(16.dp))
    Box(contentAlignment = Alignment.Center) {
      CircularProgressIndicator(
        progress = { progress },
        modifier = Modifier.size(ringSize),
        color = color,
        strokeWidth = 14.dp,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
      )
      Text(formatSeconds(secondsLeft), fontSize = 64.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(16.dp))
    Text(
      "${state.index + 1} / ${state.phases.size}" + (next?.let { " · Next: ${it.title} (${it.seconds}s)" } ?: ""),
      color = MaterialTheme.colorScheme.outline,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      IconButton(onClick = { TimerRunner.jump(-1) }) { Icon(Icons.Filled.SkipPrevious, "Previous") }
      IconButton(onClick = TimerRunner::stop) { Icon(Icons.Filled.Stop, "Stop") }
      LargeFloatingActionButton(onClick = TimerRunner::toggle) {
        Icon(if (state.running) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.running) "Pause" else "Resume")
      }
      IconButton(onClick = { TimerRunner.jump(+1) }) { Icon(Icons.Filled.SkipNext, "Next") }
      // During rest, the clip button opens the next-up clip
      if (TimerRunner.clipFor(state) != null) {
        IconButton(onClick = { TimerRunner.openInPlayer(context) }) { Icon(Icons.Filled.Videocam, "Player view") }
      }
    }
  }
}

/** Muted, looping clip in a square, center-cropped whatever the video's aspect ratio. */
@Composable
private fun SquareClip(path: String, upNext: TimerClips.Phase, modifier: Modifier) {
  Box(modifier.aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge)) {
    ClipVideo(path, Modifier.fillMaxSize())
    // Bottom scrim keeps the lower third readable on bright footage
    Box(
      Modifier.fillMaxWidth().fillMaxSize(0.45f).align(Alignment.BottomCenter).background(
        androidx.compose.ui.graphics.Brush.verticalGradient(
          listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f)),
        ),
      ),
    )
    // Re-keyed per step so the lower third animates in again for every rest
    androidx.compose.runtime.key(upNext) {
      LowerThird(
        "NEXT UP",
        upNext.name + if (upNext.side.isNotEmpty()) " (${upNext.side})" else "",
        "SET ${upNext.set}/${upNext.sets} · ${upNext.seconds}s", Modifier.align(Alignment.BottomStart).padding(20.dp))
    }
  }
}

/** Broadcast-style lower third: tag + accent bar slide in, then the title types out. */
@Composable
private fun LowerThird(
  tag: String,
  title: String,
  meta: String,
  modifier: Modifier,
  accent: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
  large: Boolean = false,
) {
  val labelStyle = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge
  val titleSize = if (large) 44.sp else 26.sp
  val slide = remember { androidx.compose.animation.core.Animatable(-1f) }
  var typed by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    delay(300)
    slide.animateTo(0f, androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    while (typed < title.length) {
      typed++
      delay(35)
    }
  }
  val white = androidx.compose.ui.graphics.Color.White
  val black = androidx.compose.ui.graphics.Color.Black
  Column(
    modifier.graphicsLayer {
      translationX = slide.value * (size.width + 40.dp.toPx())
    },
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        tag,
        color = black,
        fontWeight = FontWeight.ExtraBold,
        style = labelStyle,
        modifier = Modifier.background(white).padding(horizontal = 6.dp, vertical = 1.dp),
      )
      Box(Modifier.width(6.dp).height(if (large) 30.dp else 20.dp).background(accent))
      Text(
        meta,
        color = white,
        style = labelStyle,
        modifier = Modifier.padding(start = 8.dp),
      )
    }
    Text(
      title.take(typed),
      color = white,
      fontWeight = FontWeight.ExtraBold,
      fontSize = titleSize,
      lineHeight = titleSize * 1.15f,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.headlineSmall.copy(
        shadow = androidx.compose.ui.graphics.Shadow(black.copy(alpha = 0.8f), blurRadius = 8f),
      ),
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}

@Composable
private fun ClipVideo(path: String, modifier: Modifier) {
  androidx.compose.runtime.key(path) {
    val player = remember { android.media.MediaPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    androidx.compose.ui.viewinterop.AndroidView(
      modifier = modifier,
      factory = { ctx ->
        android.view.TextureView(ctx).apply {
          fun crop(vw: Int, vh: Int) {
            if (vw <= 0 || vh <= 0 || width == 0) return
            val m = minOf(vw, vh).toFloat()
            // TextureView stretches the video to the square; undo that and overflow the short side
            setTransform(android.graphics.Matrix().apply { setScale(vw / m, vh / m, width / 2f, height / 2f) })
          }
          surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
              runCatching {
                player.setSurface(android.view.Surface(st))
                player.setDataSource(path)
                player.setVolume(0f, 0f)
                player.isLooping = true
                player.setOnVideoSizeChangedListener { _, vw, vh -> crop(vw, vh) }
                player.setOnPreparedListener { it.start() }
                player.prepareAsync()
              }
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) =
              crop(player.videoWidth, player.videoHeight)
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
          }
        }
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerEditor(
  initial: TimerClips.Timer,
  onClose: (saved: Boolean) -> Unit,
) {
  var name by remember { mutableStateOf(initial.name) }
  var work by remember { mutableStateOf(initial.work.toString()) }
  var rest by remember { mutableStateOf(initial.rest.toString()) }
  var rounds by remember { mutableStateOf(initial.rounds.toString()) }
  val steps = remember { mutableStateListOf(*initial.steps.toTypedArray()) }
  var confirmLeave by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  // Clip folder: fixed for existing timers (clip paths are relative to it), pickable for new ones
  val isNew = remember { !initial.file.exists() }
  var clipDir by remember { mutableStateOf(if (isNew) null else initial.dir) }
  var pickingFolder by remember { mutableStateOf(false) }
  val clips = remember(clipDir) { TimerClips.videosIn(clipDir) }

  fun result() = initial.copy(
    name = name.trim().ifEmpty { "Timer" },
    work = work.toIntOrNull()?.coerceAtLeast(1) ?: initial.work,
    rest = rest.toIntOrNull()?.coerceAtLeast(0) ?: initial.rest,
    rounds = rounds.toIntOrNull()?.coerceAtLeast(1) ?: initial.rounds,
    steps = steps.toList(),
  )

  fun save() {
    val t = result().let {
      when {
        it.file.exists() -> it
        clipDir != null -> it.copy(file = TimerClips.timerFileIn(clipDir!!, it.name))
        else -> it.copy(file = TimerClips.newTimerFile(it.name))
      }
    }
    scope.launch {
      val ok = withContext(Dispatchers.IO) { runCatching { TimerClips.save(t) } }
      ok.onSuccess { onClose(true) }.onFailure { error = it.message }
    }
  }

  val dirty = result() != initial
  BackHandler { if (dirty) confirmLeave = true else onClose(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.timer_edit)) },
        navigationIcon = {
          IconButton(onClick = { if (dirty) confirmLeave = true else onClose(false) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
        actions = { TextButton(onClick = ::save) { Text("Save") } },
      )
    },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = { steps.add(TimerClips.Step("Step ${steps.size + 1}", null, 1, false, null)) },
        icon = { Icon(Icons.Filled.Add, null) },
        text = { Text("Add step") },
        modifier = Modifier.padding(bottom = xyz.mpv.rex.ui.browser.LocalNavigationBarHeight.current)
          .semantics { contentDescription = "Add step" },
      )
    },
  ) { padding ->
    LazyColumn(
      Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(work, { work = it }, "Work s", Modifier.weight(1f))
            NumberField(rest, { rest = it }, "Rest s", Modifier.weight(1f))
            NumberField(rounds, { rounds = it }, "Rounds", Modifier.weight(1f))
          }
          Text(
            "Total ${formatSeconds(TimerClips.totalSeconds(result()).toLong())} · ${if (isNew) clipDir?.path ?: "new folder" else initial.file.path}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
          )
          OutlinedTextField(
            value = clipDir?.name ?: "None",
            onValueChange = {},
            readOnly = true,
            enabled = isNew,
            label = { Text("Clip folder") },
            supportingText = {
              Text(if (isNew) "Steps can use videos from this folder" else "${clips.size} videos · fixed for saved timers")
            },
            trailingIcon = {
              if (isNew) IconButton(onClick = { pickingFolder = true }) { Icon(Icons.Filled.Folder, "Pick folder") }
            },
            modifier = Modifier.fillMaxWidth(),
          )
          error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
      }
      itemsIndexed(steps) { index, step ->
        StepCard(
          step = step,
          clips = clips,
          onChange = { steps[index] = it },
          onUp = { if (index > 0) steps.add(index - 1, steps.removeAt(index)) },
          onDown = { if (index < steps.lastIndex) steps.add(index + 1, steps.removeAt(index)) },
          onDelete = { steps.removeAt(index) },
        )
      }
    }
  }

  if (pickingFolder) {
    FolderPickerDialog(
      onPick = { dir ->
        pickingFolder = false
        clipDir = dir
        // Clip names are folder-relative: drop picks that don't exist in the new folder
        val names = TimerClips.videosIn(dir).toSet()
        steps.indices.forEach { i -> if (steps[i].file != null && steps[i].file !in names) steps[i] = steps[i].copy(file = null) }
      },
      onDismiss = { pickingFolder = false },
    )
  }

  if (confirmLeave) {
    AlertDialog(
      onDismissRequest = { confirmLeave = false },
      title = { Text("Unsaved changes") },
      confirmButton = { TextButton(onClick = { confirmLeave = false; save() }) { Text("Save") } },
      dismissButton = { TextButton(onClick = { confirmLeave = false; onClose(false) }) { Text("Discard") } },
    )
  }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
  OutlinedTextField(
    value = value,
    onValueChange = { v -> onChange(v.filter(Char::isDigit).take(4)) },
    label = { Text(label) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    modifier = modifier,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepCard(
  step: TimerClips.Step,
  clips: List<String>,
  onChange: (TimerClips.Step) -> Unit,
  onUp: () -> Unit,
  onDown: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          step.title,
          { onChange(step.copy(title = it)) },
          label = { Text("Title") },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onUp) { Icon(Icons.Filled.ArrowUpward, "Move up") }
        IconButton(onClick = onDown) { Icon(Icons.Filled.ArrowDownward, "Move down") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete step") }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(step.work?.toString() ?: "", { onChange(step.copy(work = it.toIntOrNull())) }, "Work s (default)", Modifier.weight(1f))
        NumberField(step.sets.toString(), { onChange(step.copy(sets = it.toIntOrNull()?.coerceAtLeast(1) ?: 1)) }, "Sets", Modifier.width(96.dp))
        Text("Sides")
        Switch(step.sides, { onChange(step.copy(sides = it)) })
      }
      run {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
          OutlinedTextField(
            value = step.file ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text("Clip") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
          )
          ExposedDropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { onChange(step.copy(file = null)); expanded = false })
            if (clips.isEmpty()) {
              DropdownMenuItem(text = { Text("Pick a clip folder first", color = MaterialTheme.colorScheme.outline) }, onClick = { expanded = false }, enabled = false)
            }
            clips.forEach { c ->
              DropdownMenuItem(
                text = { Text(c) },
                onClick = {
                  // Default "Step N" titles take the clip's name ("03 - Side to Side.mp4" → "Side to Side")
                  val auto = Regex("Step \\d+").matches(step.title) || step.title.isBlank()
                  val clipTitle = c.substringBeforeLast('.').replace(Regex("^\\d+\\s*-\\s*"), "")
                  onChange(step.copy(file = c, title = if (auto) clipTitle else step.title))
                  expanded = false
                },
              )
            }
          }
        }
      }
    }
  }
}

/** Compact running-timer pill drawn over the video player; tap = pause/resume. */
@Composable
fun TimerOverlay(modifier: Modifier = Modifier) {
  val state by TimerRunner.state.collectAsState()
  val s = state ?: return
  androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
    val portrait = maxHeight > maxWidth
    when {
      TimerRunner.playerMode && portrait -> PortraitTimerOverlay(s)
      else -> {
        TimerPill(s, Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp))
        val next = s.phases.getOrNull(s.index + 1)
        if (TimerRunner.playerMode && s.phase.isRest && next != null) {
          BottomScrim(0.35f, Modifier.align(Alignment.BottomCenter))
          androidx.compose.runtime.key(s.index) {
            LowerThird(
              "NEXT UP",
              next.displayName(),
              "SET ${next.set}/${next.sets} · ${next.seconds}s",
              Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 140.dp),
            )
          }
        }
      }
    }
  }
}

private fun TimerClips.Phase.displayName() = name + if (side.isNotEmpty()) " ($side)" else ""

@Composable
private fun BottomScrim(fraction: Float, modifier: Modifier) {
  Box(
    modifier.fillMaxWidth().fillMaxSize(fraction).background(
      androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f), androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.88f)),
      ),
    ),
  )
}

/**
 * Vertical layout for the portrait player: everything stacked in the lower half, big enough to
 * read mid-exercise. Lower third (NOW / NEXT UP) → big ring + phase info → whole-workout progress.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PortraitTimerOverlay(s: TimerRunner.State) {
  val phase = s.phase
  val next = s.phases.getOrNull(s.index + 1)
  val white = androidx.compose.ui.graphics.Color.White
  // Light tints: the overlay always sits on dark scrim over video, regardless of app theme
  val color = if (phase.isRest) androidx.compose.ui.graphics.Color(0xFFF2B8FF) else androidx.compose.ui.graphics.Color(0xFFB3C8FF)
  BottomScrim(0.6f, Modifier.align(Alignment.BottomCenter))
  Column(
    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 56.dp),
  ) {
    // One row: ring timer (left, tap = pause) + lower third about the step (right), centred together
    val about = if (phase.isRest) next else phase
    Row(verticalAlignment = Alignment.CenterVertically) {
      androidx.compose.material3.Surface(
        onClick = TimerRunner::toggle,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f),
        contentColor = white,
      ) {
        Box(contentAlignment = Alignment.Center) {
          CircularProgressIndicator(
            progress = { (s.remainingMs / (phase.seconds * 1000f)).coerceIn(0f, 1f) },
            modifier = Modifier.size(130.dp),
            color = color,
            strokeWidth = 10.dp,
            trackColor = white.copy(alpha = 0.2f),
          )
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatSeconds((s.remainingMs + 999) / 1000), fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Icon(if (s.running) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (s.running) "Pause" else "Resume", Modifier.size(20.dp))
          }
        }
      }
      Spacer(Modifier.width(20.dp))
      androidx.compose.runtime.key(s.index) {
        LowerThird(
          tag = if (phase.isRest) "NEXT UP" else "NOW",
          title = about?.displayName() ?: "Done",
          meta = about?.let { "SET ${it.set}/${it.sets} · ${it.seconds}s" } ?: "",
          modifier = Modifier.weight(1f),
          accent = color,
          large = true,
        )
      }
    }
    Spacer(Modifier.height(20.dp))
    // Whole-workout progress
    val totalMs = s.phases.sumOf { it.seconds } * 1000f
    val doneMs = s.phases.take(s.index).sumOf { it.seconds } * 1000f + (phase.seconds * 1000f - s.remainingMs)
    androidx.compose.material3.LinearProgressIndicator(
      progress = { (doneMs / totalMs).coerceIn(0f, 1f) },
      modifier = Modifier.fillMaxWidth().height(6.dp),
      color = white,
      trackColor = white.copy(alpha = 0.25f),
    )
    Text(
      formatSeconds(((totalMs - doneMs) / 1000).toLong().coerceAtLeast(0)) + " left",
      color = white.copy(alpha = 0.7f),
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
    )
  }
}

@Composable
private fun TimerPill(s: TimerRunner.State, modifier: Modifier) {
  val phase = s.phase
  val color = if (phase.isRest) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
  androidx.compose.material3.Surface(
    onClick = TimerRunner::toggle,
    modifier = modifier,
    shape = MaterialTheme.shapes.extraLarge,
    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
    contentColor = androidx.compose.ui.graphics.Color.White,
  ) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
          progress = { (s.remainingMs / (phase.seconds * 1000f)).coerceIn(0f, 1f) },
          modifier = Modifier.size(40.dp),
          color = color,
          strokeWidth = 4.dp,
          trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f),
        )
        Icon(if (s.running) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
      }
      Spacer(Modifier.width(10.dp))
      Column {
        Text(
          formatSeconds((s.remainingMs + 999) / 1000),
          fontSize = 22.sp,
          fontWeight = FontWeight.Bold,
        )
        Text(
          (if (phase.isRest) "REST" else "WORK") + " · " + phase.title,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.width(180.dp),
        )
      }
    }
  }
}


/** Folder browser rooted at the timer-clips folder; can't navigate above it. */
@Composable
private fun FolderPickerDialog(onPick: (File) -> Unit, onDismiss: () -> Unit) {
  val root = remember { File(TimerClips.DEFAULT_ROOT) }
  var dir by remember { mutableStateOf(root) }
  val subdirs = remember(dir) { (dir.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedBy { it.name.lowercase() } }
  val videos = remember(dir) { TimerClips.videosIn(dir).size }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (dir != root) {
          IconButton(onClick = { dir = dir.parentFile ?: root }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
        }
        Text(if (dir == root) "timer-clips" else dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    },
    text = {
      Column {
        Text("$videos videos here", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.height(360.dp)) {
          itemsIndexed(subdirs) { _, d ->
            Row(
              Modifier.fillMaxWidth().clickable { dir = d }.padding(vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
              Spacer(Modifier.width(12.dp))
              Column(Modifier.weight(1f)) {
                Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                  "${TimerClips.videosIn(d).size} videos",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.outline,
                )
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = { onPick(dir) }, enabled = dir != root) { Text("Use this folder") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
