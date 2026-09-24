package eu.kanade.tachiyomi.extension.all.randomdownloads

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

    fun readAllManga(): List<DatabaseManga> {
        val databaseFile = applicationContext.getDatabasePath(DATABASE_NAME)
        check(databaseFile.isFile) { "Mihon database not found: $databaseFile" }

        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )

        return database.use { db ->
            db.rawQuery(
                "SELECT _id, source, title FROM mangas",
                emptyArray(),
            ).use { cursor ->
                buildList {
                    val idIndex = cursor.getColumnIndexOrThrow("_id")
                    val sourceIndex = cursor.getColumnIndexOrThrow("source")
                    val titleIndex = cursor.getColumnIndexOrThrow("title")

                    while (cursor.moveToNext()) {
                        add(
                            DatabaseManga(
                                id = cursor.getLong(idIndex),
                                sourceId = cursor.getLong(sourceIndex),
                                title = cursor.getString(titleIndex),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun sourceFor(sourceId: Long): Any = getOrStubMethod.invoke(sourceManager, sourceId)
        ?: error("Unable to resolve source $sourceId")

    fun sourceDirectoryName(source: Any): String = getSourceDirNameMethod.invoke(downloadProvider, source) as String

    fun mangaDirectoryName(title: String): String = getMangaDirNameMethod.invoke(downloadProvider, title) as String

    fun sourceDisplayName(source: Any): String = source.toString()

    fun openOriginalManga(mangaId: Long) {
        val activityClass = Class.forName(
            MAIN_ACTIVITY_CLASS,
            true,
            classLoader,
        )

        val intent = android.content.Intent(applicationContext, activityClass).apply {
            action = SHOW_MANGA_ACTION
            putExtra(MANGA_EXTRA, mangaId)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        applicationContext.startActivity(intent)
    }

    private fun hostSingleton(className: String): Any {
        val clazz = Class.forName(className, true, classLoader)
        return Injekt.getInstance(clazz)
    }

    companion object {
        private const val DATABASE_NAME = "tachiyomi.db"
        private const val MAIN_ACTIVITY_CLASS = "eu.kanade.tachiyomi.ui.main.MainActivity"
        private const val SHOW_MANGA_ACTION = "eu.kanade.tachiyomi.SHOW_MANGA"
        private const val MANGA_EXTRA = "manga"
    }
}
