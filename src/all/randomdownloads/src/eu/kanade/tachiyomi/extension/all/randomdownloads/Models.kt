package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.util.Base64
import java.nio.charset.StandardCharsets

internal const val LOCAL_ASSET_HOST = "random-downloads.invalid"
internal const val LOCAL_ASSET_BASE = "https://$LOCAL_ASSET_HOST"

internal data class MangaRef(
    val sourceName: String,
    val mangaName: String,
) {
    val mangaUrl: String
        get() = "/m/${encodePathPart(sourceName)}/${encodePathPart(mangaName)}"

    val coverUrl: String
        get() = "$LOCAL_ASSET_BASE/asset/cover/${encodePathPart(sourceName)}/${encodePathPart(mangaName)}"

    val cacheKey: String
        get() = stableHash("${sourceName}\u0000$mangaName").take(24)
}

internal data class ChapterRef(
    val manga: MangaRef,
    val documentName: String,
) {
    val chapterUrl: String
        get() = "/c/${encodePathPart(manga.sourceName)}/${encodePathPart(manga.mangaName)}/${encodePathPart(documentName)}"

    val cacheKey: String
        get() = stableHash("${manga.sourceName}\u0000${manga.mangaName}\u0000$documentName").take(24)

    fun pageUrl(fileName: String): String = "$LOCAL_ASSET_BASE/asset/page/" +
        "${encodePathPart(manga.sourceName)}/" +
        "${encodePathPart(manga.mangaName)}/" +
        "${encodePathPart(documentName)}/" +
        encodePathPart(fileName)
}

internal data class DownloadedManga(
    val ref: MangaRef,
)

internal data class DownloadedChapter(
    val ref: ChapterRef,
    val displayName: String,
    val lastModified: Long,
    val size: Long?,
    val isDirectory: Boolean,
)

internal fun encodePathPart(value: String): String = Base64.encodeToString(
    value.toByteArray(StandardCharsets.UTF_8),
    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
)

internal fun decodePathPart(value: String): String = String(
    Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
    StandardCharsets.UTF_8,
)

internal fun parseMangaUrl(url: String): MangaRef? {
    val parts = url.substringBefore('?').trim('/').split('/')
    if (parts.size != 3 || parts[0] != "m") return null

    return runCatching {
        MangaRef(
            sourceName = decodePathPart(parts[1]),
            mangaName = decodePathPart(parts[2]),
        )
    }.getOrNull()
}

internal fun parseChapterUrl(url: String): ChapterRef? {
    val parts = url.substringBefore('?').trim('/').split('/')
    if (parts.size != 4 || parts[0] != "c") return null

    return runCatching {
        ChapterRef(
            manga = MangaRef(
                sourceName = decodePathPart(parts[1]),
                mangaName = decodePathPart(parts[2]),
            ),
            documentName = decodePathPart(parts[3]),
        )
    }.getOrNull()
}

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

internal val naturalStringComparator: Comparator<String> = Comparator(::naturalCompare)

internal fun stableHash(value: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))

    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
