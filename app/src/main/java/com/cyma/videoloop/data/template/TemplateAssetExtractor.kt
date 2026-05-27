package com.cyma.videoloop.data.template

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls `assets/template-N/<filename>` references out of a raw template and
 * resolves them to canonical S3 URLs. The bucket is the same one every
 * existing `onerror` fallback in the templates already points at — see
 * [ASSETS_BASE_URL].
 *
 * TODO: move ASSETS_BASE_URL to BuildConfig if the CDN ever changes
 * (mirrors the existing MEDIA_BASE_URL in ScheduleRepository).
 */
@Singleton
class TemplateAssetExtractor @Inject constructor() {

    data class TemplateAsset(
        val templateNumber: Int,
        val relativePath: String,
        val canonicalUrl: String,
    )

    fun extract(rawTemplate: String): List<TemplateAsset> {
        val assets = mutableListOf<TemplateAsset>()

        ASSET_REGEX.findAll(rawTemplate).mapTo(assets) { match ->
            val number = match.groupValues[1].toInt()
            val rest = match.groupValues[2]
            val relativePath = "assets/template-$number/$rest"
            TemplateAsset(
                templateNumber = number,
                relativePath = relativePath,
                canonicalUrl = ASSETS_BASE_URL + relativePath,
            )
        }

        SHARED_ASSET_REGEX.findAll(rawTemplate)
            .filter { match ->
                val candidate = match.groupValues[1]
                ASSET_REGEX.find(match.value) == null &&
                    assets.none { it.relativePath == "assets/$candidate" }
            }
            .mapTo(assets) { match ->
                val filename = match.groupValues[1]
                val relativePath = "assets/$filename"
                TemplateAsset(
                    templateNumber = 0,
                    relativePath = relativePath,
                    canonicalUrl = ASSETS_BASE_URL + relativePath,
                )
            }

        return assets.distinctBy { it.relativePath }
    }

    companion object {
        const val ASSETS_BASE_URL = "https://s3.sa-east-1.amazonaws.com/cymadisplay.assets/"
        private val ASSET_REGEX = Regex("""assets/template-(\d+)/([\w.\-]+(?:/[\w.\-]+)*)""")
        private val SHARED_ASSET_REGEX = Regex("""assets/((?!template-)[\w.\-]+\.[\w]+)""")
    }
}
