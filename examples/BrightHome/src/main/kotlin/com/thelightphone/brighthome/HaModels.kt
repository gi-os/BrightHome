package com.thelightphone.brighthome

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * One entity as Home Assistant reports it. Attributes stay as a JsonObject on purpose:
 * every integration invents its own, and a rigid schema would drop the one field that
 * makes a given row readable.
 */
@Serializable
data class HaState(
    @SerialName("entity_id") val entityId: String,
    val state: String = STATE_UNKNOWN,
    val attributes: JsonObject = JsonObject(emptyMap()),
) {
    val domain: String get() = entityId.substringBefore('.')

    val friendlyName: String
        get() = attributes.string("friendly_name")
            ?: entityId.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }

    val isUnavailable: Boolean
        get() = state == STATE_UNAVAILABLE || state == STATE_UNKNOWN

    companion object {
        const val STATE_UNAVAILABLE = "unavailable"
        const val STATE_UNKNOWN = "unknown"

        fun placeholder(entityId: String): HaState = HaState(entityId, STATE_UNAVAILABLE)
    }
}

internal fun JsonObject.string(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.content.takeIf { it.isNotBlank() }
}

internal fun JsonObject.number(key: String): Double? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.doubleOrNull
}

/** What kind of control a row offers. Decides the tap behaviour and the trailing glyph. */
enum class ControlKind {
    /** on/off, open/closed, locked/unlocked — tap flips it. */
    Toggle,

    /** scenes, scripts, buttons — tap fires it, there is no "off". */
    Momentary,

    /** sensors and anything unrecognised — tap does nothing. */
    ReadOnly,
}

/**
 * Domain behaviour in one place. Home Assistant's own `homeassistant.turn_on` /
 * `turn_off` work across most domains, which keeps this table short; the exceptions are
 * the domains whose "on" is not called on.
 */
object Domains {
    private val TOGGLEABLE = setOf(
        "light", "switch", "fan", "input_boolean", "automation", "humidifier", "siren",
        "remote", "media_player", "cover", "lock",
    )
    private val MOMENTARY = setOf("scene", "script", "button", "input_button")

    /** Domains worth offering in the favourites picker. Sensors are shown, never picked. */
    private val READABLE = setOf(
        "sensor", "binary_sensor", "climate", "weather", "person", "device_tracker",
        "sun", "update", "vacuum",
    )

    fun controlKind(domain: String): ControlKind = when (domain) {
        in MOMENTARY -> ControlKind.Momentary
        in TOGGLEABLE -> ControlKind.Toggle
        else -> ControlKind.ReadOnly
    }

    fun isInteresting(domain: String): Boolean =
        domain in TOGGLEABLE || domain in MOMENTARY || domain in READABLE

    /**
     * The "on" state is domain-specific. A cover is on when open, a lock when locked —
     * locked being the engaged state, so the filled toggle glyph means secured rather
     * than meaning "you can walk in".
     */
    fun isOn(state: HaState): Boolean = when (state.domain) {
        "cover" -> state.state == "open" || state.state == "opening"
        "lock" -> state.state == "locked"
        "media_player" -> state.state == "playing"
        "climate" -> state.state != "off" && !state.isUnavailable
        "vacuum" -> state.state == "cleaning"
        else -> state.state == "on"
    }

    /** The service that flips [state] to its opposite, or null when nothing can be done. */
    fun toggleService(state: HaState): ServiceCall? {
        val on = isOn(state)
        return when (state.domain) {
            "lock" -> ServiceCall("lock", if (on) "unlock" else "lock", state.entityId)
            "cover" -> ServiceCall(
                "cover",
                if (on) "close_cover" else "open_cover",
                state.entityId,
            )
            "media_player" -> ServiceCall("media_player", "media_play_pause", state.entityId)
            "scene" -> ServiceCall("scene", "turn_on", state.entityId)
            "script" -> ServiceCall("script", "turn_on", state.entityId)
            "button" -> ServiceCall("button", "press", state.entityId)
            "input_button" -> ServiceCall("input_button", "press", state.entityId)
            else -> when (controlKind(state.domain)) {
                ControlKind.Toggle -> ServiceCall(
                    "homeassistant",
                    if (on) "turn_off" else "turn_on",
                    state.entityId,
                )

                else -> null
            }
        }
    }

    /**
     * What the state will read as the moment the finger lands, before the round trip to
     * the tunnel and back. Momentary domains have nothing to predict.
     */
    fun optimisticState(state: HaState): String? = when (state.domain) {
        "lock" -> if (isOn(state)) "unlocked" else "locked"
        "cover" -> if (isOn(state)) "closing" else "opening"
        "media_player" -> if (isOn(state)) "paused" else "playing"
        else -> when (controlKind(state.domain)) {
            ControlKind.Toggle -> if (isOn(state)) "off" else "on"
            else -> null
        }
    }
}

data class ServiceCall(val domain: String, val service: String, val entityId: String) {
    fun body(): JsonObject = buildJsonObject {
        put("entity_id", JsonPrimitive(entityId))
    }
}

/**
 * The second line of a row. Plain state words are title-cased; anything with a unit or a
 * more useful attribute than its own state gets that instead.
 */
object StateSummary {
    fun secondaryLine(state: HaState): String {
        if (state.state == HaState.STATE_UNAVAILABLE) return "Unavailable"
        if (state.state == HaState.STATE_UNKNOWN) return "Unknown"

        state.attributes.string("unit_of_measurement")?.let { unit ->
            return "${state.state} $unit"
        }

        return when (state.domain) {
            "light" -> lightSummary(state)
            "climate" -> climateSummary(state)
            "media_player" -> mediaSummary(state)
            "cover" -> coverSummary(state)
            else -> humanize(state.state)
        }
    }

    private fun lightSummary(state: HaState): String {
        if (!Domains.isOn(state)) return "Off"
        val brightness = state.attributes.number("brightness") ?: return "On"
        val percent = ((brightness / 255.0) * 100).toInt().coerceIn(0, 100)
        return "On · $percent%"
    }

    private fun climateSummary(state: HaState): String {
        val current = state.attributes.number("current_temperature")
        val target = state.attributes.number("temperature")
        val mode = humanize(state.state)
        return when {
            current != null && target != null ->
                "$mode · ${current.trim1()}° → ${target.trim1()}°"

            current != null -> "$mode · ${current.trim1()}°"
            else -> mode
        }
    }

    private fun mediaSummary(state: HaState): String {
        val title = state.attributes.string("media_title") ?: return humanize(state.state)
        val artist = state.attributes.string("media_artist")
        return if (artist == null) title else "$title · $artist"
    }

    private fun coverSummary(state: HaState): String {
        val position = state.attributes.number("current_position")?.toInt()
        val base = humanize(state.state)
        return if (position != null && position in 1..99) "$base · $position%" else base
    }

    /** "heat_cool" reads badly on a 3.9" panel; "Heat cool" reads fine. */
    fun humanize(raw: String): String =
        raw.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun Double.trim1(): String {
        val rounded = (this * 10.0).roundToLong() / 10.0
        return if (rounded == floor(rounded)) rounded.toInt().toString()
        else rounded.toString()
    }
}

/** An area from config/area_registry/list. */
@Serializable
data class HaArea(
    @SerialName("area_id") val areaId: String,
    val name: String,
)

@Serializable
internal data class HaEntityRegistryEntry(
    @SerialName("entity_id") val entityId: String,
    @SerialName("area_id") val areaId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("hidden_by") val hiddenBy: String? = null,
    @SerialName("disabled_by") val disabledBy: String? = null,
)

@Serializable
internal data class HaDeviceRegistryEntry(
    val id: String,
    @SerialName("area_id") val areaId: String? = null,
)

/**
 * entity_id → area_id, resolved the way Home Assistant resolves it: an entity's own
 * area wins, and it falls back to the area of the device it belongs to.
 */
internal fun buildEntityAreaIndex(
    entities: List<HaEntityRegistryEntry>,
    devices: List<HaDeviceRegistryEntry>,
): Map<String, String> {
    val deviceArea = devices.mapNotNull { device ->
        device.areaId?.let { device.id to it }
    }.toMap()

    return entities.mapNotNull { entry ->
        if (entry.disabledBy != null || entry.hiddenBy != null) return@mapNotNull null
        val area = entry.areaId ?: entry.deviceId?.let { deviceArea[it] } ?: return@mapNotNull null
        entry.entityId to area
    }.toMap()
}
