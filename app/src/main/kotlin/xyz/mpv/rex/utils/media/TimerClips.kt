package xyz.mpv.rex.utils.media

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Timer-clip folders: `<root>/<folder>/` holds video clips plus `timer*.json` files
 * (`timer` = name/work/rest/loop, `clips` = ordered steps). Port of timer-clips-folder-playlists.lua:
 * every folder becomes a playlist (timer.json order, else alphabetical videos), and every
 * timer json is a runnable, editable timer.
 */
object TimerClips {
  const val DEFAULT_ROOT = "/storage/emulated/0/Download/timer-clips"

  private val videoExt = setOf(
    "mp4", "mkv", "webm", "mov", "avi", "m4v", "mpg", "mpeg", "ts", "m2ts", "flv", "wmv", "ogv", "3gp",
  )

  data class Clip(val path: String, val title: String)

  data class Folder(val name: String, val clips: List<Clip>)

  data class Step(
    val title: String,
    /** Work seconds; null = timer default. */
    val work: Int?,
    val sets: Int,
    val sides: Boolean,
    /** Clip file name inside the folder, if any. */
    val file: String?,
    /** Original clip json, so unknown fields (start_time, reps, …) survive an edit. */
    val raw: String = "{}",
  )

  data class Timer(
    val file: File,
    val name: String,
    val work: Int,
    val rest: Int,
    val rounds: Int,
    val steps: List<Step>,
  ) {
    val dir: File get() = file.parentFile!!
  }

  /** One flattened runtime step, the unit the runner counts down. */
  data class Phase(
    val title: String,
    val seconds: Int,
    val isRest: Boolean,
    val clipPath: String?,
    /** Exercise name without set/side/round suffixes. */
    val name: String = title,
    val set: Int = 1,
    val sets: Int = 1,
    val side: String = "",
  )

  fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in videoExt

  private fun sortedDirs(root: File) =
    (root.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedBy { it.name.lowercase() }

  /** Folder playlists, same rules as the Lua script; empty folders skipped. */
  fun scanFolders(root: File = File(DEFAULT_ROOT)): List<Folder> = sortedDirs(root).mapNotNull { dir ->
    val fromJson = runCatching { load(File(dir, "timer.json")) }.getOrNull()
      ?.steps?.mapNotNull { s ->
        s.file?.let { File(dir, it) }?.takeIf { it.isFile }?.let { Clip(it.path, s.title) }
      }.orEmpty()
    val clips = fromJson.ifEmpty {
      (dir.listFiles { f -> f.isFile && isVideo(f.name) } ?: emptyArray())
        .sortedBy { it.name.lowercase() }
        .map { Clip(it.path, it.nameWithoutExtension) }
    }
    if (clips.isEmpty()) null else Folder(dir.name, clips)
  }

  /** Every `timer*.json` one level below root. */
  fun scanTimers(root: File = File(DEFAULT_ROOT)): List<Timer> = sortedDirs(root).flatMap { dir ->
    (dir.listFiles { f -> f.isFile && f.name.startsWith("timer") && f.name.endsWith(".json") } ?: emptyArray())
      .sortedBy { it.name }
      .mapNotNull { runCatching { load(it) }.getOrNull() }
  }

  fun load(file: File): Timer {
    val json = JSONObject(file.readText())
    val t = json.optJSONObject("timer") ?: JSONObject()
    val clips = json.optJSONArray("clips") ?: JSONArray()
    val steps = (0 until clips.length()).mapNotNull { i ->
      val c = clips.optJSONObject(i) ?: return@mapNotNull null
      val f = c.optString("file").ifEmpty { null }
      Step(
        title = c.optString("title").ifEmpty { f?.substringBeforeLast('.') ?: "Step ${i + 1}" },
        work = if (c.has("work")) c.optInt("work") else null,
        sets = c.optInt("sets", 1).coerceAtLeast(1),
        sides = c.optBoolean("sides", false),
        file = f,
        raw = c.toString(),
      )
    }
    return Timer(
      file = file,
      name = t.optString("name").ifEmpty { file.parentFile?.name ?: file.nameWithoutExtension },
      work = t.optInt("work", 30),
      rest = t.optInt("rest", 15),
      rounds = t.optInt("loop", 1).coerceAtLeast(1),
      steps = steps,
    )
  }

  /** Writes [timer] back to its file, keeping every field this editor does not know about. */
  fun save(timer: Timer) {
    val json = runCatching { JSONObject(timer.file.readText()) }.getOrElse { JSONObject() }
    val t = json.optJSONObject("timer") ?: JSONObject()
    if (!t.has("id")) t.put("id", sanitize(timer.name).lowercase().replace(' ', '-'))
    t.put("name", timer.name).put("work", timer.work).put("rest", timer.rest).put("loop", timer.rounds)
    json.put("timer", t)
    val clips = JSONArray()
    timer.steps.forEach { s ->
      val c = runCatching { JSONObject(s.raw) }.getOrElse { JSONObject() }
      c.put("title", s.title).put("sets", s.sets)
      if (s.file != null) c.put("file", s.file) else c.remove("file")
      if (s.work != null) c.put("work", s.work) else c.remove("work")
      if (s.sides) c.put("sides", true) else c.remove("sides")
      clips.put(c)
    }
    json.put("clips", clips)
    timer.file.parentFile?.mkdirs()
    val tmp = File(timer.file.path + ".tmp")
    tmp.writeText(json.toString(2).replace("\\/", "/"))
    if (!tmp.renameTo(timer.file)) {
      timer.file.writeText(tmp.readText())
      tmp.delete()
    }
  }

  /** File for a new timer: `<root>/<name>/timer.json`, suffixed when taken. */
  fun newTimerFile(name: String, root: File = File(DEFAULT_ROOT)): File {
    val stem = sanitize(name)
    var dir = File(root, stem)
    var n = 2
    while (File(dir, "timer.json").exists()) dir = File(root, "${stem}_${n++}")
    return File(dir, "timer.json")
  }

  /** File for a new timer inside an existing clip folder: timer.json, else timer-<name>[-n].json. */
  fun timerFileIn(dir: File, name: String): File {
    File(dir, "timer.json").takeIf { !it.exists() }?.let { return it }
    val stem = "timer-" + sanitize(name).lowercase().replace(' ', '-')
    var f = File(dir, "$stem.json")
    var n = 2
    while (f.exists()) f = File(dir, "$stem-${n++}.json")
    return f
  }

  fun videosIn(dir: File?): List<String> =
    (dir?.listFiles { f -> f.isFile && isVideo(f.name) } ?: emptyArray()).map { it.name }.sortedBy { it.lowercase() }

  fun sanitize(name: String): String {
    val safe = name.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").trim()
    return if (safe.isEmpty() || safe == "." || safe == "..") "unnamed" else safe
  }

  /** Flattens a timer like timer-machine's step list: work per set (×2 sides), rest between. */
  fun phases(timer: Timer): List<Phase> {
    val out = mutableListOf<Phase>()
    repeat(timer.rounds) { round ->
      timer.steps.forEach { s ->
        val clip = s.file?.let { File(timer.dir, it).path }
        val sideNames = if (s.sides) listOf(" (L)", " (R)") else listOf("")
        for (set in 1..s.sets) for (side in sideNames) {
          val label = buildString {
            append(s.title)
            if (s.sets > 1) append(" · $set/${s.sets}")
            append(side)
            if (timer.rounds > 1) append(" · R${round + 1}")
          }
          out += Phase(label, s.work ?: timer.work, false, clip, s.title, set, s.sets, side.trim(' ', '(', ')'))
          if (timer.rest > 0) out += Phase("Rest", timer.rest, true, clip)
        }
      }
    }
    if (out.lastOrNull()?.isRest == true) out.removeAt(out.lastIndex)
    return out.filter { it.seconds > 0 }
  }

  fun totalSeconds(timer: Timer) = phases(timer).sumOf { it.seconds }
}
