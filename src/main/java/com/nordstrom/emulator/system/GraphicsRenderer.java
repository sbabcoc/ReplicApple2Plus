package com.nordstrom.emulator.system;

import java.awt.Color;

/**
 * The Apple II's 16 real colors, shared by {@link LoResRenderer} and
 * {@link HiResRenderer} -- these are the same physical colors, a real
 * hardware fact, not two independently-authored palettes that happen to
 * agree. Six lo-res colors are physically identical to the four hi-res
 * colors plus black and white, confirmed directly against the mrob.com
 * YIQ-derived reference table (see below), which explicitly marks which
 * lo-res color number each hi-res color equals. Giving both renderers a
 * single, shared home for this rather than one owning it and the other
 * reaching across keeps that fact obvious in the code, not just true by
 * coincidence.
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
public final class GraphicsRenderer {

    /** The 16 real Apple II colors, indexed by lo-res color number (0-15). */
    public static final Color[] PALETTE = {
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

    private GraphicsRenderer() {}
}
