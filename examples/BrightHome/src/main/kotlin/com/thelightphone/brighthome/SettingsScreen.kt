package com.thelightphone.brighthome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** What settings wants the home screen to do once it pops. */
enum class SettingsAction { None, EditFavorites, Repair }

/**
 * Connection state, and the three things worth doing to it.
 *
 * The host is shown; neither token ever is. There is nothing to gain from rendering a
 * credential on a screen someone can photograph, and the failure messages already say
 * which of the two was rejected.
 */
class SettingsScreen(
    sealedActivity: SealedLightActivity,
    private val homeViewModel: BrightHomeViewModel,
) : SimpleLightScreen<SettingsAction>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by homeViewModel.uiState.collectAsState()
        val config by homeViewModel.repository.config.collectAsState()
        var confirmingForget by remember { mutableStateOf(false) }

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
                            onClick = { goBack(SettingsAction.None) },
                        ),
                        center = LightTopBarCenter.Text("Settings"),
                    )

                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = EDGE_INSET_UNITS.gridUnitsAsDp()),
                    ) {
                        Field("Instance", config?.displayHost ?: "Not connected")
                        Field("Connection", connectionText(state.status))
                        Field(
                            "Cloudflare Access",
                            if (config?.usesAccess == true) "Service token in use"
                            else "Not configured",
                        )
                        Field(
                            "Rooms",
                            if (state.areasSupported) "From the area registry"
                            else "Unavailable — the token is not an admin token",
                        )

                        Box(modifier = Modifier.height(16.dp))

                        Action("Refresh now") { homeViewModel.refresh() }
                        Action("Edit favorites") { goBack(SettingsAction.EditFavorites) }
                        Action("Scan a new setup code") { goBack(SettingsAction.Repair) }

                        Box(modifier = Modifier.height(8.dp))

                        if (confirmingForget) {
                            LightText(
                                text = "This deletes the stored address, tokens and your " +
                                    "favorites from the phone. Home Assistant is not " +
                                    "touched, and the long-lived token stays valid until " +
                                    "you revoke it there.",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                            Action("Yes, forget it") {
                                homeViewModel.forget()
                                goBack(SettingsAction.None)
                            }
                            Action("Cancel") { confirmingForget = false }
                        } else {
                            Action("Forget this instance") { confirmingForget = true }
                        }
                    }
                }
            }
        }
    }

    private fun connectionText(status: ConnectionStatus): String = when (status) {
        is ConnectionStatus.Live -> "Live"
        is ConnectionStatus.Connecting -> "Connecting"
        is ConnectionStatus.Stale -> status.reason ?: "Not live"
        is ConnectionStatus.Failed -> status.reason
        is ConnectionStatus.Unpaired -> "Not set up"
    }

    @Composable
    private fun Field(label: String, value: String) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            LightText(text = label.uppercase(), variant = LightTextVariant.Superfine, lighten = true)
            LightText(text = value, variant = LightTextVariant.Copy)
        }
    }

    @Composable
    private fun Action(label: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { onClick() }
                .padding(vertical = 10.dp),
        ) {
            LightText(text = label, variant = LightTextVariant.Copy, underline = true)
        }
    }
}
