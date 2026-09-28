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
 * Color values: see {@link GraphicsRenderer}, shared with
 * {@link HiResRenderer} since these are the same physical colors, not
 * two independently-sourced palettes.
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
                Color top = GraphicsRenderer.PALETTE[b & 0x0F];
                Color bottom = GraphicsRenderer.PALETTE[(b >> 4) & 0x0F];
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
