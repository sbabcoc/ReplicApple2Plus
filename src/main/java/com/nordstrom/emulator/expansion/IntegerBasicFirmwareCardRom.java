package com.nordstrom.emulator.expansion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The Integer BASIC Firmware Card's built-in ROM content: Programmer's
 * Aid #1 ($D000-$D7FF), an empty $D800-$DFFF (matching real base Apple
 * II hardware, which had nothing mapped there), Integer BASIC
 * ($E000-$F7FF), and the original, non-autostart Monitor ($F800-$FFFF)
 * -- 12KB total, per Apple's 1981 Level II Service Manual's documented
 * ROM population for this specific card. Not currently wired to
 * anything -- there is no {@code IntegerBasicFirmwareCard} class yet --
 * kept here, correctly identified, for when that card is actually
 * built. This is a genuinely different card from {@link LanguageCard}:
 * the Language Card is pure RAM with no ROM content of its own, while
 * this card is a fixed, non-bank-switched ROM set selected by a simple
 * two-address toggle ($C080 for Integer BASIC, $C081 for Applesoft).
 * <p>
 * Every chip verified against MAME's own source
 * ({@code src/mame/apple/apple2.cpp}):
 * <pre>
 *   $D000-$D7FF  341-0016  Programmer's Aid #1        CRC32 4234E88A  SHA1 c9a81d704dc2f0c3416c20f9c4ab71fedda937ed
 *   $D800-$DFFF  (empty)
 *   $E000-$E7FF  341-0001  Integer BASIC              CRC32 C0A4AD3B  SHA1 bf32195efcb34b694c893c2d342321ec3a24b98f
 *   $E800-$EFFF  341-0002  Integer BASIC              CRC32 A99C2CF6  SHA1 9767d92d04fc65c626223f25564cca31f5248980
 *   $F000-$F7FF  341-0003  Integer BASIC              CRC32 62230D38  SHA1 f268022da555e4c809ca1ae9e5d2f00b388ff61c
 *   $F800-$FFFF  341-0004  Original (non-autostart) Monitor  CRC32 020A86D0  SHA1 52a18bd578a4694420009cad7a7a5779a8c00226
 * </pre>
 * At 12KB, this is too large for a {@code byte[]} array literal -- that
 * would compile to well over the JVM's 64KB-per-method bytecode limit
 * (one store instruction per element), the same constraint that applies
 * to {@code DiskBootRom}'s much smaller data at a different scale.
 * Loaded instead from a bundled classpath resource, the standard
 * approach for embedded binary data this size.
 */
final class IntegerBasicFirmwareCardRom {

    private static final byte[] DATA = load();

    private static byte[] load() {
        try (InputStream in = IntegerBasicFirmwareCardRom.class.getResourceAsStream("integer-basic-firmware-card.rom")) {
            if (in == null) {
                throw new IllegalStateException(
                    "integer-basic-firmware-card.rom is missing from the classpath -- this is a packaging bug");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load integer-basic-firmware-card.rom", e);
        }
    }

    /** Reads one byte (0-255) at {@code offset} (0-$2FFF) within this 12KB image. */
    static int read(int offset) {
        return DATA[offset] & 0xFF;
    }

    private IntegerBasicFirmwareCardRom() {}
}
