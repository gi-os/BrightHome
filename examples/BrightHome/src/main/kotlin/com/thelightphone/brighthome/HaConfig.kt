package com.thelightphone.brighthome

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Everything the tool needs to talk to one Home Assistant instance.
 *
 * [baseUrl] is always https. LightOS tools get their manifest generated from
 * lighttool.toml (see ManifestGenerator in the SDK plugin) and that manifest carries no
 * android:usesCleartextTraffic and no networkSecurityConfig, so on API 34 a plain
 * http:// call is refused by the platform before it reaches the network. A LAN address
 * therefore cannot work here — the instance has to be reachable over TLS, which is what
 * the Cloudflare tunnel is for.
 */
@Serializable
data class HaConfig(
    val baseUrl: String,
    val token: String,
    val cfClientId: String? = null,
    val cfClientSecret: String? = null,
) {
    /** wss://host/api/websocket */
    val webSocketUrl: String
        get() = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/api/websocket"

    /** Host only — safe to render. The token and the CF secret never are. */
    val displayHost: String
        get() = baseUrl.substringAfter("://").substringBefore('/')

    val usesAccess: Boolean
        get() = !cfClientId.isNullOrBlank() && !cfClientSecret.isNullOrBlank()
}

sealed interface PairingResult {
    data class Ok(val config: HaConfig) : PairingResult
    data class Invalid(val reason: String) : PairingResult
}

/**
 * Parses the pairing QR produced by docs/pair.html.
 *
 * Long-lived access tokens run to ~180 characters, which is not a thing anyone should
 * type on this keyboard, so the whole credential set travels as one scanned payload:
 *
 *     {"url":"https://ha.example.com","ha":"<token>","cfid":"...","cfsec":"..."}
 *
 * Long key names are accepted too so a hand-written payload works.
 */
object PairingPayload {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): PairingResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return PairingResult.Invalid("Empty code.")
        if (!trimmed.startsWith("{")) {
            return PairingResult.Invalid("That is not a BrightHome setup code.")
        }

        val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            ?: return PairingResult.Invalid("That code is not readable.")

        fun field(vararg names: String): String? {
            for (name in names) {
                val value = obj[name]?.jsonPrimitive?.contentOrNullSafe()?.trim()
                if (!value.isNullOrEmpty()) return value
            }
            return null
        }

        val url = field("url", "baseUrl", "base_url", "host")
            ?: return PairingResult.Invalid("The code has no Home Assistant address.")
        val token = field("ha", "token", "access_token", "accessToken")
            ?: return PairingResult.Invalid("The code has no access token.")

        return build(
            url = url,
            token = token,
            cfId = field("cfid", "cf_client_id", "cfClientId"),
            cfSecret = field("cfsec", "cf_client_secret", "cfClientSecret"),
        )
    }

    /** Shared by the QR path and the manual-entry fallback. */
    fun build(url: String, token: String, cfId: String?, cfSecret: String?): PairingResult {
        val normalized = normalizeUrl(url) ?: return PairingResult.Invalid(
            "The address must start with https:// — this phone refuses plain http.",
        )
        if (token.isBlank()) return PairingResult.Invalid("The access token is empty.")

        val id = cfId?.trim().orEmptyToNull()
        val secret = cfSecret?.trim().orEmptyToNull()
        if ((id == null) != (secret == null)) {
            return PairingResult.Invalid(
                "Cloudflare Access needs both the client ID and the client secret.",
            )
        }

        return PairingResult.Ok(
            HaConfig(
                baseUrl = normalized,
                token = token.trim(),
                cfClientId = id,
                cfClientSecret = secret,
            ),
        )
    }

    /**
     * Accepts "ha.example.com" and upgrades it, accepts https:// as-is, refuses http://
     * outright rather than letting it fail later as an opaque network error.
     */
    fun normalizeUrl(raw: String): String? {
        var value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return null
        if (value.startsWith("http://", ignoreCase = true)) return null
        if (!value.startsWith("https://", ignoreCase = true)) value = "https://$value"
        val host = value.removePrefix("https://").substringBefore('/')
        if (host.isEmpty() || !host.contains('.')) return null
        return value
    }

    private fun String?.orEmptyToNull(): String? = if (isNullOrEmpty()) null else this
}

/** jsonPrimitive.contentOrNull without pulling in the whole JsonNull dance at each call site. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

internal object ConfigCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(config: HaConfig): String = json.encodeToString(config)
    fun decode(raw: String): HaConfig? =
        runCatching { json.decodeFromString<HaConfig>(raw) }.getOrNull()
}

@Serializable
internal data class StoredFavorites(
    @SerialName("ids") val ids: List<String> = emptyList(),
)
