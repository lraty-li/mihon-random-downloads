package eu.kanade.tachiyomi.extension.all.randomdownloads

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.File

internal class LocalAssetInterceptor(
    private val chapterCache: ChapterCache,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.host != LOCAL_ASSET_HOST) {
            return chain.proceed(request)
        }

        val segments = url.pathSegments

        return when {
            segments.size == 4 &&
                segments[0] == "asset" &&
                segments[1] == "cover" -> {
                val ref = runCatching {
                    MangaRef(
                        sourceName = decodePathPart(segments[2]),
                        mangaName = decodePathPart(segments[3]),
                    )
                }.getOrNull()

                val file = ref?.let { mangaRef ->
                    runCatching { chapterCache.prepareCover(mangaRef) }.getOrNull()
                }
                if (file != null) {
                    fileResponse(chain, file)
                } else {
                    notFound(chain)
                }
            }

            segments.size == 6 &&
                segments[0] == "asset" &&
                segments[1] == "page" -> {
                val parsed = runCatching {
                    val chapterRef = ChapterRef(
                        manga = MangaRef(
                            sourceName = decodePathPart(segments[2]),
                            mangaName = decodePathPart(segments[3]),
                        ),
                        documentName = decodePathPart(segments[4]),
                    )
                    chapterRef to decodePathPart(segments[5])
                }.getOrNull()

                val file = parsed?.let { (chapterRef, fileName) ->
                    chapterCache.pageFile(
                        chapterKey = chapterRef.cacheKey,
                        fileName = fileName,
                    ) ?: runCatching {
                        chapterCache.prepareChapter(chapterRef)
                        chapterCache.pageFile(
                            chapterKey = chapterRef.cacheKey,
                            fileName = fileName,
                        )
                    }.getOrNull()
                }

                if (file != null) {
                    fileResponse(chain, file)
                } else {
                    notFound(chain)
                }
            }

            else -> notFound(chain)
        }
    }

    private fun fileResponse(
        chain: Interceptor.Chain,
        file: File,
    ): Response {
        val mediaType = MihonStorage.imageMediaType(file.name)

        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", mediaType)
            .header("Content-Length", file.length().toString())
            .body(FileResponseBody(file, mediaType))
            .build()
    }

    private fun notFound(chain: Interceptor.Chain): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(404)
        .message("Not Found")
        .body(
            "Local Random Downloads asset not found"
                .toResponseBody("text/plain".toMediaTypeOrNull()),
        )
        .build()

    private class FileResponseBody(
        private val file: File,
        mediaType: String,
    ) : ResponseBody() {
        private val type = mediaType.toMediaTypeOrNull()

        override fun contentType() = type

        override fun contentLength(): Long = file.length()

        override fun source(): BufferedSource = file.source().buffer()
    }
}
