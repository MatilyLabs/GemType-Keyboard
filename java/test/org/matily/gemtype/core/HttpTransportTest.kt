// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class HttpTransportTest {
    private lateinit var server: HttpServer
    private val seen = ArrayList<String>()

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/models/ok:generateContent") { ex ->
            seen.add(ex.requestHeaders.getFirst("x-goog-api-key") + "|" + ex.requestMethod)
            val body = "{\"hello\":true}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/models/slow:generateContent") { ex ->
            Thread.sleep(5_000)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.createContext("/models/bad:generateContent") { ex ->
            val body = "{\"error\":{\"message\":\"nope\"}}".toByteArray()
            ex.sendResponseHeaders(400, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun transport(readTimeoutMs: Int = 30_000, deadlineMs: Long = 45_000) =
        HttpTransport("http://127.0.0.1:${server.address.port}/models", readTimeoutMs = readTimeoutMs, deadlineMs = deadlineMs)

    @Test fun postsWithTheKeyHeaderAndReturnsTheBody() = runBlocking {
        val result = transport().post("ok", " secret ", "{}")
        assertEquals(200, result.status)
        assertEquals("{\"hello\":true}", result.body)
        assertEquals(listOf("secret|POST"), seen)
    }

    @Test fun errorBodiesAreReturnedToo() = runBlocking {
        val result = transport().post("bad", "k", "{}")
        assertEquals(400, result.status)
        assertTrue(result.body.contains("nope"))
    }

    @Test fun cancellingReturnsPromptlyEvenWhileTheServerIsSilent() = runBlocking {
        val t = transport()
        val start = System.nanoTime()
        val call = async { t.post("slow", "k", "{}") }
        delay(300)
        call.cancel()
        try {
            call.await()
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue("took too long", (System.nanoTime() - start) / 1_000_000 < 3_000)
    }

    @Test fun aSilentServerBecomesATimeout() = runBlocking {
        try {
            transport(readTimeoutMs = 300).post("slow", "k", "{}")
            fail("expected a timeout")
        } catch (e: GeminiException) {
            assertEquals(GeminiException.Kind.TIMEOUT, e.kind)
        }
    }

    @Test fun theWholeExchangeHasADeadlineEvenWithALongReadTimeout() = runBlocking {
        val start = System.nanoTime()
        try {
            transport(readTimeoutMs = 30_000, deadlineMs = 400).post("slow", "k", "{}")
            fail("expected a timeout")
        } catch (e: GeminiException) {
            assertEquals(GeminiException.Kind.TIMEOUT, e.kind)
        }
        assertTrue("took too long", (System.nanoTime() - start) / 1_000_000 < 3_000)
    }

    @Test fun anUnreachableServerIsANetworkError() = runBlocking {
        server.stop(0)
        try {
            transport().post("ok", "k", "{}")
            fail("expected a network error")
        } catch (e: GeminiException) {
            assertEquals(GeminiException.Kind.NETWORK, e.kind)
        }
    }
}
