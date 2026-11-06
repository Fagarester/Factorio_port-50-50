package com.factory.runtime

import com.factory.inputcontrols.GamepadState
import kotlin.math.roundToInt

/** The gamepad of the on-screen controls (GamepadState, XInput order) as the game's SDL pad, through InputBridge. */
object VirtualPad {
    /** GamepadState button index -> SDL button; L2 / R2 (10, 11) are triggers, sent as axes. */
    private val BUTTONS = arrayOf(PadButton.SOUTH, PadButton.EAST, PadButton.WEST, PadButton.NORTH,
                                  PadButton.LEFT_SHOULDER, PadButton.RIGHT_SHOULDER, PadButton.BACK, PadButton.START,
                                  PadButton.LEFT_STICK, PadButton.RIGHT_STICK)
    private val DPAD = arrayOf(PadButton.DPAD_UP, PadButton.DPAD_RIGHT, PadButton.DPAD_DOWN, PadButton.DPAD_LEFT)
    private var buttons = 0
    private val dpad = BooleanArray(4)
    private val axes = IntArray(PadAxis.entries.size)

    @Synchronized
    fun send(state: GamepadState) {
        for (i in BUTTONS.indices) {
            val pressed = state.isPressed(i)
            if (pressed != (buttons and (1 shl i) != 0)) InputBridge.button(BUTTONS[i], pressed)
        }
        buttons = state.buttons.toInt()
        for (i in DPAD.indices) {
            if (state.dpad[i] != dpad[i]) InputBridge.button(DPAD[i], state.dpad[i])
            dpad[i] = state.dpad[i]
        }
        axis(PadAxis.LEFT_X, state.thumbLX)
        axis(PadAxis.LEFT_Y, state.thumbLY)
        axis(PadAxis.RIGHT_X, state.thumbRX)
        axis(PadAxis.RIGHT_Y, state.thumbRY)
        trigger(PadAxis.LEFT_TRIGGER, state.isPressed(10))
        trigger(PadAxis.RIGHT_TRIGGER, state.isPressed(11))
    }

    private fun axis(axis: PadAxis, value: Float) = set(axis, (value.coerceIn(-1f, 1f) * 32767).roundToInt())

    private fun trigger(axis: PadAxis, pressed: Boolean) = set(axis, if (pressed) 32767 else 0)

    private fun set(axis: PadAxis, value: Int) {
        if (axes[axis.ordinal] == value) return
        axes[axis.ordinal] = value
        InputBridge.axis(axis, value)
    }
}
