package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import keiyoushi.utils.applicationContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File

internal class MihonDownloadIndexCacheReader {

    @Volatile
    private var cached: CachedSnapshot? = null

    fun random(limit: Int): List<DownloadedManga> {
        if (limit <= 0) return emptyList()
        return snapshot().manga.shuffled().take(limit)
    }

    fun find(ref: MangaRef): DownloadedManga? = snapshot().mangaByUrl[ref.mangaUrl]

    @OptIn(ExperimentalSerializationApi::class)
    private fun snapshot(): Snapshot {
        val file = File(applicationContext.cacheDir, CACHE_FILE_NAME)
        if (!file.isFile) {
            error("Mihon download index is not ready.")
        }

        val stamp = FileStamp(
            modified = file.lastModified(),
            length = file.length(),
        )

        cached?.takeIf { it.stamp == stamp }?.let { return it.snapshot }

        return synchronized(this) {
            cached?.takeIf { it.stamp == stamp }?.let { return@synchronized it.snapshot }

            val startedAt = SystemClock.elapsedRealtime()
            val previous = cached

            val loaded = runCatching {
                val root = ProtoBuf.decodeFromByteArray<CacheRoot>(file.readBytes())
                buildSnapshot(root)
            }.onFailure { error ->
                Log.w(TAG, "Failed to decode Mihon download index cache", error)
            }.getOrNull()

            if (loaded != null) {
                cached = CachedSnapshot(stamp, loaded)
                Log.i(
                    TAG,
                    "Mihon cache loaded: sources=${loaded.sourceCount}, manga=${loaded.manga.size}, chapters=${loaded.chapterCount}, bytes=${stamp.length}, elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
                )
                loaded
            } else {
                previous?.snapshot ?: error("Mihon download index is temporarily unavailable.")
            }
        }
    }

    private fun buildSnapshot(root: CacheRoot): Snapshot {
        val manga = buildList {
            root.sourceDirs.values.forEach { source ->
                val sourceName = source.dir
                    ?.let(::documentName)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@forEach

                source.mangaDirs.forEach { (mangaName, mangaDir) ->
                    val mangaUri = mangaDir.dir
                        ?.let(Uri::parse)
                        ?: return@forEach

                    add(
                        DownloadedManga(
                            ref = MangaRef(
                                sourceName = sourceName,
                                mangaName = mangaName,
                            ),
                            uri = mangaUri,
                        ),
                    )
                }
            }
        }

        return Snapshot(
            manga = manga,
            mangaByUrl = manga.associateBy { it.ref.mangaUrl },
            sourceCount = root.sourceDirs.size,
            chapterCount = root.sourceDirs.values.sumOf { source ->
                source.mangaDirs.values.sumOf { it.chapterDirs.size }
            },
        )
    }

    private fun documentName(rawUri: String): String? = runCatching {
        val uri = Uri.parse(rawUri)
        val documentId = runCatching {
            DocumentsContract.getDocumentId(uri)
        }.getOrElse {
            DocumentsContract.getTreeDocumentId(uri)
        }

        documentId.substringAfterLast('/')
    }.getOrNull()

    private data class CachedSnapshot(
        val stamp: FileStamp,
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
        private const val CACHE_FILE_NAME = "dl_index_cache_v3"
    }
}
