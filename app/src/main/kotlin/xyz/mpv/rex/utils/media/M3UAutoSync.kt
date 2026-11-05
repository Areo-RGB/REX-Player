package xyz.mpv.rex.utils.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.mpv.rex.database.repository.PlaylistRepository
import java.io.File

/**
 * Imports and refreshes the M3U/M3U8 playlists other tools drop on the device.
 *
 * mpv's Lua API cannot create REX playlists (they live in Room), so a Lua script writes playlist
 * files and this scan turns them into playlists: new files are imported, files that changed are
 * refreshed. Both happen on app start and whenever the Playlists screen loads.
 */
class M3UAutoSync(
  private val context: Context,
  private val repository: PlaylistRepository,
) {
  data class Summary(val imported: Int, val refreshed: Int, val unchanged: Int) {
    val changed: Int get() = imported + refreshed
  }

  companion object {
    private const val TAG = "M3UAutoSync"

    /** Folders scanned for playlists; every *.m3u8 up to two levels below them is considered. */
    val ROOTS = listOf(
      "/storage/emulated/0/Download/timer-clips",
      "/storage/emulated/0/Documents/Lua-scripts",
    )
  }

  suspend fun sync(): Summary = withContext(Dispatchers.IO) {
    var imported = 0
    var refreshed = 0
    var unchanged = 0
    if (!Environment.isExternalStorageManager()) {
      Log.d(TAG, "No all-files access: playlist scan skipped")
      return@withContext Summary(0, 0, 0)
    }
    for (root in ROOTS) {
      val dir = File(root)
      if (!dir.isDirectory) continue
      val files = runCatching {
        dir.walkTopDown().maxDepth(2)
          .filter { it.isFile && it.name.endsWith(".m3u8", ignoreCase = true) }
          .toList()
      }.getOrElse { emptyList() }
      for (file in files) {
        runCatching { repository.importOrRefreshM3U(context, Uri.fromFile(file)) }
          .onSuccess { action ->
            when (action) {
              PlaylistRepository.M3UAction.IMPORTED -> imported++
              PlaylistRepository.M3UAction.REFRESHED -> refreshed++
              PlaylistRepository.M3UAction.UP_TO_DATE -> unchanged++
            }
          }
          .onFailure { Log.w(TAG, "Playlist " + file.name + ": " + it.message) }
      }
    }
    if (imported + refreshed > 0) {
      Log.i(TAG, "Playlist sync: " + imported + " imported, " + refreshed + " refreshed")
    }
    Summary(imported, refreshed, unchanged)
  }
}
