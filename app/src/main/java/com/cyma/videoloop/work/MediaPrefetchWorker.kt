package com.cyma.videoloop.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cyma.videoloop.data.media.MediaCacheRepository
import com.cyma.videoloop.data.schedule.ScheduleRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class MediaPrefetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduleRepository: ScheduleRepository,
    private val mediaCacheRepository: MediaCacheRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val schedule = scheduleRepository.schedule().first()
        mediaCacheRepository.prefetchAll(schedule.items)
        mediaCacheRepository.evictOrphans(schedule.items)
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        const val WORK_NAME = "media_prefetch"
    }
}
