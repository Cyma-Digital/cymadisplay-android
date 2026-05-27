package com.cyma.videoloop.data.template

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk layout for cached templates:
 *
 *   filesDir/templates/<templateId>/
 *     index.html                       ← rendered output
 *     assets/template-N/<filename>     ← preserves the relative path used in HTML
 *
 * Mirroring the relative path is what lets WebViewAssetLoader resolve
 * `assets/template-N/...` against the local cache without rewriting any tags.
 */
@Singleton
class TemplateCatalog @Inject constructor(@ApplicationContext private val context: Context) {

    val rootDir: File = File(context.filesDir, "templates")

    fun dir(templateId: String): File = File(rootDir, templateId)

    fun indexFile(templateId: String): File = File(dir(templateId), "index.html")

    fun assetFile(templateId: String, relativePath: String): File =
        File(dir(templateId), relativePath)

    fun isMaterialized(templateId: String): Boolean {
        val f = indexFile(templateId)
        return f.exists() && f.length() > 0
    }

    fun allTemplateDirs(): List<File> =
        rootDir.listFiles { f -> f.isDirectory }?.toList().orEmpty()
}
