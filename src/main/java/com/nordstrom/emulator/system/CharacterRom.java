package com.nordstrom.emulator.system;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The Apple II+'s character generator ROM (Apple part 341-0036, a
 * repurposed general-purpose Signetics 2513 chargen chip also used
 * across many other, unrelated systems of that era). 2048 bytes: 256
 * possible character codes x 8 rows each.
 * <p>
 * Verified against MAME's own source (matching CRC32 64F415C6, SHA1
 * f9d312f128c9557d9d6ac03bfad6c3ddf83e5659 -- MAME's own comment flags
 * this as {@code BAD_DUMP}, meaning MAME's maintainers haven't fully
 * confirmed it against multiple physical chips; noted honestly rather
 * than glossed over, though it's the same dump used consistently
 * across MAME, the wider emulation community, and the archive this
 * project already sourced its other ROMs from).
 * <p>
 * {@link #read} returns the raw byte at {@code code * 8 + row}, masked
 * to its lower 7 bits. That masking is a real, permanent hardware fact
 * about this specific ROM, not a rendering choice: the 8th (high) bit
 * is genuine noise, a leftover from this chip's use in other, unrelated
 * systems, not part of the Apple II+'s own pixel pattern -- confirmed
 * directly against MAME's actual working source for the original
 * Apple II/II+ code path (a separate branch from IIe/IIgs, which handle
 * this chip differently): {@code bits = m_char_ptr[code * 8 + row] & 0x7f;}.
 * <p>
 * Deliberately does NOT apply inverse/flash inversion here -- that's a
 * genuine display-mode decision (which depends on the character code's
 * range AND, for flash, on a periodically-changing timing state this
 * class has no reason to know about), not a fact about what the ROM
 * contains. This class answers "what does the ROM say," not "how
 * should it currently be displayed" -- the same separation of concerns
 * already applied throughout this project (e.g. {@link VideoSoftSwitches}
 * reporting mode state without deciding how to render it).
 */
public final class CharacterRom {

    private static final byte[] DATA = load();

    private static byte[] load() {
        try (InputStream in = CharacterRom.class.getResourceAsStream("character-rom.rom")) {
            if (in == null) {
                throw new IllegalStateException(
                    "character-rom.rom is missing from the classpath -- this is a packaging bug");
            }
            byte[] data = in.readAllBytes();
            RomChecksum.verify(data, 0, data.length, "64F415C6", "CharacterRom");
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load character-rom.rom", e);
        }
    }

    /**
     * Reads one row of one character's raw pixel pattern, masked to its
     * meaningful lower 7 bits.
     *
     * @param code the character code, 0-255 (the raw stored screen byte,
     *             not a pre-stripped 6-bit or 7-bit value -- this
     *             method does the full-range addressing itself)
     * @param row 0-7, the row within this character's cell
     * @return the row's 7-bit pixel pattern (bit 6 is the leftmost of the 7 dots)
     */
    public static int read(int code, int row) {
        return DATA[code * 8 + row] & 0x7F;
    }

    private CharacterRom() {}
}
