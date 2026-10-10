package com.factorio.inputcontrols;

public interface GamepadSlot {
    String getName();

    GamepadState getGamepadState();

    GamepadVibration getGamepadVibration();
}
