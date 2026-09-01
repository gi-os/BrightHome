package com.thelightphone.brighthome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow

/** Where the manual fallback has got to. The QR path skips all of it. */
private enum class ManualStep { Address, Token, AccessAsk, AccessId, AccessSecret }

/**
 * Pairing, by QR first.
 *
 * A long-lived access token is around 180 characters and a Cloudflare service token adds
 * two more secrets. Typing that on this keyboard is not a reasonable thing to ask, so the
 * whole credential set travels as one scanned payload from docs/pair.html. Manual entry
 * stays as the fallback for when there is no second screen to hand.
 */
class PairingScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<HaConfig?>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var error by remember { mutableStateOf<String?>(null) }
        var manual by remember { mutableStateOf<ManualStep?>(null) }

        var address by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        var accessId by remember { mutableStateOf("") }

        val fieldState = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                val step = manual
                if (step == null) {
                    Menu(
                        onScan = { openScanner { result -> handleScan(result, { error = it }) } },
                        onManual = {
                            fieldState.clearText()
                            manual = ManualStep.Address
                        },
                        onCancel = { goBack(null) },
                    )
                } else {
                    ManualEntry(
                        step = step,
                        state = fieldState,
                        keyboardOptionsFlow = keyboardOptions,
                        onBack = {
                            manual = null
                            fieldState.clearText()
                        },
                        onAnswer = { answer ->
                            when (step) {
                                ManualStep.Address -> {
                                    val normalized = PairingPayload.normalizeUrl(answer)
                                    if (normalized == null) {
                                        error = "The address must be https — this phone " +
                                            "refuses plain http, so a bare LAN address " +
                                            "cannot work."
                                    } else {
                                        address = normalized
                                        fieldState.clearText()
                                        manual = ManualStep.Token
                                    }
                                }

                                ManualStep.Token -> {
                                    if (answer.isBlank()) {
                                        error = "The token is empty."
                                    } else {
                                        token = answer
                                        fieldState.clearText()
                                        manual = ManualStep.AccessAsk
                                    }
                                }

                                ManualStep.AccessId -> {
                                    accessId = answer
                                    fieldState.clearText()
                                    manual = ManualStep.AccessSecret
                                }

                                ManualStep.AccessSecret -> {
                                    finish(
                                        PairingPayload.build(address, token, accessId, answer),
                                    ) { error = it }
                                }

                                ManualStep.AccessAsk -> Unit
                            }
                        },
                        onAccessAnswer = { usesAccess ->
                            if (usesAccess) {
                                fieldState.clearText()
                                manual = ManualStep.AccessId
                            } else {
                                finish(PairingPayload.build(address, token, null, null)) {
                                    error = it
                                }
                            }
                        },
                    )
                }

                error?.let { message ->
                    LightFullscreenModal(message = message, onClose = { error = null })
                }
            }
        }
    }

    private fun handleScan(raw: String?, onError: (String) -> Unit) {
        if (raw == null) return
        finish(PairingPayload.parse(raw), onError)
    }

    private fun finish(result: PairingResult, onError: (String) -> Unit) {
        when (result) {
            is PairingResult.Ok -> goBack(result.config)
            is PairingResult.Invalid -> onError(result.reason)
        }
    }

    private fun openScanner(onResult: (String?) -> Unit) {
        navigateTo({ activity -> QrScanScreen(activity) }, onResult)
    }

    @Composable
    private fun Menu(onScan: () -> Unit, onManual: () -> Unit, onCancel: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    contentDescription = "Back",
                    onClick = onCancel,
                ),
                center = LightTopBarCenter.Text("Set up"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = EDGE_INSET_UNITS.gridUnitsAsDp()),
            ) {
                LightText(
                    text = "Open docs/pair.html on a computer, fill in your Home " +
                        "Assistant address, long-lived access token and Cloudflare " +
                        "Access service token, then scan the code it draws.",
                    variant = LightTextVariant.Paragraph,
                    lighten = true,
                )
                Box(modifier = Modifier.height(16.dp))
                MenuAction(label = "Scan setup code", onClick = onScan)
                MenuAction(label = "Enter it by hand", onClick = onManual)
            }
        }
    }

    @Composable
    private fun MenuAction(label: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { onClick() }
                .padding(vertical = 10.dp),
        ) {
            LightText(text = label, variant = LightTextVariant.Copy, underline = true)
        }
    }

    @Composable
    private fun ManualEntry(
        step: ManualStep,
        state: TextFieldState,
        keyboardOptionsFlow: StateFlow<KeyboardOptions>,
        onBack: () -> Unit,
        onAnswer: (String) -> Unit,
        onAccessAnswer: (Boolean) -> Unit,
    ) {
        if (step == ManualStep.AccessAsk) {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        contentDescription = "Back",
                        onClick = onBack,
                    ),
                    center = LightTopBarCenter.Text("Cloudflare"),
                )
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = EDGE_INSET_UNITS.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "Is Cloudflare Access in front of the tunnel? If it is, " +
                            "BrightHome needs a service token as well — Access cannot " +
                            "run its login page against an app.",
                        variant = LightTextVariant.Paragraph,
                        lighten = true,
                    )
                    Box(modifier = Modifier.height(16.dp))
                    MenuAction(label = "Yes, add the service token") { onAccessAnswer(true) }
                    MenuAction(label = "No, the tunnel is open") { onAccessAnswer(false) }
                }
            }
            return
        }

        val title = when (step) {
            ManualStep.Address -> "Address"
            ManualStep.Token -> "Access token"
            ManualStep.AccessId -> "CF-Access-Client-Id"
            ManualStep.AccessSecret -> "CF-Access-Client-Secret"
            ManualStep.AccessAsk -> ""
        }

        LightTextInputEditor(
            title = title,
            state = state,
            onSubmit = { text -> onAnswer(text.toString().trim()) },
            onBack = onBack,
            keyboardOptionsFlow = keyboardOptionsFlow,
            submitLabel = "NEXT",
            singleLine = true,
        )
    }
}

/** The camera half, kept separate so the scanner owns the whole screen while it is up. */
class QrScanScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<String?>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            LightQrCodeScanner(
                title = "Scan setup code",
                onScanned = { goBack(it) },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
