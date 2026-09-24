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

    private val downloadedIndex = ExistingDownloadedMangaIndex()
    private val localReadInterceptor = LocalReadInterceptor(downloadedIndex.scanner())

    override val supportsLatest: Boolean
        get() = false

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(localReadInterceptor)

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page != 1) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val manga = downloadedIndex
            .random(RANDOM_PAGE_SIZE)
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
        if (page != 1) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val manga = downloadedIndex
            .search(query)
            .take(MAX_SEARCH_RESULTS)
            .map(::toSManga)

        return MangasPage(
            mangas = manga,
            hasNextPage = false,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val mangaId = parseMangaId(url.encodedPath) ?: return null
        return downloadedIndex.get(mangaId)?.let(::toSManga)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaId = parseMangaId(manga.url)
            ?: return SMangaUpdate(manga, chapters)

        val existing = downloadedIndex.get(mangaId)
            ?: return SMangaUpdate(manga, chapters)

        val updatedManga = if (fetchDetails) {
            toSManga(existing)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            downloadedIndex
                .downloadedChapters(mangaId)
                .map { it.chapter.toSChapter() }
        } else {
            chapters
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = updatedChapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (mangaId, chapterId) = parseChapterIds(chapter.url)
            ?: error("Invalid local chapter URL: ${chapter.url}")

        val downloaded = downloadedIndex.downloadedChapter(
            mangaId = mangaId,
            chapterId = chapterId,
        ) ?: error("Downloaded chapter no longer exists")

        val document = downloaded.document

        return if (document.isDirectory) {
            downloadedIndex
                .listFolderImages(document.uri)
                .mapIndexed { index, image ->
                    Page(
                        index = index,
                        imageUrl = filePageUrl(image.uri, image.name),
                    )
                }
        } else {
            downloadedIndex
                .listArchiveImages(document.uri)
                .mapIndexed { index, entryName ->
                    Page(
                        index = index,
                        imageUrl = archivePageUrl(document.uri, entryName),
                    )
                }
        }
    }

    private fun toSManga(manga: ExistingManga): SManga = SManga.create().apply {
        url = manga.syntheticUrl
        title = manga.title
        thumbnail_url = manga.thumbnailUrl
        author = manga.author
        artist = manga.artist
        description = buildString {
            manga.description?.takeIf { it.isNotBlank() }?.let {
                append(it)
                append("\n\n")
            }
            append("本地下载来源：")
            append(manga.sourceName)
        }
        status = manga.status
        initialized = true
    }

    private fun DatabaseChapter.toSChapter(): SChapter = SChapter.create().apply {
        url = syntheticUrl
        name = this@toSChapter.name
        scanlator = this@toSChapter.scanlator
        chapter_number = chapterNumber
        date_upload = dateUpload
    }

    companion object {
        private const val RANDOM_PAGE_SIZE = 20
        private const val MAX_SEARCH_RESULTS = 200
    }
}
