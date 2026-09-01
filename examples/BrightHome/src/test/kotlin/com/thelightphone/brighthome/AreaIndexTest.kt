package com.thelightphone.brighthome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AreaIndexTest {

    @Test
    fun `an entity's own area beats its device's area`() {
        val index = buildEntityAreaIndex(
            entities = listOf(
                HaEntityRegistryEntry("light.a", areaId = "office", deviceId = "d1"),
            ),
            devices = listOf(HaDeviceRegistryEntry("d1", areaId = "kitchen")),
        )
        assertEquals("office", index["light.a"])
    }

    @Test
    fun `an entity with no area of its own inherits the device's`() {
        val index = buildEntityAreaIndex(
            entities = listOf(HaEntityRegistryEntry("light.b", deviceId = "d1")),
            devices = listOf(HaDeviceRegistryEntry("d1", areaId = "kitchen")),
        )
        assertEquals("kitchen", index["light.b"])
    }

    @Test
    fun `an entity with neither is left out so it lands in the loose bucket`() {
        val index = buildEntityAreaIndex(
            entities = listOf(HaEntityRegistryEntry("light.c")),
            devices = emptyList(),
        )
        assertTrue(index.isEmpty())
        assertNull(index["light.c"])
    }

    @Test
    fun `hidden and disabled entities never make it into a room`() {
        val index = buildEntityAreaIndex(
            entities = listOf(
                HaEntityRegistryEntry("light.d", areaId = "office", hiddenBy = "user"),
                HaEntityRegistryEntry("light.e", areaId = "office", disabledBy = "integration"),
                HaEntityRegistryEntry("light.f", areaId = "office"),
            ),
            devices = emptyList(),
        )
        assertEquals(mapOf("light.f" to "office"), index)
    }
}
