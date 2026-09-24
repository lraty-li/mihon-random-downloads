package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Source
abstract class RandomDownloads :
    KeiSource(),
    ConfigurableSource {

    private val downloadedIndex = ExistingDownloadedMangaIndex()
    private val requestGeneration = AtomicInteger(0)

    override val supportsLatest: Boolean
        get() = false

    /**
     * Intentionally empty.
     *
     * Returning virtual manga from a normal source listing makes Mihon persist a second manga
     * identity through NetworkToLocalManga. This extension deliberately avoids that path.
     * Use the source's Settings screen instead.
     */
    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), hasNextPage = false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), hasNextPage = false)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = MangasPage(emptyList(), hasNextPage = false)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = null

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = SMangaUpdate(manga, chapters)

    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        Log.i(TAG, "Opening read-only settings UI")
        val notice = newPreference(screen.context).apply {
            title = "随机已下载漫画（原条目）"
            summary =
                "只读扫描 Mihon 现有下载目录，并打开数据库中原有的 Manga ID。" +
                "不会创建虚拟漫画、索引文件、缓存文件，也不会修改下载内容。"
            setEnabled(false)
        }

        val shuffle = newPreference(screen.context).apply {
            title = "🎲 换一批"
            summary = "使用内存中的已识别列表重新随机 20 本"
        }

        val rescan = newPreference(screen.context).apply {
            title = "↻ 重新扫描下载目录"
            summary = "只读重新扫描；下载内容发生变化后使用"
        }

        val status = newPreference(screen.context).apply {
            title = "随机结果"
            summary = "尚未加载"
            setEnabled(false)
        }

        val resultPreferences = List(RANDOM_PAGE_SIZE) {
            newPreference(screen.context).apply {
                setVisible(false)
            }
        }

        screen.addPreference(notice)
        screen.addPreference(shuffle)
        screen.addPreference(rescan)
        screen.addPreference(status)
        resultPreferences.forEach(screen::addPreference)

        fun render(rescanDirectories: Boolean) {
            val generation = requestGeneration.incrementAndGet()

            shuffle.setEnabled(false)
            rescan.setEnabled(false)
            status.title = if (rescanDirectories) {
                "正在只读扫描下载目录…"
            } else {
                "正在随机…"
            }
            status.summary = null
            resultPreferences.forEach { it.setVisible(false) }

            executor.execute {
                val loaded = runCatching {
                    downloadedIndex.random(
                        limit = RANDOM_PAGE_SIZE,
                        rescan = rescanDirectories,
                    )
                }

                mainHandler.post {
                    if (generation != requestGeneration.get()) {
                        return@post
                    }

                    shuffle.setEnabled(true)
                    rescan.setEnabled(true)

                    loaded.onSuccess { selection ->
                        Log.i(TAG, "Read-only scan completed")
                        status.title = "随机结果"
                        status.summary = "共识别 ${selection.total} 本有下载漫画"

                        resultPreferences.forEachIndexed { index, preference ->
                            val manga = selection.items.getOrNull(index)
                            if (manga == null) {
                                preference.setVisible(false)
                                return@forEachIndexed
                            }

                            preference.title = manga.title
                            preference.summary = manga.sourceName
                            preference.setOnPreferenceClickListener {
                                downloadedIndex.open(manga.id)
                                true
                            }
                            preference.setVisible(true)
                        }

                        if (selection.items.isEmpty()) {
                            status.title = "没有匹配到已下载的原漫画条目"
                            status.summary = "可以点“重新扫描下载目录”再试一次"
                        }
                    }.onFailure { error ->
                        Log.e(TAG, "Read-only scan failed", error)
                        status.title = "读取失败"
                        status.summary = error.message ?: error.javaClass.simpleName
                    }
                }
            }
        }

        shuffle.setOnPreferenceClickListener {
            render(rescanDirectories = false)
            true
        }

        rescan.setOnPreferenceClickListener {
            render(rescanDirectories = true)
            true
        }

        render(rescanDirectories = true)
    }

    private fun newPreference(context: Context): Preference = Preference::class.java
        .getConstructor(Context::class.java)
        .newInstance(context)

    companion object {
        private const val TAG = "RandomDownloads"
        private const val RANDOM_PAGE_SIZE = 20

        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RandomDownloads-ReadOnly").apply {
                isDaemon = true
            }
        }

        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
