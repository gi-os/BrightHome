package com.thelightphone.brighthome

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Both halves of the connection — REST for the snapshot and the service calls, the
 * WebSocket for live updates — hang off one HttpClient so the three auth headers are
 * declared exactly once.
 *
 * Cloudflare Access cannot run its interactive login against a Kotlin HTTP client, so
 * when Access is in front of the tunnel the request carries a service token
 * (CF-Access-Client-Id / CF-Access-Client-Secret) alongside the Home Assistant bearer.
 * Access authorises the request at the edge; Home Assistant authorises it at the origin.
 */
class HaClient(private val config: HaConfig) {

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val http: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(this@HaClient.json) }
        install(WebSockets)
        install(HttpTimeout) {
            // A tunnelled request leaves the building, so these are edge round trips,
            // not LAN ones. Long enough to survive a cold Cloudflare edge, short enough
            // that a dead instance does not hang a tap.
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        defaultRequest {
            header("Authorization", "Bearer ${config.token}")
            if (config.usesAccess) {
                header("CF-Access-Client-Id", config.cfClientId)
                header("CF-Access-Client-Secret", config.cfClientSecret)
            }
        }
    }

    /** GET /api/ — cheap reachability probe that also proves both credentials work. */
    suspend fun ping(): Result<Unit> = runCatching {
        val response = http.get("${config.baseUrl}/api/")
        if (!response.status.isSuccess()) {
            throw HaException(describe(response.status.value, response.bodyAsText()))
        }
    }

    /** GET /api/states — the full snapshot the UI opens on. */
    suspend fun states(): Result<List<HaState>> = runCatching {
        val response = http.get("${config.baseUrl}/api/states")
        if (!response.status.isSuccess()) {
            throw HaException(describe(response.status.value, response.bodyAsText()))
        }
        json.decodeFromString<List<HaState>>(response.bodyAsText())
    }

    /** POST /api/services/<domain>/<service> with {"entity_id": ...}. */
    suspend fun callService(call: ServiceCall): Result<Unit> = runCatching {
        val response = http.post(
            "${config.baseUrl}/api/services/${call.domain}/${call.service}",
        ) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(call.body()))
        }
        if (!response.status.isSuccess()) {
            throw HaException(describe(response.status.value, response.bodyAsText()))
        }
    }

    fun close() {
        http.close()
    }

    /**
     * A 403 from Access and a 401 from Home Assistant are the two failures worth telling
     * apart — one means the service token, the other means the long-lived token, and
     * "request failed" sends you to re-scan the wrong credential.
     */
    private fun describe(status: Int, body: String): String = when (status) {
        401 -> "Home Assistant rejected the access token."
        403 ->
            if (config.usesAccess) "Cloudflare Access rejected the service token."
            else "Forbidden — is Cloudflare Access in front of this tunnel?"

        404 -> "No Home Assistant API at ${config.displayHost}."
        502, 503, 504 -> "The tunnel is up but Home Assistant did not answer."
        else -> "HTTP $status — ${body.take(120)}"
    }
}

class HaException(message: String) : Exception(message)
