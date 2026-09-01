package com.thelightphone.brighthome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Horizontal inset for every screen, per the LightOS grid. */
internal const val EDGE_INSET_UNITS = 1f
internal const val TOP_BAR_UNITS = 3f
internal const val BOTTOM_BAR_UNITS = 4f
internal const val TRAILING_GLYPH_UNITS = 2f

private val ROW_VERTICAL_PADDING = 6.dp

/**
 * lightClickable with a hold.
 *
 * The SDK has no long-press helper because LightOS itself has no long-press. It is worth
 * adding here for one reason: a tap on a light has to stay instant — that one-tap promise
 * is the whole point of the Favorites page — so the brightness controls need a different
 * gesture rather than replacing the tap with a menu.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.lightRowGestures(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
): Modifier {
    if (onClick == null && onLongClick == null) return this
    return this.combinedClickable(
        interactionSource = null,
        indication = null,
        onLongClick = onLongClick,
        onClick = { onClick?.invoke() },
    )
}

/**
 * The row height LightLazyScrollView needs, in grid units.
 *
 * It has to be measured rather than picked. Grid units scale with screen *width* while
 * the type scale is derived from screen *height*, so a hard-coded unit count that looks
 * right on one panel clips the second line on another. Measure the two line heights,
 * add the padding, then convert to units — and give the row exactly that height so the
 * scrollbar maths matches what is drawn.
 */
@Composable
internal fun measuredRowHeight(): Pair<Dp, Float> {
    val typography = LightThemeTokens.typography
    val density = LocalDensity.current
    val titleDp = with(density) { typography.copy.lineHeight.toDp() }
    val subtitleDp = with(density) { typography.detail.lineHeight.toDp() }
    val total = titleDp + subtitleDp + (ROW_VERTICAL_PADDING * 2)
    val oneUnit = 1f.gridUnitsAsDp()
    val units = if (oneUnit.value <= 0f) 4f else total / oneUnit
    return total to units
}

/**
 * One entity. Title over state, with the on/off glyph on the right.
 *
 * There is no ripple anywhere in LightOS, so the feedback for a tap is the 45ms buzz
 * lightClickable fires on finger-down plus the state flipping optimistically underneath
 * it. That combination is what makes a tunnelled toggle feel local.
 */
@Composable
internal fun EntityRowView(
    row: EntityRow,
    height: Dp,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    trailing: RowTrailing = RowTrailing.State,
) {
    val colors = LightThemeTokens.colors
    // An unavailable entity cannot be controlled, but it can still be starred or
    // un-starred, so only the state-showing rows refuse the tap.
    val clickable = onClick != null &&
        (trailing !is RowTrailing.State || !row.unavailable)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .lightRowGestures(
                onClick = if (clickable) onClick else null,
                onLongClick = onLongClick,
            )
            .padding(horizontal = EDGE_INSET_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = row.title,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lighten = row.unavailable,
            )
            LightText(
                text = row.subtitle,
                variant = LightTextVariant.Detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lighten = true,
            )
        }

        Box(
            modifier = Modifier.width(TRAILING_GLYPH_UNITS.gridUnitsAsDp() + 8.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            when (trailing) {
                RowTrailing.None -> Unit

                RowTrailing.State -> when {
                    row.unavailable -> Unit
                    row.kind == ControlKind.Toggle -> LightIcon(
                        icon = if (row.isOn) LightIcons.TOGGLE_STATE_ON
                        else LightIcons.TOGGLE_STATE_OFF,
                        size = TRAILING_GLYPH_UNITS,
                    )

                    // A thermostat or a blind opens rather than flips, so it gets the
                    // chevron every list in LightOS uses for "there is more here".
                    row.kind == ControlKind.Detail -> LightIcon(
                        icon = LightIcons.ARROW_RIGHT,
                        size = TRAILING_GLYPH_UNITS,
                    )

                    row.kind == ControlKind.Momentary -> LightIcon(
                        icon = LightIcons.ARROW_RIGHT,
                        size = TRAILING_GLYPH_UNITS,
                    )

                    else -> Unit
                }

                is RowTrailing.Selection -> LightIcon(
                    icon = if (trailing.selected) LightIcons.SELECT_ON
                    else LightIcons.SELECT_OFF,
                    size = TRAILING_GLYPH_UNITS,
                )
            }
        }
    }
}

internal sealed interface RowTrailing {
    data object None : RowTrailing
    data object State : RowTrailing
    data class Selection(val selected: Boolean) : RowTrailing
}

/** A room, with how much of it is currently on. */
@Composable
internal fun AreaRowView(row: AreaRow, height: Dp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .lightClickable { onClick() }
            .padding(horizontal = EDGE_INSET_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = row.name,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = if (row.onCount == 0) "${row.total} devices"
                else "${row.onCount} on of ${row.total}",
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
            )
        }
        LightIcon(icon = LightIcons.ARROW_RIGHT, size = TRAILING_GLYPH_UNITS)
    }
}

/** A section header inside a long list. */
@Composable
internal fun SectionHeader(text: String, height: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = EDGE_INSET_UNITS.gridUnitsAsDp()),
        contentAlignment = Alignment.BottomStart,
    ) {
        LightText(text = text.uppercase(), variant = LightTextVariant.Superfine, lighten = true)
    }
}

/**
 * Centred prose for the empty and error states, with an optional tappable line under it.
 */
@Composable
internal fun CenteredNotice(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = (EDGE_INSET_UNITS * 2).gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Paragraph,
            align = TextAlign.Center,
            lighten = true,
        )
        if (actionLabel != null && onAction != null) {
            Box(modifier = Modifier.height(12.dp))
            LightText(
                text = actionLabel,
                variant = LightTextVariant.Button,
                align = TextAlign.Center,
                underline = true,
                modifier = Modifier.lightClickable { onAction() },
            )
        }
    }
}

/**
 * The one line the connection is allowed to say. It sits directly under the top bar so
 * it reads as chrome, and it is absent entirely when the socket is live.
 */
@Composable
internal fun StatusLine(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = EDGE_INSET_UNITS.gridUnitsAsDp(),
                vertical = 2.dp,
            ),
    ) {
        LightText(
            text = text,
            variant = LightTextVariant.Superfine,
            lighten = true,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
