package com.cyma.videoloop.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cyma.videoloop.data.schedule.ScheduleRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ScheduleSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduleRepository: ScheduleRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return scheduleRepository.syncFromNetwork()
            .fold(
                onSuccess = {
                    enqueueMediaPrefetch()
                    Result.success()
                },
                onFailure = { Result.retry() },
            )
    }

    // Reconciles the on-disk media/template cache against whatever schedule
    // just landed — only worth running once we know the schedule is current.
    // KEEP (not REPLACE): a prefetch/evict cycle already in flight (e.g. a
    // large video download) shouldn't be cancelled; the next sync tick retries.
    private fun enqueueMediaPrefetch() {
        val request = OneTimeWorkRequestBuilder<MediaPrefetchWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            MediaPrefetchWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val WORK_NAME = "schedule_sync"
    }
}
