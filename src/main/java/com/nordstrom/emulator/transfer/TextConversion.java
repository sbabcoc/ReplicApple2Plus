package com.nordstrom.emulator.transfer;

import java.io.ByteArrayOutputStream;

/**
 * Text conversion between guest and host, driven only by the adapter's
 * capabilities (TRANSFER-CARD.md 4.5). The host side is LF-terminated plain
 * ASCII; the guest side is whatever the capability record declares.
 */
public final class TextConversion {

    private TextConversion() {
    }

    /**
     * Guest text to host text: clears bit 7 if the guest sets it, and turns
     * the guest's line ending into LF.
     *
     * @param guest the text as the guest stores it
     * @param caps  the adapter's capabilities
     * @return the text for the host
     */
    public static byte[] toHost(byte[] guest, Capabilities caps) {
        byte[] lineEnd = caps.textLineEnd();
        ByteArrayOutputStream out = new ByteArrayOutputStream(guest.length);
        for (int i = 0; i < guest.length; ) {
            if (matches(guest, i, lineEnd, caps.textHighBit())) {
                out.write('\n');
                i += lineEnd.length;
            } else {
                out.write(caps.textHighBit() ? guest[i] & 0x7F : guest[i]);
                i++;
            }
        }
        return out.toByteArray();
    }

    /**
     * Host text to guest text: any of LF, CR LF or a lone CR becomes the
     * guest's line ending, and bit 7 is set if the guest sets it.
     *
     * @param host the text as the host stores it
     * @param caps the adapter's capabilities
     * @return the text for the guest
     */
    public static byte[] toGuest(byte[] host, Capabilities caps) {
        byte[] lineEnd = caps.textLineEnd();
        int highBit = caps.textHighBit() ? 0x80 : 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(host.length);
        for (int i = 0; i < host.length; i++) {
            int c = host[i] & 0xFF;
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < host.length && host[i + 1] == '\n') {
                    i++; // CR LF: one line ending
                }
                for (byte b : lineEnd) {
                    out.write(b | highBit);
                }
            } else {
                out.write(c | highBit);
            }
        }
        return out.toByteArray();
    }

    /**
     * Whether host content is plain text -- printable ASCII, tab, CR and LF
     * only -- for importing a file whose name doesn't say.
     *
     * @param host the file's bytes
     * @return true if they are plain text
     */
    public static boolean isPlainText(byte[] host) {
        for (byte b : host) {
            int c = b & 0xFF;
            if (!(c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c < 0x7F))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(byte[] data, int at, byte[] seq, boolean ignoreHighBit) {
        if (at + seq.length > data.length) {
            return false;
        }
        for (int j = 0; j < seq.length; j++) {
            int a = data[at + j] & 0xFF;
            int b = seq[j] & 0xFF;
            if (ignoreHighBit ? (a & 0x7F) != (b & 0x7F) : a != b) {
                return false;
            }
        }
        return true;
    }
}
