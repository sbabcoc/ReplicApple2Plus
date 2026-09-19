package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;

/**
 * Renders the current 40x24 text screen into a pixel grid, using
 * {@link CharacterRom} for glyphs and {@link VideoSoftSwitches} for
 * which page is active. A pure function of the current bus and switch
 * state plus a caller-supplied flash-visibility flag -- flash is a
 * real-time visual effect tied to wall-clock time, not emulated CPU
 * cycles, so this class holds no timing state of its own, the same
 * reasoning already applied to {@link SystemClock} excluding real-time
 * pacing from cycle-accurate execution. Whoever drives the actual
 * display (a Swing repaint loop, most likely) owns toggling that flag
 * roughly twice a second; this class just answers "what does the
 * screen look like right now, given that flag's current value."
 * <p>
 * Row-to-address addressing matches {@link VideoScanner}'s own
 * text/lores formula exactly, applied directly across all 24 rows at
 * once rather than tied to per-cycle scanline timing -- text-mode
 * rendering doesn't need {@link VideoScanner}'s cycle accuracy at all,
 * since nothing about "what character is in this cell" depends on
 * exactly when within the frame it's read.
 * <p>
 * Deliberately scoped to text mode only. Real hardware would show
 * something different when the graphics/hires switches are active;
 * this class has no opinion about that and assumes the caller only
 * invokes it while text mode is genuinely what should be displayed.
 */
public final class TextScreenRenderer {

    /** Text screen columns. */
    public static final int COLUMNS = 40;
    /** Text screen rows. */
    public static final int ROWS = 24;
    /** Glyph cell width in pixels. */
    public static final int GLYPH_WIDTH = 7;
    /** Glyph cell height in pixels. */
    public static final int GLYPH_HEIGHT = 8;

    /**
     * Renders the current text screen.
     *
     * @param bus the memory bus to read screen bytes from
     * @param videoSoftSwitches which page is active
     * @param flashVisible whether flashing characters should currently
     *                     show inverted (the caller toggles this over
     *                     real time; this method makes no assumption
     *                     about how often)
     * @return a (ROWS*GLYPH_HEIGHT) x (COLUMNS*GLYPH_WIDTH) grid; true
     *         means a lit pixel
     */
    public static boolean[][] render(MemoryBus bus, VideoSoftSwitches videoSoftSwitches, boolean flashVisible) {
        boolean[][] pixels = new boolean[ROWS * GLYPH_HEIGHT][COLUMNS * GLYPH_WIDTH];
        int pageBase = videoSoftSwitches.isPage2() ? 0x800 : 0x400;

        for (int row = 0; row < ROWS; row++) {
            int rowBase = pageBase + 0x80 * (row % 8) + 0x28 * (row / 8);
            for (int col = 0; col < COLUMNS; col++) {
                int code = bus.read(rowBase + col) & 0xFF;
                boolean invert = shouldInvert(code, flashVisible);
                renderGlyph(pixels, row, col, code, invert);
            }
        }
        return pixels;
    }

    private static void renderGlyph(boolean[][] pixels, int row, int col, int code, boolean invert) {
        for (int glyphRow = 0; glyphRow < GLYPH_HEIGHT; glyphRow++) {
            int bits = CharacterRom.read(code, glyphRow);
            if (invert) {
                bits ^= 0x7F;
            }
            int pixelRow = row * GLYPH_HEIGHT + glyphRow;
            for (int col7 = 0; col7 < GLYPH_WIDTH; col7++) {
                // bit 6 is the leftmost dot, per CharacterRom's own documented bit order
                boolean lit = ((bits >> (GLYPH_WIDTH - 1 - col7)) & 1) != 0;
                pixels[pixelRow][col * GLYPH_WIDTH + col7] = lit;
            }
        }
    }

    private static boolean shouldInvert(int code, boolean flashVisible) {
        if (code < 0x40) {
            return true; // inverse range: always inverted
        }
        if (code < 0x80) {
            return !flashVisible; // flash range: alternates
        }
        return false; // normal range: never inverted
    }

    private TextScreenRenderer() {}
}
