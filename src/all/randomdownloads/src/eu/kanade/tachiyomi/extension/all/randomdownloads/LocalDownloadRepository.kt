package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.net.Uri

internal class LocalDownloadRepository(
    private val scanner: ReadOnlyDownloadScanner = ReadOnlyDownloadScanner(),
    private val index: MihonDownloadIndexCacheReader = MihonDownloadIndexCacheReader(scanner),
) {

    fun random(limit: Int): List<DownloadedManga> = index.random(limit)

    fun search(
        query: String,
        limit: Int,
    ): List<DownloadedManga> = index.search(query, limit)

    fun resolveManga(ref: MangaRef): DownloadedManga? = runCatching {
        index.find(ref)
    }.getOrNull()

    fun listChapters(ref: MangaRef): List<DownloadedChapter> {
        val manga = resolveManga(ref) ?: return emptyList()

        return scanner.listChildren(manga.uri)
            .asSequence()
            .filter { node -> isValidChapterNode(node, manga.indexedChapterNames) }
            .sortedWith { left, right -> naturalCompare(right.name, left.name) }
            .map { node ->
                DownloadedChapter(
                    ref = ChapterRef(ref, node.name),
                    displayName = chapterDisplayName(node.name),
                    uri = node.uri,
                    isDirectory = node.isDirectory,
                )
            }
            .toList()
    }

    fun resolveChapter(ref: ChapterRef): DownloadedChapter? {
        val manga = resolveManga(ref.manga) ?: return null
        val node = scanner.listChildren(manga.uri)
            .firstOrNull { it.name == ref.documentName }
            ?.takeIf { isValidChapterNode(it, manga.indexedChapterNames) }
            ?: return null

        return DownloadedChapter(
            ref = ref,
            displayName = chapterDisplayName(node.name),
            uri = node.uri,
            isDirectory = node.isDirectory,
        )
    }

    fun listFolderImages(uri: Uri): List<DocumentNode> = scanner.listImages(uri)

    fun listArchiveImages(uri: Uri): List<ArchiveImageEntry> = scanner.listArchiveImageEntries(uri)

    fun coverFileCandidates(ref: MangaRef): List<LocalImageTarget.File> {
        val manga = resolveManga(ref) ?: return emptyList()

        return COVER_NAMES.map { name ->
            LocalImageTarget.File(
                uri = scanner.childDocumentUri(manga.uri, name),
                name = name,
            )
        }
    }

    fun coverFallbackTarget(ref: MangaRef): LocalImageTarget? {
        val manga = resolveManga(ref) ?: return null

        val chapter = scanner.listChildren(manga.uri)
            .asSequence()
            .filter { node -> isValidChapterNode(node, manga.indexedChapterNames) }
            .sortedWith { left, right -> naturalCompare(left.name, right.name) }
            .firstOrNull()
            ?: return null

        return if (chapter.isDirectory) {
            val first = scanner.listImages(chapter.uri).firstOrNull() ?: return null
            LocalImageTarget.File(first.uri, first.name)
        } else {
            val first = scanner.listArchiveImageEntries(chapter.uri).firstOrNull() ?: return null
            LocalImageTarget.ArchiveEntry(first)
        }
    }

    fun scanner(): ReadOnlyDownloadScanner = scanner

    private fun isValidChapterNode(
        node: DocumentNode,
        indexedChapterNames: Set<String>?,
    ): Boolean {
        if (isTemporaryDownloadName(node.name)) return false

        return when {
            node.isDirectory -> {
                indexedChapterNames?.contains(node.name) ?: isPlausibleChapterDirectory(node.name)
            }

            node.name.endsWith(".cbz", ignoreCase = true) -> {
                val baseName = node.name.substringBeforeLast('.')
                indexedChapterNames?.contains(baseName) ?: true
            }

            node.name.endsWith(".zip", ignoreCase = true) -> true

            else -> false
        }
    }

    private fun isTemporaryDownloadName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(TEMP_DOWNLOAD_SUFFIX) ||
            lower.endsWith(".tmp") ||
            lower.startsWith(".")
    }

    private fun isPlausibleChapterDirectory(name: String): Boolean = name.isNotBlank() &&
        name.lowercase() !in COVER_DIRECTORY_NAMES

    companion object {
        private const val TEMP_DOWNLOAD_SUFFIX = "_temp"

        private val COVER_NAMES = listOf(
            "cover.jpg",
            "cover.jpeg",
            "cover.png",
            "cover.webp",
            "cover.avif",
        )

        private val COVER_DIRECTORY_NAMES = setOf(
            "cover",
            "covers",
            "thumbnail",
            "thumbnails",
        )
    }
}
