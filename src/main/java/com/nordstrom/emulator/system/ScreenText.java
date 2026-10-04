package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;

/**
 * Reads the text currently on screen back out as plain characters -- the
 * Edit menu's "Copy Screen."
 * <p>
 * Which rows count as text is taken from {@link ScanlineModes}' completed
 * frame, the same per-scan-line record {@code ScreenPanel} paints from, so
 * this captures exactly what is displayed: all 24 rows in text mode, only
 * the text rows in mixed mode or a mid-frame mode split, and nothing at
 * all in full-screen graphics. A text row is included only if all 8 of
 * its scan lines were text; its page comes from its first scan line.
 * <p>
 * Screen codes are decoded the way the II+ character generator displays
 * them: the low 6 bits select one of 64 glyphs ({@code $00-$1F} are
 * {@code @A-Z[\]^_}, {@code $20-$3F} are space through {@code ?}), and the
 * top two bits only choose inverse ({@code $00-$3F}), flashing
 * ({@code $40-$7F}) or normal ({@code $80-$FF}) display -- see
 * {@link TextScreenRenderer}. Copied text is therefore always plain,
 * uppercase ASCII regardless of how it was shown.
 */
public final class ScreenText {

    private static final int LINES_PER_ROW = ScanlineModes.LINES / TextScreenRenderer.ROWS;

    private ScreenText() {}

    /**
     * Captures the displayed text rows, with trailing spaces trimmed from
     * every row and trailing blank rows dropped.
     *
     * @param bus   memory to read the text pages from
     * @param modes one completed frame's per-scan-line modes, as from
     *              {@link ScanlineModes#completedFrame()}
     * @return the text, rows separated by {@code \n}; empty if no row is text
     */
    public static String capture(MemoryBus bus, ScanlineModes.LineMode[] modes) {
        StringBuilder text = new StringBuilder();
        int keptLength = 0; // length of text up to and including the last non-blank row
        boolean anyRow = false;
        for (int row = 0; row < TextScreenRenderer.ROWS; row++) {
            if (!isTextRow(modes, row)) {
                continue;
            }
            if (anyRow) {
                text.append('\n');
            }
            anyRow = true;
            int pageBase = modes[row * LINES_PER_ROW].page2() ? 0x800 : 0x400;
            int rowBase = pageBase + 0x80 * (row % 8) + 0x28 * (row / 8);
            StringBuilder line = new StringBuilder(TextScreenRenderer.COLUMNS);
            for (int col = 0; col < TextScreenRenderer.COLUMNS; col++) {
                line.append(decode(bus.read(rowBase + col)));
            }
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') {
                end--;
            }
            text.append(line, 0, end);
            if (end > 0) {
                keptLength = text.length();
            }
        }
        return text.substring(0, keptLength);
    }

    /** True if every scan line of this text row was displayed as text. */
    private static boolean isTextRow(ScanlineModes.LineMode[] modes, int row) {
        for (int line = row * LINES_PER_ROW; line < (row + 1) * LINES_PER_ROW; line++) {
            if (modes[line].source() != ScanlineModes.Source.TEXT) {
                return false;
            }
        }
        return true;
    }

    /**
     * One screen code to the character it displays as.
     *
     * @param screenCode a byte from a text page
     * @return the displayed character, as plain ASCII
     */
    static char decode(int screenCode) {
        int glyph = screenCode & 0x3F;
        return (char) (glyph < 0x20 ? glyph + 0x40 : glyph);
    }
}
