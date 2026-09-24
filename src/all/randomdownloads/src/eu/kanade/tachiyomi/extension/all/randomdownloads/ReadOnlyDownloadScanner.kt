package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import keiyoushi.utils.applicationContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal class ReadOnlyDownloadScanner(
    private val context: Context = applicationContext,
) {

    private val resolver
        get() = context.contentResolver

    fun downloadsRoot(): DocumentNode {
        val treeUri = storageTreeUri()
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

        return findDirectory(rootDocumentUri, DOWNLOADS_DIR)
            ?: error("Mihon downloads directory was not found.")
    }

    fun listDirectories(parentUri: Uri): List<DocumentNode> = listChildren(parentUri).filter { it.isDirectory }

    fun findDirectory(
        parentUri: Uri,
        name: String,
    ): DocumentNode? = listDirectories(parentUri).firstOrNull { it.name == name }

    fun listChildren(parentUri: Uri): List<DocumentNode> {
        val treeUri = storageTreeUri()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentUri,
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
                    val documentId = cursor.getString(idIndex)
                    val mimeType = cursor.getString(mimeIndex).orEmpty()

                    add(
                        DocumentNode(
                            uri = DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                documentId,
                            ),
                            name = cursor.getString(nameIndex).orEmpty(),
                            isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
        }
    }

    fun listImages(directoryUri: Uri): List<DocumentNode> = listChildren(directoryUri)
        .filter { !it.isDirectory && isImageName(it.name) }
        .sortedWith { left, right -> naturalCompare(left.name, right.name) }

    fun listArchiveImageEntries(archiveUri: Uri): List<String> {
        val entries = mutableListOf<String>()

        openInputStream(archiveUri).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    try {
                        if (!entry.isDirectory && isImageName(entry.name)) {
                            entries += entry.name
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        }

        return entries.sortedWith(Comparator(::naturalCompare))
    }

    fun firstArchiveImageEntry(archiveUri: Uri): String? {
        openInputStream(archiveUri).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: return null
                    try {
                        if (!entry.isDirectory && isImageName(entry.name)) {
                            return entry.name
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    fun openInputStream(uri: Uri): InputStream = resolver.openInputStream(uri)
        ?: error("Unable to open document: $uri")

    fun openFileDescriptor(uri: Uri): ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r")
        ?: error("Unable to open file descriptor: $uri")

    private fun storageTreeUri(): Uri {
        val prefs = context.getSharedPreferences(
            "${context.packageName}_preferences",
            Context.MODE_PRIVATE,
        )

        val raw = sequenceOf(
            prefs.getString(STORAGE_KEY, null),
            prefs.getString(LEGACY_STORAGE_KEY, null),
            prefs.all.entries
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

    companion object {
        private const val STORAGE_KEY = "__APP_STATE_storage_dir"
        private const val LEGACY_STORAGE_KEY = "storage_dir"
        private const val DOWNLOADS_DIR = "downloads"
    }
}
