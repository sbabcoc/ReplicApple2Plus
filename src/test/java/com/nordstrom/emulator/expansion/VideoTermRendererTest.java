package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoTermRendererTest {

    /** A card with the firmware's CRTC setup (80 x 24, 9 lines per row), every cell a space. */
    private static VideoTerm card() {
        VideoTerm card = new VideoTerm();
        card.configure(new Properties());
        int[] firmwareTable = {0x7B, 0x50, 0x5E, 0x29, 0x1B, 0x08, 0x18, 0x19, 0x00, 0x08, 0xE0, 0x08, 0x00, 0x00, 0x00, 0x00};
        for (int r = 0; r < firmwareTable.length; r++) {
            setRegister(card, r, firmwareTable[r]);
        }
        for (int address = 0; address < VideoTerm.VRAM_SIZE; address++) {
            poke(card, address, ' ');
        }
        setRegister(card, 14, 0x3F); // cursor parked off-screen unless a test moves it
        return card;
    }

    private static void setRegister(VideoTerm card, int register, int value) {
        card.writeIoSwitch(0x0, register);
        card.writeIoSwitch(0x1, value);
    }

    /** Writes VRAM the way software does: select the page, then store through the $CC00 window. */
    private static void poke(VideoTerm card, int address, int value) {
        card.readIoSwitch(((address >> 9) & 3) << 2);
        card.writeExpansionRom(0x400 + (address & 0x1FF), value);
    }

    private static String rowBits(boolean[][] dots, int line, int fromDot) {
        StringBuilder bits = new StringBuilder();
        for (int dot = fromDot; dot < fromDot + 8; dot++) {
            bits.append(dots[line][dot] ? '#' : '.');
        }
        return bits.toString();
    }

    @Test
    void theScreenIsEightyByTwentyFourCellsOfNineLinesEach() {
        boolean[][] dots = VideoTermRenderer.render(card(), true);
        assertEquals(24 * 9, dots.length);
        assertEquals(80 * 8, dots[0].length);
    }

    @Test
    void aCharacterIsDrawnFromTheCharacterRom() {
        VideoTerm card = card();
        poke(card, 0, 'A');
        boolean[][] dots = VideoTermRenderer.render(card, true);
        String[] expected = {"...##...", "..#..#..", ".#....#.", ".#....#.", ".######.", ".#....#.", ".#....#.", "........", "........"};
        for (int line = 0; line < 9; line++) {
            assertEquals(expected[line], rowBits(dots, line, 0), "line " + line);
        }
    }

    @Test
    void theStartAddressScrollsAndTheScreenWrapsAroundVram() {
        VideoTerm card = card();
        setRegister(card, 12, 0x07); // start at $7F0: 16 cells before the end of VRAM
        setRegister(card, 13, 0xF0);
        poke(card, 0x7F0, 'A');      // top-left cell
        poke(card, 0x000, 'B');      // 17th cell of the top row, after wrapping
        boolean[][] dots = VideoTermRenderer.render(card, true);
        assertEquals("...##...", rowBits(dots, 0, 0));
        assertEquals(".#####..", rowBits(dots, 0, 16 * 8));
    }

    @Test
    void theCursorInvertsItsLinesAndFollowsItsMode() {
        VideoTerm card = card();
        setRegister(card, 14, 0x00);
        setRegister(card, 15, 81); // row 1, column 1
        int line = 9, dot = 8;     // that cell's first line and first dot

        setRegister(card, 10, 0x00); // steady, lines 0-8
        assertTrue(VideoTermRenderer.render(card, false)[line][dot], "a steady cursor shows in either blink phase");

        setRegister(card, 10, 0x20); // hidden
        assertFalse(VideoTermRenderer.render(card, true)[line][dot]);

        setRegister(card, 10, 0x60); // blinking
        assertTrue(VideoTermRenderer.render(card, true)[line][dot]);
        assertFalse(VideoTermRenderer.render(card, false)[line][dot]);

        setRegister(card, 10, 0x05); // steady, but only from line 5
        assertFalse(VideoTermRenderer.render(card, true)[line][dot]);
        assertTrue(VideoTermRenderer.render(card, true)[line + 5][dot]);
    }

    @Test
    void blinkCyclesAreSixteenOrThirtyTwoFieldsVisibleForTheFirstHalf() {
        long field = 1_000_000_000L / 60;
        assertTrue(VideoTermRenderer.cursorBlinkPhase(0x60, 0));
        assertTrue(VideoTermRenderer.cursorBlinkPhase(0x60, 15 * field + field / 2));
        assertFalse(VideoTermRenderer.cursorBlinkPhase(0x60, 16 * field + field / 2));
        assertTrue(VideoTermRenderer.cursorBlinkPhase(0x40, 7 * field + field / 2));
        assertFalse(VideoTermRenderer.cursorBlinkPhase(0x40, 8 * field + field / 2));
    }

    @Test
    void textComesOutPlainWithTrailingSpaceAndBlankRowsTrimmed() {
        VideoTerm card = card();
        String first = "]PRINT \"lowercase too\"";
        for (int i = 0; i < first.length(); i++) {
            poke(card, i, first.charAt(i));
        }
        poke(card, 80 * 2 + 3, 0x01); // a graphics character on row 2
        poke(card, 80 * 2 + 4, 'x');
        assertEquals(first + "\n\n    x", VideoTermRenderer.text(card));
    }
}
