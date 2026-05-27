package com.cyma.videoloop.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface CymaApi {

    /**
     * Returns the current playlist for this display.
     * URL: GET /v2/displaydata/{displayId}?parse=true
     *
     * Also doubles as the "am I paired?" check — a 2xx response means the
     * backend recognises this device and pairing is complete. 4xx means the
     * device hasn't been paired (yet).
     */
    @GET("displaydata/{displayId}")
    suspend fun getDisplayData(
        @Path("displayId") displayId: String,
        @Query("parse") parse: Boolean = true,
    ): DisplayDataDto

    /**
     * Registers this display's pairing code in the backend so it can be claimed
     * via the admin panel. Once registered, the client polls [getDisplayData]
     * to detect when the admin completes pairing (2xx = paired, 4xx = waiting).
     *
     * URL: POST /v2/pairing-codes
     * Body: { dsCodigo, codigoPareamento }
     *
     * Idempotent on the backend (safe to call repeatedly with the same payload).
     */
    @POST("pairing-codes")
    suspend fun registerPairingCode(@Body request: PairingRequestDto)
}

// ── DTOs ─────────────────────────────────────────────────────────────────────

/**
 * Wire format for `GET /displaydata/{displayId}?parse=true`.
 *
 * Example response:
 * ```
 * {
 *   "midias":     ["midias/1746599680605-demo.mp4", ...],
 *   "imagens":    [{"dsUrl": "midias/...", "duration": 5}],
 *   "cardapios":  [], "promocoes": [], "efeito": [], "templates": [],
 *   "orientation": "horizontal",
 *   "meta": { "weekDay": 0, "currentTime": "16:53", "now": "...", "validUntil": null }
 * }
 * ```
 *
 * `midias` and `imagens` contain *relative* paths under the assets bucket — the
 * mapper in [com.cyma.videoloop.data.schedule.ScheduleRepository] joins them with
 * the assets base URL to produce full media URLs.
 *
 * Other content arrays (`cardapios`, `promocoes`, `efeito`, `templates`) are
 * not yet handled and are silently ignored thanks to `ignoreUnknownKeys=true`.
 */
@Serializable
data class DisplayDataDto(
    val midias: List<String> = emptyList(),
    val imagens: List<ImagemDto> = emptyList(),
    val templates: List<TemplateDto> = emptyList(),
    /** "horizontal" | "vertical" | "horizontal_inverted" | "vertical_inverted". */
    val orientation: String? = null,
    val meta: DisplayMetaDto? = null,
)

@Serializable
data class ImagemDto(
    val dsUrl: String,
    val duration: Int? = null,
)

/**
 * Wire format for an entry in `templates[]`. The HTML in [template] is a raw
 * skeleton with placeholders like `<!-- text-editor -->`; values from
 * [conteudo] are substituted into those placeholders per the [campos] schema.
 *
 * `conteudo` is `JsonElement` rather than `String` because the production API
 * sometimes ships richer shapes; the renderer pulls `.jsonPrimitive.content`
 * and falls back to `defaultValue` when the value isn't a primitive.
 */
@Serializable
data class TemplateDto(
    val template: String,
    val conteudo: Map<String, List<Map<String, JsonElement>>> = emptyMap(),
    val campos: List<CampoDto> = emptyList(),
    val arquivos: JsonElement? = null,
    val duracao: Int? = null,
)

@Serializable
data class CampoDto(
    val fieldName: String,
    val inputs: List<InputDto> = emptyList(),
    val maxQuantity: Int? = null,
)

@Serializable
data class InputDto(
    val name: String,
    val label: String? = null,
    val defaultValue: String? = null,
    val required: Boolean? = null,
    val isContentEditable: Boolean? = null,
)

@Serializable
data class DisplayMetaDto(
    val weekDay: Int? = null,
    val currentTime: String? = null,
    val now: String? = null,
    /** ISO-ish timestamp when this schedule expires; null means no expiry. */
    val validUntil: String? = null,
)

/** Body for `POST /v2/pairing-codes`. */
@Serializable
data class PairingRequestDto(
    val dsCodigo: String,
    val codigoPareamento: String,
)
