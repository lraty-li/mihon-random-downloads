package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

internal class LocalDownloadRepository(
    private val scanner: ReadOnlyDownloadScanner = ReadOnlyDownloadScanner(),
) {

    private val knownManga = ConcurrentHashMap<String, DownloadedManga>()
    private val sourceDirectoryCache = ConcurrentHashMap<String, DocumentNode>()

    @Volatile
    private var sources: List<DocumentNode>? = null

    fun random(limit: Int): List<DownloadedManga> {
        if (limit <= 0) return emptyList()

        val result = linkedMapOf<String, DownloadedManga>()

        sourceDirectories()
            .shuffled()
            .forEach { sourceDir ->
                if (result.size >= limit) return@forEach

                val remaining = limit - result.size
                val take = minOf(PER_SOURCE_SAMPLE, remaining)

                scanner.listDirectories(sourceDir.uri)
                    .shuffled()
                    .take(take)
                    .forEach { mangaDir ->
                        val manga = remember(sourceDir, mangaDir)
                        result[manga.ref.mangaUrl] = manga
                    }
            }

        return result.values.take(limit)
    }

    fun searchKnown(query: String, limit: Int): List<DownloadedManga> {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            return knownManga.values
                .shuffled()
                .take(limit)
        }

        return knownManga.values
            .asSequence()
            .filter {
                it.ref.mangaName.contains(normalized, ignoreCase = true) ||
                    it.ref.sourceName.contains(normalized, ignoreCase = true)
            }
            .take(limit)
            .toList()
    }

    fun resolveManga(ref: MangaRef): DownloadedManga? {
        knownManga[ref.mangaUrl]?.let { return it }

        val sourceDir = sourceDirectoryCache[ref.sourceName]
            ?: sourceDirectories()
                .firstOrNull { it.name == ref.sourceName }
                ?.also { sourceDirectoryCache[ref.sourceName] = it }
            ?: return null

        val mangaDir = scanner.findDirectory(sourceDir.uri, ref.mangaName)
            ?: return null

        return remember(sourceDir, mangaDir)
    }

    fun listChapters(ref: MangaRef): List<DownloadedChapter> {
        val manga = resolveManga(ref) ?: return emptyList()

        return scanner.listChildren(manga.uri)
            .asSequence()
            .filter {
                it.isDirectory ||
                    it.name.endsWith(".cbz", ignoreCase = true) ||
                    it.name.endsWith(".zip", ignoreCase = true)
            }
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
            ?: return null

        if (
            !node.isDirectory &&
            !node.name.endsWith(".cbz", ignoreCase = true) &&
            !node.name.endsWith(".zip", ignoreCase = true)
        ) {
            return null
        }

        return DownloadedChapter(
            ref = ref,
            displayName = chapterDisplayName(node.name),
            uri = node.uri,
            isDirectory = node.isDirectory,
        )
    }

    fun listFolderImages(uri: Uri): List<DocumentNode> = scanner.listImages(uri)

    fun listArchiveImages(uri: Uri): List<ArchiveImageEntry> = scanner.listArchiveImageEntries(uri)

    fun coverTarget(ref: MangaRef): LocalImageTarget? {
        val manga = resolveManga(ref) ?: return null
        val children = scanner.listChildren(manga.uri)

        children
            .firstOrNull { node ->
                !node.isDirectory &&
                    node.name.lowercase() in COVER_NAMES
            }
            ?.let { cover ->
                return LocalImageTarget.File(
                    uri = cover.uri,
                    name = cover.name,
                )
            }

        val chapter = children
            .asSequence()
            .filter {
                it.isDirectory ||
                    it.name.endsWith(".cbz", ignoreCase = true) ||
                    it.name.endsWith(".zip", ignoreCase = true)
            }
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

    private fun sourceDirectories(): List<DocumentNode> {
        sources?.let { return it }

        return synchronized(this) {
            sources ?: scanner.listDirectories(scanner.downloadsRoot().uri)
                .also { loaded ->
                    loaded.forEach { sourceDirectoryCache[it.name] = it }
                    sources = loaded
                }
        }
    }

    private fun remember(manga: DownloadedManga): DownloadedManga {
        knownManga[manga.ref.mangaUrl] = manga
        return manga
    }

    private fun remember(
        sourceDir: DocumentNode,
        mangaDir: DocumentNode,
    ): DownloadedManga = remember(
        DownloadedManga(
            ref = MangaRef(
                sourceName = sourceDir.name,
                mangaName = mangaDir.name,
            ),
            uri = mangaDir.uri,
        ),
    )

    companion object {
        private const val PER_SOURCE_SAMPLE = 5

        private val COVER_NAMES = setOf(
            "cover.jpg",
            "cover.jpeg",
            "cover.png",
            "cover.webp",
            "cover.avif",
        )
    }
}
