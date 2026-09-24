package eu.kanade.tachiyomi.extension.all.randomdownloads

internal class ExistingDownloadedMangaIndex(
    private val bridge: ReadOnlyMihonBridge = ReadOnlyMihonBridge(),
    private val scanner: ReadOnlyDownloadScanner = ReadOnlyDownloadScanner(),
) {

    private val lock = Any()

    @Volatile
    private var memoryCache: List<ExistingManga>? = null

    fun random(
        limit: Int,
        rescan: Boolean = false,
    ): RandomSelection {
        val all = if (rescan) {
            rebuild()
        } else {
            memoryCache ?: rebuild()
        }

        return RandomSelection(
            items = all.shuffled().take(limit),
            total = all.size,
        )
    }

    fun open(mangaId: Long) {
        bridge.openOriginalManga(mangaId)
    }

    private fun rebuild(): List<ExistingManga> = synchronized(lock) {
        val downloadIndex = scanner.scan()
        val sourceCache = mutableMapOf<Long, SourceInfo>()

        bridge.readAllManga()
            .asSequence()
            .mapNotNull { manga ->
                val sourceInfo = runCatching {
                    sourceCache.getOrPut(manga.sourceId) {
                        val source = bridge.sourceFor(manga.sourceId)
                        SourceInfo(
                            displayName = bridge.sourceDisplayName(source),
                            directoryName = bridge.sourceDirectoryName(source),
                        )
                    }
                }.getOrNull() ?: return@mapNotNull null

                val downloadedMangaDirs =
                    downloadIndex.mangaDirsBySource[sourceInfo.directoryName.lowercase()]
                        ?: return@mapNotNull null

                val mangaDirectoryName = bridge.mangaDirectoryName(manga.title)
                if (mangaDirectoryName !in downloadedMangaDirs) {
                    return@mapNotNull null
                }

                ExistingManga(
                    id = manga.id,
                    sourceId = manga.sourceId,
                    title = manga.title,
                    sourceName = sourceInfo.displayName,
                )
            }
            .distinctBy { it.id }
            .toList()
            .also { memoryCache = it }
    }

    data class RandomSelection(
        val items: List<ExistingManga>,
        val total: Int,
    )

    private data class SourceInfo(
        val displayName: String,
        val directoryName: String,
    )
}
