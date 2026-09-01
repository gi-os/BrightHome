package com.thelightphone.brighthome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.flow.StateFlow

/**
 * Find one thing in a house full of them.
 *
 * Typing on this phone is deliberate work, so the query is entered once in the SDK's
 * full-screen editor and the results stay put afterwards — tapping the title goes back
 * to the field rather than making you retype. Results behave exactly like any other
 * list: a tap toggles, a hold opens the controls.
 */
class SearchScreen(
    sealedActivity: SealedLightActivity,
    private val homeViewModel: BrightHomeViewModel,
) : SimpleLightScreen<Unit>(sealedActivity) {

    override fun onAppPause() = homeViewModel.pause()

    override fun willShow() = homeViewModel.resume()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val ui by homeViewModel.uiState.collectAsState()
        val fieldState = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()
        var query by remember { mutableStateOf<String?>(null) }
        val (rowHeight, rowUnits) = measuredRowHeight()

        val results = remember(query, ui) {
            query?.let { homeViewModel.search(it) }.orEmpty()
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                val current = query
                if (current == null) {
                    Editor(fieldState, keyboardOptions) { submitted ->
                        query = submitted.ifBlank { null }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                contentDescription = "Back",
                                onClick = { goBack() },
                            ),
                            center = LightTopBarCenter.TwoLineDetail(
                                line1 = current,
                                line2 = if (results.isEmpty()) "nothing found"
                                else "${results.size} found",
                                onClick = { query = null },
                            ),
                            rightButton = LightBarButton.LightIcon(
                                icon = LightIcons.SEARCH,
                                contentDescription = "Search again",
                                onClick = { query = null },
                            ),
                        )
                        ui.statusLine?.let { StatusLine(it) }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (results.isEmpty()) {
                                CenteredNotice(
                                    message = "Nothing matches “$current”.",
                                    actionLabel = "Search again",
                                    onAction = { query = null },
                                )
                            } else {
                                LightLazyScrollView(uniformItemHeightGridUnits = rowUnits) {
                                    items(results.size) { index ->
                                        val row = results[index]
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
                }

                ui.toast?.let { message ->
                    LightFullscreenModal(
                        message = message,
                        onClose = { homeViewModel.dismissToast() },
                    )
                }
            }
        }
    }

    private fun rowTap(row: EntityRow): (() -> Unit)? = when (row.kind) {
        ControlKind.ReadOnly -> null
        ControlKind.Detail -> ({ openControl(row.entityId) })
        else -> ({ homeViewModel.act(row.entityId) })
    }

    private fun rowHold(row: EntityRow): (() -> Unit)? =
        if (row.hasDetail) ({ openControl(row.entityId) }) else null

    private fun openControl(entityId: String) {
        navigateTo({ activity -> EntityControlScreen(activity, homeViewModel, entityId) })
    }

    @Composable
    private fun Editor(
        state: TextFieldState,
        keyboardOptionsFlow: StateFlow<KeyboardOptions>,
        onSubmit: (String) -> Unit,
    ) {
        LightTextInputEditor(
            title = "Search",
            state = state,
            onSubmit = { text -> onSubmit(text.toString().trim()) },
            onBack = { goBack() },
            keyboardOptionsFlow = keyboardOptionsFlow,
            submitLabel = "FIND",
            submitIcon = LightIcons.SEARCH,
            singleLine = true,
        )
    }
}
