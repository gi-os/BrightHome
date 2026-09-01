package com.thelightphone.brighthome

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainsTest {

    private fun state(entityId: String, value: String, attributes: String = "{}"): HaState =
        HaState(
            entityId = entityId,
            state = value,
            attributes = Json.parseToJsonElement(attributes) as JsonObject,
        )

    @Test
    fun `on means something different per domain`() {
        assertTrue(Domains.isOn(state("light.desk", "on")))
        assertTrue(!Domains.isOn(state("light.desk", "off")))
        // A cover is on when open, not when its state string says "on".
        assertTrue(Domains.isOn(state("cover.garage", "open")))
        assertTrue(!Domains.isOn(state("cover.garage", "closed")))
        // Locked is the engaged state, so the filled glyph means secured.
        assertTrue(Domains.isOn(state("lock.front", "locked")))
        assertTrue(!Domains.isOn(state("lock.front", "unlocked")))
        assertTrue(Domains.isOn(state("media_player.kitchen", "playing")))
        assertTrue(!Domains.isOn(state("media_player.kitchen", "paused")))
    }

    @Test
    fun `the generic service covers the ordinary domains`() {
        val call = Domains.toggleService(state("light.desk", "off"))
        assertEquals(ServiceCall("homeassistant", "turn_on", "light.desk"), call)
        assertEquals(
            ServiceCall("homeassistant", "turn_off", "switch.fan"),
            Domains.toggleService(state("switch.fan", "on")),
        )
    }

    @Test
    fun `the exceptions get their own service`() {
        assertEquals(
            ServiceCall("lock", "unlock", "lock.front"),
            Domains.toggleService(state("lock.front", "locked")),
        )
        assertEquals(
            ServiceCall("cover", "open_cover", "cover.garage"),
            Domains.toggleService(state("cover.garage", "closed")),
        )
        assertEquals(
            ServiceCall("scene", "turn_on", "scene.evening"),
            Domains.toggleService(state("scene.evening", "unknown")),
        )
        assertEquals(
            ServiceCall("button", "press", "button.doorbell"),
            Domains.toggleService(state("button.doorbell", "unknown")),
        )
    }

    @Test
    fun `a sensor offers nothing to press`() {
        assertNull(Domains.toggleService(state("sensor.temp", "21.4")))
        assertEquals(ControlKind.ReadOnly, Domains.controlKind("sensor"))
        assertEquals(ControlKind.Momentary, Domains.controlKind("script"))
        assertEquals(ControlKind.Toggle, Domains.controlKind("fan"))
    }

    @Test
    fun `the optimistic guess is the state the row should show under the finger`() {
        assertEquals("on", Domains.optimisticState(state("light.desk", "off")))
        assertEquals("opening", Domains.optimisticState(state("cover.garage", "closed")))
        assertEquals("unlocked", Domains.optimisticState(state("lock.front", "locked")))
        // Nothing to predict — a script has no resting state to flip.
        assertNull(Domains.optimisticState(state("script.bedtime", "off")))
    }

    @Test
    fun `a unit turns the state into a reading`() {
        assertEquals(
            "21.4 °C",
            StateSummary.secondaryLine(
                state("sensor.temp", "21.4", """{"unit_of_measurement":"°C"}"""),
            ),
        )
    }

    @Test
    fun `brightness is shown as a percentage of 255`() {
        assertEquals(
            "On · 50%",
            StateSummary.secondaryLine(state("light.desk", "on", """{"brightness":128}""")),
        )
        assertEquals("Off", StateSummary.secondaryLine(state("light.desk", "off")))
    }

    @Test
    fun `underscores never reach the panel`() {
        assertEquals("Heat cool", StateSummary.humanize("heat_cool"))
        assertEquals(
            "Unavailable",
            StateSummary.secondaryLine(state("light.desk", "unavailable")),
        )
    }

    @Test
    fun `friendly name falls back to a readable entity id`() {
        assertEquals("Desk lamp", state("light.desk_lamp", "on").friendlyName)
        assertEquals(
            "Kitchen",
            state("light.x", "on", """{"friendly_name":"Kitchen"}""").friendlyName,
        )
    }
}
