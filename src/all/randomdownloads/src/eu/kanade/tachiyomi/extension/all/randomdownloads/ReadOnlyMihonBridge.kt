package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import keiyoushi.utils.applicationContext
import uy.kohesive.injekt.Injekt

internal class ReadOnlyMihonBridge {

    private val classLoader
        get() = applicationContext.classLoader

    private val sourceManager: Any by lazy {
        hostSingleton("tachiyomi.domain.source.service.SourceManager")
    }

    private val downloadProvider: Any by lazy {
        hostSingleton("eu.kanade.tachiyomi.data.download.DownloadProvider")
    }

    private val getOrStubMethod by lazy {
        sourceManager.javaClass.methods.first {
            it.name == "getOrStub" && it.parameterCount == 1
        }
    }

    private val getSourceDirNameMethod by lazy {
        downloadProvider.javaClass.methods.first {
            it.name == "getSourceDirName" && it.parameterCount == 1
        }
    }

    private val getMangaDirNameMethod by lazy {
        downloadProvider.javaClass.methods.first {
            it.name == "getMangaDirName" && it.parameterCount == 1
        }
    }

    private val getValidChapterDirNamesMethod by lazy {
        downloadProvider.javaClass.methods.first {
            it.name == "getValidChapterDirNames" && it.parameterCount == 3
        }
    }

    fun readAllManga(): List<DatabaseManga> = withReadOnlyDatabase { db ->
        db.rawQuery(
            """
                SELECT _id, source, title, thumbnail_url, author, artist, description, status
                FROM mangas
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toDatabaseManga())
                }
            }
        }
    }

    fun readManga(id: Long): DatabaseManga? = withReadOnlyDatabase { db ->
        db.rawQuery(
            """
                SELECT _id, source, title, thumbnail_url, author, artist, description, status
                FROM mangas
                WHERE _id = ?
                LIMIT 1
            """.trimIndent(),
            arrayOf(id.toString()),
        ).use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.toDatabaseManga()
        }
    }

    fun readChapters(mangaId: Long): List<DatabaseChapter> = withReadOnlyDatabase { db ->
        db.rawQuery(
            """
                SELECT _id, manga_id, url, name, scanlator, chapter_number, date_upload, source_order
                FROM chapters
                WHERE manga_id = ?
                ORDER BY source_order ASC
            """.trimIndent(),
            arrayOf(mangaId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toDatabaseChapter())
                }
            }
        }
    }

    fun readChapter(chapterId: Long): DatabaseChapter? = withReadOnlyDatabase { db ->
        db.rawQuery(
            """
                SELECT _id, manga_id, url, name, scanlator, chapter_number, date_upload, source_order
                FROM chapters
                WHERE _id = ?
                LIMIT 1
            """.trimIndent(),
            arrayOf(chapterId.toString()),
        ).use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.toDatabaseChapter()
        }
    }

    fun sourceFor(sourceId: Long): Any = getOrStubMethod.invoke(sourceManager, sourceId)
        ?: error("Unable to resolve source $sourceId")

    fun sourceDirectoryName(source: Any): String = getSourceDirNameMethod.invoke(downloadProvider, source) as String

    fun mangaDirectoryName(title: String): String = getMangaDirNameMethod.invoke(downloadProvider, title) as String

    @Suppress("UNCHECKED_CAST")
    fun validChapterDocumentNames(chapter: DatabaseChapter): List<String> = getValidChapterDirNamesMethod.invoke(
        downloadProvider,
        chapter.name,
        chapter.scanlator,
        chapter.url,
    ) as List<String>

    fun sourceDisplayName(source: Any): String = source.toString()

    private fun <T> withReadOnlyDatabase(block: (SQLiteDatabase) -> T): T {
        val databaseFile = applicationContext.getDatabasePath(DATABASE_NAME)
        check(databaseFile.isFile) { "Mihon database not found: $databaseFile" }

        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )

        return database.use(block)
    }

    private fun Cursor.toDatabaseManga(): DatabaseManga {
        val idIndex = getColumnIndexOrThrow("_id")
        val sourceIndex = getColumnIndexOrThrow("source")
        val titleIndex = getColumnIndexOrThrow("title")
        val thumbnailIndex = getColumnIndexOrThrow("thumbnail_url")
        val authorIndex = getColumnIndexOrThrow("author")
        val artistIndex = getColumnIndexOrThrow("artist")
        val descriptionIndex = getColumnIndexOrThrow("description")
        val statusIndex = getColumnIndexOrThrow("status")

        return DatabaseManga(
            id = getLong(idIndex),
            sourceId = getLong(sourceIndex),
            title = getString(titleIndex),
            thumbnailUrl = stringOrNull(thumbnailIndex),
            author = stringOrNull(authorIndex),
            artist = stringOrNull(artistIndex),
            description = stringOrNull(descriptionIndex),
            status = getInt(statusIndex),
        )
    }

    private fun Cursor.toDatabaseChapter(): DatabaseChapter {
        val idIndex = getColumnIndexOrThrow("_id")
        val mangaIdIndex = getColumnIndexOrThrow("manga_id")
        val urlIndex = getColumnIndexOrThrow("url")
        val nameIndex = getColumnIndexOrThrow("name")
        val scanlatorIndex = getColumnIndexOrThrow("scanlator")
        val numberIndex = getColumnIndexOrThrow("chapter_number")
        val uploadIndex = getColumnIndexOrThrow("date_upload")
        val sourceOrderIndex = getColumnIndexOrThrow("source_order")

        return DatabaseChapter(
            id = getLong(idIndex),
            mangaId = getLong(mangaIdIndex),
            url = getString(urlIndex),
            name = getString(nameIndex),
            scanlator = stringOrNull(scanlatorIndex),
            chapterNumber = getFloat(numberIndex),
            dateUpload = getLong(uploadIndex),
            sourceOrder = getLong(sourceOrderIndex),
        )
    }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun hostSingleton(className: String): Any {
        val clazz = Class.forName(className, true, classLoader)
        return Injekt.getInstance(clazz)
    }

    companion object {
        private const val DATABASE_NAME = "tachiyomi.db"
    }
}
