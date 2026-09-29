package com.nordstrom.emulator.input;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * One instant of a pad's state -- stick positions and which buttons are
 * down -- and nothing else. This is the entire contract between a
 * provider (which knows about some library or device) and the mapping
 * code (which knows about the Apple II): a provider produces these, the
 * {@link InputMapper} consumes them, and neither knows how the other
 * works. Immutable.
 */
public final class PadSnapshot {

    /** A pad that is connected and at rest: sticks centered, nothing pressed. */
    public static final PadSnapshot NEUTRAL = new Builder().build();

    /**
     * No pad connected at all. Deliberately not the same as {@link #NEUTRAL}:
     * a pad at rest is a joystick plugged in and centered, while this is
     * nothing plugged in, and the Apple II can tell the two apart -- an
     * unplugged paddle reads 255 where a centered one reads 128.
     */
    public static final PadSnapshot ABSENT = new PadSnapshot(new float[PadAxis.values().length], EnumSet.noneOf(PadButton.class), false);

    private final float[] axes;
    private final Set<PadButton> pressed;
    private final boolean present;

    private PadSnapshot(float[] axes, Set<PadButton> pressed, boolean present) {
        this.axes = axes;
        this.pressed = pressed;
        this.present = present;
    }

    /**
     * Whether a pad is connected at all.
     *
     * @return false only for {@link #ABSENT}
     */
    public boolean isPresent() {
        return present;
    }

    /**
     * Reads a stick axis.
     *
     * @param axis which axis
     * @return its position, -1.0 to +1.0, 0.0 at rest
     */
    public float axis(PadAxis axis) {
        return axes[axis.ordinal()];
    }

    /**
     * Reads a button.
     *
     * @param button which button
     * @return true while it is held down
     */
    public boolean pressed(PadButton button) {
        return pressed.contains(button);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PadSnapshot s && present == s.present && Arrays.equals(axes, s.axes) && pressed.equals(s.pressed);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(axes) + pressed.hashCode()) + Boolean.hashCode(present);
    }

    @Override
    public String toString() {
        return present ? "PadSnapshot" + Arrays.toString(axes) + pressed : "PadSnapshot(absent)";
    }

    /**
     * Starts building a snapshot.
     *
     * @return a builder with every axis at rest and no button pressed
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds a {@link PadSnapshot}. */
    public static final class Builder {
        private final float[] axes = new float[PadAxis.values().length];
        private final EnumSet<PadButton> pressed = EnumSet.noneOf(PadButton.class);

        /** Creates a builder with every axis at rest and no button pressed. */
        public Builder() {
            // fields are already at rest
        }

        /**
         * Sets an axis, clamping to the legal range so a misbehaving
         * provider cannot push a value the mapping math does not expect.
         *
         * @param axis which axis
         * @param value its position; anything outside -1.0 to +1.0 is clamped
         * @return this builder
         */
        public Builder axis(PadAxis axis, float value) {
            axes[axis.ordinal()] = Float.isNaN(value) ? 0f : Math.max(-1f, Math.min(1f, value));
            return this;
        }

        /**
         * Marks buttons as held.
         *
         * @param buttons the buttons that are down
         * @return this builder
         */
        public Builder press(PadButton... buttons) {
            pressed.addAll(Arrays.asList(buttons));
            return this;
        }

        /**
         * Builds the snapshot.
         *
         * @return an immutable snapshot of what was set
         */
        public PadSnapshot build() {
            return new PadSnapshot(axes.clone(), pressed.clone(), true);
        }
    }
}
