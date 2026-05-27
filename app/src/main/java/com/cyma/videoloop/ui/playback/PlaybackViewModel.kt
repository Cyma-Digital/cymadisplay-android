package com.cyma.videoloop.ui.playback

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyma.videoloop.data.media.MediaCacheRepository
import com.cyma.videoloop.data.media.MediaCacheRepository.MaterializeResult
import com.cyma.videoloop.data.schedule.ScheduleRepository
import com.cyma.videoloop.domain.model.PlaylistItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PlaybackUiState {
    data class Loading(val progress: Int = 0, val itemIndex: Int = 0, val total: Int = 1) : PlaybackUiState
    /** Display is paired but the schedule is empty — content hasn't been published yet. */
    object WaitingForContent : PlaybackUiState
    data class Ready(val items: List<ResolvedItem>) : PlaybackUiState
    data class Error(val message: String) : PlaybackUiState
}

sealed interface ResolvedItem {
    val id: String

    data class Video(override val id: String, val uri: Uri) : ResolvedItem
    data class Image(override val id: String, val uri: Uri, val durationSec: Int) : ResolvedItem
    data class Template(
        override val id: String,
        val indexFile: java.io.File,
        val templateId: String,
        val durationSec: Int,
    ) : ResolvedItem
}

private fun PlaylistItem.toResolved(file: java.io.File): ResolvedItem = when (this) {
    is PlaylistItem.Video -> ResolvedItem.Video(id, file.toUri())
    is PlaylistItem.Image -> ResolvedItem.Image(id, file.toUri(), durationSec)
    is PlaylistItem.Template -> ResolvedItem.Template(id, file, id, durationSec)
}

@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val scheduleRepository: ScheduleRepository,
    private val mediaCacheRepository: MediaCacheRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlaybackUiState>(PlaybackUiState.Loading())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()
    private var currentItems: List<PlaylistItem> = emptyList()

    init {
        // Foreground polling: sync, sleep pollIntervalSec, repeat. Each successful
        // sync only writes to DataStore when the schedule changed (see
        // ScheduleRepository.syncFromNetwork), so identical payloads don't trigger
        // a reload. When content does change, the flow below picks it up and
        // PlaybackEngine swaps items at the next natural item boundary.
        viewModelScope.launch {
            while (isActive) {
                scheduleRepository.syncFromNetwork()
                val pollSec = scheduleRepository.schedule().first().pollIntervalSec
                    .coerceAtLeast(MIN_POLL_INTERVAL_SEC)
                delay(pollSec.toLong() * 1000L)
            }
        }

        viewModelScope.launch {
            scheduleRepository.schedule()
                .map { it.items }
                .distinctUntilChanged()
                .collectLatest { items -> loadSchedule(items) }
        }
    }

    private suspend fun loadSchedule(items: List<PlaylistItem>) {
        currentItems = items
        if (items.isEmpty()) {
            _uiState.value = PlaybackUiState.WaitingForContent
            return
        }
        _uiState.value = PlaybackUiState.Loading(total = items.size)

        val resolved = mutableListOf<ResolvedItem>()
        items.forEachIndexed { index, item ->
            mediaCacheRepository.materialize(item).collect { result ->
                when (result) {
                    is MaterializeResult.Downloading ->
                        _uiState.value = PlaybackUiState.Loading(result.progress, index, items.size)
                    is MaterializeResult.Ready ->
                        resolved.add(item.toResolved(result.file))
                    is MaterializeResult.Error ->
                        Log.e(TAG, "Skipping ${item.id} — download failed: ${result.message}")
                }
            }
        }

        resolved.sortBy { item ->
            when (item) {
                is ResolvedItem.Image -> 0
                is ResolvedItem.Template -> 1
                is ResolvedItem.Video -> 2
            }
        }
        _uiState.value = if (resolved.isNotEmpty()) PlaybackUiState.Ready(resolved)
                         else PlaybackUiState.Error("No playable items")
    }

    fun onPlaybackError(resolvedItem: ResolvedItem) {
        val item = currentItems.find { it.id == resolvedItem.id } ?: return
        mediaCacheRepository.evict(item)

        val currentState = _uiState.value
        if (currentState is PlaybackUiState.Ready) {
            val updated = currentState.items.filter { it.id != resolvedItem.id }
            _uiState.value = if (updated.isNotEmpty()) PlaybackUiState.Ready(updated)
                             else PlaybackUiState.Error("All media items failed")
        }
    }

    private companion object {
        const val TAG = "PlaybackViewModel"
        const val MIN_POLL_INTERVAL_SEC = 60
    }
}
