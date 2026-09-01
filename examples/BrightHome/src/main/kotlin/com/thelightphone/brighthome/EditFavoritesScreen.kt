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

/** A header or an entity, flattened into one list so the scroll view sees uniform rows. */
private sealed interface PickerItem {
    data class Header(val title: String) : PickerItem
    data class Entity(val row: EntityRow) : PickerItem
}

/**
 * Pick what lands on the Favourites tab.
 *
 * Tapping here stars rather than toggles — the one screen in the app where a tap does
 * not control anything, which is why it is a separate screen rather than a mode on the
 * list you normally tap to switch things on.
 */
class EditFavoritesScreen(
    sealedActivity: SealedLightActivity,
    private val homeViewModel: BrightHomeViewModel,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by homeViewModel.uiState.collectAsState()
        val favorites by homeViewModel.repository.favorites.collectAsState()
        val (rowHeight, rowUnits) = measuredRowHeight()

        val items = remember(state) {
            buildList {
                for ((areaName, rows) in homeViewModel.pickerGroups()) {
                    add(PickerItem.Header(areaName))
                    rows.forEach { add(PickerItem.Entity(it)) }
                }
            }
        }

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
                            contentDescription = "Done",
                            onClick = { goBack() },
                        ),
                        center = LightTopBarCenter.TwoLineDetail(
                            line1 = "Favorites",
                            line2 = "${favorites.size} pinned",
                        ),
                    )

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (items.isEmpty()) {
                            CenteredNotice(
                                message = "Nothing to pin yet — BrightHome has not " +
                                    "loaded any entities.",
                            )
                        } else {
                            LightLazyScrollView(uniformItemHeightGridUnits = rowUnits) {
                                items(items.size) { index ->
                                    when (val item = items[index]) {
                                        is PickerItem.Header -> SectionHeader(
                                            text = item.title,
                                            height = rowHeight,
                                        )

                                        is PickerItem.Entity -> EntityRowView(
                                            row = item.row,
                                            height = rowHeight,
                                            trailing = RowTrailing.Selection(
                                                item.row.entityId in favorites,
                                            ),
                                            onClick = {
                                                homeViewModel.toggleFavorite(item.row.entityId)
                                            },
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
}
