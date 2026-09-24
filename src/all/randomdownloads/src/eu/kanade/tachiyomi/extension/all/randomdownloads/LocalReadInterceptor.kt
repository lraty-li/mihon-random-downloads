package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.net.Uri
import android.os.ParcelFileDescriptor
import keiyoushi.utils.applicationContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream

internal class LocalReadInterceptor(
    private val scanner: ReadOnlyDownloadScanner,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.host != LOCAL_ASSET_HOST) {
            return chain.proceed(request)
        }

        val segments = url.pathSegments
        if (segments.size != 3) {
            return notFound(chain)
        }

        return runCatching {
            when (segments[0]) {
                "file" -> {
                    val uri = Uri.parse(decodePart(segments[1]))
                    val name = decodePart(segments[2])
                    val input = scanner.openInputStream(uri)
                    streamResponse(chain, input, name)
                }

                "archive" -> {
                    val uri = Uri.parse(decodePart(segments[1]))
                    val entryName = decodePart(segments[2])
                    val input = openArchiveEntry(uri, entryName)
                        ?: return@runCatching notFound(chain)
                    streamResponse(chain, input, entryName)
                }

                else -> notFound(chain)
            }
        }.getOrElse {
            errorResponse(chain, it)
        }
    }

    private fun openArchiveEntry(
        uri: Uri,
        entryName: String,
    ): InputStream? {
        val pfd = scanner.openFileDescriptor(uri)
        var reader: Any? = null

        try {
            val readerClass = Class.forName(
                ARCHIVE_READER_CLASS,
                true,
                applicationContext.classLoader,
            )

            reader = readerClass
                .getConstructor(ParcelFileDescriptor::class.java)
                .newInstance(pfd)

            val input = readerClass
                .getMethod("getInputStream", String::class.java)
                .invoke(reader, entryName) as? InputStream

            if (input == null) {
                (reader as? Closeable)?.close()
                pfd.close()
                return null
            }

            return ClosingInputStream(input) {
                (reader as? Closeable)?.close()
                pfd.close()
            }
        } catch (e: Throwable) {
            runCatching { (reader as? Closeable)?.close() }
            runCatching { pfd.close() }
            throw e
        }
    }

    private fun streamResponse(
        chain: Interceptor.Chain,
        input: InputStream,
        name: String,
    ): Response {
        val mediaType = imageMediaType(name)

        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", mediaType)
            .body(InputStreamResponseBody(input, mediaType))
            .build()
    }

    private fun notFound(chain: Interceptor.Chain): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(404)
        .message("Not Found")
        .body(ResponseBody.EMPTY)
        .build()

    private fun errorResponse(
        chain: Interceptor.Chain,
        error: Throwable,
    ): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(500)
        .message(error.javaClass.simpleName)
        .body(ResponseBody.EMPTY)
        .build()

    private class InputStreamResponseBody(
        input: InputStream,
        mediaType: String,
    ) : ResponseBody() {
        private val type = mediaType.toMediaTypeOrNull()
        private val buffered = input.source().buffer()

        override fun contentType() = type

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = buffered
    }

    private class ClosingInputStream(
        input: InputStream,
        private val onClose: () -> Unit,
    ) : FilterInputStream(input) {

        private var closed = false

        override fun close() {
            if (closed) return
            closed = true

            try {
                super.close()
            } finally {
                onClose()
            }
        }
    }

    companion object {
        private const val ARCHIVE_READER_CLASS = "mihon.core.archive.ArchiveReader"
    }
}
