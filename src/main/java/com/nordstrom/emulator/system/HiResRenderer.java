package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;

import java.awt.Color;

/**
 * Renders the current 280x192 high-resolution graphics screen, including
 * the real NTSC composite color artifacts that are the ONLY way hi-res
 * mode displays color at all -- there is no direct color control, only a
 * 1-bit-per-pixel bitmap whose color comes entirely from how the pattern
 * of "on" bits happens to interact with the composite color signal. A
 * pure function of the current bus and switch state, the same shape as
 * {@link TextScreenRenderer} and {@link LoResRenderer}.
 * <p>
 * <b>Memory addressing</b>: verified directly against {@link VideoScanner}'s
 * own bit-level address computation (the same formula that class already
 * uses for floating-bus purposes), not derived independently and merely
 * assumed consistent -- for a given visible line Y (0-191) and byte
 * column (0-39), the address is:
 * {@code pageBase + (Y&7)*0x400 + ((Y>>3)&7)*0x80 + (Y>>6)*0x28 + column}.
 * Each byte's low 7 bits are one pixel each; bit 7 is the "palette bit"
 * described below.
 * <p>
 * <b>Color model</b>: this is the well-known "simplified" artifact-color
 * model most Apple II software of the era was actually authored against
 * -- not a full NTSC composite-signal simulation (which involves finer
 * inter-pixel signal interactions AppleWin itself only approximates with
 * an optional, much more elaborate filter). Cross-checked across several
 * independent sources -- Wikipedia's own "Subpixel rendering" article,
 * which explicitly calls this "an approximation, but... what most
 * programmers of the time would have in mind"; multiple, separate
 * Applefritter forum explanations; and Paleotronic magazine's account --
 * all describing the same rules:
 * <ul>
 *   <li>An "off" bit is always black.</li>
 *   <li>An "on" bit with an "on" neighbor on either side is always white
 *       -- two lit pixels next to each other merge, regardless of which
 *       side the neighbor is on or the palette bit's value.</li>
 *   <li>An isolated "on" bit (no lit neighbor) is colored by its
 *       horizontal position's parity together with the byte's palette
 *       bit (bit 7): even position + palette bit 0 = violet, even + 1 =
 *       blue, odd + 0 = green, odd + 1 = orange.</li>
 * </ul>
 * The four color values themselves are not a separate palette from
 * {@link LoResRenderer}'s -- six lo-res colors are the same physical
 * colors as hi-res's, a real hardware fact (confirmed directly against
 * the same mrob.com YIQ-derived reference table {@link GraphicsRenderer}
 * itself cites, which explicitly marks which lo-res color numbers each
 * hi-res color equals). Reusing {@link GraphicsRenderer#PALETTE} keeps
 * both renderers consistent with a single verified source rather than
 * risking two independently-sourced tables silently drifting apart.
 * <p>
 * <b>Dots</b>: {@link #dots} gives the video signal itself, 560 dots a
 * line, as a monochrome monitor shows it, per Sather, <i>Understanding the
 * Apple II</i>, pp. 8-18 to 8-22. Each bit is two dots, bit 0
 * first. A byte with its palette bit set is delayed by one dot (70 ns, a
 * 14M clock), and for that first dot the output holds the level it was
 * already at: the previous byte's bit 6, which this extends by a dot. An
 * undelayed byte after a delayed one starts on time, cutting that byte's
 * bit 6 to a single dot. At a line's left edge the held level comes from
 * the byte the scanner fetched in the last clock of horizontal blanking
 * ({@link #leadInAddress}); its dots are blanked, but the hold isn't, so
 * that byte's bit 6 can show as a half-dot before a delayed first byte.
 * A delayed last byte's final dot falls past the visible line.
 */
public final class HiResRenderer {

    /** Hi-Res screen columns (bytes per line; 7 pixels each = 280 total). */
    public static final int COLUMNS = 40;
    /** Pixels per byte. */
    public static final int PIXELS_PER_BYTE = 7;
    /** Dots per byte, two per pixel. */
    public static final int DOTS_PER_BYTE = PIXELS_PER_BYTE * 2;
    /** Visible scan lines. */
    public static final int LINES = 192;

    private static final Color BLACK = new Color(0x00, 0x00, 0x00);
    private static final Color WHITE = new Color(0xFF, 0xFF, 0xFF);
    // Lo-Res color numbers sharing the same physical color as each hi-res
    // color, per the mrob.com table GraphicsRenderer itself cites.
    private static final int VIOLET_LORES_INDEX = 3;
    private static final int GREEN_LORES_INDEX = 12;
    private static final int BLUE_LORES_INDEX = 6;
    private static final int ORANGE_LORES_INDEX = 9;

    /**
     * Renders the current hi-res screen.
     *
     * @param bus the memory bus to read screen bytes from
     * @param videoSoftSwitches which page is active
     * @return a {@link #LINES} x (COLUMNS*PIXELS_PER_BYTE) grid of actual colors
     */
    public static Color[][] render(MemoryBus bus, VideoSoftSwitches videoSoftSwitches) {
        Color[][] pixels = new Color[LINES][COLUMNS * PIXELS_PER_BYTE];
        int pageBase = pageBase(videoSoftSwitches);

        for (int y = 0; y < LINES; y++) {
            // Decode the whole line's bits first (plus each bit's own
            // palette flag) so the white-merge rule can look at real
            // neighboring bits, including across a byte boundary.
            boolean[] bit = new boolean[COLUMNS * PIXELS_PER_BYTE];
            boolean[] palette = new boolean[COLUMNS * PIXELS_PER_BYTE];
            decodeLine(bus, pageBase, y, bit, palette);

            for (int x = 0; x < bit.length; x++) {
                pixels[y][x] = colorAt(bit, palette, x);
            }
        }
        return pixels;
    }

    /**
     * Renders the current hi-res screen as the dots of the video signal.
     *
     * @param bus the memory bus to read screen bytes from
     * @param videoSoftSwitches which page is active
     * @return a {@link #LINES} x (COLUMNS*DOTS_PER_BYTE) grid; true means a lit dot
     */
    public static boolean[][] dots(MemoryBus bus, VideoSoftSwitches videoSoftSwitches) {
        boolean[][] dots = new boolean[LINES][COLUMNS * DOTS_PER_BYTE];
        int pageBase = pageBase(videoSoftSwitches);
        for (int y = 0; y < LINES; y++) {
            int rowBase = rowBase(pageBase, y);
            boolean held = (bus.read(leadInAddress(rowBase)) & 0x40) != 0;
            for (int col = 0; col < COLUMNS; col++) {
                int b = bus.read(rowBase + col) & 0xFF;
                boolean delayed = (b & 0x80) != 0;
                for (int k = 0; k < DOTS_PER_BYTE; k++) {
                    int bitDot = delayed ? k - 1 : k;
                    dots[y][col * DOTS_PER_BYTE + k] = bitDot < 0 ? held : ((b >> (bitDot >> 1)) & 1) != 0;
                }
                held = (b & 0x40) != 0;
            }
        }
        return dots;
    }

    /**
     * The address the scanner fetches in the last clock of horizontal
     * blanking before a line, matching {@link VideoScanner}: in a line's
     * own 128-byte block, the byte before it. For the top third of the
     * screen that wraps to the block's last byte, a screen hole; for the
     * rest it's the last byte of the line 64 above (Sather, p. 8-22).
     *
     * @param rowBase the address of the line's first byte
     * @return the address fetched just before it
     */
    static int leadInAddress(int rowBase) {
        return (rowBase & ~0x7F) | ((rowBase - 1) & 0x7F);
    }

    private static int pageBase(VideoSoftSwitches videoSoftSwitches) {
        return videoSoftSwitches.isPage2() ? 0x4000 : 0x2000;
    }

    private static int rowBase(int pageBase, int y) {
        return pageBase + (y & 7) * 0x400 + ((y >> 3) & 7) * 0x80 + (y >> 6) * 0x28;
    }

    private static void decodeLine(MemoryBus bus, int pageBase, int y, boolean[] bit, boolean[] palette) {
        int rowBase = rowBase(pageBase, y);
        for (int col = 0; col < COLUMNS; col++) {
            int b = bus.read(rowBase + col) & 0xFF;
            boolean paletteBit = (b & 0x80) != 0;
            for (int p = 0; p < PIXELS_PER_BYTE; p++) {
                int x = col * PIXELS_PER_BYTE + p;
                bit[x] = ((b >> p) & 1) != 0;
                palette[x] = paletteBit;
            }
        }
    }

    private static Color colorAt(boolean[] bit, boolean[] palette, int x) {
        if (!bit[x]) {
            return BLACK;
        }
        boolean leftLit = x > 0 && bit[x - 1];
        boolean rightLit = x < bit.length - 1 && bit[x + 1];
        if (leftLit || rightLit) {
            return WHITE;
        }
        boolean even = (x % 2) == 0;
        int loResIndex = even
            ? (palette[x] ? BLUE_LORES_INDEX : VIOLET_LORES_INDEX)
            : (palette[x] ? ORANGE_LORES_INDEX : GREEN_LORES_INDEX);
        return GraphicsRenderer.PALETTE[loResIndex];
    }

    private HiResRenderer() {}
}
