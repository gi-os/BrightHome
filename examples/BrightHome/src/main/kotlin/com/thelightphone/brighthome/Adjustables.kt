package com.thelightphone.brighthome

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The three things in a house that have a *value* rather than a state: how bright, how
 * warm, how open.
 *
 * Each one reduces to the same shape — a number, a range, a step, and a service that
 * accepts it — which is what lets one screen and one wheel handler drive all three.
 */
data class Adjustment(
    val entityId: String,
    val kind: AdjustKind,
    /** Current value in display units: percent for light and cover, degrees for climate. */
    val value: Double,
    val min: Double,
    val max: Double,
    val step: Double,
    val unit: String,
    /** False when the thing is off, or reports no value to adjust. */
    val available: Boolean,
) {
    fun nudged(steps: Int): Double =
        (value + steps * step).coerceIn(min, max).let { snap(it) }

    /** Keeps a 0.5-step thermostat off 20.7361 after a dozen notches. */
    private fun snap(v: Double): Double {
        if (step <= 0) return v
        val snapped = (v / step).roundToLong() * step
        return snapped.coerceIn(min, max)
    }

    fun format(v: Double = value): String = when (kind) {
        AdjustKind.Brightness, AdjustKind.Position -> "${v.roundToInt()}$unit"
        AdjustKind.Temperature ->
            if (step >= 1.0) "${v.roundToInt()}$unit"
            else "${(v * 10).roundToLong() / 10.0}$unit"
    }
}

enum class AdjustKind { Brightness, Temperature, Position }

/**
 * Home Assistant advertises what an entity can actually do as a bitmask in
 * `supported_features`. Reading it is the difference between offering a Stop button on a
 * blind that can stop and offering one on a blind that will ignore it.
 */
object CoverFeature {
    const val OPEN = 1
    const val CLOSE = 2
    const val SET_POSITION = 4
    const val STOP = 8
}

object ClimateFeature {
    const val TARGET_TEMPERATURE = 1
    const val TARGET_TEMPERATURE_RANGE = 2
}

internal fun HaState.supportedFeatures(): Int =
    attributes.number("supported_features")?.toInt() ?: 0

internal fun HaState.supports(flag: Int): Boolean = (supportedFeatures() and flag) != 0

/** Builds the adjustment for an entity, or null when there is nothing to adjust. */
fun adjustmentFor(state: HaState): Adjustment? = when (state.domain) {
    "light" -> lightAdjustment(state)
    "climate" -> climateAdjustment(state)
    "cover" -> coverAdjustment(state)
    else -> null
}

private fun lightAdjustment(state: HaState): Adjustment? {
    // A light that reports no brightness is a plain on/off relay; there is no percentage
    // to show and pretending otherwise gives you a slider that does nothing.
    val raw = state.attributes.number("brightness")
    val on = Domains.isOn(state)
    if (raw == null && on) return null
    val percent = raw?.let { (it / 255.0) * 100.0 } ?: 0.0
    return Adjustment(
        entityId = state.entityId,
        kind = AdjustKind.Brightness,
        value = percent.coerceIn(0.0, 100.0),
        min = 0.0,
        max = 100.0,
        step = 5.0,
        unit = "%",
        available = !state.isUnavailable,
    )
}

private fun climateAdjustment(state: HaState): Adjustment? {
    val target = state.attributes.number("temperature") ?: return null
    val min = state.attributes.number("min_temp") ?: 7.0
    val max = state.attributes.number("max_temp") ?: 35.0
    val step = state.attributes.number("target_temp_step") ?: 0.5
    return Adjustment(
        entityId = state.entityId,
        kind = AdjustKind.Temperature,
        value = target.coerceIn(min, max),
        min = min,
        max = max,
        step = if (step <= 0) 0.5 else step,
        unit = "°",
        available = !state.isUnavailable && state.state != "off",
    )
}

private fun coverAdjustment(state: HaState): Adjustment? {
    if (!state.supports(CoverFeature.SET_POSITION)) return null
    val position = state.attributes.number("current_position") ?: return null
    return Adjustment(
        entityId = state.entityId,
        kind = AdjustKind.Position,
        value = position.coerceIn(0.0, 100.0),
        min = 0.0,
        max = 100.0,
        step = 10.0,
        unit = "%",
        available = !state.isUnavailable,
    )
}

/** The service call that writes [value] back. */
fun setValueCall(adjustment: Adjustment): ServiceCall = when (adjustment.kind) {
    AdjustKind.Brightness -> ServiceCall(
        domain = "light",
        service = "turn_on",
        entityId = adjustment.entityId,
        extra = buildJsonObject {
            // brightness_pct is the humane half of this API: HA converts to 0..255
            // itself, so the rounding happens once, on the server.
            put("brightness_pct", JsonPrimitive(adjustment.value.roundToInt()))
        },
    )

    AdjustKind.Temperature -> ServiceCall(
        domain = "climate",
        service = "set_temperature",
        entityId = adjustment.entityId,
        extra = buildJsonObject {
            put("temperature", JsonPrimitive(adjustment.value))
        },
    )

    AdjustKind.Position -> ServiceCall(
        domain = "cover",
        service = "set_cover_position",
        entityId = adjustment.entityId,
        extra = buildJsonObject {
            put("position", JsonPrimitive(adjustment.value.roundToInt()))
        },
    )
}

/** Cover buttons, filtered to what the device says it can do. */
data class CoverActions(
    val canOpen: Boolean,
    val canClose: Boolean,
    val canStop: Boolean,
    val canPosition: Boolean,
) {
    companion object {
        fun of(state: HaState): CoverActions {
            val features = state.supportedFeatures()
            // A cover that advertises nothing at all is almost always an older
            // integration rather than a cover that does nothing; assume open/close.
            if (features == 0) {
                return CoverActions(canOpen = true, canClose = true, canStop = false, canPosition = false)
            }
            return CoverActions(
                canOpen = (features and CoverFeature.OPEN) != 0,
                canClose = (features and CoverFeature.CLOSE) != 0,
                canStop = (features and CoverFeature.STOP) != 0,
                canPosition = (features and CoverFeature.SET_POSITION) != 0,
            )
        }
    }
}

fun coverCall(entityId: String, action: CoverAction): ServiceCall = ServiceCall(
    domain = "cover",
    service = when (action) {
        CoverAction.Open -> "open_cover"
        CoverAction.Close -> "close_cover"
        CoverAction.Stop -> "stop_cover"
    },
    entityId = entityId,
)

enum class CoverAction { Open, Close, Stop }

/** The HVAC modes this thermostat actually offers, in a stable order. */
fun climateModes(state: HaState): List<String> {
    val listed = state.attributes["hvac_modes"] as? kotlinx.serialization.json.JsonArray
    val modes = listed
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    return modes.ifEmpty { listOf("off", "heat") }
}

fun setHvacModeCall(entityId: String, mode: String): ServiceCall = ServiceCall(
    domain = "climate",
    service = "set_hvac_mode",
    entityId = entityId,
    extra = buildJsonObject { put("hvac_mode", JsonPrimitive(mode)) },
)

/** What the thermostat is doing right now, as opposed to what it is set to. */
fun climateAction(state: HaState): String? =
    state.attributes.string("hvac_action")?.let { StateSummary.humanize(it) }

fun currentTemperature(state: HaState): Double? =
    state.attributes.number("current_temperature")
