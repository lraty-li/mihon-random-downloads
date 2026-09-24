package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.net.Uri
import android.util.Base64
import java.nio.charset.StandardCharsets

internal const val LOCAL_ASSET_HOST = "random-downloads.invalid"
internal const val LOCAL_ASSET_BASE = "https://$LOCAL_ASSET_HOST"

internal data class ExistingManga(
    val id: Long,
    val sourceId: Long,
    val title: String,
    val sourceName: String,
    val thumbnailUrl: String?,
    val author: String?,
    val artist: String?,
    val description: String?,
    val status: Int,
    val mangaDirectoryUri: Uri,
) {
    val syntheticUrl: String
        get() = "/m/$id"
}

internal data class DatabaseManga(
    val id: Long,
    val sourceId: Long,
    val title: String,
    val thumbnailUrl: String?,
    val author: String?,
    val artist: String?,
    val description: String?,
    val status: Int,
)

internal data class DatabaseChapter(
    val id: Long,
    val mangaId: Long,
    val url: String,
    val name: String,
    val scanlator: String?,
    val chapterNumber: Float,
    val dateUpload: Long,
    val sourceOrder: Long,
) {
    val syntheticUrl: String
        get() = "/c/$mangaId/$id"
}

internal data class DocumentNode(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
)

internal data class DownloadedChapterRef(
    val chapter: DatabaseChapter,
    val document: DocumentNode,
)

internal data class DownloadDirectoryIndex(
    val mangaDirsBySource: Map<String, Map<String, Uri>>,
)

internal fun parseMangaId(url: String): Long? {
    val parts = url.substringBefore('?').trim('/').split('/')
    if (parts.size != 2 || parts[0] != "m") return null
    return parts[1].toLongOrNull()
}

internal fun parseChapterIds(url: String): Pair<Long, Long>? {
    val parts = url.substringBefore('?').trim('/').split('/')
    if (parts.size != 3 || parts[0] != "c") return null
    val mangaId = parts[1].toLongOrNull() ?: return null
    val chapterId = parts[2].toLongOrNull() ?: return null
    return mangaId to chapterId
}

internal fun filePageUrl(uri: Uri, name: String): String = "$LOCAL_ASSET_BASE/file/${encodePart(uri.toString())}/${encodePart(name)}"

internal fun archivePageUrl(uri: Uri, entryName: String): String = "$LOCAL_ASSET_BASE/archive/${encodePart(uri.toString())}/${encodePart(entryName)}"

internal fun encodePart(value: String): String = Base64.encodeToString(
    value.toByteArray(StandardCharsets.UTF_8),
    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
)

internal fun decodePart(value: String): String = String(
    Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
    StandardCharsets.UTF_8,
)

internal fun naturalCompare(left: String, right: String): Int {
    var li = 0
    var ri = 0

    while (li < left.length && ri < right.length) {
        val lc = left[li]
        val rc = right[ri]

        if (lc.isDigit() && rc.isDigit()) {
            val lStart = li
            val rStart = ri

            while (li < left.length && left[li].isDigit()) li++
            while (ri < right.length && right[ri].isDigit()) ri++

            val lRaw = left.substring(lStart, li)
            val rRaw = right.substring(rStart, ri)
            val lNorm = lRaw.trimStart('0').ifEmpty { "0" }
            val rNorm = rRaw.trimStart('0').ifEmpty { "0" }

            if (lNorm.length != rNorm.length) {
                return lNorm.length.compareTo(rNorm.length)
            }

            val numericCompare = lNorm.compareTo(rNorm)
            if (numericCompare != 0) return numericCompare

            if (lRaw.length != rRaw.length) {
                return lRaw.length.compareTo(rRaw.length)
            }

            continue
        }

        val charCompare = lc.lowercaseChar().compareTo(rc.lowercaseChar())
        if (charCompare != 0) return charCompare

        li++
        ri++
    }

    return left.length.compareTo(right.length)
}

internal fun imageMediaType(name: String): String = when (
    name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "avif" -> "image/avif"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    else -> "application/octet-stream"
}

internal fun isImageName(name: String): Boolean {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif")
}
