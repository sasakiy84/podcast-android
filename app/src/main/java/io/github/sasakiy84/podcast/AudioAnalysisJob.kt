package io.github.sasakiy84.podcast

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Battery-aware local work; survives the UI closing and never needs network access. */
class AudioAnalysisJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work =
            scope.launch {
                var retry = false
                try {
                    retry = (application as PodcastApplication).repository.analyzeDownloadedAudio()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: java.io.IOException) {
                    retry = true
                } catch (_: SecurityException) {
                    retry = true
                } catch (_: android.database.SQLException) {
                    retry = true
                } finally {
                    if (coroutineContext[Job]?.isActive == true) jobFinished(params, retry)
                }
            }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val JOB_ID = 7302

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (
                !PlaybackPreferences(context.getSharedPreferences("playback", Context.MODE_PRIVATE))
                    .normalizeVolume
            ) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, AudioAnalysisJob::class.java))
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .setBackoffCriteria(
                        TimeUnit.MINUTES.toMillis(1),
                        JobInfo.BACKOFF_POLICY_EXPONENTIAL,
                    )
                    .build()
            )
        }
    }
}

/** The broadcast only schedules a scan of our own files; no intent-supplied path is trusted. */
class DownloadCompletionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            AudioAnalysisJob.schedule(context)
    }
}
