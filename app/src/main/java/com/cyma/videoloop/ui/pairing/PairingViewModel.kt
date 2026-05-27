package com.cyma.videoloop.ui.pairing

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyma.videoloop.data.api.CymaApi
import com.cyma.videoloop.data.api.PairingRequestDto
import com.cyma.videoloop.data.identity.DeviceIdentityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

private const val POLL_INTERVAL_MS = 8_000L
private const val POLL_TIMEOUT_MS = 5 * 60 * 1000L
private const val REGISTER_MAX_RETRIES = 3
private const val REGISTER_RETRY_DELAY_MS = 2_000L

enum class PairingStatus { Idle, Polling, Paired, TimedOut, Error }

data class PairingUiState(
    val deviceId: String = "",
    val pairingCode: String = "",
    val status: PairingStatus = PairingStatus.Idle,
    val elapsedSec: Int = 0,
    val errorMessage: String? = null,
)

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val identity: DeviceIdentityRepository,
    private val api: CymaApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            val deviceId = identity.getOrCreateDeviceId()
            val pairingCode = identity.getOrCreatePairingCode()
            _uiState.value = PairingUiState(deviceId = deviceId, pairingCode = pairingCode)
            startPolling(deviceId, pairingCode)
        }
    }

    fun retryPolling() {
        val state = _uiState.value
        if (state.deviceId.isNotEmpty()) startPolling(state.deviceId, state.pairingCode)
    }

    private fun startPolling(deviceId: String, pairingCode: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(status = PairingStatus.Polling, elapsedSec = 0)

            val request = PairingRequestDto(dsCodigo = deviceId, codigoPareamento = pairingCode)

            var registered = false
            var lastError: Throwable? = null
            repeat(REGISTER_MAX_RETRIES) { attempt ->
                val result = runCatching { api.registerPairingCode(request) }
                if (result.isSuccess) {
                    registered = true
                    return@repeat
                }
                lastError = result.exceptionOrNull()
                Log.w(TAG, "registerPairingCode attempt ${attempt + 1} failed", lastError)
                if (attempt < REGISTER_MAX_RETRIES - 1) delay(REGISTER_RETRY_DELAY_MS)
            }
            if (!registered) {
                Log.e(TAG, "All registerPairingCode attempts failed", lastError)
                val detail = lastError?.let { "${it::class.java.simpleName}: ${it.message}" } ?: "unknown"
                _uiState.value = _uiState.value.copy(
                    status = PairingStatus.Error,
                    errorMessage = "Failed to register pairing code.\n$detail",
                )
                return@launch
            }

            // 2xx = paired, 4xx = still waiting (see CymaApi.getDisplayData).
            val startMs = System.currentTimeMillis()
            while (System.currentTimeMillis() - startMs < POLL_TIMEOUT_MS) {
                val elapsed = ((System.currentTimeMillis() - startMs) / 1000).toInt()
                _uiState.value = _uiState.value.copy(elapsedSec = elapsed)

                try {
                    api.getDisplayData(deviceId)
                    identity.setPaired(true)
                    _uiState.value = _uiState.value.copy(status = PairingStatus.Paired)
                    return@launch
                } catch (e: HttpException) {
                    if (e.code() !in 400..499) {
                        Log.w(TAG, "Unexpected HTTP ${e.code()} while polling pairing state", e)
                    }
                    // 4xx: still not paired — keep polling.
                } catch (e: Exception) {
                    Log.w(TAG, "Network error while polling pairing state", e)
                }

                delay(POLL_INTERVAL_MS)
            }

            _uiState.value = _uiState.value.copy(status = PairingStatus.TimedOut)
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
    }

    private companion object { const val TAG = "PairingViewModel" }
}
