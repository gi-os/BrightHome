package com.thelightphone.brighthome

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdjustablesTest {

    private fun state(entityId: String, value: String, attributes: String = "{}"): HaState =
        HaState(entityId, value, Json.parseToJsonElement(attributes) as JsonObject)

    // ---- lights ----------------------------------------------------------------

    @Test
    fun `brightness is reported as a percentage of 255`() {
        val a = adjustmentFor(state("light.desk", "on", """{"brightness":128}"""))
        assertNotNull(a)
        assertEquals(AdjustKind.Brightness, a.kind)
        assertEquals(50, a.value.roundToInt())
        assertEquals("50%", a.format())
    }

    @Test
    fun `a light with no brightness attribute offers no dial`() {
        // A relay pretending to be a light. A slider here would do nothing at all.
        assertNull(adjustmentFor(state("light.porch", "on")))
    }

    @Test
    fun `an off light still offers a dial, starting at zero`() {
        val a = adjustmentFor(state("light.desk", "off"))
        assertNotNull(a)
        assertEquals(0.0, a.value)
    }

    @Test
    fun `the light service sends brightness_pct, not a 0-255 value`() {
        val a = adjustmentFor(state("light.desk", "on", """{"brightness":128}"""))!!
        val call = setValueCall(a.copy(value = 40.0))
        assertEquals("light", call.domain)
        assertEquals("turn_on", call.service)
        assertEquals("40", call.body()["brightness_pct"]?.jsonPrimitive?.content)
        assertEquals("light.desk", call.body()["entity_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `nudging clamps at both ends instead of wrapping`() {
        val a = adjustmentFor(state("light.desk", "on", """{"brightness":255}"""))!!
        assertEquals(100.0, a.nudged(5))
        assertEquals(0.0, a.copy(value = 2.0).nudged(-5))
    }

    // ---- thermostats -----------------------------------------------------------

    private val thermostat = """
        {"current_temperature":19.5,"temperature":21.0,"min_temp":7,"max_temp":30,
         "target_temp_step":0.5,"hvac_modes":["off","heat","cool"],"hvac_action":"heating"}
    """.trimIndent()

    @Test
    fun `a thermostat dials its target, not its current reading`() {
        val a = adjustmentFor(state("climate.hall", "heat", thermostat))
        assertNotNull(a)
        assertEquals(AdjustKind.Temperature, a.kind)
        assertEquals(21.0, a.value)
        assertEquals(0.5, a.step)
        assertEquals(19.5, currentTemperature(state("climate.hall", "heat", thermostat)))
    }

    @Test
    fun `half-degree steps do not drift after a long spin`() {
        val a = adjustmentFor(state("climate.hall", "heat", thermostat))!!
        var v = a.value
        repeat(7) { v = a.copy(value = v).nudged(1) }
        assertEquals(24.5, v)
        assertEquals("24.5°", a.format(v))
    }

    @Test
    fun `the setpoint cannot be dialled past the thermostat's own limits`() {
        val a = adjustmentFor(state("climate.hall", "heat", thermostat))!!
        assertEquals(30.0, a.copy(value = 29.5).nudged(20))
        assertEquals(7.0, a.copy(value = 7.5).nudged(-20))
    }

    @Test
    fun `an off thermostat is shown but not adjustable`() {
        val a = adjustmentFor(state("climate.hall", "off", thermostat))
        assertNotNull(a)
        assertTrue(!a.available)
    }

    @Test
    fun `modes come from the thermostat, with a fallback for one that lists none`() {
        assertEquals(
            listOf("off", "heat", "cool"),
            climateModes(state("climate.hall", "heat", thermostat)),
        )
        assertEquals(listOf("off", "heat"), climateModes(state("climate.bare", "heat")))
        val call = setHvacModeCall("climate.hall", "cool")
        assertEquals("set_hvac_mode", call.service)
        assertEquals("cool", call.body()["hvac_mode"]?.jsonPrimitive?.content)
    }

    // ---- blinds ----------------------------------------------------------------

    @Test
    fun `a positionable blind dials its position`() {
        // supported_features 15 = OPEN|CLOSE|SET_POSITION|STOP
        val s = state("cover.study", "open", """{"supported_features":15,"current_position":40}""")
        val a = adjustmentFor(s)
        assertNotNull(a)
        assertEquals(AdjustKind.Position, a.kind)
        assertEquals(40.0, a.value)
        assertEquals("40%", a.format())
        val call = setValueCall(a.copy(value = 70.0))
        assertEquals("set_cover_position", call.service)
        assertEquals("70", call.body()["position"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a blind that cannot set position offers no dial`() {
        // 3 = OPEN|CLOSE only
        assertNull(adjustmentFor(state("cover.garage", "open", """{"supported_features":3}""")))
    }

    @Test
    fun `buttons follow what the blind says it supports`() {
        val full = CoverActions.of(
            state("cover.study", "open", """{"supported_features":15}"""),
        )
        assertTrue(full.canOpen && full.canClose && full.canStop && full.canPosition)

        val simple = CoverActions.of(
            state("cover.garage", "open", """{"supported_features":3}"""),
        )
        assertTrue(simple.canOpen && simple.canClose)
        assertTrue(!simple.canStop, "no Stop button on a blind that cannot stop")
        assertTrue(!simple.canPosition)
    }

    @Test
    fun `a blind advertising nothing is assumed to open and close`() {
        // Older integrations report 0 rather than a mask. Showing no buttons at all
        // would be the wrong reading of that.
        val a = CoverActions.of(state("cover.old", "open"))
        assertTrue(a.canOpen && a.canClose)
        assertTrue(!a.canStop)
    }

    @Test
    fun `cover services are named the way Home Assistant names them`() {
        assertEquals("open_cover", coverCall("cover.x", CoverAction.Open).service)
        assertEquals("close_cover", coverCall("cover.x", CoverAction.Close).service)
        assertEquals("stop_cover", coverCall("cover.x", CoverAction.Stop).service)
    }

    // ---- routing ---------------------------------------------------------------

    @Test
    fun `thermostats and blinds open, everything else flips`() {
        assertEquals(ControlKind.Detail, Domains.controlKind("climate"))
        assertEquals(ControlKind.Detail, Domains.controlKind("cover"))
        assertEquals(ControlKind.Toggle, Domains.controlKind("light"))
        assertEquals(ControlKind.Momentary, Domains.controlKind("scene"))
    }

    @Test
    fun `holding leads somewhere only when there is something behind it`() {
        assertTrue(Domains.hasDetail(state("light.desk", "on", """{"brightness":10}""")))
        assertTrue(!Domains.hasDetail(state("light.porch", "on")))
        assertTrue(Domains.hasDetail(state("climate.hall", "heat", thermostat)))
        assertTrue(Domains.hasDetail(state("cover.study", "open")))
        assertTrue(!Domains.hasDetail(state("switch.kettle", "on")))
    }

    @Test
    fun `a climate entity is never blind-toggled`() {
        // Before this, "toggle" on a thermostat resolved to homeassistant.turn_off.
        assertNull(Domains.toggleService(state("climate.hall", "heat", thermostat)))
    }
}
