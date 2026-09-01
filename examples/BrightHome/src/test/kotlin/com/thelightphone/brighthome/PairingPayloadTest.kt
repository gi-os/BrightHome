package com.thelightphone.brighthome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingPayloadTest {

    private fun ok(raw: String): HaConfig {
        val result = PairingPayload.parse(raw)
        assertTrue(result is PairingResult.Ok, "expected Ok, got $result")
        return result.config
    }

    private fun invalid(raw: String): String {
        val result = PairingPayload.parse(raw)
        assertTrue(result is PairingResult.Invalid, "expected Invalid, got $result")
        return result.reason
    }

    @Test
    fun `parses the short payload the generator writes`() {
        val config = ok(
            """{"url":"https://ha.example.com","ha":"token123","cfid":"id","cfsec":"secret"}""",
        )
        assertEquals("https://ha.example.com", config.baseUrl)
        assertEquals("token123", config.token)
        assertEquals("id", config.cfClientId)
        assertEquals("secret", config.cfClientSecret)
        assertTrue(config.usesAccess)
    }

    @Test
    fun `accepts the long key names a hand-written payload would use`() {
        val config = ok(
            """{"baseUrl":"https://ha.example.com","access_token":"t"}""",
        )
        assertEquals("https://ha.example.com", config.baseUrl)
        assertEquals("t", config.token)
        assertNull(config.cfClientId)
    }

    @Test
    fun `an open tunnel needs no Cloudflare fields`() {
        val config = ok("""{"url":"https://ha.example.com","ha":"t"}""")
        assertTrue(!config.usesAccess)
    }

    @Test
    fun `a trailing slash never doubles up in a request path`() {
        val config = ok("""{"url":"https://ha.example.com/","ha":"t"}""")
        assertEquals("https://ha.example.com", config.baseUrl)
    }

    @Test
    fun `a bare host is upgraded to https`() {
        val config = ok("""{"url":"ha.example.com","ha":"t"}""")
        assertEquals("https://ha.example.com", config.baseUrl)
    }

    @Test
    fun `plain http is refused rather than left to fail as a network error`() {
        // The generated manifest carries no usesCleartextTraffic, so a http:// base URL
        // is blocked by the platform. Saying so here beats an opaque failure later.
        val reason = invalid("""{"url":"http://192.168.68.42:8123","ha":"t"}""")
        assertTrue(reason.contains("https"), reason)
    }

    @Test
    fun `half a service token is refused`() {
        val result = PairingPayload.build("https://ha.example.com", "t", "id", null)
        assertTrue(result is PairingResult.Invalid)
        assertTrue(result.reason.contains("client secret"), result.reason)
    }

    @Test
    fun `a missing token is named specifically`() {
        assertTrue(invalid("""{"url":"https://ha.example.com"}""").contains("access token"))
    }

    @Test
    fun `a non-JSON scan is rejected without throwing`() {
        assertTrue(invalid("https://ha.example.com").isNotEmpty())
        assertTrue(invalid("").isNotEmpty())
        assertTrue(invalid("{not json").isNotEmpty())
    }

    @Test
    fun `the websocket url swaps the scheme and keeps the host`() {
        val config = ok("""{"url":"https://ha.example.com","ha":"t"}""")
        assertEquals("wss://ha.example.com/api/websocket", config.webSocketUrl)
        assertEquals("ha.example.com", config.displayHost)
    }
}
