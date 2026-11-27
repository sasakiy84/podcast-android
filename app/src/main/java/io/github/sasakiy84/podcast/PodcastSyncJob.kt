package io.github.sasakiy84.podcast

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Persisted, network-constrained work continues checking new episodes when the UI is closed. */
class PodcastSyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try {
                val repository = (application as PodcastApplication).repository
                val hours = repository.settings.refreshHours
                if (hours > 0) {
                    val interval = TimeUnit.HOURS.toMillis(hours.toLong())
                    repository.refreshIfDue(interval - flexMillis(interval))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: java.io.IOException) {
                // Try again at the next scheduled refresh.
            } catch (_: android.database.SQLException) {
                // Storage errors should also wait for the next scheduled refresh.
            } finally {
                if (coroutineContext[Job]?.isActive == true) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return false
    }

    override fun onDestroy(): Unit { scope.cancel(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 7301
        private fun flexMillis(interval: Long): Long = maxOf(TimeUnit.MINUTES.toMillis(5), interval / 20)

        fun schedule(context: Context, enabled: Boolean, hours: Int): Unit {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!enabled || hours == 0) { scheduler.cancel(JOB_ID); return }
            val interval = TimeUnit.HOURS.toMillis(hours.toLong())
            val flex = flexMillis(interval)
            val current = scheduler.getPendingJob(JOB_ID)
            if (current?.intervalMillis == interval && current.flexMillis == flex && current.isRequireBatteryNotLow) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, PodcastSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(interval, flex)
                .setPersisted(true)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) throw PodcastException("バックグラウンド更新を設定できませんでした。")
        }
    }
}
