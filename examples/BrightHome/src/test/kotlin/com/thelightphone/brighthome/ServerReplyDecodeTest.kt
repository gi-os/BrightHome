package com.thelightphone.brighthome

import com.thelightphone.sdk.shared.LightServiceMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A tool is compiled against one LightOS build and then runs on later ones.
 *
 * BrightHome v1.0.1 died the moment the pairing screen appeared, and nothing was wrong
 * with the pairing screen: opening it asked LightOS for the keyboard options, the running
 * server encodes with `explicitNulls = false` and so *omits* its null fields, and to
 * kotlinx.serialization a nullable property with no default is still a required one.
 * MissingFieldException came out of a bare `scope.launch` with no handler attached, which
 * is the process.
 *
 * These decode the payload shapes a newer phone actually sends. They are cheap, they run
 * on the JVM, and they are the test that was missing.
 */
class ServerReplyDecodeTest {

    @Test
    fun `keyboard options decode when the server omits its null fields`() {
        val fromNewerServer = """{"displayVoice":true,"enableKeyAnimation":false}"""
        val decoded = LightServiceMethod.GetKeyboardOptions.decodeResponse(fromNewerServer)
        assertNull(decoded.emojisAsString)
        assertNull(decoded.swipeEnabled)
        assertTrue(decoded.displayVoice)
        assertTrue(!decoded.enableKeyAnimation)
    }

    @Test
    fun `keyboard options decode from a completely empty object`() {
        val decoded = LightServiceMethod.GetKeyboardOptions.decodeResponse("{}")
        assertNull(decoded.emojisAsString)
        assertNull(decoded.swipeEnabled)
    }

    @Test
    fun `keyboard options still decode when the fields are present`() {
        val full = """{"emojisAsString":"😅","displayVoice":false,""" +
            """"enableKeyAnimation":true,"swipeEnabled":true}"""
        val decoded = LightServiceMethod.GetKeyboardOptions.decodeResponse(full)
        assertEquals("😅", decoded.emojisAsString)
        assertTrue(decoded.swipeEnabled == true)
        assertTrue(!decoded.displayVoice)
    }

    @Test
    fun `a field the tool has never heard of is ignored, not fatal`() {
        val fromEvenNewerServer =
            """{"displayVoice":true,"enableKeyAnimation":true,"somethingNew":42}"""
        val decoded = LightServiceMethod.GetKeyboardOptions.decodeResponse(fromEvenNewerServer)
        assertTrue(decoded.displayVoice)
    }

    /**
     * The camera permission check the QR scanner runs before it opens the viewfinder goes
     * down the same path, so it had the same fault.
     */
    @Test
    fun `a permission reply with nothing in it decodes to Unknown`() {
        val decoded = LightServiceMethod.GetPermission.decodeResponse("{}")
        assertEquals(LightServiceMethod.GetPermission.Result.Unknown, decoded.permissionResult)
    }

    @Test
    fun `a granted permission still reads as granted`() {
        val decoded = LightServiceMethod.GetPermission
            .decodeResponse("""{"permissionResult":"Granted"}""")
        assertEquals(LightServiceMethod.GetPermission.Result.Granted, decoded.permissionResult)
    }

    @Test
    fun `the other replies the tool depends on survive an empty object`() {
        assertEquals("", LightServiceMethod.GetToken.decodeResponse("{}").token)
        assertEquals("", LightServiceMethod.GetVersion.decodeResponse("{}").version)
        assertEquals(
            "",
            LightServiceMethod.RequestPermissionComponent.decodeResponse("{}").componentName,
        )
        assertTrue(LightServiceMethod.GetUserPreferences.decodeResponse("{}").hapticsEnabled)
    }
}
