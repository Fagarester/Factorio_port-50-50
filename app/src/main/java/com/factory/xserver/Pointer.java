package com.factory.xserver;

/** Pointer state as the on-screen controls see it; the real pointer lives in Termux:X11's X server. */
public class Pointer {
    public enum Button {
        BUTTON_LEFT, BUTTON_MIDDLE, BUTTON_RIGHT, BUTTON_SCROLL_UP, BUTTON_SCROLL_DOWN, BUTTON_SCROLL_CLICK_LEFT, BUTTON_SCROLL_CLICK_RIGHT;

        public byte code() {
            return (byte)(ordinal() + 1);
        }

        public int flag() {
            return 1<<(code() + MAX_BUTTONS);
        }
    }
    public static final byte MAX_BUTTONS = 7;
    private int buttonMask;
    private int x;
    private int y;

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    void moveTo(int x, int y) {
        this.x = x;
        this.y = y;
    }

    void setButton(Button button, boolean pressed) {
        if (pressed) buttonMask |= button.flag();
        else buttonMask &= ~button.flag();
    }

    public boolean isButtonPressed(Button button) {
        return (buttonMask & button.flag()) != 0;
    }
}
