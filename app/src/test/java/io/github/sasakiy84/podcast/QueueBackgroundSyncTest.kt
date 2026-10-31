package io.github.sasakiy84.podcast

import android.net.NetworkCapabilities
import android.app.job.JobScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueueBackgroundSyncTest {
    @Test fun backgroundChecksArePersistedNetworkConstrainedAndCanBeCancelled(): Unit {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        PodcastSyncJob.schedule(context, true)
        PodcastSyncJob.schedule(context, true)
        val job = scheduler.allPendingJobs.single()
        assertTrue(job.isPersisted)
        assertTrue(job.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
        assertEquals(TimeUnit.HOURS.toMillis(1), job.intervalMillis)
        assertEquals(PodcastSyncJob::class.java.name, job.service.className)
        PodcastSyncJob.schedule(context, false)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
}
