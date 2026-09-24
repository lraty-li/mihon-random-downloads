package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import keiyoushi.utils.applicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

internal data class DocumentNode(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val lastModified: Long,
) {
    val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

internal class MihonStorage(
    private val context: Context = applicationContext,
) {
    private val resolver
        get() = context.contentResolver

    fun scanDownloadedManga(): List<DownloadedManga> {
        val downloads = downloadsRoot()
        val result = mutableListOf<DownloadedManga>()

        listChildren(downloads.uri)
            .filter { it.isDirectory }
            .forEach { sourceDir ->
                listChildren(sourceDir.uri)
                    .filter { it.isDirectory }
                    .forEach { mangaDir ->
                        result += DownloadedManga(
                            ref = MangaRef(
                                sourceName = sourceDir.name,
                                mangaName = mangaDir.name,
                            ),
                        )
                    }
            }

        return result
    }

    fun resolveManga(ref: MangaRef): DocumentNode? {
        val downloads = runCatching { downloadsRoot() }.getOrNull() ?: return null
        val sourceDir = findChild(downloads.uri, ref.sourceName, directoryOnly = true) ?: return null
        return findChild(sourceDir.uri, ref.mangaName, directoryOnly = true)
    }

    fun listChapters(ref: MangaRef): List<DownloadedChapter> {
        val mangaDir = resolveManga(ref) ?: return emptyList()

        return listChildren(mangaDir.uri)
            .filter { node ->
                node.isDirectory ||
                    node.name.endsWith(".cbz", ignoreCase = true) ||
                    node.name.endsWith(".zip", ignoreCase = true)
            }
            .sortedWith { left, right -> naturalCompare(right.name, left.name) }
            .map { node ->
                DownloadedChapter(
                    ref = ChapterRef(ref, node.name),
                    displayName = chapterDisplayName(node.name),
                    lastModified = node.lastModified,
                    size = node.size,
                    isDirectory = node.isDirectory,
                )
            }
    }

    fun resolveChapter(ref: ChapterRef): DocumentNode? {
        val mangaDir = resolveManga(ref.manga) ?: return null
        return findChild(mangaDir.uri, ref.documentName)
    }

    fun listImagesInDirectory(directoryUri: Uri): List<DocumentNode> = listChildren(directoryUri)
        .filter { !it.isDirectory && isImageName(it.name) }
        .sortedWith { left, right -> naturalCompare(left.name, right.name) }

    fun openInputStream(uri: Uri): InputStream = resolver.openInputStream(uri)
        ?: error("Unable to open document: $uri")

    fun copyToFile(uri: Uri, destination: File) {
        destination.parentFile?.mkdirs()

        openInputStream(uri).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }

    fun storageTreeUri(): Uri {
        val prefs = context.getSharedPreferences(
            "${context.packageName}_preferences",
            Context.MODE_PRIVATE,
        )

        val raw = sequenceOf(
            prefs.getString(STORAGE_KEY, null),
            prefs.getString(LEGACY_STORAGE_KEY, null),
            prefs.all.entries
                .firstOrNull { (key, value) ->
                    key.endsWith("storage_dir") &&
                        value is String &&
                        value.startsWith("content://")
                }
                ?.value as? String,
        ).firstOrNull { !it.isNullOrBlank() }
            ?: error(
                "Mihon storage location was not found in default preferences. " +
                    "Open Mihon > Settings > Data and storage and select a storage location first.",
            )

        return Uri.parse(raw)
    }

    private fun downloadsRoot(): DocumentNode {
        val treeUri = storageTreeUri()
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

        return findChild(rootDocumentUri, DOWNLOADS_DIR, directoryOnly = true)
            ?: error("Mihon downloads directory was not found under the configured storage location.")
    }

    private fun findChild(
        parentUri: Uri,
        name: String,
        directoryOnly: Boolean = false,
    ): DocumentNode? = listChildren(parentUri).firstOrNull {
        it.name == name && (!directoryOnly || it.isDirectory)
    }

    private fun listChildren(parentUri: Uri): List<DocumentNode> {
        val treeUri = storageTreeUri()
        val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentUri,
            parentDocumentId,
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val result = mutableListOf<DocumentNode>()

        resolver.query(
            childrenUri,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

                result += DocumentNode(
                    uri = childUri,
                    name = cursor.getString(nameIndex).orEmpty(),
                    mimeType = cursor.getString(mimeIndex).orEmpty(),
                    size = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex),
                    lastModified = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex),
                )
            }
        }

        return result
    }

    companion object {
        private const val STORAGE_KEY = "__APP_STATE_storage_dir"
        private const val LEGACY_STORAGE_KEY = "storage_dir"
        private const val DOWNLOADS_DIR = "downloads"

        private val HASH_SUFFIX = Regex("""\s+\([0-9a-fA-F]{6,32}\)$""")

        fun isImageName(name: String): Boolean {
            val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return extension in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif")
        }

        fun imageMediaType(name: String): String = when (
            name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        ) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif" -> "image/avif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> "application/octet-stream"
        }

        private fun chapterDisplayName(fileName: String): String {
            val withoutExtension = fileName
                .removeSuffix(".cbz")
                .removeSuffix(".CBZ")
                .removeSuffix(".zip")
                .removeSuffix(".ZIP")

            return withoutExtension.replace(HASH_SUFFIX, "")
        }
    }
}
