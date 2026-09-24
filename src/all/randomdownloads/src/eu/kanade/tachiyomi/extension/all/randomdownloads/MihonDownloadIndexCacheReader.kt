package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import keiyoushi.utils.applicationContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File

internal class MihonDownloadIndexCacheReader(
    private val scanner: ReadOnlyDownloadScanner = ReadOnlyDownloadScanner(),
) {

    @Volatile
    private var cached: CachedSnapshot? = null

    fun random(limit: Int): List<DownloadedManga> {
        if (limit <= 0) return emptyList()
        return snapshot().manga.shuffled().take(limit)
    }

    fun search(
        query: String,
        limit: Int,
    ): List<DownloadedManga> {
        if (limit <= 0) return emptyList()

        val normalized = query.trim()
        val all = snapshot().manga

        if (normalized.isEmpty()) {
            return all.shuffled().take(limit)
        }

        return all
            .asSequence()
            .filter { manga ->
                manga.ref.mangaName.contains(normalized, ignoreCase = true) ||
                    manga.ref.sourceName.contains(normalized, ignoreCase = true)
            }
            .take(limit)
            .toList()
    }

    fun find(ref: MangaRef): DownloadedManga? = snapshot().mangaByUrl[ref.mangaUrl]

    @OptIn(ExperimentalSerializationApi::class)
    private fun snapshot(): Snapshot {
        val file = compatibleCacheFile()

        if (file == null) {
            cached?.snapshot?.let { return it }
            return buildFullSafSnapshot("compatible Mihon cache unavailable")
                .also { cached = CachedSnapshot(stamp = null, snapshot = it) }
        }

        val stamp = fileStamp(file)
        cached?.takeIf { it.stamp == stamp }?.let { return it.snapshot }

        return synchronized(this) {
            cached?.takeIf { it.stamp == stamp }?.let { return@synchronized it.snapshot }

            val previous = cached
            val startedAt = SystemClock.elapsedRealtime()

            val loaded = runCatching {
                val root = decodeStable(file, stamp)
                buildSnapshot(root)
            }.onFailure { error ->
                Log.w(TAG, "Failed to decode Mihon download index cache", error)
            }.getOrNull()

            when {
                loaded != null -> {
                    cached = CachedSnapshot(stamp, loaded)
                    Log.i(
                        TAG,
                        "Mihon cache loaded: sources=${loaded.sourceCount}, manga=${loaded.manga.size}, supplementalSources=${loaded.supplementalSourceCount}, supplementalManga=${loaded.supplementalMangaCount}, chapters=${loaded.chapterCount}, bytes=${stamp.length}, elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                    )
                    loaded
                }

                previous != null -> {
                    Log.w(TAG, "Using previous in-memory download snapshot after cache decode failure")
                    previous.snapshot
                }

                else -> {
                    buildFullSafSnapshot("cache decode failed")
                        .also { cached = CachedSnapshot(stamp = null, snapshot = it) }
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeStable(
        file: File,
        expectedStamp: FileStamp,
    ): CacheRoot {
        val bytes = file.readBytes()
        check(fileStamp(file) == expectedStamp) {
            "Mihon download index changed while it was being read"
        }
        return ProtoBuf.decodeFromByteArray(bytes)
    }

    private fun buildSnapshot(root: CacheRoot): Snapshot {
        val mangaByUrl = linkedMapOf<String, DownloadedManga>()
        val indexedSourceNames = mutableSetOf<String>()
        var chapterCount = 0

        root.sourceDirs.values.forEach { source ->
            val sourceUri = source.dir
                ?.let(Uri::parse)
                ?: return@forEach
            val sourceName = scanner.documentName(sourceUri)
                .takeIf { it.isNotBlank() }
                ?: return@forEach

            indexedSourceNames += sourceName.lowercase()

            source.mangaDirs.forEach { (mangaName, mangaDir) ->
                val mangaUri = mangaDir.dir
                    ?.let(Uri::parse)
                    ?: return@forEach

                val chapters = mangaDir.chapterDirs.toSet()
                chapterCount += chapters.size

                val manga = DownloadedManga(
                    ref = MangaRef(
                        sourceName = sourceName,
                        mangaName = mangaName,
                    ),
                    uri = mangaUri,
                    indexedChapterNames = chapters,
                )
                mangaByUrl[manga.ref.mangaUrl] = manga
            }
        }

        val physicalSources = listPhysicalSources(root)
        var supplementalSourceCount = 0
        var supplementalMangaCount = 0

        physicalSources.forEach { sourceDir ->
            if (sourceDir.name.lowercase() in indexedSourceNames) return@forEach

            supplementalSourceCount++
            scanner.listDirectories(sourceDir.uri).forEach { mangaDir ->
                val manga = DownloadedManga(
                    ref = MangaRef(
                        sourceName = sourceDir.name,
                        mangaName = mangaDir.name,
                    ),
                    uri = mangaDir.uri,
                    indexedChapterNames = null,
                )

                if (mangaByUrl.putIfAbsent(manga.ref.mangaUrl, manga) == null) {
                    supplementalMangaCount++
                }
            }
        }

        val manga = mangaByUrl.values.toList()

        return Snapshot(
            manga = manga,
            mangaByUrl = mangaByUrl,
            sourceCount = physicalSources.size.coerceAtLeast(indexedSourceNames.size),
            supplementalSourceCount = supplementalSourceCount,
            supplementalMangaCount = supplementalMangaCount,
            chapterCount = chapterCount,
        )
    }

    private fun listPhysicalSources(root: CacheRoot): List<DocumentNode> {
        val cachedRootUri = root.dir
            ?.let(Uri::parse)

        if (cachedRootUri != null) {
            runCatching {
                scanner.listDirectories(cachedRootUri)
            }.getOrNull()?.let { return it }
        }

        return scanner.listDirectories(scanner.downloadsRoot().uri)
    }

    private fun buildFullSafSnapshot(reason: String): Snapshot {
        val startedAt = SystemClock.elapsedRealtime()
        val downloadsRoot = scanner.downloadsRoot()
        val sources = scanner.listDirectories(downloadsRoot.uri)
        val mangaByUrl = linkedMapOf<String, DownloadedManga>()

        sources.forEach { sourceDir ->
            scanner.listDirectories(sourceDir.uri).forEach { mangaDir ->
                val manga = DownloadedManga(
                    ref = MangaRef(
                        sourceName = sourceDir.name,
                        mangaName = mangaDir.name,
                    ),
                    uri = mangaDir.uri,
                    indexedChapterNames = null,
                )
                mangaByUrl[manga.ref.mangaUrl] = manga
            }
        }

        val manga = mangaByUrl.values.toList()

        Log.w(
            TAG,
            "Built full read-only SAF fallback snapshot: reason=$reason, sources=${sources.size}, manga=${manga.size}, elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
        )

        return Snapshot(
            manga = manga,
            mangaByUrl = mangaByUrl,
            sourceCount = sources.size,
            supplementalSourceCount = sources.size,
            supplementalMangaCount = manga.size,
            chapterCount = 0,
        )
    }

    private fun compatibleCacheFile(): File? {
        val newest = applicationContext.cacheDir
            .listFiles()
            .orEmpty()
            .mapNotNull { file ->
                val match = CACHE_FILE_PATTERN.matchEntire(file.name)
                    ?: return@mapNotNull null
                val version = match.groupValues[1].toIntOrNull()
                    ?: return@mapNotNull null
                version to file
            }
            .maxByOrNull { it.first }
            ?: return null

        if (newest.first != SUPPORTED_CACHE_VERSION) {
            Log.w(
                TAG,
                "Unsupported Mihon download index version v${newest.first}; using read-only SAF fallback",
            )
            return null
        }

        return newest.second.takeIf { it.isFile }
    }

    private fun fileStamp(file: File): FileStamp = FileStamp(
        modified = file.lastModified(),
        length = file.length(),
    )

    private data class CachedSnapshot(
        val stamp: FileStamp?,
        val snapshot: Snapshot,
    )

    private data class FileStamp(
        val modified: Long,
        val length: Long,
    )

    private data class Snapshot(
        val manga: List<DownloadedManga>,
        val mangaByUrl: Map<String, DownloadedManga>,
        val sourceCount: Int,
        val supplementalSourceCount: Int,
        val supplementalMangaCount: Int,
        val chapterCount: Int,
    )

    @Serializable
    private data class CacheRoot(
        val dir: String? = null,
        val sourceDirs: Map<Long, CacheSourceDirectory> = emptyMap(),
    )

    @Serializable
    private data class CacheSourceDirectory(
        val dir: String? = null,
        val mangaDirs: Map<String, CacheMangaDirectory> = emptyMap(),
    )

    @Serializable
    private data class CacheMangaDirectory(
        val dir: String? = null,
        val chapterDirs: Set<String> = emptySet(),
    )

    companion object {
        private const val TAG = "RandomDownloads"
        private const val SUPPORTED_CACHE_VERSION = 3
        private val CACHE_FILE_PATTERN = Regex("""dl_index_cache_v(\d+)""")
    }
}
