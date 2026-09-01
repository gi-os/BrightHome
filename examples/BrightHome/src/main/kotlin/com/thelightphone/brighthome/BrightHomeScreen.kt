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
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

/**
 * The whole app is one screen with three tabs, the way the Weather example is one screen
 * with several modes. LightOS owns the back button, so pushing a screen per tab would
 * build a back stack the user never asked for.
 *
 * Favourites is the start tab and the reason the app exists: the four or five things you
 * actually touch, one tap from the launcher.
 */
@InitialScreen
class BrightHomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, BrightHomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<BrightHomeViewModel>
        get() = BrightHomeViewModel::class.java

    override fun createViewModel(): BrightHomeViewModel =
        BrightHomeViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val (rowHeight, rowUnits) = measuredRowHeight()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = if (state.paired) {
                            LightBarButton.LightIcon(
                                icon = LightIcons.SEARCH,
                                contentDescription = "Search",
                                onClick = { openSearch() },
                            )
                        } else {
                            null
                        },
                        center = LightTopBarCenter.Text(
                            if (state.paired) state.tab.title else "Home",
                        ),
                        rightButton = LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            contentDescription = "Settings",
                            onClick = { openSettings() },
                        ),
                    )

                    state.statusLine?.let { StatusLine(it) }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (!state.paired) {
                            CenteredNotice(
                                message = "BrightHome is not connected to Home Assistant yet.",
                                actionLabel = "Set it up",
                                onAction = { openPairing() },
                            )
                        } else {
                            TabBody(state, rowHeight, rowUnits)
                        }
                    }

                    if (state.paired) {
                        LightBottomBar(
                            items = HomeTab.entries.map { tab ->
                                LightBarButton.Text(
                                    text = tab.title,
                                    onClick = { viewModel.selectTab(tab) },
                                )
                            },
                        )
                    }
                }

                state.toast?.let { message ->
                    LightFullscreenModal(
                        message = message,
                        onClose = { viewModel.dismissToast() },
                    )
                }
            }
        }
    }

    @Composable
    private fun TabBody(
        state: BrightHomeUiState,
        rowHeight: androidx.compose.ui.unit.Dp,
        rowUnits: Float,
    ) {
        when (state.tab) {
            HomeTab.Favorites -> when {
                state.favorites.isEmpty() && state.loading ->
                    CenteredNotice(message = "Loading…")

                state.favorites.isEmpty() -> CenteredNotice(
                    message = "No favorites yet. Pin the handful of things you actually " +
                        "touch and they will be one tap from the launcher. Hold any " +
                        "light, blind or thermostat for its controls.",
                    actionLabel = "Choose favorites",
                    onAction = { openEditFavorites() },
                )

                else -> LightLazyScrollView(uniformItemHeightGridUnits = rowUnits) {
                    items(state.favorites.size) { index ->
                        val row = state.favorites[index]
                        EntityRowView(
                            row = row,
                            height = rowHeight,
                            onClick = rowTap(row),
                            onLongClick = rowHold(row),
                        )
                    }
                }
            }

            HomeTab.Rooms -> {
                val rooms = remember(state) {
                    state.areas + listOfNotNull(state.unassigned)
                }
                when {
                    rooms.isEmpty() && state.loading -> CenteredNotice(message = "Loading…")

                    rooms.isEmpty() -> CenteredNotice(
                        message = if (state.areasSupported) "No rooms found."
                        else "This token cannot read the area registry, so rooms are " +
                            "unavailable. A long-lived token from an admin account can.",
                    )

                    else -> LightLazyScrollView(uniformItemHeightGridUnits = rowUnits) {
                        items(rooms.size) { index ->
                            val room = rooms[index]
                            AreaRowView(
                                row = room,
                                height = rowHeight,
                                onClick = { openArea(room.areaId) },
                            )
                        }
                    }
                }
            }

            HomeTab.Scenes -> when {
                state.scenes.isEmpty() && state.loading -> CenteredNotice(message = "Loading…")

                state.scenes.isEmpty() -> CenteredNotice(
                    message = "No scenes, scripts or buttons in this instance.",
                )

                else -> LightLazyScrollView(uniformItemHeightGridUnits = rowUnits) {
                    items(state.scenes.size) { index ->
                        val row = state.scenes[index]
                        EntityRowView(
                            row = row,
                            height = rowHeight,
                            onClick = rowTap(row),
                            onLongClick = rowHold(row),
                        )
                    }
                }
            }
        }
    }

    /**
     * A tap does the obvious thing: flips a switch, fires a scene, opens the controls for
     * the two domains where "flip" means nothing. Holding always opens the controls, so
     * a light keeps its one-tap toggle and still has a brightness dial behind it.
     */
    private fun rowTap(row: EntityRow): (() -> Unit)? = when (row.kind) {
        ControlKind.ReadOnly -> null
        ControlKind.Detail -> ({ openControl(row.entityId) })
        else -> ({ viewModel.act(row.entityId) })
    }

    private fun rowHold(row: EntityRow): (() -> Unit)? =
        if (row.hasDetail) ({ openControl(row.entityId) }) else null

    private fun openControl(entityId: String) {
        navigateTo({ activity -> EntityControlScreen(activity, viewModel, entityId) })
    }

    private fun openSearch() {
        navigateTo({ activity -> SearchScreen(activity, viewModel) })
    }

    private fun openPairing() {
        navigateTo({ activity -> PairingScreen(activity) }) { config ->
            if (config != null) viewModel.pair(config)
        }
    }

    private fun openEditFavorites() {
        navigateTo({ activity -> EditFavoritesScreen(activity, viewModel) })
    }

    private fun openArea(areaId: String) {
        navigateTo({ activity -> AreaScreen(activity, viewModel, areaId) })
    }

    private fun openSettings() {
        navigateTo({ activity -> SettingsScreen(activity, viewModel) }) { action ->
            when (action) {
                SettingsAction.EditFavorites -> openEditFavorites()
                SettingsAction.Repair -> openPairing()
                SettingsAction.None, null -> Unit
            }
        }
    }
}
