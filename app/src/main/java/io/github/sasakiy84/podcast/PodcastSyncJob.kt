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
            var retry = false
            try {
                val repository = (application as PodcastApplication).repository
                repository.load()
                retry = repository.refresh().isNotEmpty()
            } catch (error: CancellationException) { throw error }
            catch (_: java.io.IOException) { retry = true }
            catch (_: android.database.SQLException) { retry = true }
            finally { if (coroutineContext[Job]?.isActive == true) jobFinished(params, retry) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy(): Unit { scope.cancel(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 7301
        fun schedule(context: Context, enabled: Boolean): Unit {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!enabled) { scheduler.cancel(JOB_ID); return }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, PodcastSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.HOURS.toMillis(1))
                .setPersisted(true)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) throw PodcastException("バックグラウンド更新を設定できませんでした。")
        }
    }
}
