package com.cyma.videoloop.data.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaDownloader @Inject constructor(private val okHttpClient: OkHttpClient) {

    sealed interface DownloadResult {
        data class Success(val etag: String?) : DownloadResult
        object NotModified : DownloadResult
        data class Failure(val message: String) : DownloadResult
    }

    suspend fun download(
        url: String,
        destFile: File,
        etag: String? = null,
        allowTextual: Boolean = false,
        onProgress: suspend (Int) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get()
                .apply { etag?.let { header("If-None-Match", it) } }
                .build()

            val response = okHttpClient.newCall(request).execute()
            when {
                response.code == 304 -> {
                    response.close()
                    DownloadResult.NotModified
                }
                !response.isSuccessful -> {
                    response.close()
                    DownloadResult.Failure("HTTP ${response.code}")
                }
                else -> {
                    val contentType = response.header("Content-Type")
                    if (contentType != null && !allowTextual) {
                        val mime = contentType.substringBefore(';').trim().lowercase()
                        if (mime.startsWith("text/") || mime == "application/json" || mime == "application/xml") {
                            response.close()
                            return@withContext DownloadResult.Failure("Invalid content type: $mime")
                        }
                    }
                    val body = response.body ?: run {
                        response.close()
                        return@withContext DownloadResult.Failure("Empty body")
                    }
                    val newEtag = response.header("ETag")
                    val contentLength = body.contentLength()
                    destFile.parentFile?.mkdirs()
                    val partFile = File(destFile.parent, "${destFile.name}.part")
                    try {
                        partFile.outputStream().use { out ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                var totalBytes = 0L
                                var lastReportedProgress = -1
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    out.write(buffer, 0, bytesRead)
                                    totalBytes += bytesRead
                                    if (contentLength > 0) {
                                        val progress = (totalBytes * 100 / contentLength).toInt()
                                        if (progress != lastReportedProgress) {
                                            lastReportedProgress = progress
                                            onProgress(progress)
                                        }
                                    }
                                }
                            }
                        }
                        if (partFile.renameTo(destFile)) {
                            DownloadResult.Success(newEtag)
                        } else {
                            DownloadResult.Failure("Failed to finalize file")
                        }
                    } catch (e: Exception) {
                        partFile.delete()
                        DownloadResult.Failure(e.message ?: "Write error")
                    } finally {
                        body.close()
                    }
                }
            }
        } catch (e: Exception) {
            DownloadResult.Failure(e.message ?: "Network error")
        }
    }
}
