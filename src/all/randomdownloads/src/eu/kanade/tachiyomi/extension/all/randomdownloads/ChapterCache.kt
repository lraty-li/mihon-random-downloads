package eu.kanade.tachiyomi.extension.all.randomdownloads

import keiyoushi.utils.applicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

internal class ChapterCache(
    private val storage: MihonStorage,
) {
    private val root = applicationContext.cacheDir
        .resolve("random-downloads")
        .apply { mkdirs() }

    private val chapterRoot = root.resolve("chapters").apply { mkdirs() }
    private val coverRoot = root.resolve("covers").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Any>()

    init {
        root.resolve(".nomedia").runCatching {
            if (!exists()) createNewFile()
        }
    }

    fun prepareChapter(ref: ChapterRef): List<File> {
        val lock = locks.getOrPut("chapter:${ref.cacheKey}") { Any() }

        return synchronized(lock) {
            val finalDir = chapterRoot.resolve(ref.cacheKey)
            val ready = finalDir.resolve(READY_MARKER)

            if (ready.isFile) {
                finalDir.setLastModified(System.currentTimeMillis())
                val cached = pageFiles(finalDir)
                if (cached.isNotEmpty()) return@synchronized cached
            }

            val source = storage.resolveChapter(ref)
                ?: error("Downloaded chapter no longer exists: ${ref.documentName}")

            val tempDir = chapterRoot.resolve(".${ref.cacheKey}.tmp")
            tempDir.deleteRecursively()
            tempDir.mkdirs()

            try {
                if (source.isDirectory) {
                    stageDirectoryChapter(source, tempDir)
                } else {
                    stageArchiveChapter(source, tempDir)
                }

                val pages = pageFiles(tempDir)
                check(pages.isNotEmpty()) {
                    "No readable image pages found in ${ref.documentName}"
                }

                tempDir.resolve(READY_MARKER).writeText("ok")

                finalDir.deleteRecursively()
                if (!tempDir.renameTo(finalDir)) {
                    tempDir.copyRecursively(finalDir, overwrite = true)
                    tempDir.deleteRecursively()
                }

                finalDir.setLastModified(System.currentTimeMillis())
                trimChapterCache(keepKey = ref.cacheKey)
                pageFiles(finalDir)
            } catch (e: Exception) {
                tempDir.deleteRecursively()
                throw e
            }
        }
    }

    fun pageFile(chapterKey: String, fileName: String): File? {
        if (!PAGE_FILE.matches(fileName)) return null

        val dir = chapterRoot.resolve(chapterKey)
        val file = dir.resolve(fileName)

        if (!dir.resolve(READY_MARKER).isFile || !file.isFile) return null

        dir.setLastModified(System.currentTimeMillis())
        return file
    }

    fun prepareCover(ref: MangaRef): File? {
        findCachedCover(ref)?.let { return it }

        val lock = locks.getOrPut("cover:${ref.cacheKey}") { Any() }
        return synchronized(lock) {
            findCachedCover(ref)?.let { return@synchronized it }

            val chapter = storage.listChapters(ref).firstOrNull() ?: return@synchronized null
            val source = storage.resolveChapter(chapter.ref) ?: return@synchronized null

            if (source.isDirectory) {
                val image = storage.listImagesInDirectory(source.uri).firstOrNull()
                    ?: return@synchronized null
                val extension = safeExtension(image.name)
                val destination = coverRoot.resolve("${ref.cacheKey}.$extension")
                storage.copyToFile(image.uri, destination)
                destination
            } else {
                extractFirstArchiveImage(source, ref)
            }
        }
    }

    private fun findCachedCover(ref: MangaRef): File? = coverRoot.listFiles()
        ?.firstOrNull { it.isFile && it.name.startsWith("${ref.cacheKey}.") }

    private fun extractFirstArchiveImage(
        source: DocumentNode,
        ref: MangaRef,
    ): File? {
        storage.openInputStream(source.uri).use { rawInput ->
            ZipInputStream(BufferedInputStream(rawInput)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    try {
                        if (!entry.isDirectory && MihonStorage.isImageName(entry.name)) {
                            val extension = safeExtension(entry.name)
                            val destination = coverRoot.resolve("${ref.cacheKey}.$extension")
                            FileOutputStream(destination).use { output ->
                                zip.copyTo(output)
                            }
                            return destination
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        }

        return null
    }

    private fun stageDirectoryChapter(
        source: DocumentNode,
        destinationDir: File,
    ) {
        storage.listImagesInDirectory(source.uri)
            .forEachIndexed { index, image ->
                val destination = destinationDir.resolve(
                    pageFileName(index, image.name),
                )
                storage.copyToFile(image.uri, destination)
            }
    }

    private fun stageArchiveChapter(
        source: DocumentNode,
        destinationDir: File,
    ) {
        data class ExtractedPage(
            val originalName: String,
            val tempFile: File,
        )

        val extracted = mutableListOf<ExtractedPage>()

        storage.openInputStream(source.uri).use { rawInput ->
            ZipInputStream(BufferedInputStream(rawInput)).use { zip ->
                var ordinal = 0

                while (true) {
                    val entry = zip.nextEntry ?: break
                    try {
                        if (entry.isDirectory || !MihonStorage.isImageName(entry.name)) {
                            continue
                        }

                        val extension = safeExtension(entry.name)
                        val tempFile = destinationDir.resolve("raw-${ordinal.toString().padStart(6, '0')}.$extension")

                        FileOutputStream(tempFile).use { output ->
                            zip.copyTo(output)
                        }

                        extracted += ExtractedPage(entry.name, tempFile)
                        ordinal++
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        }

        extracted
            .sortedWith { left, right -> naturalCompare(left.originalName, right.originalName) }
            .forEachIndexed { index, page ->
                val destination = destinationDir.resolve(
                    pageFileName(index, page.originalName),
                )

                if (!page.tempFile.renameTo(destination)) {
                    page.tempFile.copyTo(destination, overwrite = true)
                    page.tempFile.delete()
                }
            }
    }

    private fun pageFiles(directory: File): List<File> = directory.listFiles()
        ?.filter { it.isFile && PAGE_FILE.matches(it.name) }
        ?.sortedBy { it.name }
        .orEmpty()

    private fun trimChapterCache(keepKey: String) {
        val directories = chapterRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            .orEmpty()

        var totalBytes = directories.sumOf(::directorySize)
        if (totalBytes <= MAX_CHAPTER_CACHE_BYTES) return

        directories
            .filterNot { it.name == keepKey }
            .sortedBy { it.lastModified() }
            .forEach { directory ->
                if (totalBytes <= MAX_CHAPTER_CACHE_BYTES) return

                val bytes = directorySize(directory)
                if (directory.deleteRecursively()) {
                    totalBytes -= bytes
                }
            }
    }

    private fun directorySize(directory: File): Long = directory.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    private fun pageFileName(index: Int, originalName: String): String = "${index.toString().padStart(5, '0')}.${safeExtension(originalName)}"

    private fun safeExtension(name: String): String {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "jpg").lowercase()
        return if (extension.length in 2..5 && extension.all(Char::isLetterOrDigit)) {
            extension
        } else {
            "jpg"
        }
    }

    companion object {
        private const val READY_MARKER = ".ready"
        private const val MAX_CHAPTER_CACHE_BYTES = 512L * 1024L * 1024L
        private val PAGE_FILE = Regex("""\d{5}\.[A-Za-z0-9]{2,5}""")
    }
}
