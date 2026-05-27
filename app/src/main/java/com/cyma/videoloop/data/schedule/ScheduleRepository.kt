package com.cyma.videoloop.data.schedule

import com.cyma.videoloop.data.api.CampoDto
import com.cyma.videoloop.data.api.CymaApi
import com.cyma.videoloop.data.api.DisplayDataDto
import com.cyma.videoloop.data.api.TemplateDto
import com.cyma.videoloop.data.identity.DeviceIdentityRepository
import com.cyma.videoloop.data.template.TemplateAssetExtractor
import com.cyma.videoloop.domain.model.Orientation
import com.cyma.videoloop.domain.model.PlaylistItem
import com.cyma.videoloop.domain.model.Schedule
import com.cyma.videoloop.util.sha256
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CDN base URL for media downloads. The API may return paths like
 * "midias/1746599680605-demo.mp4" or just "1746599680605-demo.mp4" — only the
 * filename matters; any directory prefix is stripped before joining.
 */
private const val MEDIA_BASE_URL = "https://media.cymadisplay.com/"
/** Default duration for image items (the API doesn't return a per-image duration). */
private const val DEFAULT_IMAGE_DURATION_SEC = 10
/** Default duration for template items when [TemplateDto.duracao] is null. */
private const val DEFAULT_TEMPLATE_DURATION_SEC = 10

private val CONTEUDO_SERIALIZER = MapSerializer(
    String.serializer(),
    ListSerializer(MapSerializer(String.serializer(), JsonElement.serializer())),
)
private val CAMPOS_SERIALIZER = ListSerializer(CampoDto.serializer())

@Singleton
class ScheduleRepository @Inject constructor(
    private val api: CymaApi,
    private val store: ScheduleStore,
    private val identity: DeviceIdentityRepository,
    private val templateAssetExtractor: TemplateAssetExtractor,
    private val json: Json,
) {
    fun schedule(): Flow<Schedule> = store.schedule()

    suspend fun syncFromNetwork(): Result<Schedule> = runCatching {
        val deviceId = identity.getOrCreateDeviceId()
        val dto = try {
            api.getDisplayData(deviceId)
        } catch (e: HttpException) {
            // 4xx = backend no longer recognises this device (e.g. unpaired
            // from the admin panel, or never paired). Flip local state so the
            // UI routes back to the pairing screen.
            if (e.code() in 400..499) identity.setPaired(false)
            throw e
        }
        identity.setPaired(true)
        val schedule = dto.toDomain()
        val existing = store.schedule().first()
        if (existing != schedule) store.save(schedule)
        schedule
    }

    /**
     * Asks the schedule endpoint whether this device is paired.
     *
     * Returns:
     *  - `true`  → 2xx from the backend (device is recognised and paired)
     *  - `false` → 4xx (auth/not-found — device is not paired in the backend)
     *  - `null`  → server unreachable (network error or 5xx). Caller should
     *    fall back to cached state ([DeviceIdentityRepository.isLocallyPaired]).
     */
    suspend fun isPaired(): Boolean? = try {
        val deviceId = identity.getOrCreateDeviceId()
        api.getDisplayData(deviceId)
        true
    } catch (e: HttpException) {
        if (e.code() in 400..499) false else null
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        null
    }

    // ── DTO → domain mapping ────────────────────────────────────────────────

    private fun DisplayDataDto.toDomain(): Schedule {
        android.util.Log.i(TAG, "wire orientation='${orientation}' -> ${Orientation.fromWire(orientation)}")
        val items = buildList {
            midias.forEachIndexed { i, path ->
                val name = path.fileName()
                add(PlaylistItem.Video(
                    id = "video-$i-$name",
                    sourceUrl = MEDIA_BASE_URL + name,
                ))
            }
            imagens.forEachIndexed { i, img ->
                val name = img.dsUrl.fileName()
                add(PlaylistItem.Image(
                    id = "image-$i-$name",
                    sourceUrl = MEDIA_BASE_URL + name,
                    durationSec = img.duration ?: DEFAULT_IMAGE_DURATION_SEC,
                ))
            }
            templates.forEach { t -> add(toTemplateItem(t)) }
        }
        return Schedule(
            id = "remote",
            items = items.distinctBy { it.id },
            pollIntervalSec = 60,
            orientation = Orientation.fromWire(orientation),
        )
    }

    private fun toTemplateItem(dto: TemplateDto): PlaylistItem.Template {
        val assets = templateAssetExtractor.extract(dto.template)
        val templateNumber = assets.firstOrNull()?.templateNumber ?: 0
        val conteudoJson = json.encodeToString(CONTEUDO_SERIALIZER, dto.conteudo)
        val camposJson = json.encodeToString(CAMPOS_SERIALIZER, dto.campos)
        val id = "template-${sha256(dto.template + conteudoJson)}"
        if (assets.isEmpty()) {
            android.util.Log.w(TAG, "Template $id has no extractable assets — keeping as self-contained")
        }
        return PlaylistItem.Template(
            id = id,
            sourceUrl = "template://$id",
            templateNumber = templateNumber,
            rawTemplate = dto.template,
            conteudoJson = conteudoJson,
            camposJson = camposJson,
            assetUrls = assets.map { it.canonicalUrl },
            durationSec = dto.duracao?.takeIf { it > 0 } ?: DEFAULT_TEMPLATE_DURATION_SEC,
        )
    }
    private companion object {
        private const val TAG = "ScheduleRepo"
    }
}

private fun String.fileName(): String = substringAfterLast('/').substringBefore('?')
