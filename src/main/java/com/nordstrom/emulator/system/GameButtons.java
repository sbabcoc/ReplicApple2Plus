package com.nordstrom.emulator.system;

/**
 * The game connector's three pushbutton inputs, read by software at
 * $C061 (button 0), $C062 (button 1) and $C063 (button 2). Real
 * hardware: each is a plain switch, and a read reports its state in
 * bit 7 -- set while the button is held, clear otherwise. This class
 * holds that state; {@link GameIoReadHandler} is what makes it visible
 * to the CPU.
 * <p>
 * Like {@link PaddleTimers}, nothing here knows or cares where a
 * button press comes from. Whatever real input source is eventually
 * wired up (keyboard, mouse, gamepad) calls {@link #setPressed}.
 * <p>
 * <b>Threading:</b> this is emulator state, owned by the emulation
 * thread. A host input handler running on the Swing event thread must
 * not call {@link #setPressed} directly; it hands the call to the
 * emulation loop instead, the same way keystrokes are delivered (see
 * {@code EmulationLoop}).
 */
public final class GameButtons {

    /** How many pushbuttons the game connector has. */
    static final int COUNT = 3;

    private final boolean[] pressed = new boolean[COUNT];

    /** Creates the button set with every button released. */
    public GameButtons() {
        // all buttons start released -- boolean[] defaults to false
    }

    /**
     * Sets whether a button is currently held down.
     *
     * @param button 0-2, matching $C061-$C063
     * @param isPressed true while the button is held, false when released
     * @throws IllegalArgumentException if {@code button} is not 0-2
     */
    public void setPressed(int button, boolean isPressed) {
        checkRange(button);
        pressed[button] = isPressed;
    }

    /**
     * Reads a button the way the hardware reports it.
     *
     * @param button 0-2
     * @return 0x80 while held, 0x00 otherwise -- bit 7 only, the same
     *         convention {@link PaddleTimers#read} uses for its own switches
     */
    int read(int button) {
        checkRange(button);
        return pressed[button] ? 0x80 : 0x00;
    }

    private static void checkRange(int button) {
        if (button < 0 || button >= COUNT) {
            throw new IllegalArgumentException("button must be 0-" + (COUNT - 1) + ", got " + button);
        }
    }
}
