package com.cyma.videoloop.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
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
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        const val WORK_NAME = "schedule_sync"
    }
}
