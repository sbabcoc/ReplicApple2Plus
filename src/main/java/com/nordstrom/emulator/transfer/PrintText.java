package com.nordstrom.emulator.transfer;

import java.io.ByteArrayOutputStream;

/**
 * A print job as host text. What's printed is screen output -- the same for
 * every OS and BASIC: characters with bit 7 set, lines ending in CR -- so
 * the conversion needs no capability record: clear bit 7, CR to LF.
 */
public final class PrintText {

    private PrintText() {
    }

    /**
     * @param printed the job's bytes, as printed
     * @return the text for the host: plain ASCII, LF line endings
     */
    public static byte[] toHost(byte[] printed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(printed.length);
        for (byte b : printed) {
            int c = b & 0x7F;
            out.write(c == '\r' ? '\n' : c);
        }
        return out.toByteArray();
    }
}
