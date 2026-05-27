package com.cyma.videoloop.data.template

import com.cyma.videoloop.data.api.CampoDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TemplateRenderer @Inject constructor() {

    /**
     * Substitutes placeholders in [rawTemplate] with values from [conteudo],
     * mirroring the server-side Python `generate()` exactly:
     *
     *   placeholder = "<!-- name -->"           when len(group) == 1
     *   placeholder = "<!-- name-{i + 1} -->"   when len(group) > 1 (1-indexed)
     *
     * Falls back to `input.defaultValue` (then "") when the lookup misses or
     * the value isn't a JSON primitive.
     *
     * Strips `onerror=` / `onload=` event handlers afterward so the WebView
     * never reaches for the S3 fallback baked into every template tag.
     */
    fun render(
        rawTemplate: String,
        conteudo: Map<String, List<Map<String, JsonElement>>>,
        campos: List<CampoDto>,
    ): String {
        var out = rawTemplate
        for (field in campos) {
            val group = conteudo[field.fieldName].orEmpty()
            group.forEachIndexed { i, content ->
                for (input in field.inputs) {
                    val value = content[input.name]?.maybeContent()
                        ?: input.defaultValue
                        ?: ""
                    val placeholder = if (group.size == 1) {
                        "<!-- ${input.name} -->"
                    } else {
                        "<!-- ${input.name}-${i + 1} -->"
                    }
                    out = out.replace(placeholder, value)
                }
            }
        }
        return rewriteAssetUrls(sanitizeLinks(stripEventHandlers(out)))
    }

    private fun JsonElement.maybeContent(): String? = runCatching {
        jsonPrimitive.contentOrNull
    }.getOrNull()

    companion object {
        // Matches `onerror="..."`, `onload='...'`, with optional whitespace.
        private val EVENT_HANDLER_REGEX = Regex(
            """\s+on(?:error|load)\s*=\s*(?:"[^"]*"|'[^']*')""",
            RegexOption.IGNORE_CASE,
        )

        private val BASE_TAG_REGEX = Regex(
            """<base\b[^>]*>""",
            RegexOption.IGNORE_CASE,
        )

        private val ABSOLUTE_S3_URL_REGEX = Regex(
            """https?://s3\.sa-east-1\.amazonaws\.com/cymadisplay\.assets/(assets/[\w.\-/]+)""",
            RegexOption.IGNORE_CASE,
        )

        private val PROTOCOL_RELATIVE_S3_URL_REGEX = Regex(
            """//s3\.sa-east-1\.amazonaws\.com/cymadisplay\.assets/(assets/[\w.\-/]+)""",
            RegexOption.IGNORE_CASE,
        )

        // `as="style"` on `<link rel="stylesheet">` is invalid HTML; some
        // production templates ship it. Removing keeps Chromium from treating
        // the same href's preload+stylesheet pair as conflicting requests.
        private val STYLESHEET_AS_STYLE_REGEX = Regex(
            """(<link\b[^>]*\brel\s*=\s*["']stylesheet["'][^>]*?)\s+as\s*=\s*["']style["']""",
            RegexOption.IGNORE_CASE,
        )

        // `<link rel="preload" as="font">` requires `crossorigin` for the
        // preload to be matched against the @font-face fetch — without it
        // the preload is wasted (Chromium logs a warning) and the font may
        // flash transparent.
        private val FONT_PRELOAD_REGEX = Regex(
            """(<link\b[^>]*\brel\s*=\s*["']preload["'][^>]*\bas\s*=\s*["']font["'][^>]*?)(\s*/?>)""",
            RegexOption.IGNORE_CASE,
        )

        fun rewriteAssetUrls(html: String): String {
            var out = BASE_TAG_REGEX.replace(html, "")
            out = ABSOLUTE_S3_URL_REGEX.replace(out) { it.groupValues[1] }
            out = PROTOCOL_RELATIVE_S3_URL_REGEX.replace(out) { it.groupValues[1] }
            return out
        }

        fun stripEventHandlers(html: String): String =
            EVENT_HANDLER_REGEX.replace(html, "")

        fun sanitizeLinks(html: String): String {
            var out = STYLESHEET_AS_STYLE_REGEX.replace(html) { it.groupValues[1] }
            out = FONT_PRELOAD_REGEX.replace(out) { m ->
                val head = m.groupValues[1]
                val tail = m.groupValues[2]
                if (head.contains("crossorigin", ignoreCase = true)) m.value
                else "$head crossorigin=\"anonymous\"$tail"
            }
            return out
        }
    }
}
