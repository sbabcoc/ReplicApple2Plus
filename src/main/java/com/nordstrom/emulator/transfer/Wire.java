package com.nordstrom.emulator.transfer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The encodings of TRANSFER-CARD.md 5.2: strings, paths, little-endian numbers. */
final class Wire {

    private Wire() {
    }

    /** Writes values in the protocol's encodings. */
    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer string(String s) {
            byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
            if (bytes.length > 255) {
                throw new IllegalArgumentException("string longer than 255 bytes: " + s);
            }
            out.write(bytes.length);
            out.writeBytes(bytes);
            return this;
        }

        Writer path(List<String> path) {
            if (path.size() > 255) {
                throw new IllegalArgumentException("path has more than 255 components");
            }
            out.write(path.size());
            path.forEach(this::string);
            return this;
        }

        Writer u8(int v) {
            out.write(v & 0xFF);
            return this;
        }

        Writer u16(int v) {
            out.write(v & 0xFF);
            out.write(v >> 8 & 0xFF);
            return this;
        }

        Writer u32(long v) {
            for (int i = 0; i < 4; i++) {
                out.write((int) (v >> 8 * i) & 0xFF);
            }
            return this;
        }

        Writer bytes(byte[] b) {
            out.writeBytes(b);
            return this;
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    /** Reads values in the protocol's encodings; reading past the end throws. */
    static final class Reader {
        private final byte[] in;
        private int pos;

        Reader(byte[] in) {
            this.in = in;
        }

        boolean atEnd() {
            return pos >= in.length;
        }

        int u8() {
            if (pos >= in.length) {
                throw new IllegalArgumentException("reply ended early");
            }
            return in[pos++] & 0xFF;
        }

        int u16() {
            return u8() | u8() << 8;
        }

        long u32() {
            long v = 0;
            for (int i = 0; i < 4; i++) {
                v |= (long) u8() << 8 * i;
            }
            return v;
        }

        String string() {
            int length = u8();
            if (pos + length > in.length) {
                throw new IllegalArgumentException("reply ended inside a string");
            }
            String s = new String(in, pos, length, StandardCharsets.ISO_8859_1);
            pos += length;
            return s;
        }

        List<String> path() {
            int count = u8();
            List<String> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                out.add(string());
            }
            return out;
        }

        byte[] rest() {
            byte[] r = java.util.Arrays.copyOfRange(in, pos, in.length);
            pos = in.length;
            return r;
        }
    }
}
