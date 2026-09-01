package com.thelightphone.brighthome

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
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
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The screen behind a light, a thermostat or a blind.
 *
 * All three are the same problem — a number, a range, and a service that takes it — so
 * they share one screen rather than three near-identical ones. What differs is the row
 * of buttons underneath: on/off for a light, the mode list for a thermostat, open/stop/
 * close for a blind.
 *
 * The wheel adjusts the number. See [WheelInput] for why the screen refuses the key
 * whenever there is nothing here to adjust.
 */
class EntityControlScreen(
    sealedActivity: SealedLightActivity,
    private val homeViewModel: BrightHomeViewModel,
    private val entityId: String,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private val notches = MutableSharedFlow<WheelInput.Turn>(extraBufferCapacity = 32)

    /**
     * Set from the composition. False for a plain relay light or a blind with no
     * position, and then the wheel must reach LightOS so it still dims the screen.
     */
    @Volatile
    private var wheelDoesSomething = false

    override fun onAppPause() = homeViewModel.pause()

    override fun willShow() = homeViewModel.resume()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val turn = WheelInput.turnOf(keyCode, event) ?: return false
        if (!wheelDoesSomething) return false
        notches.tryEmit(turn)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Each notch is a DOWN and an UP. Claiming only the DOWN lets the UP through to
        // the server, which still reads it as a brightness step.
        return wheelDoesSomething && WheelInput.handles(keyCode, event)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val stateFlow = remember(entityId) { homeViewModel.entityFlow(entityId) }
        val state by stateFlow.collectAsState()
        val pending by homeViewModel.repository.pending.collectAsState()
        val ui by homeViewModel.uiState.collectAsState()

        val entity = state
        val adjustment = remember(entity) { entity?.let { adjustmentFor(it) } }
        val shown = adjustment?.let { a ->
            pending[entityId]?.coerceIn(a.min, a.max) ?: a.value
        }
        wheelDoesSomething = adjustment != null && adjustment.available

        // Collect the wheel exactly once. Keying this on the value would tear the
        // collector down and rebuild it on every notch, and each rebuild would capture
        // the value as it was when the spin started.
        val latestAdjustment = rememberUpdatedState(adjustment)
        val latestShown = rememberUpdatedState(shown)
        LaunchedEffect(entityId) {
            notches.collect { turn ->
                val a = latestAdjustment.value ?: return@collect
                if (!a.available) return@collect
                val base = latestShown.value ?: a.value
                val steps = if (turn == WheelInput.Turn.Up) 1 else -1
                homeViewModel.adjust(a, a.copy(value = base).nudged(steps))
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
                            contentDescription = "Back",
                            onClick = { goBack() },
                        ),
                        center = LightTopBarCenter.Text(
                            entity?.friendlyName ?: "Loading…",
                        ),
                    )
                    ui.statusLine?.let { StatusLine(it) }

                    if (entity == null) {
                        CenteredNotice(message = "That entity is not in Home Assistant.")
                    } else {
                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = EDGE_INSET_UNITS.gridUnitsAsDp()),
                        ) {
                            Body(entity, adjustment, shown)
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

    @Composable
    private fun Body(entity: HaState, adjustment: Adjustment?, shown: Double?) {
        Box(modifier = Modifier.height(8.dp))

        if (adjustment != null && shown != null && adjustment.available) {
            Dial(adjustment, shown)
        } else {
            // No number to show, so the state carries the screen instead of an empty gap.
            LightText(
                text = StateSummary.secondaryLine(entity),
                variant = LightTextVariant.Title,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.height(14.dp))

        when (entity.domain) {
            "light" -> LightSection(entity)
            "climate" -> ClimateSection(entity)
            "cover" -> CoverSection(entity)
            else -> Unit
        }

        Box(modifier = Modifier.height(10.dp))
        Caption(entity)
        Box(modifier = Modifier.height(16.dp))
    }

    /** The big number, flanked by the two things that change it. */
    @Composable
    private fun Dial(adjustment: Adjustment, shown: Double) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StepButton(LightIcons.DOWN, "Down") {
                homeViewModel.adjust(adjustment, adjustment.copy(value = shown).nudged(-1))
            }
            LightText(
                text = adjustment.format(shown),
                variant = LightTextVariant.Title,
                align = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            StepButton(LightIcons.UP, "Up") {
                homeViewModel.adjust(adjustment, adjustment.copy(value = shown).nudged(1))
            }
        }
        LightText(
            text = "Turn the wheel to adjust",
            variant = LightTextVariant.Superfine,
            align = TextAlign.Center,
            lighten = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun StepButton(
        icon: LightIconConfiguration,
        label: String,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .lightClickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 12.dp),
        ) {
            LightIcon(icon = icon, size = 2.5f, contentDescription = label)
        }
    }

    @Composable
    private fun LightSection(entity: HaState) {
        val on = Domains.isOn(entity)
        ActionRow(if (on) "Turn off" else "Turn on", selected = on) {
            homeViewModel.act(entity.entityId)
        }
    }

    @Composable
    private fun ClimateSection(entity: HaState) {
        val current = currentTemperature(entity)
        val doing = climateAction(entity)
        if (current != null) {
            Caption("Currently ${adjustmentFor(entity)?.format(current) ?: current.toString()}" +
                (doing?.let { " · $it" } ?: ""))
            Box(modifier = Modifier.height(10.dp))
        }
        SectionLabel("Mode")
        climateModes(entity).forEach { mode ->
            ActionRow(StateSummary.humanize(mode), selected = entity.state == mode) {
                homeViewModel.send(setHvacModeCall(entity.entityId, mode))
            }
        }
    }

    @Composable
    private fun CoverSection(entity: HaState) {
        val actions = CoverActions.of(entity)
        SectionLabel("Blind")
        if (actions.canOpen) {
            ActionRow("Open", selected = entity.state == "open") {
                homeViewModel.send(coverCall(entity.entityId, CoverAction.Open))
            }
        }
        if (actions.canStop) {
            ActionRow("Stop", selected = false) {
                homeViewModel.send(coverCall(entity.entityId, CoverAction.Stop))
            }
        }
        if (actions.canClose) {
            ActionRow("Close", selected = entity.state == "closed") {
                homeViewModel.send(coverCall(entity.entityId, CoverAction.Close))
            }
        }
    }

    /** A tappable line with the SDK's on/off glyph carrying the current selection. */
    @Composable
    private fun ActionRow(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { onClick() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = label,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LightIcon(
                icon = if (selected) LightIcons.SELECT_ON else LightIcons.SELECT_OFF,
                size = TRAILING_GLYPH_UNITS,
            )
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        LightText(text = text.uppercase(), variant = LightTextVariant.Superfine, lighten = true)
    }

    @Composable
    private fun Caption(text: String) {
        LightText(text = text, variant = LightTextVariant.Detail, lighten = true)
    }

    @Composable
    private fun Caption(entity: HaState) {
        Caption(entity.entityId)
    }
}
