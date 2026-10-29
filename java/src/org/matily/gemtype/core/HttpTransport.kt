// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** One HTTP answer: status code and the whole body. */
class HttpResult(val status: Int, val body: String)

/** What [GeminiClient] needs from the network, so tests can fake it. */
fun interface Transport {
    /** POST [body] to the generateContent endpoint of [model]. Throws [GeminiException] on network failure. */
    suspend fun post(model: String, apiKey: String, body: String): HttpResult
}

/**
 * HttpURLConnection transport. The body is always read to the end and its stream
 * closed, and the connection is not disconnect()ed afterwards, so the platform
 * keeps the TLS connection alive for the next request (a fix that follows a
 * chip tap skips the handshake). Only an aborted exchange or a cancelled
 * request disconnects.
 */
class HttpTransport(
    private val apiBase: String,
    private val connectTimeoutMs: Int = 15_000,
    // A fix should take a second or two; past this the model is struggling, and the
    // user is better off retrying or switching.
    private val readTimeoutMs: Int = 30_000,
) : Transport {

    override suspend fun post(model: String, apiKey: String, body: String): HttpResult = coroutineScope {
        val conn = try {
            URL("$apiBase/${URLEncoder.encode(model, "UTF-8")}:generateContent").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw GeminiException(GeminiException.Kind.NETWORK, e.message)
        }
        val aborted = AtomicBoolean(false)
        val exchange = async(Dispatchers.IO) { exchange(conn, apiKey, body, aborted) }
        try {
            exchange.await()
        } catch (e: CancellationException) {
            // The blocking read can't be interrupted; closing the socket unblocks it.
            aborted.set(true)
            conn.disconnect()
            throw e
        }
    }

    private fun exchange(
        conn: HttpURLConnection, apiKey: String, body: String, aborted: AtomicBoolean,
    ): HttpResult {
        var complete = false
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey.trim())
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            complete = true
            return HttpResult(status, text)
        } catch (e: IOException) {
            // The failure of a request we cancelled ourselves is not an error to report.
            if (aborted.get()) throw CancellationException("request cancelled")
            if (e is SocketTimeoutException) throw GeminiException(GeminiException.Kind.TIMEOUT, e.message)
            throw GeminiException(GeminiException.Kind.NETWORK, e.message)
        } finally {
            if (!complete) conn.disconnect()
        }
    }
}
