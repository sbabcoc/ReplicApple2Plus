package com.nordstrom.emulator.system;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The Language Card's built-in ROM content: Programmer's Aid #1
 * ($D000-$D7FF), an empty $D800-$DFFF (matching real base Apple II
 * hardware, which had nothing mapped there), Integer BASIC
 * ($E000-$F7FF), and the Autostart Monitor ($F800-$FFFF) -- 12KB
 * total. The Autostart Monitor, not the original (non-autostart)
 * monitor, matches the actual photographed "Apple Language Card"
 * product referenced in MAME's own source comments, which paired that
 * specific monitor chip with Integer BASIC.
 * <p>
 * Every chip verified against MAME's own source
 * ({@code src/mame/apple/apple2.cpp}):
 * <pre>
 *   $D000-$D7FF  341-0016  Programmer's Aid #1  CRC32 4234E88A  SHA1 c9a81d704dc2f0c3416c20f9c4ab71fedda937ed
 *   $D800-$DFFF  (empty)
 *   $E000-$E7FF  341-0001  Integer BASIC        CRC32 C0A4AD3B  SHA1 bf32195efcb34b694c893c2d342321ec3a24b98f
 *   $E800-$EFFF  341-0002  Integer BASIC        CRC32 A99C2CF6  SHA1 9767d92d04fc65c626223f25564cca31f5248980
 *   $F000-$F7FF  341-0003  Integer BASIC        CRC32 62230D38  SHA1 f268022da555e4c809ca1ae9e5d2f00b388ff61c
 *   $F800-$FFFF  341-0020  Autostart Monitor    CRC32 079589C4  SHA1 a28852ff997b4790e53d8d0352112c4b1a395098
 * </pre>
 * At 12KB, this is too large for a {@code byte[]} array literal -- that
 * would compile to well over the JVM's 64KB-per-method bytecode limit
 * (one store instruction per element). Loaded instead from a bundled
 * classpath resource ({@code language-card.rom}, alongside this class),
 * the standard approach for embedded binary data at this scale. A
 * missing resource is a packaging bug, not a recoverable condition, so
 * it fails loudly and immediately rather than being silently tolerated.
 */
final class LanguageCardRom {

    private static final byte[] DATA = load();

    private static byte[] load() {
        try (InputStream in = LanguageCardRom.class.getResourceAsStream("language-card.rom")) {
            if (in == null) {
                throw new IllegalStateException(
                    "language-card.rom is missing from the classpath -- this is a packaging bug");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load language-card.rom", e);
        }
    }

    /** Reads one byte (0-255) at {@code offset} (0-$2FFF) within this 12KB image. */
    static int read(int offset) {
        return DATA[offset] & 0xFF;
    }

    private LanguageCardRom() {}
}
