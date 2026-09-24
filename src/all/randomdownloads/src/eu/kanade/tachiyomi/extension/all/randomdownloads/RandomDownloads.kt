package eu.kanade.tachiyomi.extension.all.randomdownloads

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

@Source
abstract class RandomDownloads : KeiSource() {

    private val storage = MihonStorage()
    private val chapterCache = ChapterCache(storage)

    @Volatile
    private var catalogCache: List<DownloadedManga> = emptyList()

    @Volatile
    private var catalogScannedAt: Long = 0L

    private val catalogLock = Any()

    override val supportsLatest: Boolean
        get() = false

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(LocalAssetInterceptor(chapterCache))

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page != 1) return MangasPage(emptyList(), hasNextPage = false)

        val manga = catalog()
            .shuffled()
            .take(RANDOM_PAGE_SIZE)
            .map(::toSManga)

        return MangasPage(
            mangas = manga,
            hasNextPage = false,
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), hasNextPage = false)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (page != 1) return MangasPage(emptyList(), hasNextPage = false)

        val normalized = query.trim()
        val result = catalog()
            .asSequence()
            .filter { downloaded ->
                normalized.isBlank() ||
                    downloaded.ref.mangaName.contains(normalized, ignoreCase = true) ||
                    downloaded.ref.sourceName.contains(normalized, ignoreCase = true)
            }
            .sortedWith { left, right ->
                naturalCompare(left.ref.mangaName, right.ref.mangaName)
            }
            .map(::toSManga)
            .toList()

        return MangasPage(result, hasNextPage = false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val ref = parseMangaUrl(url.encodedPath) ?: return null
        if (storage.resolveManga(ref) == null) return null

        return toSManga(DownloadedManga(ref))
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val ref = parseMangaUrl(manga.url)
            ?: return SMangaUpdate(manga = manga, chapters = chapters)

        if (fetchDetails) {
            manga.title = ref.mangaName
            manga.thumbnail_url = ref.coverUrl
            manga.description =
                "Local downloaded manga.\n\n" +
                "Original Mihon download source: ${ref.sourceName}"
        }

        val updatedChapters = if (fetchChapters) {
            storage.listChapters(ref).map(::toSChapter)
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = manga,
            chapters = updatedChapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val ref = parseChapterUrl(chapter.url)
            ?: error("Invalid local chapter URL: ${chapter.url}")

        val files = chapterCache.prepareChapter(ref)

        return files.mapIndexed { index, file ->
            Page(
                index = index,
                imageUrl = ref.pageUrl(file.name),
            )
        }
    }

    private fun catalog(): List<DownloadedManga> {
        val now = System.currentTimeMillis()
        val existing = catalogCache

        if (existing.isNotEmpty() && now - catalogScannedAt < CATALOG_TTL_MS) {
            return existing
        }

        return synchronized(catalogLock) {
            val current = catalogCache
            val currentNow = System.currentTimeMillis()

            if (current.isNotEmpty() && currentNow - catalogScannedAt < CATALOG_TTL_MS) {
                current
            } else {
                storage.scanDownloadedManga().also {
                    catalogCache = it
                    catalogScannedAt = currentNow
                }
            }
        }
    }

    private fun toSManga(downloaded: DownloadedManga): SManga = SManga.create().apply {
        url = downloaded.ref.mangaUrl
        title = downloaded.ref.mangaName
        thumbnail_url = downloaded.ref.coverUrl
    }

    private fun toSChapter(downloaded: DownloadedChapter): SChapter = SChapter.create().apply {
        url = downloaded.ref.chapterUrl
        name = downloaded.displayName
        date_upload = downloaded.lastModified
    }

    companion object {
        private const val RANDOM_PAGE_SIZE = 20
        private const val CATALOG_TTL_MS = 30_000L
    }
}
