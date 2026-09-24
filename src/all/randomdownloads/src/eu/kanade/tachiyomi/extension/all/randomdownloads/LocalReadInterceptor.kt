package eu.kanade.tachiyomi.extension.all.randomdownloads

import android.net.Uri
import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.Source
import okio.buffer
import okio.source

internal class LocalReadInterceptor(
    private val repository: LocalDownloadRepository,
) : Interceptor {

    private val scanner
        get() = repository.scanner()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.host != LOCAL_ASSET_HOST) {
            return chain.proceed(request)
        }

        val segments = url.pathSegments

        return runCatching {
            when {
                segments.size == 3 && segments[0] == "file" -> {
                    val uri = Uri.parse(decodePart(segments[1]))
                    val name = decodePart(segments[2])

                    streamResponse(
                        chain = chain,
                        source = scanner.openInputStream(uri).source(),
                        name = name,
                    )
                }

                segments.size == 6 && segments[0] == "archive" -> {
                    val entry = parseArchivePageSegments(segments)
                        ?: return@runCatching notFound(chain)

                    streamResponse(
                        chain = chain,
                        source = scanner.openArchiveEntry(entry),
                        name = entry.name,
                    )
                }

                segments.size == 3 &&
                    (segments[0] == "cover" || segments[0] == "cover-v2") -> {
                    val ref = MangaRef(
                        sourceName = decodePart(segments[1]),
                        mangaName = decodePart(segments[2]),
                    )

                    when (val target = repository.coverTarget(ref)) {
                        is LocalImageTarget.File -> {
                            Log.d(
                                TAG,
                                "Cover file: ${ref.sourceName}/${ref.mangaName} -> ${target.name}",
                            )
                            streamResponse(
                                chain = chain,
                                source = scanner.openInputStream(target.uri).source(),
                                name = target.name,
                            )
                        }

                        is LocalImageTarget.ArchiveEntry -> {
                            Log.d(
                                TAG,
                                "Cover archive fallback: ${ref.sourceName}/${ref.mangaName} -> ${target.entry.name}",
                            )
                            streamResponse(
                                chain = chain,
                                source = scanner.openArchiveEntry(target.entry),
                                name = target.entry.name,
                            )
                        }

                        null -> notFound(chain)
                    }
                }

                else -> notFound(chain)
            }
        }.getOrElse { error ->
            errorResponse(chain, error)
        }
    }

    private fun streamResponse(
        chain: Interceptor.Chain,
        source: Source,
        name: String,
    ): Response {
        val mediaType = imageMediaType(name)

        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", mediaType)
            .body(SourceResponseBody(source, mediaType))
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
    ): Response {
        Log.w(TAG, "Local image request failed: ${chain.request().url}", error)

        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(500)
            .message(error.message ?: "Local read failed")
            .body(ResponseBody.EMPTY)
            .build()
    }

    private class SourceResponseBody(
        source: Source,
        mediaType: String,
    ) : ResponseBody() {
        private val type = mediaType.toMediaTypeOrNull()
        private val buffered = source.buffer()

        override fun contentType() = type

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = buffered
    }

    companion object {
        private const val TAG = "RandomDownloads"
    }
}
