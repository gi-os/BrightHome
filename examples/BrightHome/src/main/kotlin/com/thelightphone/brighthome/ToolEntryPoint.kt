package com.thelightphone.brighthome

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint

/**
 * The SDK's KSP processor requires an object here, not a class.
 *
 * BrightHome has nothing to do at tool-create: the connection opens when the screen
 * shows and closes when it hides, because the screen is the only thing that consumes
 * live state. Holding a socket open in a pocket would cost battery and buy nothing.
 */
@EntryPoint
object ToolEntryPoint : LightEntryPoint
