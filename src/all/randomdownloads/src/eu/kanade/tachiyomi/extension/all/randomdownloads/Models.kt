package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.net.Uri
import android.util.Base64
import java.nio.charset.StandardCharsets

internal const val LOCAL_ASSET_HOST = "random-downloads.invalid"
internal const val LOCAL_ASSET_BASE = "https://$LOCAL_ASSET_HOST"

internal data class MangaRef(
    val sourceName: String,
    val mangaName: String,
) {
    val mangaUrl: String
        get() = "/m/${encodePart(sourceName)}/${encodePart(mangaName)}"

    val coverUrl: String
        get() = "$LOCAL_ASSET_BASE/cover/${encodePart(sourceName)}/${encodePart(mangaName)}"
}

internal data class DownloadedManga(
    val ref: MangaRef,
    val uri: Uri,
)

internal data class ChapterRef(
    val manga: MangaRef,
    val documentName: String,
) {
    val chapterUrl: String
        get() = "/c/${encodePart(manga.sourceName)}/${encodePart(manga.mangaName)}/${encodePart(documentName)}"
}

internal data class DownloadedChapter(
    val ref: ChapterRef,
    val displayName: String,
    val uri: Uri,
    val isDirectory: Boolean,
)

internal data class DocumentNode(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
)

internal sealed interface LocalImageTarget {
    data class File(
        val uri: Uri,
        val name: String,
    ) : LocalImageTarget

    data class ArchiveEntry(
        val archiveUri: Uri,
        val entryName: String,
    ) : LocalImageTarget
}

internal fun parseMangaUrl(url: String): MangaRef? {
    val parts = url.substringBefore('?').trim('/').split('/')
    if (parts.size != 3 || parts[0] != "m") return null

    return runCatching {
        MangaRef(
            sourceName = decodePart(parts[1]),
            mangaName = decodePart(parts[2]),
        )
    }.getOrNull()
}

internal fun parseChapterUrl(url: String): ChapterRef? {
    val parts = url.substringBefore('?').trim('/').split('/')
    if (parts.size != 4 || parts[0] != "c") return null

    return runCatching {
        ChapterRef(
            manga = MangaRef(
                sourceName = decodePart(parts[1]),
                mangaName = decodePart(parts[2]),
            ),
            documentName = decodePart(parts[3]),
        )
    }.getOrNull()
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

internal fun chapterDisplayName(fileName: String): String {
    val withoutExtension = fileName
        .removeSuffix(".cbz")
        .removeSuffix(".CBZ")
        .removeSuffix(".zip")
        .removeSuffix(".ZIP")

    return withoutExtension.replace(Regex("""_[0-9a-fA-F]{6}$"""), "")
}
