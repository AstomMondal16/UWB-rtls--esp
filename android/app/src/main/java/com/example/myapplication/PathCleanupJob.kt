package com.example.myapplication

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Cleanup can run without opening the dashboard; no network or new library is needed. */
class PathCleanupJob : JobService() {
    private val worker = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        task = worker.submit {
            val retry = runCatching {
                PathSessionStore.deleteExpiredFiles(File(filesDir, "paths"), System.currentTimeMillis())
            }.isFailure
            if (!Thread.currentThread().isInterrupted) jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel(true)
        return true
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 0x555742

        internal fun schedule(context: Context): String? = try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) null
            else {
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, PathCleanupJob::class.java))
                    .setPeriodic(TimeUnit.DAYS.toMillis(1)).setPersisted(true).build()
                if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) null
                else "Background path cleanup could not be scheduled; cleanup still runs when the app is open."
            }
        } catch (_: Exception) {
            "Background path cleanup could not be scheduled; cleanup still runs when the app is open."
        }
    }
}
