package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.os.SystemClock
import android.util.Log
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import okhttp3.OkHttpClient

@Source
abstract class RandomDownloads : HttpSource() {

    private val repository = LocalDownloadRepository()
    private val localReadInterceptor = LocalReadInterceptor(repository)

    override val client: OkHttpClient by lazy {
        network.client
            .newBuilder()
            .addInterceptor(localReadInterceptor)
            .build()
    }

    override val supportsLatest: Boolean
        get() = true

    override suspend fun getPopularManga(page: Int): MangasPage = getRandomMangaPage(page, "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getRandomMangaPage(page, "latest")

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (page != 1) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        return MangasPage(
            mangas = repository
                .search(query, MAX_SEARCH_RESULTS)
                .map(::toSManga),
            hasNextPage = false,
        )
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val ref = parseMangaUrl(manga.url)
            ?: return SMangaUpdate(manga, chapters)

        val local = repository.resolveManga(ref)
            ?: return SMangaUpdate(manga, chapters)

        val updatedManga = if (fetchDetails) {
            val metadataStartedAt = SystemClock.elapsedRealtime()
            val metadata = repository.metadata(ref)

            Log.i(
                TAG,
                "Metadata details: hit=${metadata != null}, elapsed=${SystemClock.elapsedRealtime() - metadataStartedAt}ms",
            )

            toSManga(local).apply {
                author = metadata?.author
                artist = metadata?.artist
                description = "本地下载来源：${ref.sourceName}"
            }
        } else {
            manga
        }.apply {
            initialized = true
        }

        val updatedChapters = if (fetchChapters) {
            repository
                .listChapters(ref)
                .map(::toSChapter)
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val ref = parseChapterUrl(chapter.url)
            ?: error("Invalid local chapter URL: ${chapter.url}")

        val local = repository.resolveChapter(ref)
            ?: error("Downloaded chapter no longer exists")

        val pages = if (local.isDirectory) {
            repository
                .listFolderImages(local.uri)
                .mapIndexed { index, image ->
                    Page(
                        index = index,
                        imageUrl = filePageUrl(image.uri, image.name),
                    )
                }
        } else {
            repository
                .listArchiveImages(local.uri)
                .mapIndexed { index, entry ->
                    Page(
                        index = index,
                        imageUrl = archivePageUrl(entry),
                    )
                }
        }

        Log.i(
            TAG,
            "Page list: ${ref.documentName}, directory=${local.isDirectory}, count=${pages.size}",
        )

        return pages
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    private fun getRandomMangaPage(
        page: Int,
        lane: String,
    ): MangasPage {
        if (page != 1) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val startedAt = SystemClock.elapsedRealtime()
        val items = repository.random(RANDOM_PAGE_SIZE)

        Log.i(
            TAG,
            "Random page [$lane]: count=${items.size}, elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
        )

        return MangasPage(
            mangas = items.map(::toSManga),
            hasNextPage = false,
        )
    }

    private fun toSManga(manga: DownloadedManga): SManga = SManga.create().apply {
        url = manga.ref.mangaUrl
        title = manga.ref.mangaName
        thumbnail_url = manga.ref.coverUrl
    }

    private fun toSChapter(chapter: DownloadedChapter): SChapter = SChapter.create().apply {
        url = chapter.ref.chapterUrl
        name = chapter.displayName
    }

    companion object {
        private const val TAG = "RandomDownloads"
        private const val RANDOM_PAGE_SIZE = 20
        private const val MAX_SEARCH_RESULTS = 200
    }
}
