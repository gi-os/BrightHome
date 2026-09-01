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
 * caches its own copy goes stale the moment the list behind it rebuilds. Rows are
 * re-derived from the ViewModel each time its state changes.
 */
class AreaScreen(
    sealedActivity: SealedLightActivity,
    private val homeViewModel: BrightHomeViewModel,
    private val areaId: String,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by homeViewModel.uiState.collectAsState()
        val rows = remember(state) { homeViewModel.rowsForArea(areaId) }
        val title = remember(state) { homeViewModel.areaName(areaId) }
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
                                        onClick = if (row.kind == ControlKind.ReadOnly) null
                                        else ({ homeViewModel.act(row.entityId) }),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
