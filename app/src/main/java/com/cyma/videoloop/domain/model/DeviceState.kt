package com.cyma.videoloop.domain.model

sealed interface DeviceState {
    object Unpaired : DeviceState
    data class Paired(val deviceId: String, val authToken: String) : DeviceState
}
