package com.thelightphone.brighthome

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntitySearchTest {

    private fun entity(id: String, name: String, state: String = "on"): HaState =
        HaState(id, state, Json.parseToJsonElement("""{"friendly_name":"$name"}""") as JsonObject)

    private val house = listOf(
        entity("light.kitchen_ceiling", "Kitchen ceiling"),
        entity("light.desk_lamp", "Desk lamp"),
        entity("light.sideboard", "Sideboard desk light"),
        entity("switch.kettle", "Kettle"),
        entity("climate.hall", "Hall thermostat"),
        entity("cover.study_blind", "Study blind"),
        entity("sensor.outside_temp", "Outside temperature"),
        entity("light.hue_1f2e3d", "hue_1f2e3d"),
    )

    private val areas = mapOf(
        "light.kitchen_ceiling" to "Kitchen",
        "switch.kettle" to "Kitchen",
        "light.desk_lamp" to "Office",
    )

    private fun find(q: String) =
        EntitySearch.search(q, house, areaOf = { areas[it] }).map { it.state.entityId }

    @Test
    fun `an empty query returns nothing rather than everything`() {
        assertTrue(find("").isEmpty())
        assertTrue(find("   ").isEmpty())
    }

    @Test
    fun `a name that starts with the query comes first`() {
        val hits = find("desk")
        assertEquals("light.desk_lamp", hits.first())
        assertTrue("light.sideboard" in hits, "the mid-name match is still found")
    }

    @Test
    fun `a word inside the name beats a match buried mid-word`() {
        // "Sideboard desk light" starts a word with "desk"; both rank above an id hit.
        val hits = find("desk")
        assertTrue(hits.indexOf("light.sideboard") < hits.size)
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(find("KITCHEN"), find("kitchen"))
        assertTrue("light.kitchen_ceiling" in find("Kitchen"))
    }

    @Test
    fun `the room is searchable, because that is how people think about a light`() {
        val hits = find("kitchen")
        assertTrue("light.kitchen_ceiling" in hits)
        assertTrue("switch.kettle" in hits, "the kettle is in the kitchen but is not named for it")
        // the name match sorts above the area-only match
        assertTrue(hits.indexOf("light.kitchen_ceiling") < hits.indexOf("switch.kettle"))
    }

    @Test
    fun `the entity id is the last resort and still finds a badly named thing`() {
        assertTrue("light.hue_1f2e3d" in find("1f2e3d"))
    }

    @Test
    fun `sensors are searchable even though they cannot be tapped`() {
        assertTrue("sensor.outside_temp" in find("outside"))
    }

    @Test
    fun `nothing matching returns an empty list, not a wall of everything`() {
        assertTrue(find("zzzznope").isEmpty())
    }

    @Test
    fun `results are capped so a one-letter query cannot flood the screen`() {
        val many = (1..200).map { entity("light.l$it", "Lamp $it") }
        assertEquals(60, EntitySearch.search("lamp", many, areaOf = { null }).size)
        assertEquals(5, EntitySearch.search("lamp", many, areaOf = { null }, limit = 5).size)
    }
}
