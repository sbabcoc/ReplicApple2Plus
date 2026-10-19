package com.nordstrom.emulator.system;

/**
 * The game connector's three pushbutton inputs, read by software at
 * $C061 (button 0), $C062 (button 1) and $C063 (button 2). Real
 * hardware: each is a plain switch, and a read reports its state in
 * bit 7 -- set while the button is held, clear otherwise. This class
 * holds that state; {@link GameIoReadHandler} is what makes it visible
 * to the CPU.
 * <p>
 * <b>Nothing plugged in.</b> The II and II+ motherboard has no pull-down
 * on these inputs; the pull-down lives in the controller (Apple's own
 * joysticks hold each button line low through a 560&Omega; resistor until
 * the button is pressed). An input with nothing attached floats high and
 * reads as pressed -- the IIe added pull-downs, the II+ did not. So,
 * like {@link PaddleTimers}, every input starts unplugged, reading 0x80,
 * until {@link #setPressed} connects it, and {@link #disconnect} returns
 * it to that state. It matters beyond games: software written for the
 * shift-key mod reads an unwired $C063 as Shift up, which is what makes
 * the VideoTerm firmware's Ctrl-A lowercase toggle work on an unmodified
 * machine.
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
    private final boolean[] connected = new boolean[COUNT]; // nothing is plugged in until setPressed says so

    /** Creates the button set with nothing plugged in. */
    public GameButtons() {
        // all inputs start unplugged -- boolean[] defaults to false
    }

    /**
     * Plugs a button into its input (if it was not already) and sets
     * whether it is currently held down.
     *
     * @param button 0-2, matching $C061-$C063
     * @param isPressed true while the button is held, false when released
     * @throws IllegalArgumentException if {@code button} is not 0-2
     */
    public void setPressed(int button, boolean isPressed) {
        checkRange(button);
        connected[button] = true;
        pressed[button] = isPressed;
    }

    /**
     * Unplugs the button from its input: open again, so it floats high.
     *
     * @param button 0-2, matching $C061-$C063
     * @throws IllegalArgumentException if {@code button} is not 0-2
     */
    public void disconnect(int button) {
        checkRange(button);
        connected[button] = false;
        pressed[button] = false;
    }

    /**
     * Reads a button the way the hardware reports it.
     *
     * @param button 0-2
     * @return 0x80 while held or while nothing is plugged in, 0x00
     *         otherwise -- bit 7 only, the same convention
     *         {@link PaddleTimers#read} uses for its own switches
     */
    int read(int button) {
        checkRange(button);
        return (pressed[button] || !connected[button]) ? 0x80 : 0x00;
    }

    private static void checkRange(int button) {
        if (button < 0 || button >= COUNT) {
            throw new IllegalArgumentException("button must be 0-" + (COUNT - 1) + ", got " + button);
        }
    }
}
