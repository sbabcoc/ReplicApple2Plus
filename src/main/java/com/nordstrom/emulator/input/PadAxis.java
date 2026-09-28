package com.nordstrom.emulator.input;

/**
 * The analog stick axes this emulator understands, in a vocabulary of
 * its own rather than any particular gamepad library's. Values run from
 * -1.0 to +1.0 with 0.0 at rest; by the convention of SDL, which the
 * gamepad provider sits on, negative is left and up. Whether a given
 * physical pad agrees is exactly what the {@code invert} setting in the
 * input configuration exists to fix.
 */
public enum PadAxis {
    /** Left stick, horizontal. */
    LEFT_X,
    /** Left stick, vertical. */
    LEFT_Y,
    /** Right stick, horizontal. */
    RIGHT_X,
    /** Right stick, vertical. */
    RIGHT_Y
}
