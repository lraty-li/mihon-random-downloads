package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import keiyoushi.utils.applicationContext

internal class ReadOnlyDownloadScanner(
    private val context: Context = applicationContext,
) {

    private val resolver
        get() = context.contentResolver

    fun scan(): DownloadDirectoryIndex {
        val treeUri = storageTreeUri()
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

        val downloads = findDirectory(
            treeUri = treeUri,
            parentUri = rootDocumentUri,
            name = DOWNLOADS_DIR,
        ) ?: error("Mihon downloads directory was not found.")

        val mangaDirsBySource = listDirectories(treeUri, downloads.uri)
            .associate { sourceDir ->
                sourceDir.name.lowercase() to
                    listDirectories(treeUri, sourceDir.uri)
                        .mapTo(linkedSetOf()) { it.name }
            }

        return DownloadDirectoryIndex(mangaDirsBySource)
    }

    private fun storageTreeUri(): Uri {
        val prefs = context.getSharedPreferences(
            "${context.packageName}_preferences",
            Context.MODE_PRIVATE,
        )

        val raw = sequenceOf(
            prefs.getString(STORAGE_KEY, null),
            prefs.getString(LEGACY_STORAGE_KEY, null),
            prefs.all
                .entries
                .firstOrNull { entry ->
                    entry.key.endsWith("storage_dir") &&
                        entry.value is String &&
                        (entry.value as String).startsWith("content://")
                }
                ?.value as? String,
        ).firstOrNull { !it.isNullOrBlank() }
            ?: error("Mihon storage location is not configured.")

        return Uri.parse(raw)
    }

    private fun findDirectory(
        treeUri: Uri,
        parentUri: Uri,
        name: String,
    ): DocumentDirectory? = listDirectories(treeUri, parentUri)
        .firstOrNull { it.name == name }

    private fun listDirectories(
        treeUri: Uri,
        parentUri: Uri,
    ): List<DocumentDirectory> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parentUri),
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        return buildList {
            resolver.query(
                childrenUri,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                )
                val nameIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
                val mimeIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )

                while (cursor.moveToNext()) {
                    if (
                        cursor.getString(mimeIndex) !=
                        DocumentsContract.Document.MIME_TYPE_DIR
                    ) {
                        continue
                    }

                    val documentId = cursor.getString(idIndex)
                    add(
                        DocumentDirectory(
                            uri = DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                documentId,
                            ),
                            name = cursor.getString(nameIndex).orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    private data class DocumentDirectory(
        val uri: Uri,
        val name: String,
    )

    companion object {
        private const val STORAGE_KEY = "__APP_STATE_storage_dir"
        private const val LEGACY_STORAGE_KEY = "storage_dir"
        private const val DOWNLOADS_DIR = "downloads"
    }
}
