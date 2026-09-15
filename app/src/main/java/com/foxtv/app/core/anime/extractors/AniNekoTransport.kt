package com.foxtv.app.core.anime.extractors

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Maximum decoded HTML or manifest response size retained by the AniNeko transport. */
internal const val ANI_NEKO_MAX_RESPONSE_BODY_BYTES: Long = 2L * 1024L * 1024L

/** Injectable, resource-closing network boundary for AniNeko extraction. */
internal fun interface AniNekoTransport {
    suspend fun get(request: Request): AniNekoHttpResponse
}

/** Resource-independent response snapshot. The underlying OkHttp response is already closed. */
internal data class AniNekoHttpResponse(
    val code: Int,
    val finalUrl: HttpUrl,
    val contentType: MediaType?,
    val body: String,
)

/** Failures emitted by [OkHttpAniNekoTransport] contain only a query-free diagnostic URL. */
internal sealed class AniNekoTransportException(
    open val safeUrl: String,
    message: String,
) : IOException(message) {

    internal class Network(
        override val safeUrl: String,
    ) : AniNekoTransportException(
        safeUrl = safeUrl,
        message = "AniNeko request failed for $safeUrl",
    )

    internal class ResponseBodyTooLarge(
        override val safeUrl: String,
        val limitBytes: Long,
        val observedBytes: Long,
    ) : AniNekoTransportException(
        safeUrl = safeUrl,
        message = "AniNeko response body exceeds the $limitBytes-byte limit for $safeUrl",
    )
}

/**
 * Adapts the configured OkHttp client without replacing its redirect, cookie, TLS, or timeout policy.
 * A callback has one atomic owner, while coroutine cancellation always cancels the underlying call.
 */
internal class OkHttpAniNekoTransport(
    private val client: OkHttpClient,
    private val maxResponseBodyBytes: Long = ANI_NEKO_MAX_RESPONSE_BODY_BYTES,
) : AniNekoTransport {

    init {
        require(maxResponseBodyBytes in 1L..Int.MAX_VALUE.toLong()) {
            "AniNeko response body limit must be between 1 and ${Int.MAX_VALUE} bytes"
        }
    }

    override suspend fun get(request: Request): AniNekoHttpResponse {
        val safeUrl = request.url.toSafeAniNekoDiagnosticUrl()
        val call = try {
            client.newCall(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            throw AniNekoTransportException.Network(safeUrl)
        }

        return suspendCancellableCoroutine { continuation ->
            val callbackClaimed = AtomicBoolean(false)

            continuation.invokeOnCancellation {
                callbackClaimed.compareAndSet(false, true)
                call.cancel()
            }

            if (!continuation.isActive) {
                return@suspendCancellableCoroutine
            }

            val callback = object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!callbackClaimed.compareAndSet(false, true)) return
                    continuation.resumeWith(
                        Result.failure(AniNekoTransportException.Network(safeUrl)),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!callbackClaimed.compareAndSet(false, true)) {
                        response.close()
                        return
                    }

                    try {
                        val snapshot = response.use {
                            it.toAniNekoSnapshot(safeUrl, maxResponseBodyBytes)
                        }
                        continuation.resumeWith(Result.success(snapshot))
                    } catch (cancellation: CancellationException) {
                        continuation.cancel(cancellation)
                    } catch (error: AniNekoTransportException) {
                        continuation.resumeWith(Result.failure(error))
                    } catch (error: Exception) {
                        continuation.resumeWith(
                            Result.failure(AniNekoTransportException.Network(safeUrl)),
                        )
                    }
                }
            }

            try {
                call.enqueue(callback)
            } catch (cancellation: CancellationException) {
                if (callbackClaimed.compareAndSet(false, true)) {
                    continuation.cancel(cancellation)
                }
            } catch (error: Exception) {
                if (callbackClaimed.compareAndSet(false, true)) {
                    continuation.resumeWith(
                        Result.failure(AniNekoTransportException.Network(safeUrl)),
                    )
                }
            }
        }
    }
}

private fun Response.toAniNekoSnapshot(
    safeUrl: String,
    maxResponseBodyBytes: Long,
): AniNekoHttpResponse {
    val responseBody = body
    val contentType = responseBody.contentType()
    return AniNekoHttpResponse(
        code = code,
        finalUrl = request.url,
        contentType = contentType,
        body = responseBody.readAniNekoBody(safeUrl, maxResponseBodyBytes),
    )
}

private fun ResponseBody.readAniNekoBody(
    safeUrl: String,
    maxResponseBodyBytes: Long,
): String {
    val declaredBytes = contentLength()
    if (declaredBytes > maxResponseBodyBytes) {
        throw AniNekoTransportException.ResponseBodyTooLarge(
            safeUrl = safeUrl,
            limitBytes = maxResponseBodyBytes,
            observedBytes = declaredBytes,
        )
    }

    val buffer = Buffer()
    val source = source()
    var totalBytes = 0L
    while (true) {
        val bytesToRead = minOf(8_192L, maxResponseBodyBytes - totalBytes + 1L)
        val read = source.read(buffer, bytesToRead)
        if (read == -1L) break
        totalBytes += read
        if (totalBytes > maxResponseBodyBytes) {
            throw AniNekoTransportException.ResponseBodyTooLarge(
                safeUrl = safeUrl,
                limitBytes = maxResponseBodyBytes,
                observedBytes = totalBytes,
            )
        }
    }

    val charset = contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    return buffer.readString(charset)
}

private fun HttpUrl.toSafeAniNekoDiagnosticUrl(): String =
    newBuilder()
        .username("")
        .password("")
        .query(null)
        .fragment(null)
        .build()
        .toString()
