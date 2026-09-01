package com.thelightphone.brighthome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

/**
 * Everything in one room.
 *
 * The screen holds the ViewModel, never a snapshot of the rows — a detail screen that
 * caches its own copy goes stale the moment the list behind it rebuilds — and it
 * collects a flow of its own rows rather than deriving them from the home screen's
 * summarised state, which conflates away every change that does not move an on-count.
 *
 * It also forwards the app lifecycle. SimpleLightScreen's onAppPause is a no-op, so
 * without this the socket would stay open in a pocket whenever the app was backgrounded
 * from inside a room.
 */
class AreaScreen(
    sealedActivity: SealedLightActivity,
    private val homeViewModel: BrightHomeViewModel,
    private val areaId: String,
) : SimpleLightScreen<Unit>(sealedActivity) {

    override fun onAppPause() = homeViewModel.pause()

    override fun willShow() = homeViewModel.resume()

    private fun openControl(entityId: String) {
        navigateTo({ activity -> EntityControlScreen(activity, homeViewModel, entityId) })
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val rowsFlow = remember(areaId) { homeViewModel.areaRows(areaId) }
        val rows by rowsFlow.collectAsState()
        val state by homeViewModel.uiState.collectAsState()
        val title = remember(areaId, state.areasSupported) { homeViewModel.areaName(areaId) }
        val (rowHeight, rowUnits) = measuredRowHeight()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            contentDescription = "Back",
                            onClick = { goBack() },
                        ),
                        center = LightTopBarCenter.Text(title),
                    )

                    state.statusLine?.let { StatusLine(it) }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (rows.isEmpty()) {
                            CenteredNotice(message = "Nothing in this room.")
                        } else {
                            LightLazyScrollView(uniformItemHeightGridUnits = rowUnits) {
                                items(rows.size) { index ->
                                    val row = rows[index]
                                    EntityRowView(
                                        row = row,
                                        height = rowHeight,
                                        onClick = when (row.kind) {
                                            ControlKind.ReadOnly -> null
                                            ControlKind.Detail -> ({ openControl(row.entityId) })
                                            else -> ({ homeViewModel.act(row.entityId) })
                                        },
                                        onLongClick = if (row.hasDetail) {
                                            { openControl(row.entityId) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // A tap that fails here has to say so here. Letting the message wait for
                // the home screen ambushes the user with a modal about a room they left.
                state.toast?.let { message ->
                    LightFullscreenModal(
                        message = message,
                        onClose = { homeViewModel.dismissToast() },
                    )
                }
            }
        }
    }
}
