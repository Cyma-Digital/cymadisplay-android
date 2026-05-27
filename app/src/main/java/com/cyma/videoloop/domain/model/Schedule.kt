package com.cyma.videoloop.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Orientation {
    @SerialName("horizontal") HORIZONTAL,
    @SerialName("vertical") VERTICAL,
    @SerialName("horizontal_inverted") HORIZONTAL_INVERTED,
    @SerialName("vertical_inverted") VERTICAL_INVERTED;

    companion object {
        fun fromWire(value: String?): Orientation = when (value?.lowercase()) {
            "vertical" -> VERTICAL
            "horizontal_inverted" -> HORIZONTAL_INVERTED
            "vertical_inverted" -> VERTICAL_INVERTED
            else -> HORIZONTAL
        }
    }
}

@Serializable
data class Schedule(
    val id: String,
    val items: List<PlaylistItem>,
    val pollIntervalSec: Int = 60,
    val orientation: Orientation = Orientation.HORIZONTAL,
)

@Serializable
data class ActiveWindow(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
)
