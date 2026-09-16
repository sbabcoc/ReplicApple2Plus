package com.nordstrom.emulator.system;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The Apple II+'s own motherboard system ROM at $D000-$FFFF: Applesoft
 * BASIC ($D000-$F7FF) and the Autostart Monitor ($F800-$FFFF). This is
 * what shows through at $D000-$FFFF when nothing overrides it -- an
 * empty slot 0, or a slot-0 card whose own read source currently
 * selects "ROM" rather than intercepting with its own RAM. Consumed
 * only by {@link SystemRomHandler} and {@link SlotZeroBankingHandler},
 * both in this same package -- a card like
 * {@link com.nordstrom.emulator.expansion.LanguageCard} never
 * references this class at all, deliberately: it has no business
 * knowing the Apple II+'s own ROM contents just to report that it
 * isn't intercepting a given address (see {@link SlotCard#readSlotZeroBank}).
 * <p>
 * Every chip verified against MAME's own source
 * ({@code src/mame/apple/apple2.cpp}, the {@code apple2p} driver):
 * <pre>
 *   $D000-$D7FF  341-0011      Applesoft BASIC     CRC32 6F05F949  SHA1 0287ebcef2c1ce11dc71be15a99d2d7e0e128b1e
 *   $D800-$DFFF  341-0012      Applesoft BASIC     CRC32 1F08087C  SHA1 a75ce5aab6401355bf1ab01b04e4946a424879b5
 *   $E000-$E7FF  341-0013      Applesoft BASIC     CRC32 2B8D9A89  SHA1 8d82a1da63224859bd619005fab62c4714b25dd7
 *   $E800-$EFFF  341-0014      Applesoft BASIC     CRC32 5719871A  SHA1 37501be96d36d041667c15d63e0c1eff2f7dd4e9
 *   $F000-$F7FF  341-0015      Applesoft BASIC     CRC32 9A04EECF  SHA1 e6bf91ed28464f42b807f798fc6422e5948bf581
 *   $F800-$FFFF  341-0020-00   Autostart Monitor   CRC32 079589C4  SHA1 a28852ff997b4790e53d8d0352112c4b1a395098
 * </pre>
 * At 12KB, this is too large for a {@code byte[]} array literal -- that
 * would compile to well over the JVM's 64KB-per-method bytecode limit
 * (one store instruction per element). Loaded instead from a bundled
 * classpath resource, the standard approach for embedded binary data
 * this size.
 */
final class SystemRom {

    private static final byte[] DATA = load();

    private static byte[] load() {
        try (InputStream in = SystemRom.class.getResourceAsStream("system-rom.rom")) {
            if (in == null) {
                throw new IllegalStateException(
                    "system-rom.rom is missing from the classpath -- this is a packaging bug");
            }
            byte[] data = in.readAllBytes();
            verify(data);
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load system-rom.rom", e);
        }
    }

    private static void verify(byte[] data) {
        RomChecksum.verify(data, 0x0000, 0x800, "6F05F949", "SystemRom $D000-$D7FF (341-0011)");
        RomChecksum.verify(data, 0x0800, 0x800, "1F08087C", "SystemRom $D800-$DFFF (341-0012)");
        RomChecksum.verify(data, 0x1000, 0x800, "2B8D9A89", "SystemRom $E000-$E7FF (341-0013)");
        RomChecksum.verify(data, 0x1800, 0x800, "5719871A", "SystemRom $E800-$EFFF (341-0014)");
        RomChecksum.verify(data, 0x2000, 0x800, "9A04EECF", "SystemRom $F000-$F7FF (341-0015)");
        RomChecksum.verify(data, 0x2800, 0x800, "079589C4", "SystemRom $F800-$FFFF (341-0020-00)");
    }

    /** Reads one byte (0-255) at {@code offset} (0-$2FFF) within this 12KB image. */
    static int read(int offset) {
        return DATA[offset] & 0xFF;
    }

    private SystemRom() {}
}
