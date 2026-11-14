package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** Writes a version-1 text marker at {@code marker}, with its 80 x 24 buffer (all blank) right after it. */
    private static void mark(Ram ram, int marker, int version) {
        byte[] signature = "A2SCR80".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < signature.length; i++) {
            ram.bytes[marker + i] = signature[i];
        }
        int buffer = marker + 16;
        int[] fields = {version, 80, 24, 70, 1, buffer & 0xFF, buffer >> 8, buffer & 0xFF, buffer >> 8};
        for (int i = 0; i < fields.length; i++) {
            ram.bytes[marker + 7 + i] = fields[i];
        }
        Arrays.fill(ram.bytes, buffer, buffer + 80 * 24, 0);
    }

    /** Writes ASCII text into a marked buffer, encoded as encoding 1 (normal video unless {@code inverse}). */
    private static void putMarked(Ram ram, int marker, int row, int col, String text, boolean inverse) {
        for (int i = 0; i < text.length(); i++) {
            int screenCode = (text.charAt(i) & 0x7F) | (inverse ? 0 : 0x80);
            ram.bytes[marker + 16 + row * 80 + col + i] = screenCode ^ 0xA0;
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

    @Test
    void fullScreenHiResCopiesAllEightyColumnsOfMarkedText() {
        Ram ram = new Ram().blank(0x400).put(0x400, 0, 0, "TEXT PAGE");
        int marker = 0x1234;
        mark(ram, marker, 1);
        putMarked(ram, marker, 0, 0, "Gopher: lowercase kept", false);
        putMarked(ram, marker, 0, 75, "edge", false); // panned out of the 70 visible columns
        putMarked(ram, marker, 2, 1, "inverse", true);
        ram.bytes[marker + 16 + 3 * 80] = 0x01 ^ 0xA0; // a control code, shown as its letter
        String expected = String.join("\n",
            "Gopher: lowercase kept" + " ".repeat(53) + "edge", "", " inverse", "A");
        ScanlineModes.LineMode[] hires = frame(ScanlineModes.Source.HIRES, false);
        assertTrue(ScreenText.markedTextShown(ram, hires));
        assertEquals(expected, ScreenText.capture(ram, hires));
    }

    @Test
    void anAllZeroMarkedBufferIsABlankScreen() {
        Ram ram = new Ram();
        mark(ram, 0x0800, 1);
        assertEquals("", ScreenText.capture(ram, frame(ScanlineModes.Source.HIRES, false)));
    }

    @Test
    void markersOfOtherVersionsOrOutsideTheSearchRangeAreIgnored() {
        Ram ram = new Ram();
        mark(ram, 0x1000, 2);
        putMarked(ram, 0x1000, 0, 0, "VERSION 2", false);
        mark(ram, 0x2000, 1); // past $1FFF
        putMarked(ram, 0x2000, 0, 0, "TOO HIGH", false);
        ScanlineModes.LineMode[] hires = frame(ScanlineModes.Source.HIRES, false);
        assertFalse(ScreenText.markedTextShown(ram, hires));
        assertEquals("", ScreenText.capture(ram, hires));
    }

    @Test
    void aMarkerIsIgnoredUnlessTheWholeScreenIsHiRes() {
        Ram ram = new Ram().blank(0x400).put(0x400, 23, 0, "]");
        mark(ram, 0x1800, 1);
        putMarked(ram, 0x1800, 0, 0, "MARKED", false);
        ScanlineModes.LineMode[] mixed = frame(ScanlineModes.Source.HIRES, false);
        Arrays.fill(mixed, 160, 192, new ScanlineModes.LineMode(ScanlineModes.Source.TEXT, false));
        assertFalse(ScreenText.markedTextShown(ram, mixed));
        assertEquals("\n\n\n]", ScreenText.capture(ram, mixed));
        assertEquals("", ScreenText.capture(ram, frame(ScanlineModes.Source.LORES, false)));
    }
}
