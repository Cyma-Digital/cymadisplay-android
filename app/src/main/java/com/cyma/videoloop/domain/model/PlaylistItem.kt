package com.cyma.videoloop.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface PlaylistItem {
    val id: String
    val sourceUrl: String
    val checksum: String?
    val activeWindow: ActiveWindow?

    @Serializable
    @SerialName("video")
    data class Video(
        override val id: String,
        override val sourceUrl: String,
        override val checksum: String? = null,
        override val activeWindow: ActiveWindow? = null,
    ) : PlaylistItem

    @Serializable
    @SerialName("image")
    data class Image(
        override val id: String,
        override val sourceUrl: String,
        override val checksum: String? = null,
        override val activeWindow: ActiveWindow? = null,
        val durationSec: Int,
    ) : PlaylistItem

    @Serializable
    @SerialName("template")
    data class Template(
        override val id: String,
        override val sourceUrl: String,
        override val checksum: String? = null,
        override val activeWindow: ActiveWindow? = null,
        val templateNumber: Int,
        val rawTemplate: String,
        val conteudoJson: String,
        val camposJson: String,
        val assetUrls: List<String>,
        val durationSec: Int,
    ) : PlaylistItem
}

fun PlaylistItem.fileExtension(): String = when (this) {
    is PlaylistItem.Video -> sourceUrl.substringAfterLast('.').substringBefore('?').lowercase()
        .takeIf { it in setOf("mp4", "webm", "mkv") } ?: "mp4"
    is PlaylistItem.Image -> sourceUrl.substringAfterLast('.').substringBefore('?').lowercase()
        .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") } ?: "jpg"
    is PlaylistItem.Template -> "html"
}
