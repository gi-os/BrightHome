package com.thelightphone.brighthome

import android.view.KeyEvent

/**
 * The Light Phone III brightness wheel, as it reaches an SDK tool.
 *
 * It is not a rotary encoder — it is an optical sensor that emits one DOWN/UP pair per
 * notch, which Light's patched keylayout labels WHEEL_CCW and WHEEL_CW. Those are not
 * AOSP keycodes, so they are resolved by name at runtime and matched on scancode as a
 * fallback rather than hard-coded.
 *
 * **The rule that matters:** in an SDK tool, returning false forwards the key to the
 * LightOS server, which turns it into screen brightness. Claiming the wheel on a screen
 * that does nothing with it does not merely waste the gesture — it removes working
 * brightness control from the phone. So [handles] is asked first, and every screen that
 * has nothing to scroll or adjust must let the key through.
 */
object WheelInput {

    private const val SCANCODE_CCW = 19
    private const val SCANCODE_CW = 20

    private val ccwKeyCode: Int by lazy { KeyEvent.keyCodeFromString("WHEEL_CCW") }
    private val cwKeyCode: Int by lazy { KeyEvent.keyCodeFromString("WHEEL_CW") }

    enum class Turn { Up, Down }

    /** The direction of a notch, or null if this key is not the wheel. */
    fun turnOf(keyCode: Int, event: KeyEvent?): Turn? {
        val scan = event?.scanCode ?: 0
        return when {
            keyCode == cwKeyCode && cwKeyCode != KeyEvent.KEYCODE_UNKNOWN -> Turn.Up
            keyCode == ccwKeyCode && ccwKeyCode != KeyEvent.KEYCODE_UNKNOWN -> Turn.Down
            scan == SCANCODE_CW -> Turn.Up
            scan == SCANCODE_CCW -> Turn.Down
            else -> null
        }
    }

    fun handles(keyCode: Int, event: KeyEvent?): Boolean = turnOf(keyCode, event) != null
}
