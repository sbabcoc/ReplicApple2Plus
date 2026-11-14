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
 * <p>
 * <b>Dots</b>: the video signal itself is monochrome. Each block is 14
 * dots of the 560-dot line, and its color number's 4 bits repeat across
 * them; a color monitor decodes the pattern's phase against the color
 * burst as a color, and a monochrome monitor shows the pattern itself.
 * {@link #dots} gives that pattern: dot d is lit when bit {@code d & 3} of
 * the color number is set. The phase is relative to the whole line, not
 * the block, so an odd block -- starting at dot 14 x odd, 2 mod 4 --
 * starts two bits into its pattern. Bit k's phase is pinned down by the
 * hi-res equivalences {@link HiResRenderer} uses: equal colors mean equal
 * phase, and hi-res violet (even pixels: phases 0-1) is color 3, green
 * (odd: 2-3) is 12, blue (even, delayed a dot: 1-2) is 6, and orange (odd,
 * delayed: 3-0) is 9 -- satisfied only by bit k at phase k.
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
    /** Block width in dots, two per pixel. */
    public static final int BLOCK_DOTS = BLOCK_WIDTH * 2;

    /**
     * Renders the current lo-res screen.
     *
     * @param bus the memory bus to read screen bytes from
     * @param videoSoftSwitches which page is active
     * @return a (BLOCK_ROWS*BLOCK_HEIGHT) x (COLUMNS*BLOCK_WIDTH) grid of actual colors
     */
    public static Color[][] render(MemoryBus bus, VideoSoftSwitches videoSoftSwitches) {
        Color[][] pixels = new Color[BLOCK_ROWS * BLOCK_HEIGHT][COLUMNS * BLOCK_WIDTH];
        int[][] colors = colorNumbers(bus, videoSoftSwitches);
        for (int blockRow = 0; blockRow < BLOCK_ROWS; blockRow++) {
            for (int col = 0; col < COLUMNS; col++) {
                fillBlock(pixels, blockRow, col, GraphicsRenderer.PALETTE[colors[blockRow][col]]);
            }
        }
        return pixels;
    }

    /**
     * Renders the current lo-res screen as the dots of the video signal.
     *
     * @param bus the memory bus to read screen bytes from
     * @param videoSoftSwitches which page is active
     * @return a (BLOCK_ROWS*BLOCK_HEIGHT) x (COLUMNS*BLOCK_DOTS) grid; true means a lit dot
     */
    public static boolean[][] dots(MemoryBus bus, VideoSoftSwitches videoSoftSwitches) {
        boolean[][] dots = new boolean[BLOCK_ROWS * BLOCK_HEIGHT][COLUMNS * BLOCK_DOTS];
        int[][] colors = colorNumbers(bus, videoSoftSwitches);
        for (int blockRow = 0; blockRow < BLOCK_ROWS; blockRow++) {
            for (int col = 0; col < COLUMNS; col++) {
                int color = colors[blockRow][col];
                for (int d = col * BLOCK_DOTS; d < (col + 1) * BLOCK_DOTS; d++) {
                    boolean lit = ((color >> (d & 3)) & 1) != 0;
                    for (int dy = 0; dy < BLOCK_HEIGHT; dy++) {
                        dots[blockRow * BLOCK_HEIGHT + dy][d] = lit;
                    }
                }
            }
        }
        return dots;
    }

    /** @return each block's color number (0-15), indexed [blockRow][column] */
    private static int[][] colorNumbers(MemoryBus bus, VideoSoftSwitches videoSoftSwitches) {
        int[][] colors = new int[BLOCK_ROWS][COLUMNS];
        int pageBase = videoSoftSwitches.isPage2() ? 0x800 : 0x400;
        for (int textRow = 0; textRow < TextScreenRenderer.ROWS; textRow++) {
            int rowBase = pageBase + 0x80 * (textRow % 8) + 0x28 * (textRow / 8);
            for (int col = 0; col < COLUMNS; col++) {
                int b = bus.read(rowBase + col) & 0xFF;
                colors[textRow * 2][col] = b & 0x0F;
                colors[textRow * 2 + 1][col] = (b >> 4) & 0x0F;
            }
        }
        return colors;
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
