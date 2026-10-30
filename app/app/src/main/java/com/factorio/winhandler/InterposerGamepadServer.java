package com.factorio.winhandler;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import com.factorio.inputcontrols.GamepadState;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves virtual gamepads to NATIVE Linux (glibc) games through the Selkies input interposer
 * (selkies_input_interposer.so + fake libudev, loaded with LD_PRELOAD).
 *
 * Protocol (see input-interposer/README.md): per slot N there are two AF_UNIX stream sockets in
 * SELKIES_JS_SOCKET_PATH:  selkies_jsN.sock (joydev)  and  selkies_event100N.sock (evdev).
 * The game side connects, we send a 1360 byte js_config_t, the client answers with 1 byte (sizeof(long)),
 * then we stream js_event (joydev) / input_event (evdev) records.
 *
 * A slot's sockets exist only while a pad is attached to that slot (so the game sees real hot-plug).
 */
public class InterposerGamepadServer {
    private static final String TAG = "InterposerGamepad";
    public static final int SLOT_COUNT = 4;

    private static final int CONFIG_SIZE = 1360;
    private static final int NUM_BUTTONS = 11;
    private static final int NUM_AXES = 8;

    // evdev codes
    private static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
    private static final int[] BTN_MAP = {
        0x130, // A
        0x131, // B
        0x133, // X
        0x134, // Y
        0x136, // TL
        0x137, // TR
        0x13a, // SELECT
        0x13b, // START
        0x13c, // MODE
        0x13d, // THUMBL
        0x13e  // THUMBR
    };
    private static final int[] AXES_MAP = {
        0x00, // ABS_X
        0x01, // ABS_Y
        0x02, // ABS_Z   (left trigger)
        0x03, // ABS_RX
        0x04, // ABS_RY
        0x05, // ABS_RZ  (right trigger)
        0x10, // ABS_HAT0X
        0x11  // ABS_HAT0Y
    };

    // Winlator button index -> position in BTN_MAP (-1 = not mapped)
    private static final int[] WINLATOR_TO_BTN = {
        0,  // A
        1,  // B
        2,  // X
        3,  // Y
        4,  // L1
        5,  // R1
        6,  // SELECT
        7,  // START
        9,  // L3
        10  // R3
    };
    private static final int WINLATOR_L2 = 10, WINLATOR_R2 = 11;

    public interface RumbleListener {
        void onRumble(int slot, int strongMotor, int weakMotor);
    }

    private static class Client {
        final LocalSocket socket;
        final boolean evdev;
        volatile boolean is64bit = true;
        volatile boolean ready = false;
        OutputStream out;

        Client(LocalSocket socket, boolean evdev) {
            this.socket = socket;
            this.evdev = evdev;
        }
    }

    private class Endpoint {
        final int slot;
        final boolean evdev;
        final File path;
        LocalSocket bound;
        LocalServerSocket server;
        volatile boolean running;
        final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();

        Endpoint(int slot, boolean evdev) {
            this.slot = slot;
            this.evdev = evdev;
            this.path = new File(socketDir, evdev ? "selkies_event"+(1000+slot)+".sock" : "selkies_js"+slot+".sock");
        }

        boolean start() {
            try {
                if (path.exists()) path.delete();
                bound = new LocalSocket(LocalSocket.SOCKET_STREAM);
                bound.bind(new LocalSocketAddress(path.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
                server = new LocalServerSocket(bound.getFileDescriptor());
                path.setReadable(true, false);
                path.setWritable(true, false);
                running = true;
                Thread thread = new Thread(this::acceptLoop, "gp-accept-"+path.getName());
                thread.setDaemon(true);
                thread.start();
                return true;
            }
            catch (Exception e) {
                ;
                stop();
                return false;
            }
        }

        void acceptLoop() {
            while (running) {
                try {
                    LocalSocket socket = server.accept();
                    final Client client = new Client(socket, evdev);
                    Thread thread = new Thread(() -> serveClient(this, client), "gp-client-"+path.getName());
                    thread.setDaemon(true);
                    thread.start();
                }
                catch (IOException e) {
                    if (running) ;
                    break;
                }
            }
        }

        void stop() {
            running = false;
            try { if (server != null) server.close(); } catch (Exception e) {}
            try { if (bound != null) bound.close(); } catch (Exception e) {}
            server = null;
            bound = null;
            for (Client client : clients) {
                try { client.socket.close(); } catch (Exception e) {}
            }
            clients.clear();
            path.delete();
        }
    }

    private final File socketDir;
    private final RumbleListener rumbleListener;
    private final Endpoint[] jsEndpoints = new Endpoint[SLOT_COUNT];
    private final Endpoint[] evEndpoints = new Endpoint[SLOT_COUNT];
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Object stateLock = new Object();
    // last published state per slot
    private final short[][] lastButtons = new short[SLOT_COUNT][NUM_BUTTONS];
    private final short[][] lastAxes = new short[SLOT_COUNT][NUM_AXES];

    public InterposerGamepadServer(File socketDir, RumbleListener rumbleListener) {
        this.socketDir = socketDir;
        this.rumbleListener = rumbleListener;
        if (!socketDir.isDirectory()) socketDir.mkdirs();
        if (socketDir.getPath().length() + 24 > 107) {
            ;
        }
        for (int slot = 0; slot < SLOT_COUNT; slot++) resetState(slot);
    }

    private void resetState(int slot) {
        synchronized (stateLock) {
            for (int i = 0; i < NUM_BUTTONS; i++) lastButtons[slot][i] = 0;
            for (int i = 0; i < NUM_AXES; i++) lastAxes[slot][i] = 0;
            lastAxes[slot][2] = -32767; // triggers rest at the minimum
            lastAxes[slot][5] = -32767;
        }
    }

    /** Creates or removes the sockets of a slot, which the game sees as plugging / unplugging a pad. */
    public synchronized void setSlotPresent(int slot, boolean present) {
        if (slot < 0 || slot >= SLOT_COUNT) return;
        boolean isPresent = jsEndpoints[slot] != null;
        if (present == isPresent) return;

        if (present) {
            resetState(slot);
            // evdev first: SDL (through fake-udev) reacts to either socket appearing
            Endpoint ev = new Endpoint(slot, true);
            Endpoint js = new Endpoint(slot, false);
            if (ev.start() && js.start()) {
                evEndpoints[slot] = ev;
                jsEndpoints[slot] = js;
                ;
            }
            else {
                ev.stop();
                js.stop();
            }
        }
        else {
            jsEndpoints[slot].stop();
            evEndpoints[slot].stop();
            jsEndpoints[slot] = null;
            evEndpoints[slot] = null;
            ;
        }
    }

    public synchronized void stop() {
        for (int slot = 0; slot < SLOT_COUNT; slot++) setSlotPresent(slot, false);
        writer.shutdownNow();
    }

    private static short axis(float value) {
        int v = Math.round(value * 32767f);
        return (short)Math.max(-32767, Math.min(32767, v));
    }

    /** Converts Winlator's GamepadState and sends only what changed. Safe to call from the UI thread. */
    public void publish(int slot, GamepadState state) {
        if (slot < 0 || slot >= SLOT_COUNT || jsEndpoints[slot] == null) return;

        short[] buttons = new short[NUM_BUTTONS];
        for (int i = 0; i < WINLATOR_TO_BTN.length; i++) {
            if (state.isPressed(i)) buttons[WINLATOR_TO_BTN[i]] = 1;
        }

        short[] axes = new short[NUM_AXES];
        axes[0] = axis(state.thumbLX);
        axes[1] = axis(state.thumbLY);
        axes[2] = state.isPressed(WINLATOR_L2) ? (short)32767 : (short)-32767;
        axes[3] = axis(state.thumbRX);
        axes[4] = axis(state.thumbRY);
        axes[5] = state.isPressed(WINLATOR_R2) ? (short)32767 : (short)-32767;
        axes[6] = (short)state.getDPadX();
        axes[7] = (short)state.getDPadY();

        final int[] changedButtons = new int[NUM_BUTTONS];
        final int[] changedAxes = new int[NUM_AXES];
        int bc = 0, ac = 0;
        synchronized (stateLock) {
            for (int i = 0; i < NUM_BUTTONS; i++) {
                if (buttons[i] != lastButtons[slot][i]) {
                    lastButtons[slot][i] = buttons[i];
                    changedButtons[bc++] = i;
                }
            }
            for (int i = 0; i < NUM_AXES; i++) {
                if (axes[i] != lastAxes[slot][i]) {
                    lastAxes[slot][i] = axes[i];
                    changedAxes[ac++] = i;
                }
            }
        }
        if (bc == 0 && ac == 0) return;

        final int buttonCount = bc, axisCount = ac;
        final int fSlot = slot;
        try {
            writer.execute(() -> {
                int ms = (int)(System.currentTimeMillis() & 0x7fffffffL);
                long nowMs = System.currentTimeMillis();
                Endpoint js = jsEndpoints[fSlot], ev = evEndpoints[fSlot];
                for (int i = 0; i < buttonCount; i++) {
                    int idx = changedButtons[i];
                    sendEvent(js, ev, ms, nowMs, true, idx, buttons[idx]);
                }
                for (int i = 0; i < axisCount; i++) {
                    int idx = changedAxes[i];
                    sendEvent(js, ev, ms, nowMs, false, idx, axes[idx]);
                }
            });
        }
        catch (Exception e) {}
    }

    private void sendEvent(Endpoint js, Endpoint ev, int ms, long nowMs, boolean isButton, int index, short value) {
        if (js != null) {
            ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(ms).putShort(value).put((byte)(isButton ? 0x01 : 0x02)).put((byte)index);
            writeToClients(js, b.array(), null);
        }
        if (ev != null) {
            int type = isButton ? EV_KEY : EV_ABS;
            int code = isButton ? BTN_MAP[index] : AXES_MAP[index];
            writeToClients(ev, buildInputEvents(true, nowMs, type, code, value), buildInputEvents(false, nowMs, type, code, value));
        }
    }

    private static byte[] buildInputEvents(boolean is64, long nowMs, int type, int code, int value) {
        int recordSize = is64 ? 24 : 16;
        ByteBuffer b = ByteBuffer.allocate(recordSize * 2).order(ByteOrder.LITTLE_ENDIAN);
        long sec = nowMs / 1000, usec = (nowMs % 1000) * 1000;
        for (int i = 0; i < 2; i++) {
            if (is64) b.putLong(sec).putLong(usec);
            else b.putInt((int)sec).putInt((int)usec);
            if (i == 0) b.putShort((short)type).putShort((short)code).putInt(value);
            else b.putShort((short)EV_SYN).putShort((short)0).putInt(0); // SYN_REPORT
        }
        return b.array();
    }

    private void writeToClients(Endpoint endpoint, byte[] data64, byte[] data32) {
        for (Client client : endpoint.clients) {
            if (!client.ready) continue;
            try {
                byte[] data = (data32 != null && !client.is64bit) ? data32 : data64;
                synchronized (client) {
                    client.out.write(data);
                    client.out.flush();
                }
            }
            catch (IOException e) {
                endpoint.clients.remove(client);
                try { client.socket.close(); } catch (Exception ex) {}
            }
        }
    }

    private static byte[] buildConfig() {
        ByteBuffer b = ByteBuffer.allocate(CONFIG_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        byte[] name = "Microsoft X-Box 360 pad".getBytes();
        b.put(name, 0, Math.min(name.length, 254)); // NUL padded char[255]
        b.position(256);
        b.putShort((short)0x045e).putShort((short)0x028e).putShort((short)0x0114);
        b.putShort((short)NUM_BUTTONS).putShort((short)NUM_AXES);
        for (int i = 0; i < NUM_BUTTONS; i++) b.putShort((short)BTN_MAP[i]);
        b.position(266 + 1024);
        for (int i = 0; i < NUM_AXES; i++) b.put((byte)AXES_MAP[i]);
        return b.array();
    }

    private void serveClient(Endpoint endpoint, Client client) {
        try {
            client.socket.setSoTimeout(5000);
            client.out = client.socket.getOutputStream();
            InputStream in = client.socket.getInputStream();

            client.out.write(buildConfig());
            client.out.flush();

            int arch = in.read();
            if (arch < 0) throw new IOException("closed during handshake");
            client.is64bit = arch >= 8;
            client.socket.setSoTimeout(0);

            if (!endpoint.evdev) {
                // init burst: current state of every button and axis, JS_EVENT_INIT (0x80) OR'd in
                ByteBuffer b = ByteBuffer.allocate((NUM_BUTTONS + NUM_AXES) * 8).order(ByteOrder.LITTLE_ENDIAN);
                int ms = (int)(System.currentTimeMillis() & 0x7fffffffL);
                synchronized (stateLock) {
                    for (int i = 0; i < NUM_BUTTONS; i++) {
                        b.putInt(ms).putShort(lastButtons[endpoint.slot][i]).put((byte)0x81).put((byte)i);
                    }
                    for (int i = 0; i < NUM_AXES; i++) {
                        b.putInt(ms).putShort(lastAxes[endpoint.slot][i]).put((byte)0x82).put((byte)i);
                    }
                }
                synchronized (client) {
                    client.out.write(b.array());
                    client.out.flush();
                }
            }

            endpoint.clients.add(client);
            client.ready = true;

            // Read the reverse direction: force feedback records (evdev only), joydev writes are dropped
            byte[] record = new byte[16];
            while (true) {
                int read = 0;
                while (read < record.length) {
                    int n = in.read(record, read, record.length - read);
                    if (n < 0) throw new IOException("closed");
                    read += n;
                }
                if (endpoint.evdev && rumbleListener != null) {
                    ByteBuffer r = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
                    int kind = r.get(0) & 0xff;
                    if (kind == 1) rumbleListener.onRumble(endpoint.slot, r.getShort(4) & 0xffff, r.getShort(6) & 0xffff);
                    else if (kind == 2) rumbleListener.onRumble(endpoint.slot, 0, 0);
                }
            }
        }
        catch (Exception e) {
            // client went away: normal when the game closes a device
        }
        finally {
            client.ready = false;
            endpoint.clients.remove(client);
            try { client.socket.close(); } catch (Exception e) {}
        }
    }
}
