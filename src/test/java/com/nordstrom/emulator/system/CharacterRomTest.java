package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks in {@link CharacterRom}'s addressing ({@code code * 8 + row})
 * and, more importantly, the masking detail that took real
 * investigation to get right: the fetched byte's 8th bit is genuine
 * noise from this chip's reuse across unrelated systems, confirmed
 * directly against MAME's own source for the original II/II+ code
 * path. The strongest original confirmation was printing the resulting
 * glyph as ASCII art and recognizing an actual letter -- this test
 * checks the same underlying bit pattern precisely instead, since a
 * human reading ASCII art isn't something a test suite can do, but the
 * bits it read are exactly reproducible.
 */
class CharacterRomTest {

    @Test
    void checksumVerifiesAtClassLoadTimeWithoutThrowing() {
        // If the ROM's checksum didn't match, referencing the class at all would already have
        // thrown during its static initializer -- this test existing and running at all is itself
        // part of the confirmation, made explicit here rather than left implicit.
        CharacterRom.read(0, 0);
    }

    @Test
    void theHighNoiseBitIsMaskedOffRegardlessOfCodeRange() {
        // 'A' at $41 (flash range) and $C1 (normal range) share the same lower 7 bits
        // ($C1 & 0x7F == $41) -- once masked, every row must match exactly, confirming the
        // 8th bit really is discarded, not part of the glyph.
        for (int row = 0; row < 8; row++) {
            assertEquals(CharacterRom.read(0x41, row), CharacterRom.read(0xC1, row),
                "row " + row + " should match once the noise bit is masked off");
        }
    }

    @Test
    void allThreeRepeatedCopiesOfACharacterProduceIdenticalMaskedBits() {
        // $41 (flash), $81 (one normal-range copy), $C1 (the other normal-range copy) --
        // per the original investigation, these are three separate physical ROM locations
        // that all encode the same glyph, differing only in the masked-off noise bit.
        for (int row = 0; row < 8; row++) {
            int a41 = CharacterRom.read(0x41, row);
            int a81 = CharacterRom.read(0x81, row);
            int aC1 = CharacterRom.read(0xC1, row);
            assertEquals(a41, a81, "row " + row + ": $41 vs $81");
            assertEquals(a41, aC1, "row " + row + ": $41 vs $C1");
        }
    }

    @Test
    void readReturnsOnlySevenMeaningfulBits() {
        for (int code = 0; code < 256; code += 17) { // sample across the range, not exhaustively
            for (int row = 0; row < 8; row++) {
                int value = CharacterRom.read(code, row);
                assertEquals(value, value & 0x7F, "code " + code + " row " + row + " should never have bit 7 set");
            }
        }
    }

    @Test
    void theLetterAHasTheExactConfirmedBitPattern() {
        // The same pattern originally confirmed by printing it as ASCII art and recognizing a
        // real letter "A" -- pinned down numerically here (values confirmed by running the real
        // ROM, not derived by hand) so a future change can't silently corrupt the glyph without a
        // test noticing, even though a test can't "look" at ASCII art the way a person can.
        int[] expectedRows = {0, 8, 20, 34, 34, 62, 34, 34};
        for (int row = 0; row < 8; row++) {
            assertEquals(expectedRows[row], CharacterRom.read(0xC1, row), "row " + row + " of 'A'");
        }
    }
}
