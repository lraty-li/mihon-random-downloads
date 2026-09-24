package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

internal class ExistingDownloadedMangaIndex(
    private val bridge: ReadOnlyMihonBridge = ReadOnlyMihonBridge(),
    private val scanner: ReadOnlyDownloadScanner = ReadOnlyDownloadScanner(),
) {

    private val lock = Any()
    private val chapterDocumentCache = ConcurrentHashMap<Long, DocumentNode>()

    @Volatile
    private var memoryCache: List<ExistingManga>? = null

    fun random(
        limit: Int,
        rescan: Boolean = false,
    ): List<ExistingManga> {
        val all = if (rescan) {
            rebuild()
        } else {
            memoryCache ?: rebuild()
        }

        return all.shuffled().take(limit)
    }

    fun get(mangaId: Long): ExistingManga? = (memoryCache ?: rebuild()).firstOrNull { it.id == mangaId }

    fun search(query: String): List<ExistingManga> {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            return (memoryCache ?: rebuild())
        }

        return (memoryCache ?: rebuild()).filter {
            it.title.contains(normalized, ignoreCase = true) ||
                it.sourceName.contains(normalized, ignoreCase = true)
        }
    }

    fun downloadedChapters(mangaId: Long): List<DownloadedChapterRef> {
        val manga = get(mangaId) ?: return emptyList()
        val childrenByName = scanner.listChildren(manga.mangaDirectoryUri)
            .associateBy { it.name }

        return bridge.readChapters(mangaId)
            .mapNotNull { chapter ->
                val document = bridge.validChapterDocumentNames(chapter)
                    .firstNotNullOfOrNull(childrenByName::get)
                    ?: return@mapNotNull null

                chapterDocumentCache[chapter.id] = document
                DownloadedChapterRef(chapter, document)
            }
    }

    fun downloadedChapter(
        mangaId: Long,
        chapterId: Long,
    ): DownloadedChapterRef? {
        val cachedDocument = chapterDocumentCache[chapterId]
        val chapter = bridge.readChapter(chapterId)
            ?.takeIf { it.mangaId == mangaId }
            ?: return null

        if (cachedDocument != null) {
            return DownloadedChapterRef(chapter, cachedDocument)
        }

        val manga = get(mangaId) ?: return null
        val document = scanner.findChapterDocument(
            manga.mangaDirectoryUri,
            bridge.validChapterDocumentNames(chapter),
        ) ?: return null

        chapterDocumentCache[chapterId] = document
        return DownloadedChapterRef(chapter, document)
    }

    fun listFolderImages(uri: Uri): List<DocumentNode> = scanner.listImages(uri)

    fun listArchiveImages(uri: Uri): List<String> = scanner.listArchiveImageEntries(uri)

    fun scanner(): ReadOnlyDownloadScanner = scanner

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
                val mangaDirectoryUri = downloadedMangaDirs[mangaDirectoryName]
                    ?: return@mapNotNull null

                ExistingManga(
                    id = manga.id,
                    sourceId = manga.sourceId,
                    title = manga.title,
                    sourceName = sourceInfo.displayName,
                    thumbnailUrl = manga.thumbnailUrl,
                    author = manga.author,
                    artist = manga.artist,
                    description = manga.description,
                    status = manga.status,
                    mangaDirectoryUri = mangaDirectoryUri,
                )
            }
            .distinctBy { it.id }
            .toList()
            .also {
                memoryCache = it
                chapterDocumentCache.clear()
            }
    }

    private data class SourceInfo(
        val displayName: String,
        val directoryName: String,
    )
}
