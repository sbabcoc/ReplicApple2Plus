package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScreenTextTest {

    /** Plain 64K of RAM -- enough for text pages, no ROM files needed. */
    private static final class Ram implements MemoryBus {
        final int[] bytes = new int[0x10000];

        @Override public int read(int address) { return bytes[address]; }
        @Override public void write(int address, int value) { bytes[address] = value & 0xFF; }

        /** Fills a text page with normal-video spaces. */
        Ram blank(int pageBase) {
            for (int row = 0; row < 24; row++) {
                Arrays.fill(bytes, rowBase(pageBase, row), rowBase(pageBase, row) + 40, 0xA0);
            }
            return this;
        }

        /** Writes ASCII text at a row/column as normal-video screen codes. */
        Ram put(int pageBase, int row, int col, String text) {
            for (int i = 0; i < text.length(); i++) {
                bytes[rowBase(pageBase, row) + col + i] = text.charAt(i) | 0x80;
            }
            return this;
        }
    }

    private static int rowBase(int pageBase, int row) {
        return pageBase + 0x80 * (row % 8) + 0x28 * (row / 8);
    }

    private static ScanlineModes.LineMode[] frame(ScanlineModes.Source source, boolean page2) {
        ScanlineModes.LineMode[] modes = new ScanlineModes.LineMode[ScanlineModes.LINES];
        Arrays.fill(modes, new ScanlineModes.LineMode(source, page2));
        return modes;
    }

    @Test
    void decodesAllThreeVideoRangesToTheSamePlainCharacter() {
        // 'A' as inverse ($01), flashing ($41) and normal ($C1, and the duplicate copy at $81).
        for (int code : new int[] {0x01, 0x41, 0x81, 0xC1}) {
            assertEquals('A', ScreenText.decode(code), "code $" + Integer.toHexString(code));
        }
        assertEquals(' ', ScreenText.decode(0xA0));
        assertEquals('0', ScreenText.decode(0xB0));
        assertEquals('?', ScreenText.decode(0x3F)); // inverse '?'
        assertEquals('@', ScreenText.decode(0x80));
        assertEquals('_', ScreenText.decode(0xDF));
    }

    @Test
    void textModeCopiesRowsInScreenOrderWithTrailingSpacesAndBlankRowsTrimmed() {
        Ram ram = new Ram().blank(0x400)
            .put(0x400, 0, 0, "]LIST")
            .put(0x400, 2, 0, "10 PRINT \"HELLO\"   ")
            .put(0x400, 8, 0, "ROW 8") // 8 is the first row of the screen's second third -- a different memory interleave
            .put(0x400, 16, 2, "INDENTED");
        String expected = String.join("\n",
            "]LIST", "", "10 PRINT \"HELLO\"", "", "", "", "", "", "ROW 8",
            "", "", "", "", "", "", "", "  INDENTED");
        assertEquals(expected, ScreenText.capture(ram, frame(ScanlineModes.Source.TEXT, false)));
    }

    @Test
    void page2IsReadWhenPage2IsDisplayed() {
        Ram ram = new Ram().blank(0x400).blank(0x800)
            .put(0x400, 0, 0, "PAGE ONE")
            .put(0x800, 0, 0, "PAGE TWO");
        assertEquals("PAGE TWO", ScreenText.capture(ram, frame(ScanlineModes.Source.TEXT, true)));
    }

    @Test
    void mixedModeCopiesOnlyTheTextRows() {
        Ram ram = new Ram().blank(0x400)
            .put(0x400, 0, 0, "UNDER THE GRAPHICS")
            .put(0x400, 20, 0, "]CALL -151")
            .put(0x400, 23, 0, "*");
        ScanlineModes.LineMode[] modes = frame(ScanlineModes.Source.HIRES, false);
        Arrays.fill(modes, 160, 192, new ScanlineModes.LineMode(ScanlineModes.Source.TEXT, false));
        assertEquals("]CALL -151\n\n\n*", ScreenText.capture(ram, modes));
    }

    @Test
    void aRowThatIsOnlyPartlyTextIsLeftOut() {
        Ram ram = new Ram().blank(0x400).put(0x400, 1, 0, "SPLIT ROW").put(0x400, 2, 0, "WHOLE ROW");
        ScanlineModes.LineMode[] modes = frame(ScanlineModes.Source.LORES, false);
        Arrays.fill(modes, 12, 24, new ScanlineModes.LineMode(ScanlineModes.Source.TEXT, false)); // row 1 half, row 2 whole
        assertEquals("WHOLE ROW", ScreenText.capture(ram, modes));
    }

    @Test
    void fullScreenGraphicsCopiesNothing() {
        Ram ram = new Ram().blank(0x400).put(0x400, 0, 0, "HIDDEN");
        assertEquals("", ScreenText.capture(ram, frame(ScanlineModes.Source.LORES, false)));
    }
}
