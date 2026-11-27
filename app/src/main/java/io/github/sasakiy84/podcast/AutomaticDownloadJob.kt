package io.github.sasakiy84.podcast

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Hands saved reservations to DownloadManager once the device permits automatic saving. */
class AutomaticDownloadJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            var blocked = false
            try {
                blocked = (application as PodcastApplication).repository.processAutomaticDownloads()
            } catch (_: android.database.SQLException) {
                // Leave the reservations available at the next feed refresh or app start.
            } catch (_: PodcastException) {
                // Scheduling can be unavailable while the system is shutting down.
            } finally {
                if (isActive) jobFinished(params, blocked)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy(): Unit {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 7303

        @Suppress("DEPRECATION") // The system keeps this broadcast sticky while storage is low.
        internal fun waitingReason(context: Context, settings: PlaybackPreferences): String? {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return when {
                settings.autoDownloadChargingOnly &&
                    !context.getSystemService(BatteryManager::class.java).isCharging -> "充電待ち"
                settings.autoDownloadRequireStorage &&
                    context.registerReceiver(null, IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW)) != null ->
                    "空き容量待ち"
                // Start a charger-constrained transfer only if it can also use the current network.
                settings.autoDownloadChargingOnly && connectivity.activeNetwork == null -> "ネットワーク接続待ち"
                settings.autoDownloadChargingOnly && settings.wifiOnly && connectivity.isActiveNetworkMetered ->
                    "Wi-Fi 接続待ち"
                else -> null
            }
        }

        internal fun schedule(context: Context, settings: PlaybackPreferences, blocked: Boolean): Unit {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val current = scheduler.getPendingJob(JOB_ID)
            if (!blocked) {
                if (current != null) scheduler.cancel(JOB_ID)
                return
            }
            val network = if (settings.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
            if (current?.isRequireCharging == settings.autoDownloadChargingOnly &&
                current.isRequireStorageNotLow == settings.autoDownloadRequireStorage &&
                current.requiredNetwork?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == settings.wifiOnly) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutomaticDownloadJob::class.java))
                .setRequiresCharging(settings.autoDownloadChargingOnly)
                .setRequiresStorageNotLow(settings.autoDownloadRequireStorage)
                .setRequiredNetworkType(network)
                .setPersisted(true)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS)
                throw PodcastException("自動ダウンロードの待機を設定できませんでした")
        }
    }
}
