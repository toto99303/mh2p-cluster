/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom;

import java.io.ByteArrayOutputStream;

/** AA InputReport payload only. This class does not transmit anything.
 * Message ID0x8001; timestamp field1; RelativeEvent field6; repeated Rel field1;
 * keycode field1=65536 (rotary); signed int32 delta field2 (not zigzag).
 * Reference: opencardev/aasdk aap_protobuf at9bf6adf933665dee26532201719fac14a047ccf1.
 */
public final class Mh2pAaWheelWire {
    private Mh2pAaWheelWire() { }
    public static byte[] inputReport(long timestampNanos, int steps) {
        if (timestampNanos < 0 || steps == 0 || steps < -16 || steps > 16)
            throw new IllegalArgumentException("invalid rotary input");
        ByteArrayOutputStream rel = new ByteArrayOutputStream();
        rel.write(8); varint(rel, 65536L);
        rel.write(16); varint(rel, (long) steps);
        ByteArrayOutputStream relative = new ByteArrayOutputStream();
        message(relative, 10, rel.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(128); out.write(1); // Two-byte BE InputReport message type.
        out.write(8); varint(out, timestampNanos);
        message(out, 50, relative.toByteArray());
        return out.toByteArray();
    }
    private static void message(ByteArrayOutputStream out, int tag, byte[] bytes) {
        out.write(tag); varint(out, bytes.length); out.write(bytes, 0, bytes.length);
    }
    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~127L) != 0) { out.write(((int) value & 127) | 128); value >>>= 7; }
        out.write((int) value);
    }
}
