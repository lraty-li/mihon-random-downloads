package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.net.Uri
import keiyoushi.utils.applicationContext
import uy.kohesive.injekt.Injekt
import kotlin.random.Random

/**
 * Read-only bridge to Mihon's existing DownloadCache.
 *
 * This never calls cache mutation/invalidation methods. It only snapshots directory objects already
 * present in memory and reservoir-samples manga entries, avoiding a filesystem-wide scan.
 */
internal class HostDownloadCacheSampler {

    private val cache: Any by lazy {
        val clazz = Class.forName(
            DOWNLOAD_CACHE_CLASS,
            true,
            applicationContext.classLoader,
        )
        Injekt.getInstance(clazz)
    }

    fun sample(limit: Int): List<DownloadedManga>? {
        if (limit <= 0) return emptyList()

        return runCatching {
            val root = field(cache, "rootDownloadsDir") ?: return null
            val sourceDirs = field(root, "sourceDirs") as? Map<*, *> ?: return null
            if (sourceDirs.isEmpty()) return null

            val reservoir = ArrayList<DownloadedManga>(limit)
            var seen = 0

            sourceDirs.values.toList().forEach { sourceDirectory ->
                if (sourceDirectory == null) return@forEach

                val sourceFile = field(sourceDirectory, "dir") ?: return@forEach
                val sourceName = uniFileName(sourceFile) ?: return@forEach

                val mangaDirs = field(sourceDirectory, "mangaDirs") as? Map<*, *>
                    ?: return@forEach

                mangaDirs.values.toList().forEach mangaLoop@{ mangaDirectory ->
                    if (mangaDirectory == null) return@mangaLoop

                    val mangaFile = field(mangaDirectory, "dir") ?: return@mangaLoop
                    val mangaName = uniFileName(mangaFile) ?: return@mangaLoop
                    val mangaUri = uniFileUri(mangaFile) ?: return@mangaLoop

                    val manga = DownloadedManga(
                        ref = MangaRef(
                            sourceName = sourceName,
                            mangaName = mangaName,
                        ),
                        uri = mangaUri,
                    )

                    seen++
                    if (reservoir.size < limit) {
                        reservoir += manga
                    } else {
                        val replaceAt = Random.nextInt(seen)
                        if (replaceAt < limit) {
                            reservoir[replaceAt] = manga
                        }
                    }
                }
            }

            reservoir.takeIf { it.isNotEmpty() }?.shuffled()
        }.getOrNull()
    }

    private fun field(
        instance: Any,
        name: String,
    ): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance)
    }

    private fun uniFileName(file: Any): String? = file.javaClass.getMethod("getName").invoke(file) as? String

    private fun uniFileUri(file: Any): Uri? = file.javaClass.getMethod("getUri").invoke(file) as? Uri

    companion object {
        private const val DOWNLOAD_CACHE_CLASS =
            "eu.kanade.tachiyomi.data.download.DownloadCache"
    }
}
