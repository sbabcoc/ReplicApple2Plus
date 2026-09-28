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

    /** A pad at rest: sticks centered, nothing pressed. Also what "no pad connected" looks like. */
    public static final PadSnapshot NEUTRAL = new Builder().build();

    private final float[] axes;
    private final Set<PadButton> pressed;

    private PadSnapshot(float[] axes, Set<PadButton> pressed) {
        this.axes = axes;
        this.pressed = pressed;
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
        return other instanceof PadSnapshot s && Arrays.equals(axes, s.axes) && pressed.equals(s.pressed);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(axes) + pressed.hashCode();
    }

    @Override
    public String toString() {
        return "PadSnapshot" + Arrays.toString(axes) + pressed;
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
            return new PadSnapshot(axes.clone(), pressed.clone());
        }
    }
}
