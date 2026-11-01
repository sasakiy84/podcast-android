package io.github.sasakiy84.podcast

import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal fun synchronizePlaybackQueue(player: Player, repository: PodcastRepository, library: Library): Unit {
    repository.preferences.getString("active_queue", null)?.let { active -> synchronizeQueueContents(player, repository, library, active) }
    // Downloads can finish after playback started. Future items must use the local file.
    for (index in 0 until player.mediaItemCount) {
        if (index == player.currentMediaItemIndex && player.isPlaying) continue
        val old = player.getMediaItemAt(index)
        val updated = library.episodes.firstOrNull { it.id == old.mediaId }?.mediaItem(repository, applyStartSkip = old.mediaMetadata.extras?.getBoolean("apply_start_skip", true) != false) ?: continue
        if (old.localConfiguration?.uri != updated.localConfiguration?.uri) {
            val isCurrent = index == player.currentMediaItemIndex
            val position = player.currentPosition.coerceAtLeast(0)
            val prepared = player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
            player.replaceMediaItem(index, updated)
            if (isCurrent) { player.seekTo(index, position); if (prepared) player.prepare() }
        }
    }
}

private fun synchronizeQueueContents(player: Player, repository: PodcastRepository, library: Library, active: String): Unit {
    val queue = library.queues.firstOrNull { it.id == active }
    val current = player.currentMediaItem?.mediaId
    // Retain the stopped final item and its previous history. Rebuilding only the
    // prefix here would replay already completed episodes after the queue ends.
    if (current != null && library.progress[current]?.completed == true && !player.isPlaying) {
        if (queue?.episodeIds.orEmpty().none { library.progress[it]?.completed != true }) repository.preferences.edit { remove("active_queue") }
        return
    }
    val past = (0 until player.currentMediaItemIndex.coerceAtLeast(0)).map { player.getMediaItemAt(it).mediaId }.filter { id -> library.episodes.any { it.id == id } }.takeLast(50)
    val desired = (past + queuePlaybackIds(queue, current, library)).distinct()
    if (queue == null || desired.isEmpty()) repository.preferences.edit { remove("active_queue") }
    val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
    if (current != null && current in existing && current in desired) {
        // Keep the current audio buffer while removing finished items or changing order.
        for (index in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(index).mediaId !in desired) player.removeMediaItem(index)
        desired.forEachIndexed { target, id ->
            val from = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id }
            if (from == null) library.episodes.firstOrNull { it.id == id }?.let { player.addMediaItem(target, it.mediaItem(repository)) }
            else if (from != target) player.moveMediaItem(from, target)
        }
    } else if (existing != desired) {
        val position = player.currentPosition.coerceAtLeast(0)
        val playing = player.playWhenReady
        val index = desired.indexOf(current).takeIf { it >= 0 } ?: 0
        val start = if (desired.getOrNull(index) == current) position else desired.getOrNull(index)?.let { library.progress[it]?.positionMs } ?: 0
        player.setMediaItems(desired.mapNotNull { id -> library.episodes.firstOrNull { it.id == id }?.mediaItem(repository) }, index, start)
        if (desired.isNotEmpty()) { player.prepare(); player.playWhenReady = playing }
    }
}

fun queuePlaybackIds(queue: PlaybackQueue?, currentId: String?, library: Library): List<String> {
    val valid = library.episodes.map { it.id }.toSet()
    val ids = queue?.episodeIds.orEmpty().filter { it in valid && library.progress[it]?.completed != true }
    return if (currentId != null && currentId in valid && library.progress[currentId]?.completed != true && currentId !in ids) listOf(currentId) + ids else ids
}

fun Episode.mediaItem(repository: PodcastRepository, applyStartSkip: Boolean = true): MediaItem {
    val podcast = repository.library.value.podcasts.firstOrNull { it.feedUrl == feedUrl } ?: repository.store.podcast(feedUrl)
    return MediaItem.Builder().setMediaId(id).setUri(repository.audioUri(this))
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist(podcast?.title)
            .setExtras(android.os.Bundle().apply { putString("feed_url", feedUrl); putBoolean("apply_start_skip", applyStartSkip) })
            .setArtworkUri((artwork.ifBlank { podcast?.artwork.orEmpty() }).takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)).build())
        .build()
}
