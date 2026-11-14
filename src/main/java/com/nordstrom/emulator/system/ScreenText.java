package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the text currently on screen back out as plain characters -- the
 * Edit menu's "Copy Screen Text."
 * <p>
 * Which rows count as text is taken from {@link ScanlineModes}' completed
 * frame, the same per-scan-line record {@code ScreenPanel} paints from, so
 * this captures exactly what is displayed: all 24 rows in text mode, only
 * the text rows in mixed mode or a mid-frame mode split, and nothing at
 * all in full-screen lo-res. A text row is included only if all 8 of
 * its scan lines were text; its page comes from its first scan line.
 * <p>
 * Full-screen hi-res holds no text-page text, but may be showing text
 * drawn from a buffer that a {@link MarkedScreenText} marker announces
 * (a2lib's Soft-70); if a marker is found, that buffer is copied instead,
 * and otherwise nothing is.
 * <p>
 * Screen codes are decoded the way the II+ character generator displays
 * them: the low 6 bits select one of 64 glyphs ({@code $00-$1F} are
 * {@code @A-Z[\]^_}, {@code $20-$3F} are space through {@code ?}), and the
 * top two bits only choose inverse ({@code $00-$3F}), flashing
 * ({@code $40-$7F}) or normal ({@code $80-$FF}) display -- see
 * {@link TextScreenRenderer}. Text copied from a text page is therefore
 * always plain, uppercase ASCII regardless of how it was shown.
 */
public final class ScreenText {

    private static final int LINES_PER_ROW = ScanlineModes.LINES / TextScreenRenderer.ROWS;

    private ScreenText() {}

    /**
     * Captures the displayed text rows -- or, in full-screen hi-res, the
     * marked buffer's -- with trailing spaces trimmed from every row and
     * trailing blank rows dropped.
     *
     * @param bus   memory to read the text pages or marked buffer from
     * @param modes one completed frame's per-scan-line modes, as from
     *              {@link ScanlineModes#completedFrame()}
     * @return the text, rows separated by {@code \n}; empty if there is no text to copy
     */
    public static String capture(MemoryBus bus, ScanlineModes.LineMode[] modes) {
        if (isFullScreenHiRes(modes)) {
            int marker = MarkedScreenText.find(bus);
            return marker < 0 ? "" : MarkedScreenText.capture(bus, marker);
        }
        List<CharSequence> rows = new ArrayList<>(TextScreenRenderer.ROWS);
        for (int row = 0; row < TextScreenRenderer.ROWS; row++) {
            if (!isTextRow(modes, row)) {
                continue;
            }
            int pageBase = modes[row * LINES_PER_ROW].page2() ? 0x800 : 0x400;
            int rowBase = pageBase + 0x80 * (row % 8) + 0x28 * (row / 8);
            StringBuilder line = new StringBuilder(TextScreenRenderer.COLUMNS);
            for (int col = 0; col < TextScreenRenderer.COLUMNS; col++) {
                line.append(decode(bus.read(rowBase + col)));
            }
            rows.add(line);
        }
        return joinTrimmed(rows);
    }

    /**
     * Whether {@link #capture} would find marked hi-res text: the frame is
     * full-screen hi-res and memory holds a {@link MarkedScreenText} marker.
     *
     * @param bus   memory to search for the marker
     * @param modes one completed frame's per-scan-line modes
     * @return true if there is marked text on screen to copy
     */
    public static boolean markedTextShown(MemoryBus bus, ScanlineModes.LineMode[] modes) {
        return isFullScreenHiRes(modes) && MarkedScreenText.find(bus) >= 0;
    }

    /**
     * Joins rows with {@code \n}, trimming trailing spaces from every row
     * and dropping trailing blank rows.
     *
     * @param rows the rows, top first
     * @return the joined text; empty if every row is blank
     */
    static String joinTrimmed(List<? extends CharSequence> rows) {
        StringBuilder text = new StringBuilder();
        int keptLength = 0; // length of text up to and including the last non-blank row
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            CharSequence line = rows.get(i);
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

    private static boolean isFullScreenHiRes(ScanlineModes.LineMode[] modes) {
        for (ScanlineModes.LineMode mode : modes) {
            if (mode.source() != ScanlineModes.Source.HIRES) {
                return false;
            }
        }
        return true;
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
