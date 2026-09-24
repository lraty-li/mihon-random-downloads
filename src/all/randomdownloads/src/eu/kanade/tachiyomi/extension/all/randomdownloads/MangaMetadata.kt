package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.util.Xml
import java.io.StringReader

internal data class MangaMetadata(
    val author: String? = null,
    val artist: String? = null,
)

internal fun parseComicInfoMetadata(xml: String): MangaMetadata? = runCatching {
    val parser = Xml.newPullParser().apply {
        setInput(StringReader(xml))
    }

    var writer: String? = null
    var penciller: String? = null

    while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
            when (parser.name) {
                "Writer" -> writer = parser.nextText().trim().takeIf { it.isNotBlank() }
                "Penciller" -> penciller = parser.nextText().trim().takeIf { it.isNotBlank() }
            }
        }
        parser.next()
    }

    val author = writer ?: penciller
    if (author == null && penciller == null) {
        null
    } else {
        MangaMetadata(
            author = author,
            artist = penciller,
        )
    }
}.getOrNull()
