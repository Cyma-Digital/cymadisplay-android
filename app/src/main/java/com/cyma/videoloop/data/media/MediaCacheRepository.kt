package com.cyma.videoloop.data.media

import com.cyma.videoloop.data.api.CampoDto
import com.cyma.videoloop.data.template.TemplateAssetExtractor
import com.cyma.videoloop.data.template.TemplateCatalog
import com.cyma.videoloop.data.template.TemplateRenderer
import com.cyma.videoloop.domain.model.PlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCacheRepository @Inject constructor(
    private val downloader: MediaDownloader,
    private val catalog: MediaCatalog,
    private val templateCatalog: TemplateCatalog,
    private val templateRenderer: TemplateRenderer,
    private val json: Json,
) {
    sealed interface MaterializeResult {
        data class Downloading(val progress: Int) : MaterializeResult
        data class Ready(val file: File) : MaterializeResult
        data class Error(val message: String) : MaterializeResult
    }

    /**
     * Returns a flow that emits download progress and finally a Ready or Error result.
     * Skips the download entirely if the file is already on disk.
     *
     * Uses [channelFlow] (not `flow {}`) because [MediaDownloader.download] invokes
     * the `onProgress` callback from inside its own `withContext(Dispatchers.IO)`
     * block — a child coroutine. A plain `flow` would reject those emissions as
     * a flow-invariant violation; the channel-backed flow routes them safely.
     */
    fun materialize(item: PlaylistItem): Flow<MaterializeResult> = channelFlow {
        when (item) {
            is PlaylistItem.Template -> materializeTemplate(item)
            else -> materializeMedia(item)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<MaterializeResult>.materializeMedia(item: PlaylistItem) {
        val file = catalog.localFile(item)
        if (catalog.isCached(item)) {
            send(MaterializeResult.Ready(file))
            return
        }

        send(MaterializeResult.Downloading(0))

        val result = downloader.download(
            url = item.sourceUrl,
            destFile = file,
            onProgress = { progress -> send(MaterializeResult.Downloading(progress)) },
        )

        when (result) {
            is MediaDownloader.DownloadResult.Success,
            MediaDownloader.DownloadResult.NotModified -> send(MaterializeResult.Ready(file))
            is MediaDownloader.DownloadResult.Failure -> send(MaterializeResult.Error(result.message))
        }
    }

    private suspend fun ProducerScope<MaterializeResult>.materializeTemplate(item: PlaylistItem.Template) {
        val indexFile = templateCatalog.indexFile(item.id)
        val alreadyComplete = templateCatalog.isMaterialized(item.id) &&
            item.assetUrls.all { url ->
                val relPath = url.substringAfter("cymadisplay.assets/").substringBefore('?')
                val f = templateCatalog.assetFile(item.id, relPath)
                f.exists() && f.length() > 0
            }
        if (alreadyComplete) {
            send(MaterializeResult.Ready(indexFile))
            return
        }

        send(MaterializeResult.Downloading(0))

        val total = item.assetUrls.size
        item.assetUrls.forEachIndexed { i, url ->
            val relPath = url.substringAfter("cymadisplay.assets/").substringBefore('?')
            val destFile = templateCatalog.assetFile(item.id, relPath)
            if (destFile.exists() && destFile.length() > 0) {
                if (total > 0) send(MaterializeResult.Downloading(((i + 1) * 100) / total))
                return@forEachIndexed
            }
            val result = downloader.download(
                url = url,
                destFile = destFile,
                allowTextual = true,
            )
            when (result) {
                is MediaDownloader.DownloadResult.Success,
                MediaDownloader.DownloadResult.NotModified -> {
                    if (total > 0) send(MaterializeResult.Downloading(((i + 1) * 100) / total))
                }
                is MediaDownloader.DownloadResult.Failure -> {
                    send(MaterializeResult.Error("asset $url: ${result.message}"))
                    return
                }
            }
        }

        val discoveredAssets = mutableMapOf<String, String>()
        for (url in item.assetUrls) {
            val relPath = url.substringAfter("cymadisplay.assets/").substringBefore('?')
            if (!relPath.endsWith(".css", ignoreCase = true)) continue
            val cssFile = templateCatalog.assetFile(item.id, relPath)
            if (!cssFile.exists()) continue
            val cssDir = relPath.substringBeforeLast('/', "")
            CSS_URL_REGEX.findAll(cssFile.readText(Charsets.UTF_8)).forEach { match ->
                val ref = match.groupValues[1]
                if (ref.startsWith("data:", ignoreCase = true) || ref.startsWith("#")) return@forEach
                when {
                    ref.contains("cymadisplay.assets/") -> {
                        val assetPath = ref.substringAfter("cymadisplay.assets/").substringBefore('?')
                        discoveredAssets[assetPath] = TemplateAssetExtractor.ASSETS_BASE_URL + assetPath
                    }
                    !ref.startsWith("http") -> {
                        val resolved = if (cssDir.isEmpty()) ref
                                       else File(cssDir, ref).path.replace('\\', '/')
                        discoveredAssets[resolved] = TemplateAssetExtractor.ASSETS_BASE_URL + resolved
                    }
                }
            }
        }
        for ((localPath, s3Url) in discoveredAssets) {
            val destFile = templateCatalog.assetFile(item.id, localPath)
            if (destFile.exists() && destFile.length() > 0) continue
            val result = downloader.download(
                url = s3Url,
                destFile = destFile,
                allowTextual = true,
            )
            if (result is MediaDownloader.DownloadResult.Failure) {
                android.util.Log.w(TAG, "CSS asset download failed: $s3Url: ${result.message}")
            }
        }

        val rendered = try {
            val conteudo = json.decodeFromString<Map<String, List<Map<String, JsonElement>>>>(item.conteudoJson)
            val campos = json.decodeFromString<List<CampoDto>>(item.camposJson)
            templateRenderer.render(item.rawTemplate, conteudo, campos)
        } catch (e: Exception) {
            send(MaterializeResult.Error("render failed: ${e.message}"))
            return
        }

        val partFile = File(indexFile.parent, "${indexFile.name}.part")
        try {
            indexFile.parentFile?.mkdirs()
            partFile.writeText(rendered, Charsets.UTF_8)
            if (!partFile.renameTo(indexFile)) {
                partFile.delete()
                send(MaterializeResult.Error("Failed to finalize index.html"))
                return
            }
        } catch (e: Exception) {
            partFile.delete()
            send(MaterializeResult.Error("write failed: ${e.message}"))
            return
        }

        send(MaterializeResult.Ready(indexFile))
    }

    /**
     * Ensures all items in the list are cached, downloading any that are missing.
     * Called by MediaPrefetchWorker after a schedule sync.
     */
    suspend fun prefetchAll(items: List<PlaylistItem>) {
        for (item in items) {
            val needsFetch = when (item) {
                is PlaylistItem.Template -> !templateCatalog.isMaterialized(item.id)
                else -> !catalog.isCached(item)
            }
            if (needsFetch) {
                materialize(item).collect {}
            }
        }
    }

    fun evict(item: PlaylistItem) {
        when (item) {
            is PlaylistItem.Template -> templateCatalog.dir(item.id).deleteRecursively()
            else -> {
                val file = catalog.localFile(item)
                if (file.exists()) file.delete()
                File(file.parent, "${file.name}.part").let { if (it.exists()) it.delete() }
            }
        }
    }

    fun evictOrphans(activeItems: List<PlaylistItem>) {
        val activeMediaFiles = activeItems
            .filter { it !is PlaylistItem.Template }
            .map { catalog.localFile(it) }
            .toSet()
        catalog.cachedFiles()
            .filterNot { it in activeMediaFiles || it.name.endsWith(".part") }
            .forEach { it.delete() }

        val activeTemplateIds = activeItems
            .filterIsInstance<PlaylistItem.Template>()
            .map { it.id }
            .toSet()
        templateCatalog.allTemplateDirs()
            .filterNot { it.name in activeTemplateIds }
            .forEach { it.deleteRecursively() }
    }

    private companion object {
        const val TAG = "MediaCacheRepo"
        private val CSS_URL_REGEX = Regex("""url\(\s*['"]?([^'")\s]+)['"]?\s*\)""")
    }
}
