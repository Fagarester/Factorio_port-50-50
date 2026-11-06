package com.factory.xserver;

import com.termux.x11.LorieView;

/**
 * What the on-screen controls, the touchpad and the controller bindings drive: mouse and keyboard
 * of Termux:X11's X server, which runs in its own process and draws the game, or of the X server inside the app
 * (com.factorio.xserver, Gladio's "X server" display) when one is set with setEmbedded.
 * Coordinates are X screen pixels; Termux:X11 letterboxes the screen into the view the same way as the touchpad does.
 */
public class XServer {
    public static class ScreenInfo {
        public final short width;
        public final short height;

        public ScreenInfo(int width, int height) {
            this.width = (short)width;
            this.height = (short)height;
        }
    }

    private static final int LORIE_BUTTON_UNDEFINED = 0;
    private static final int LORIE_BUTTON_LEFT = 1;
    private static final int LORIE_BUTTON_MIDDLE = 2;
    private static final int LORIE_BUTTON_RIGHT = 3;
    private static final int WHEEL_NOTCH = 120;

    public final ScreenInfo screenInfo;
    public final Pointer pointer = new Pointer();
    private volatile LorieView lorieView;
    private volatile com.factorio.xserver.XServer embedded;
    private boolean relativeMouseMovement = false;

    public XServer(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
        pointer.moveTo(screenInfo.width / 2, screenInfo.height / 2);
    }

    public void setLorieView(LorieView lorieView) {
        this.lorieView = lorieView;
    }

    public void setEmbedded(com.factorio.xserver.XServer embedded) {
        this.embedded = embedded;
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public void injectPointerMove(int x, int y) {
        x = Math.max(0, Math.min(x, screenInfo.width - 1));
        y = Math.max(0, Math.min(y, screenInfo.height - 1));
        pointer.moveTo(x, y);
        com.factorio.xserver.XServer server = embedded;
        if (server != null) {
            server.injectPointerMove(x, y);
            return;
        }
        LorieView view = lorieView;
        if (view != null) view.sendMouseEvent(x, y, LORIE_BUTTON_UNDEFINED, false, false);
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        if (relativeMouseMovement && embedded == null) {
            LorieView view = lorieView;
            if (view != null) view.sendMouseEvent(dx, dy, LORIE_BUTTON_UNDEFINED, false, true);
            pointer.moveTo(Math.max(0, Math.min(pointer.getX() + dx, screenInfo.width - 1)),
                           Math.max(0, Math.min(pointer.getY() + dy, screenInfo.height - 1)));
        }
        else injectPointerMove(pointer.getX() + dx, pointer.getY() + dy);
    }

    public void injectPointerButtonPress(Pointer.Button button) {
        pointer.setButton(button, true);
        sendButton(button, true);
    }

    public void injectPointerButtonRelease(Pointer.Button button) {
        pointer.setButton(button, false);
        sendButton(button, false);
    }

    private void sendButton(Pointer.Button button, boolean down) {
        com.factorio.xserver.XServer server = embedded;
        if (server != null) {
            com.factorio.xserver.Pointer.Button same = com.factorio.xserver.Pointer.Button.valueOf(button.name());
            if (down) server.injectPointerButtonPress(same);
            else server.injectPointerButtonRelease(same);
            return;
        }
        LorieView view = lorieView;
        if (view == null) return;
        switch (button) {
            case BUTTON_LEFT:
                view.sendMouseEvent(0, 0, LORIE_BUTTON_LEFT, down, false);
                break;
            case BUTTON_MIDDLE:
                view.sendMouseEvent(0, 0, LORIE_BUTTON_MIDDLE, down, false);
                break;
            case BUTTON_RIGHT:
                view.sendMouseEvent(0, 0, LORIE_BUTTON_RIGHT, down, false);
                break;
            // a wheel notch is one event, sent on press
            case BUTTON_SCROLL_UP:
                if (down) view.sendMouseWheelEvent(0, -WHEEL_NOTCH);
                break;
            case BUTTON_SCROLL_DOWN:
                if (down) view.sendMouseWheelEvent(0, WHEEL_NOTCH);
                break;
            case BUTTON_SCROLL_CLICK_LEFT:
                if (down) view.sendMouseWheelEvent(-WHEEL_NOTCH, 0);
                break;
            case BUTTON_SCROLL_CLICK_RIGHT:
                if (down) view.sendMouseWheelEvent(WHEEL_NOTCH, 0);
                break;
        }
    }

    /** X keycodes are evdev codes + 8, Termux:X11 takes the evdev code. */
    public void injectKeyPress(XKeycode keycode) {
        sendKey(keycode, true);
    }

    public void injectKeyRelease(XKeycode keycode) {
        sendKey(keycode, false);
    }

    private void sendKey(XKeycode keycode, boolean down) {
        com.factorio.xserver.XServer server = embedded;
        if (server != null) {
            com.factorio.xserver.XKeycode same = com.factorio.xserver.XKeycode.valueOf(keycode.name());
            if (down) server.injectKeyPress(same);
            else server.injectKeyRelease(same);
            return;
        }
        LorieView view = lorieView;
        int code = keycode.id & 0xff;
        if (view == null || code <= 8) return;
        view.sendKeyEvent(code - 8, 0, down);
    }
}
