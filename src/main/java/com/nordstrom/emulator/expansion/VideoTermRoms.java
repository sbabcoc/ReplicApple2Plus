package com.nordstrom.emulator.expansion;

import com.nordstrom.emulator.system.RomChecksum;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The Videx VideoTerm's two ROMs, loaded from classpath resources beside
 * this class and verified against the dumps documented in
 * HARDWARE-REFERENCE.md section 3:
 * <ul>
 *   <li>{@value #FIRMWARE_RESOURCE} -- firmware 2.4, 1024 bytes covering
 *       {@code $C800}-{@code $CBFF}; its last 256 bytes also appear in the
 *       card's own {@code $C300}-{@code $C3FF} page.</li>
 *   <li>{@value #CHARSET_RESOURCE} -- the character generator, 128 glyphs of
 *       16 scan lines, one byte per line.</li>
 * </ul>
 * Like the system ROMs, these are copyrighted dumps the user supplies; they
 * aren't in source control. Loaded on first use, not at class load, so that
 * merely discovering the card (which constructs every registered card) never
 * requires them -- only configuring one does.
 */
final class VideoTermRoms {

    static final String FIRMWARE_RESOURCE = "videx-videoterm-firmware-2_4.rom";
    static final String CHARSET_RESOURCE = "videx-videoterm-charset-normal.rom";

    static final int FIRMWARE_SIZE = 1024;
    static final int CHARSET_SIZE = 2048;

    private VideoTermRoms() {}

    /** Holder idiom: loaded and verified the first time firmware() is called. */
    private static final class Firmware {
        static final byte[] DATA = load(FIRMWARE_RESOURCE, FIRMWARE_SIZE, "4DDBE669", "VideoTerm firmware 2.4");
    }

    /** Holder idiom: loaded and verified the first time charset() is called. */
    private static final class Charset {
        static final byte[] DATA = load(CHARSET_RESOURCE, CHARSET_SIZE, "87F89F08", "VideoTerm character generator");
    }

    /** @return the 1024-byte firmware image ($C800-$CBFF) */
    static byte[] firmware() {
        return Firmware.DATA;
    }

    /** @return the 2048-byte character generator image */
    static byte[] charset() {
        return Charset.DATA;
    }

    private static byte[] load(String resource, int size, String crc32, String label) {
        try (InputStream in = VideoTermRoms.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing from the classpath -- the VideoTerm card needs "
                    + "it; place it in src/main/resources/com/nordstrom/emulator/expansion/");
            }
            byte[] data = in.readAllBytes();
            if (data.length != size) {
                throw new IllegalStateException(resource + " is " + data.length + " bytes, expected " + size);
            }
            RomChecksum.verify(data, 0, size, crc32, label);
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + resource, e);
        }
    }
}
