package com.nordstrom.emulator.expansion;

/**
 * Draws a {@link VideoTerm}'s screen the way its CRTC scans it: R1 columns
 * by R6 rows, R9 + 1 scan lines per row, starting at the VRAM address in
 * R12/R13 (which is how the firmware scrolls) and wrapping within the 2K
 * VRAM. Each cell's glyph comes from the character generator ROM -- 16
 * bytes per character, one per scan line, leftmost dot in bit 7 -- and the
 * cursor at R14/R15 covers scan lines R10 bits 0-4 through R11, shown
 * steadily, hidden, or blinking per R10 bits 5-6.
 * <p>
 * Bit 7 of a VRAM byte selects the card's alternate character set. Only the
 * standard set is modeled, so those characters use the standard glyphs. The
 * firmware stores bit 7 clear unless software switches to the alternate set.
 */
public final class VideoTermRenderer {

    /** Dots per character cell, across: the character ROM's 8 bits. */
    public static final int GLYPH_WIDTH = 8;
    private static final int GLYPH_LINES = 16;

    /** The fields-per-second rate the cursor blink is timed against. */
    private static final double FIELD_RATE = 60.0;

    private VideoTermRenderer() {}

    /**
     * @param card        the card to draw
     * @param cursorPhase whether a blinking cursor is in the visible half of
     *                    its cycle -- see {@link #cursorBlinkPhase}
     * @return the screen's dots, [scan line][dot], true = lit
     */
    public static boolean[][] render(VideoTerm card, boolean cursorPhase) {
        byte[] charset = VideoTermRoms.charset();
        int columns = Math.min(card.register(1), 128);
        int rows = Math.min(card.register(6) & 0x7F, 64);
        int linesPerRow = (card.register(9) & 0x1F) + 1;
        int start = ((card.register(12) & 0x3F) << 8) | card.register(13);
        int cursor = ((card.register(14) & 0x3F) << 8) | card.register(15);
        int cursorStart = card.register(10) & 0x1F;
        int cursorEnd = card.register(11) & 0x1F;
        boolean cursorShown = cursorDisplayed(card.register(10), cursorPhase);

        boolean[][] dots = new boolean[rows * linesPerRow][columns * GLYPH_WIDTH];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int address = (start + row * columns + col) & (VideoTerm.VRAM_SIZE - 1);
                int glyph = card.vram(address) & 0x7F;
                boolean cursorCell = cursorShown && address == (cursor & (VideoTerm.VRAM_SIZE - 1));
                for (int line = 0; line < linesPerRow; line++) {
                    int bits = line < GLYPH_LINES ? charset[glyph * GLYPH_LINES + line] & 0xFF : 0;
                    if (cursorCell && line >= cursorStart && line <= cursorEnd) {
                        bits ^= 0xFF;
                    }
                    boolean[] scanLine = dots[row * linesPerRow + line];
                    for (int dot = 0; dot < GLYPH_WIDTH; dot++) {
                        scanLine[col * GLYPH_WIDTH + dot] = (bits & (0x80 >> dot)) != 0;
                    }
                }
            }
        }
        return dots;
    }

    /**
     * Whether the cursor shows at all, given R10's mode bits 5-6: 00 steady,
     * 01 hidden, 10 blinking at 1/16 field rate, 11 blinking at 1/32.
     */
    static boolean cursorDisplayed(int r10, boolean cursorPhase) {
        return switch ((r10 >> 5) & 3) {
            case 0 -> true;
            case 1 -> false;
            default -> cursorPhase;
        };
    }

    /**
     * Which half of its blink cycle a cursor with this R10 is in at the given
     * time: a cycle of 16 or 32 fields, visible for its first half.
     *
     * @param r10   the cursor-start register, whose bit 5 picks the blink rate
     * @param nanos a monotonic time, e.g. {@link System#nanoTime()}
     * @return true in the visible half of the cycle
     */
    public static boolean cursorBlinkPhase(int r10, long nanos) {
        int fieldsPerCycle = ((r10 >> 5) & 1) == 1 ? 32 : 16;
        long field = (long) (nanos / 1_000_000_000.0 * FIELD_RATE);
        return field % fieldsPerCycle < fieldsPerCycle / 2;
    }

    /**
     * The screen's text as the CRTC displays it, one line per row, with
     * trailing spaces and trailing blank rows trimmed -- the 80-column
     * counterpart of {@code ScreenText}. Character codes {@code $20}-{@code $7F}
     * are ASCII in this character set; the graphics characters
     * {@code $00}-{@code $1F} come out as spaces.
     *
     * @param card the card to read
     * @return the displayed text, rows separated by {@code \n}
     */
    public static String text(VideoTerm card) {
        int columns = Math.min(card.register(1), 128);
        int rows = Math.min(card.register(6) & 0x7F, 64);
        int start = ((card.register(12) & 0x3F) << 8) | card.register(13);
        StringBuilder text = new StringBuilder();
        int keptLength = 0;
        for (int row = 0; row < rows; row++) {
            if (row > 0) {
                text.append('\n');
            }
            StringBuilder line = new StringBuilder(columns);
            for (int col = 0; col < columns; col++) {
                int code = card.vram((start + row * columns + col) & (VideoTerm.VRAM_SIZE - 1)) & 0x7F;
                line.append(code < 0x20 ? ' ' : (char) code);
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
}
