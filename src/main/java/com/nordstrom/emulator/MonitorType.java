package com.nordstrom.emulator;

import java.awt.Color;

/**
 * The kind of monitor a video signal is shown on. A color monitor decodes
 * the Apple's artifact color from the dot pattern -- but only while the
 * signal carries a color burst, so text (which the II+'s color killer sends
 * without one) and the VideoTerm's output (which never has one) show white.
 * A monochrome monitor shows every lit dot in its phosphor's color, which
 * reveals the dot patterns behind the colors.
 * <p>
 * Public for {@link ScreenImage}, so screenshots taken without the window
 * can choose the monitor as the toolbar does.
 */
public enum MonitorType {

    /** A color monitor: artifact color decoded, white where there's no color burst. */
    COLOR("Color", new Color(0xFF, 0xFF, 0xFF)),
    /** A white monochrome monitor. */
    WHITE("White", new Color(0xFF, 0xFF, 0xFF)),
    // RGB approximations of the P1 (525 nm) and P3 (602 nm) phosphors' emission.
    /** A green monochrome monitor (P1 phosphor). */
    GREEN("Green", new Color(0x33, 0xFF, 0x33)),
    /** An amber monochrome monitor (P3 phosphor). */
    AMBER("Amber", new Color(0xFF, 0xB0, 0x00));

    private final String label;
    private final Color lit;

    MonitorType(String label, Color lit) {
        this.label = label;
        this.lit = lit;
    }

    /**
     * The monitor's name, as the toolbar shows it.
     *
     * @return the name shown in the toolbar's drop-down
     */
    public String label() {
        return label;
    }

    /**
     * The color a lit dot shows in: the phosphor's on a monochrome monitor.
     *
     * @return the color of a lit dot in a signal with no color burst
     */
    public Color lit() {
        return lit;
    }

    /**
     * Whether this monitor shows the Apple's artifact color.
     *
     * @return true if this monitor decodes artifact color
     */
    public boolean decodesColor() {
        return this == COLOR;
    }

    /**
     * Finds a monitor by name, as a command line gives it.
     *
     * @param name a monitor's name, in any case: color, white, green or amber
     * @return that monitor
     * @throws IllegalArgumentException if there's no monitor of that name
     */
    public static MonitorType named(String name) {
        for (MonitorType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("no monitor \"" + name + "\": color, white, green or amber");
    }
}
