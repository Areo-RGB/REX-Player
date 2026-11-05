package xyz.mpv.rex.database.repository

import android.content.Context
import android.net.Uri
import xyz.mpv.rex.database.dao.PlaylistDao
import xyz.mpv.rex.database.entities.PlaylistEntity
import xyz.mpv.rex.database.entities.PlaylistItemEntity
import xyz.mpv.rex.utils.media.M3UParser
import xyz.mpv.rex.utils.media.M3UParseResult
import xyz.mpv.rex.utils.media.M3UPlaylistItem
import kotlinx.coroutines.flow.Flow
import java.io.File

class PlaylistRepository(
  private val playlistDao: PlaylistDao,
  private val appContext: Context? = null,
) {
  // Playlist operations
  suspend fun createPlaylist(name: String): Long {
    val now = System.currentTimeMillis()
    return playlistDao.insertPlaylist(
      PlaylistEntity(
        name = name,
        createdAt = now,
        updatedAt = now,
      ),
    )
  }

  suspend fun updatePlaylist(playlist: PlaylistEntity) {
    playlistDao.updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
  }

  suspend fun deletePlaylist(playlist: PlaylistEntity) {
    playlistDao.deletePlaylist(playlist)
  }

  fun observeAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observeAllPlaylists()

  suspend fun getAllPlaylists(): List<PlaylistEntity> = playlistDao.getAllPlaylists()

  suspend fun getPlaylistById(playlistId: Int): PlaylistEntity? = playlistDao.getPlaylistById(playlistId)

  fun observePlaylistById(playlistId: Int): Flow<PlaylistEntity?> = playlistDao.observePlaylistById(playlistId)

  // Playlist item operations
  suspend fun addItemToPlaylist(playlistId: Int, filePath: String, fileName: String) {
    val maxPosition = playlistDao.getMaxPosition(playlistId) ?: -1
    playlistDao.insertPlaylistItem(
      PlaylistItemEntity(
        playlistId = playlistId,
        filePath = filePath,
        fileName = fileName,
        position = maxPosition + 1,
        addedAt = System.currentTimeMillis(),
      ),
    )
    // Update playlist's updatedAt timestamp
    getPlaylistById(playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  suspend fun addItemsToPlaylist(playlistId: Int, items: List<Pair<String, String>>) {
    val maxPosition = playlistDao.getMaxPosition(playlistId) ?: -1
    val now = System.currentTimeMillis()
    val playlistItems = items.mapIndexed { index, (filePath, fileName) ->
      PlaylistItemEntity(
        playlistId = playlistId,
        filePath = filePath,
        fileName = fileName,
        position = maxPosition + 1 + index,
        addedAt = now,
      )
    }
    playlistDao.insertPlaylistItems(playlistItems)
    // Update playlist's updatedAt timestamp
    getPlaylistById(playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  suspend fun removeItemFromPlaylist(item: PlaylistItemEntity) {
    playlistDao.deletePlaylistItem(item)
    // Update playlist's updatedAt timestamp
    getPlaylistById(item.playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  suspend fun removeItemsFromPlaylist(items: List<PlaylistItemEntity>) {
    if (items.isEmpty()) return
    // Use batch delete for better performance and to avoid race conditions
    playlistDao.deletePlaylistItems(items)
    // Update playlist's updatedAt timestamp
    getPlaylistById(items.first().playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  suspend fun removeItemById(itemId: Int) {
    playlistDao.deletePlaylistItemById(itemId)
  }

  suspend fun clearPlaylist(playlistId: Int) {
    playlistDao.deleteAllItemsFromPlaylist(playlistId)
    getPlaylistById(playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  fun observePlaylistItems(playlistId: Int): Flow<List<PlaylistItemEntity>> =
    playlistDao.observePlaylistItems(playlistId)

  suspend fun getPlaylistItems(playlistId: Int): List<PlaylistItemEntity> =
    playlistDao.getPlaylistItems(playlistId)

  fun observePlaylistItemCount(playlistId: Int): Flow<Int> =
    playlistDao.observePlaylistItemCount(playlistId)

  suspend fun getPlaylistItemCount(playlistId: Int): Int =
    playlistDao.getPlaylistItemCount(playlistId)

  suspend fun getFirstPlaylistItemPath(playlistId: Int): String? =
    playlistDao.getFirstPlaylistItemPath(playlistId)

  suspend fun setPlaylistCustomThumbnail(playlistId: Int, path: String?) {
    playlistDao.updateCustomThumbnailPath(playlistId, path)
    getPlaylistById(playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  suspend fun getEffectiveThumbnailPath(playlistId: Int): String? =
    playlistDao.getEffectiveThumbnailPath(playlistId)

  suspend fun reorderPlaylistItems(playlistId: Int, newOrder: List<Int>) {
    playlistDao.reorderPlaylistItems(playlistId, newOrder)
    getPlaylistById(playlistId)?.let { playlist ->
      updatePlaylist(playlist)
    }
  }

  // Helper to get playlist items as URIs for playback
  suspend fun getPlaylistItemsAsUris(playlistId: Int): List<Uri> {
    return getPlaylistItems(playlistId).map { item ->
      if (item.filePath.startsWith("/") || item.filePath.startsWith("file://")) {
        val path = if (item.filePath.startsWith("file://")) item.filePath.removePrefix("file://") else item.filePath
        Uri.fromFile(java.io.File(path))
      } else {
        Uri.parse(item.filePath)
      }
    }
  }

  /**
   * Get a windowed subset of playlist items as URIs to avoid loading huge playlists at once.
   * This prevents ANR issues and TransactionTooLargeException with large M3U playlists.
   * 
   * @param playlistId The playlist ID
   * @param centerIndex The index to center the window around (typically current playing position)
   * @param windowSize Total number of items to load (default 100)
   * @return List of URIs in the windowed range
   */
  suspend fun getPlaylistItemsWindowAsUris(
    playlistId: Int, 
    centerIndex: Int = 0, 
    windowSize: Int = 100
  ): List<Uri> {
    val totalCount = getPlaylistItemCount(playlistId)
    if (totalCount == 0) return emptyList()
    
    // If playlist is small enough, return all items
    if (totalCount <= windowSize) {
      return getPlaylistItemsAsUris(playlistId)
    }
    
    // Calculate window boundaries
    val halfWindow = windowSize / 2
    val startPosition = (centerIndex - halfWindow).coerceAtLeast(0)
    val endPosition = (startPosition + windowSize).coerceAtMost(totalCount)
    
    // Get items in range
    return playlistDao.getPlaylistItemsInRange(playlistId, startPosition, endPosition)
      .map { item ->
        if (item.filePath.startsWith("/") || item.filePath.startsWith("file://")) {
          val path = if (item.filePath.startsWith("file://")) item.filePath.removePrefix("file://") else item.filePath
          Uri.fromFile(java.io.File(path))
        } else {
          Uri.parse(item.filePath)
        }
      }
  }

  // Play history operations
  suspend fun updatePlayHistory(playlistId: Int, filePath: String, position: Long = 0) {
    playlistDao.updatePlayHistory(playlistId, filePath, System.currentTimeMillis(), position)
  }

  suspend fun getRecentlyPlayedInPlaylist(playlistId: Int, limit: Int = 20): List<PlaylistItemEntity> {
    return playlistDao.getRecentlyPlayedInPlaylist(playlistId, limit)
  }

  fun observeRecentlyPlayedInPlaylist(playlistId: Int, limit: Int = 20): Flow<List<PlaylistItemEntity>> {
    return playlistDao.observeRecentlyPlayedInPlaylist(playlistId, limit)
  }

  suspend fun getPlaylistItemByPath(playlistId: Int, filePath: String): PlaylistItemEntity? {
    return playlistDao.getPlaylistItemByPath(playlistId, filePath)
  }

  // M3U Playlist operations
  suspend fun createM3UPlaylist(url: String): Result<Long> {
    return try {
      val parseResult = M3UParser.parseFromUrl(url)
      
      when (parseResult) {
        is M3UParseResult.Success -> {
          val now = System.currentTimeMillis()
          val playlistId = playlistDao.insertPlaylist(
            PlaylistEntity(
              name = parseResult.playlistName,
              createdAt = now,
              updatedAt = now,
              m3uSourceUrl = url,
              isM3uPlaylist = true
            )
          )
          
          // Add all items from the M3U playlist
          val items = parseResult.items.mapIndexed { index, m3uItem ->
            PlaylistItemEntity(
              playlistId = playlistId.toInt(),
              filePath = m3uItem.url,
              fileName = m3uItem.title ?: "Item ${index + 1}",
              position = index,
              addedAt = now
            )
          }
          
          playlistDao.insertPlaylistItems(items)
          
          Result.success(playlistId)
        }
        is M3UParseResult.Error -> {
          Result.failure(Exception(parseResult.message, parseResult.exception))
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /** What an M3U sync did with one file. */
  enum class M3UAction { IMPORTED, REFRESHED, UP_TO_DATE }

  /**
   * Imports a local M3U/M3U8, or refreshes the playlist already bound to it when the file is newer
   * than the last import. This is how playlists written outside REX (the timer-clips Lua script)
   * appear without a manual import.
   */
  suspend fun importOrRefreshM3U(context: Context, uri: Uri): M3UAction {
    val source = uri.toString()
    val existing = playlistDao.getPlaylistBySourceUrl(source)
    if (existing == null) {
      createM3UPlaylistFromFile(context, uri, source).getOrThrow()
      return M3UAction.IMPORTED
    }
    val modified = fileModified(context, uri)
    if (modified > 0 && existing.updatedAt >= modified) return M3UAction.UP_TO_DATE
    refreshM3UPlaylist(existing.id).getOrThrow()
    return M3UAction.REFRESHED
  }

  /** Playlist title from the file name ("timer-clips--FIFA 11+.m3u8" -> "FIFA 11+"). */
  private fun displayName(uri: Uri): String =
    (uri.lastPathSegment ?: "playlist")
      .substringAfterLast('/')
      .substringBeforeLast('.')
      .removePrefix("timer-clips--")
      .replace('_', ' ')
      .replace('-', ' ')
      .trim()
      .ifEmpty { "M3U Playlist" }

  private fun fileModified(context: Context, uri: Uri): Long = runCatching {
    if (uri.scheme == "file") {
      val path = uri.path ?: return@runCatching 0L
      File(path).lastModified()
    } else {
      context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
      } ?: 0L
    }
  }.getOrDefault(0L)

  suspend fun createM3UPlaylistFromFile(
    context: Context,
    uri: Uri,
    sourceUrl: String? = null,
  ): Result<Long> {
    return try {
      val parseResult = M3UParser.parseFromUri(context, uri)
      
      when (parseResult) {
        is M3UParseResult.Success -> {
          val now = System.currentTimeMillis()
          val playlistId = playlistDao.insertPlaylist(
            PlaylistEntity(
              name = displayName(uri),
              createdAt = now,
              updatedAt = now,
              m3uSourceUrl = sourceUrl ?: uri.toString(), // kept so the file can be re-read later
              isM3uPlaylist = true
            )
          )
          
          // Add all items from the M3U playlist
          val items = parseResult.items.mapIndexed { index, m3uItem ->
            PlaylistItemEntity(
              playlistId = playlistId.toInt(),
              filePath = m3uItem.url,
              fileName = m3uItem.title ?: "Item ${index + 1}",
              position = index,
              addedAt = now
            )
          }
          
          playlistDao.insertPlaylistItems(items)
          
          Result.success(playlistId)
        }
        is M3UParseResult.Error -> {
          Result.failure(Exception(parseResult.message, parseResult.exception))
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun refreshM3UPlaylist(playlistId: Int): Result<Unit> {
    return try {
      val playlist = getPlaylistById(playlistId)
        ?: return Result.failure(Exception("Playlist not found"))
      
      if (!playlist.isM3uPlaylist || playlist.m3uSourceUrl == null) {
        return Result.failure(Exception("Not an M3U playlist or no source URL available"))
      }

      val source = playlist.m3uSourceUrl
      val parseResult = if (source.startsWith("http://") || source.startsWith("https://")) {
        M3UParser.parseFromUrl(source)
      } else {
        val context = appContext ?: return Result.failure(Exception("No context for a local playlist"))
        M3UParser.parseFromUri(context, Uri.parse(source))
      }
      
      when (parseResult) {
        is M3UParseResult.Success -> {
          // Clear existing items
          playlistDao.deleteAllItemsFromPlaylist(playlistId)
          
          // Add refreshed items
          val now = System.currentTimeMillis()
          val items = parseResult.items.mapIndexed { index, m3uItem ->
            PlaylistItemEntity(
              playlistId = playlistId,
              filePath = m3uItem.url,
              fileName = m3uItem.title ?: "Item ${index + 1}",
              position = index,
              addedAt = now
            )
          }
          
          playlistDao.insertPlaylistItems(items)
          updatePlaylist(playlist.copy(updatedAt = now))
          
          Result.success(Unit)
        }
        is M3UParseResult.Error -> {
          Result.failure(Exception(parseResult.message, parseResult.exception))
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}
