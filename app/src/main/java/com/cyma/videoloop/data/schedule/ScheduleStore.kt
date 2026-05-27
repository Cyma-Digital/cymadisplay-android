package com.cyma.videoloop.data.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cyma.videoloop.domain.model.Schedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    companion object {
        private val KEY_SCHEDULE = stringPreferencesKey("schedule")

        /** Empty until the first successful network sync — drives the WaitingForContent state. */
        val DEFAULT_SCHEDULE = Schedule(
            id = "default",
            items = emptyList(),
            pollIntervalSec = 60,
        )
    }

    fun schedule(): Flow<Schedule> = dataStore.data.map { prefs ->
        prefs[KEY_SCHEDULE]
            ?.runCatching { json.decodeFromString<Schedule>(this) }
            ?.getOrNull()
            ?: DEFAULT_SCHEDULE
    }

    suspend fun save(schedule: Schedule) {
        dataStore.edit { it[KEY_SCHEDULE] = json.encodeToString(Schedule.serializer(), schedule) }
    }
}
