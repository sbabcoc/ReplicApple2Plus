package com.nordstrom.emulator.input;

/**
 * Turns pad snapshots into Apple II paddle positions and pushbutton
 * states, per an {@link InputMapping}, and tells an {@link InputSink}
 * about anything that changed. All the "feel" lives here -- dead zone,
 * rescaling, inversion, D-pad override -- so it is one piece of pure
 * arithmetic that behaves identically wherever the pad is read.
 * <p>
 * A paddle is either plugged in, at some position, or unplugged. It is
 * unplugged whenever no pad is connected ({@link PadSnapshot#ABSENT}),
 * and also whenever the mapping binds neither a stick nor D-pad buttons
 * to it: nothing is attached to that input, so it must read as
 * nothing attached -- 255, like real hardware -- and not as a joystick
 * held at center. A pad at rest is a joystick plugged in and centered,
 * a different thing that the Apple II can tell apart.
 * <p>
 * Only changes are reported, except for the very first snapshot, which
 * reports everything: the machine's own idea of where the paddles sit
 * before any input arrives is not something to rely on, so the first
 * call establishes the whole state, not just the differences from it.
 * <p>
 * Not thread-safe: call it from one thread.
 */
public final class InputMapper {

    /** The paddle position that means "centered". */
    static final int CENTER = 128;

    private final InputMapping mapping;
    private final InputSink sink;
    /** Stands for "unplugged" in {@link #lastPaddle}; no real position is negative. */
    private static final int UNPLUGGED = -1;

    private final int[] lastPaddle = new int[InputMapping.PADDLES];
    private final boolean[] lastButton = new boolean[InputMapping.BUTTONS];
    private boolean primed;

    /**
     * Creates a mapper.
     *
     * @param mapping how pad inputs map to the Apple II
     * @param sink where to send changes
     */
    public InputMapper(InputMapping mapping, InputSink sink) {
        this.mapping = mapping;
        this.sink = sink;
    }

    /**
     * Applies one snapshot, reporting whatever differs from the last one.
     *
     * @param snapshot the pad's current state
     */
    public void apply(PadSnapshot snapshot) {
        for (int channel = 0; channel < InputMapping.PADDLES; channel++) {
            InputMapping.PaddleBinding binding = mapping.paddle(channel);
            int state = (snapshot.isPresent() && isBound(binding))
                ? position(binding, mapping.deadZone(), snapshot)
                : UNPLUGGED;
            if (!primed || state != lastPaddle[channel]) {
                lastPaddle[channel] = state;
                if (state == UNPLUGGED) {
                    sink.disconnectPaddle(channel);
                } else {
                    sink.setPaddle(channel, state);
                }
            }
        }
        for (int button = 0; button < InputMapping.BUTTONS; button++) {
            boolean down = false;
            for (PadButton source : mapping.button(button)) {
                down |= snapshot.pressed(source);
            }
            if (!primed || down != lastButton[button]) {
                lastButton[button] = down;
                sink.setButton(button, down);
            }
        }
        primed = true;
    }

    /** Whether the mapping attaches anything to this paddle at all. */
    private static boolean isBound(InputMapping.PaddleBinding binding) {
        return binding.axis() != null || binding.dpadLow() != null;
    }

    /**
     * One plugged-in paddle's position for a snapshot: 0 to 255, 128 at center.
     * <p>
     * The stick is inverted if configured, then passed through the dead
     * zone: nothing registers inside it, and the travel outside it is
     * rescaled so the stick still reaches both extremes (a plain cutoff
     * would leave a jump at the edge and a stick that never quite
     * reaches full deflection). A D-pad button held on exactly one side
     * overrides the stick with full deflection; both held cancel to
     * center.
     *
     * @param binding what drives this paddle
     * @param deadZone the configured dead zone
     * @param snapshot the pad state
     * @return the paddle position
     */
    static int position(InputMapping.PaddleBinding binding, float deadZone, PadSnapshot snapshot) {
        float value = 0f;
        if (binding.axis() != null) {
            value = snapshot.axis(binding.axis());
            if (binding.invert()) {
                value = -value;
            }
            value = applyDeadZone(value, deadZone);
        }
        boolean low = binding.dpadLow() != null && snapshot.pressed(binding.dpadLow());
        boolean high = binding.dpadHigh() != null && snapshot.pressed(binding.dpadHigh());
        if (low && high) {
            value = 0f;
        } else if (low) {
            value = -1f;
        } else if (high) {
            value = 1f;
        }
        return Math.round((value + 1f) * 127.5f);
    }

    private static float applyDeadZone(float value, float deadZone) {
        float magnitude = Math.abs(value);
        if (magnitude <= deadZone) {
            return 0f;
        }
        float rescaled = (magnitude - deadZone) / (1f - deadZone);
        return Math.signum(value) * Math.min(1f, rescaled);
    }
}
