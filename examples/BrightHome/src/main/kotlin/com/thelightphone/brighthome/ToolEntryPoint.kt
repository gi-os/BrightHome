package com.thelightphone.brighthome

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * The SDK's KSP processor requires an object here, not a class.
 *
 * There is nothing to do at tool-create. BrightHome's connection opens when the screen
 * shows and closes when it hides, because the screen is the only thing that consumes
 * live state — a socket held open in a pocket would cost battery and buy nothing.
 */
@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) = Unit
}
