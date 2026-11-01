package io.github.sasakiy84.podcast

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** One grouped update per show. Initial subscription/backfill never floods notifications. */
class PodcastNotifications(private val context: Context) {
    fun newEpisodes(podcast: Podcast, episodes: List<Episode>): Unit {
        if (episodes.isEmpty() || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("new_episodes", "新着エピソード", NotificationManager.IMPORTANCE_DEFAULT))
        val id = podcast.feedUrl.hashCode()
        val intent = Intent(context, MainActivity::class.java).putExtra("feed_url", podcast.feedUrl).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "new_episodes").setSmallIcon(R.drawable.ic_podcast)
            .setContentTitle(podcast.title).setContentText(episodes.first().title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(episodes.take(5).joinToString("\n") { it.title }))
            .setContentIntent(pending).setAutoCancel(true).build()
        try { manager.notify(id, notification) } catch (_: SecurityException) { /* Permission can be revoked between checking and posting. */ }
    }
}
