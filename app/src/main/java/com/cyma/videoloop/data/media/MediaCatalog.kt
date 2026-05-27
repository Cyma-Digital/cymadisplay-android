package com.cyma.videoloop.data.media

import android.content.Context
import com.cyma.videoloop.domain.model.PlaylistItem
import com.cyma.videoloop.domain.model.fileExtension
import com.cyma.videoloop.util.sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCatalog @Inject constructor(@ApplicationContext private val context: Context) {

    private val cacheDir = File(context.filesDir, "media")

    fun localFile(item: PlaylistItem): File {
        val name = "${sha256(item.sourceUrl)}.${item.fileExtension()}"
        return File(cacheDir, name)
    }

    fun isCached(item: PlaylistItem): Boolean {
        val f = localFile(item)
        return f.exists() && f.length() > 0
    }

    fun cachedFiles(): Set<File> = cacheDir.listFiles()?.toSet() ?: emptySet()

    fun totalCacheSizeBytes(): Long = cachedFiles().sumOf { it.length() }
}
