package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;

import java.awt.Color;

/**
 * Renders the current 40x48 low-resolution graphics screen into a pixel
 * grid, using {@link VideoSoftSwitches} for which page is active. A pure
 * function of the current bus and switch state, the same shape as
 * {@link TextScreenRenderer} (see that class's own Javadoc for why
 * neither class needs {@link VideoScanner}'s cycle accuracy for pixel
 * *content* -- only *which mode applies to which scan line*, which
 * {@link ScanlineModes} handles separately, needs that).
 * <p>
 * Row-to-address addressing matches {@link TextScreenRenderer}'s own
 * formula exactly -- Lo-Res shares text mode's memory pages entirely,
 * just reinterprets each byte as two stacked color blocks instead of a
 * character code. Each of the 24 text rows holds two lo-res block-rows:
 * the low nibble of a byte is the top block's color, the high nibble is
 * the bottom block's, a real, standard hardware fact about how the
 * Apple II's video generator reads this memory, not a rendering choice.
 * <p>
 * Color values: the 16 lo-res colors have no single, universally-agreed
 * RGB values -- real hardware's actual on-screen color depended on the
 * specific TV/monitor, and even serious modern emulators have disagreed
 * (AppleWin's own palette has been described as closer to the IIgs's
 * defined palette than to real 8-bit Apple II NTSC output, and at least
 * one other project's hand-copied "AppleWin" palette table was later
 * found to have been reconstructed from fabricated source comments, not
 * the real thing). The values here are Robert Munafo's published
 * derivation from the actual YIQ/NTSC composite math (mrob.com/pub/
 * xapple2/colors.html), cross-checked against the independently
 * published YIQ table for these same 16 colors (Wikipedia, "Apple II
 * graphics") and against well-known Hi-Res/Lo-Res color equivalences
 * (e.g. lo-res 3/Purple matching hi-res 2, lo-res 12/Green matching
 * hi-res 1) -- the best-documented available reference, not a claim of
 * one undisputed ground truth.
 */
public final class LoResRenderer {

    /** Lo-Res screen columns (blocks). */
    public static final int COLUMNS = 40;
    /** Lo-Res screen block-rows (two per text row). */
    public static final int BLOCK_ROWS = 48;
    /** Block width in pixels. */
    public static final int BLOCK_WIDTH = 7;
    /** Block height in pixels. */
    public static final int BLOCK_HEIGHT = 4;

    private static final Color[] PALETTE = {
        new Color(0x00, 0x00, 0x00), // 0  black
        new Color(0x90, 0x17, 0x40), // 1  magenta
        new Color(0x40, 0x2C, 0xA5), // 2  dark blue
        new Color(0xD0, 0x43, 0xE5), // 3  purple
        new Color(0x00, 0x69, 0x40), // 4  dark green
        new Color(0x80, 0x80, 0x80), // 5  gray 1
        new Color(0x2F, 0x95, 0xE5), // 6  medium blue
        new Color(0xBF, 0xAB, 0xFF), // 7  light blue
        new Color(0x40, 0x54, 0x00), // 8  brown
        new Color(0xD0, 0x6A, 0x1A), // 9  orange
        new Color(0x80, 0x80, 0x80), // 10 gray 2
        new Color(0xFF, 0x96, 0xBF), // 11 pink
        new Color(0x2F, 0xBC, 0x1A), // 12 green
        new Color(0xBF, 0xD3, 0x5A), // 13 yellow
        new Color(0x6F, 0xE8, 0xBF), // 14 aqua
        new Color(0xFF, 0xFF, 0xFF), // 15 white
    };

    /**
     * Renders the current lo-res screen.
     *
     * @param bus the memory bus to read screen bytes from
     * @param videoSoftSwitches which page is active
     * @return a (BLOCK_ROWS*BLOCK_HEIGHT) x (COLUMNS*BLOCK_WIDTH) grid of actual colors
     */
    public static Color[][] render(MemoryBus bus, VideoSoftSwitches videoSoftSwitches) {
        Color[][] pixels = new Color[BLOCK_ROWS * BLOCK_HEIGHT][COLUMNS * BLOCK_WIDTH];
        int pageBase = videoSoftSwitches.isPage2() ? 0x800 : 0x400;

        for (int textRow = 0; textRow < TextScreenRenderer.ROWS; textRow++) {
            int rowBase = pageBase + 0x80 * (textRow % 8) + 0x28 * (textRow / 8);
            for (int col = 0; col < COLUMNS; col++) {
                int b = bus.read(rowBase + col) & 0xFF;
                Color top = PALETTE[b & 0x0F];
                Color bottom = PALETTE[(b >> 4) & 0x0F];
                fillBlock(pixels, textRow * 2, col, top);
                fillBlock(pixels, textRow * 2 + 1, col, bottom);
            }
        }
        return pixels;
    }

    private static void fillBlock(Color[][] pixels, int blockRow, int col, Color color) {
        int pixelRowBase = blockRow * BLOCK_HEIGHT;
        int pixelColBase = col * BLOCK_WIDTH;
        for (int dy = 0; dy < BLOCK_HEIGHT; dy++) {
            for (int dx = 0; dx < BLOCK_WIDTH; dx++) {
                pixels[pixelRowBase + dy][pixelColBase + dx] = color;
            }
        }
    }

    private LoResRenderer() {}
}
