package eu.kanade.tachiyomi.extension.all.randomdownloads

internal data class ExistingManga(
    val id: Long,
    val sourceId: Long,
    val title: String,
    val sourceName: String,
)

internal data class DatabaseManga(
    val id: Long,
    val sourceId: Long,
    val title: String,
)

internal data class DownloadDirectoryIndex(
    val mangaDirsBySource: Map<String, Set<String>>,
)
