package com.nordstrom.emulator;

import java.awt.Color;

/**
 * The kind of monitor a video signal is shown on. A color monitor decodes
 * the Apple's artifact color from the dot pattern -- but only while the
 * signal carries a color burst, so text (which the II+'s color killer sends
 * without one) and the VideoTerm's output (which never has one) show white.
 * A monochrome monitor shows every lit dot in its phosphor's color, which
 * reveals the dot patterns behind the colors.
 */
enum MonitorType {

    COLOR("Color", new Color(0xFF, 0xFF, 0xFF)),
    WHITE("White", new Color(0xFF, 0xFF, 0xFF)),
    // RGB approximations of the P1 (525 nm) and P3 (602 nm) phosphors' emission.
    GREEN("Green", new Color(0x33, 0xFF, 0x33)),
    AMBER("Amber", new Color(0xFF, 0xB0, 0x00));

    private final String label;
    private final Color lit;

    MonitorType(String label, Color lit) {
        this.label = label;
        this.lit = lit;
    }

    /** @return the name shown in the toolbar's drop-down */
    String label() {
        return label;
    }

    /** @return the color of a lit dot in a signal with no color burst */
    Color lit() {
        return lit;
    }

    /** @return true if this monitor decodes artifact color */
    boolean decodesColor() {
        return this == COLOR;
    }
}
