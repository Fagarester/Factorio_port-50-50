package com.factorio.xconnector;

/** Opt-in, bounded diagnostics of actual output-buffer flushes, not delivery claims. */
public final class WireTrace {
    public interface Sink {
        boolean isEnabled();
        void log(String line);
    }

    private final int fd;
    private final int resourceBase;
    private final Sink sink;
    private final int[] counts = new int[128];

    public WireTrace(int fd, int resourceBase, Sink sink) {
        this.fd = fd;
        this.resourceBase = resourceBase;
        this.sink = sink;
    }

    public boolean isEnabled() { return sink.isEnabled(); }

    public void sent(byte[] header, int expected, int written, int error) {
        if (header == null || header.length < 4) return;
        int type = header[0] & 127;
        int n = ++counts[type];
        boolean failed = written != expected;
        int budget = type == 6 ? 8 : type == 35 ? 12 : 64;
        if (!failed && n > budget && n % 512 != 0) return;
        StringBuilder line = new StringBuilder("WIRE-TX fd=").append(fd)
            .append(" base=0x").append(Integer.toHexString(resourceBase))
            .append(" type=").append(type).append(" seq=").append(u16(header, 2))
            .append(" bytes=").append(written).append('/').append(expected)
            .append(" errno=").append(error).append(" sample=").append(n);
        long packetBytes = type == 0 ? 32 : (type == 1 || type == 35) && header.length >= 8
            ? 32L + 4L * u32(header, 4) : 32;
        line.append(" first-packet-bytes=").append(packetBytes);
        if (packetBytes != expected) line.append(" CHECK-FRAMING-OR-BATCH");
        if (type >= 2 && type <= 8 && header.length >= 20)
            line.append(" window=0x").append(Long.toHexString(u32(header, 12)));
        else if ((type == 9 || type == 10) && header.length >= 8)
            line.append(" window=0x").append(Long.toHexString(u32(header, 4)));
        else if (type == 35 && header.length >= 20)
            line.append(" extension=").append(header[1] & 255)
                .append(" evtype=").append(u16(header, 8))
                .append(" event-id=0x").append(Long.toHexString(u32(header, 12)))
                .append(" window=0x").append(Long.toHexString(u32(header, 16)));
        if (failed) line.append(" INCOMPLETE-WRITE");
        line.append(" hex=");
        final char[] hex = "0123456789abcdef".toCharArray();
        for (byte b : header) line.append(hex[(b & 255) >>> 4]).append(hex[b & 15]);
        sink.log(line.toString());
    }

    private static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p+1] & 255) << 8); }
    private static long u32(byte[] b, int p) {
        return (u16(b, p) | ((long)u16(b, p+2) << 16)) & 0xffffffffL;
    }
}
