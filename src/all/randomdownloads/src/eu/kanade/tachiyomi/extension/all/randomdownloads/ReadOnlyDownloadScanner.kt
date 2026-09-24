package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import keiyoushi.utils.applicationContext
import keiyoushi.zip.Entry
import keiyoushi.zip.fixedLength
import keiyoushi.zip.readZipDirectory
import keiyoushi.zip.readZipEntry
import okio.BufferedSource
import okio.Source
import okio.buffer
import okio.source
import java.io.InputStream

internal class ReadOnlyDownloadScanner(
    private val context: Context = applicationContext,
) {

    private val resolver
        get() = context.contentResolver

    fun downloadsRoot(): DocumentNode {
        resolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .mapNotNull { permission ->
                val treeUri = permission.uri
                runCatching {
                    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
                }.getOrNull()
            }
            .forEach { rootUri ->
                val rootName = documentName(rootUri)
                if (rootName.equals(DOWNLOADS_DIR, ignoreCase = true)) {
                    return DocumentNode(
                        uri = rootUri,
                        name = rootName,
                        isDirectory = true,
                    )
                }

                findDirectory(rootUri, DOWNLOADS_DIR)?.let { return it }
            }

        error("Mihon downloads directory was not found in persisted storage permissions.")
    }

    fun listDirectories(parentUri: Uri): List<DocumentNode> = listChildren(parentUri).filter { it.isDirectory }

    fun findDirectory(
        parentUri: Uri,
        name: String,
    ): DocumentNode? = listDirectories(parentUri).firstOrNull { it.name == name }

    fun listChildren(parentUri: Uri): List<DocumentNode> {
        val documentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentUri,
            documentId,
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
                    val childDocumentId = cursor.getString(idIndex)
                    val mimeType = cursor.getString(mimeIndex).orEmpty()

                    add(
                        DocumentNode(
                            uri = DocumentsContract.buildDocumentUriUsingTree(
                                parentUri,
                                childDocumentId,
                            ),
                            name = cursor.getString(nameIndex).orEmpty(),
                            isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
        }
    }

    fun childDocumentUri(
        parentUri: Uri,
        childName: String,
    ): Uri {
        val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
        val childDocumentId = "$parentDocumentId/$childName"
        return DocumentsContract.buildDocumentUriUsingTree(
            parentUri,
            childDocumentId,
        )
    }

    fun documentName(uri: Uri): String = runCatching {
        DocumentsContract.getDocumentId(uri)
    }.getOrElse {
        DocumentsContract.getTreeDocumentId(uri)
    }.substringAfterLast('/')

    fun listImages(directoryUri: Uri): List<DocumentNode> = listChildren(directoryUri)
        .filter { !it.isDirectory && isImageName(it.name) }
        .sortedWith { left, right -> naturalCompare(left.name, right.name) }

    fun listArchiveImageEntries(archiveUri: Uri): List<ArchiveImageEntry> {
        val directory = readZipDirectory(
            totalSize = archiveSize(archiveUri),
            fetch = { range -> rangeSource(archiveUri, range) },
        )

        return directory.entries
            .asSequence()
            .filter { isImageName(it.name) }
            .sortedWith { left, right -> naturalCompare(left.name, right.name) }
            .map { entry ->
                ArchiveImageEntry(
                    archiveUri = archiveUri,
                    name = entry.name,
                    method = entry.method,
                    compressedSize = entry.compressedSize,
                    localHeaderOffset = entry.localHeaderOffset,
                )
            }
            .toList()
    }

    fun openArchiveEntry(entry: ArchiveImageEntry): Source = readZipEntry(
        entry = Entry(
            name = entry.name,
            method = entry.method,
            compressedSize = entry.compressedSize,
            localHeaderOffset = entry.localHeaderOffset,
        ),
        fetch = { range -> rangeSource(entry.archiveUri, range) },
    )

    fun openInputStream(uri: Uri): InputStream = resolver.openInputStream(uri)
        ?: error("Unable to open document: $uri")

    fun openInputStreamOrNull(uri: Uri): InputStream? = runCatching {
        resolver.openInputStream(uri)
    }.getOrNull()

    fun openFileDescriptor(uri: Uri): ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r")
        ?: error("Unable to open file descriptor: $uri")

    private fun archiveSize(uri: Uri): Long = openFileDescriptor(uri).use { descriptor ->
        descriptor.statSize.takeIf { it >= 0L }
            ?: error("Unable to determine archive size: $uri")
    }

    private fun rangeSource(
        uri: Uri,
        range: LongRange,
    ): BufferedSource {
        require(!range.isEmpty()) { "Empty archive range" }

        val input = ParcelFileDescriptor.AutoCloseInputStream(
            openFileDescriptor(uri),
        )
        input.channel.position(range.first)

        val byteCount = range.last - range.first + 1L
        return input
            .source()
            .fixedLength(byteCount)
            .buffer()
    }

    companion object {
        private const val DOWNLOADS_DIR = "downloads"
    }
}
