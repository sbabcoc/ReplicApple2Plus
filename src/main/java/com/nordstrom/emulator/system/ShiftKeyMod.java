package com.nordstrom.emulator.system;

/**
 * The shift-key modification: a wire run from the keyboard's Shift key
 * (and, for some word processors, its Control key) to one of the
 * motherboard's game I/O inputs, so software can tell what the II+
 * keyboard itself never reports. The keyboard still sends uppercase
 * only; lowercase-aware software (80-column firmware, word processors)
 * polls the wired input and does its own case conversion.
 * <p>
 * The wirings offered are the ones MAME's {@code apple2}/{@code apple2p}
 * drivers offer, with the same input assignments:
 * <ul>
 *   <li>{@link Wiring#SW2}: Shift on pushbutton 2 ($C063) -- by far the
 *       most common, and the input the IIe later reserved for it.</li>
 *   <li>{@link Wiring#PADDL2_PADDL3}: Shift on paddle 2 ($C066), Control
 *       on paddle 3 ($C067) -- what the Zardax word processor expects.</li>
 *   <li>{@link Wiring#SW0_SW1}: Shift on pushbutton 0 ($C061), Control on
 *       pushbutton 1 ($C062) -- supported by some versions of Format-II
 *       and Sandy's Word Processor.</li>
 * </ul>
 * <p>
 * Electrically the key grounds the input it's wired to, so a wired input
 * reads bit 7 <em>clear</em> while its key is held and set while it's
 * up -- MAME's keyboard connector documents its own Shift and Control
 * lines the same way (0 = pressed). A wired input reads the key and only
 * the key: in MAME the game connector no longer drives that input, and
 * the same holds here, including a paddle input, which reads the key's
 * level directly rather than a timer.
 * <p>
 * Inputs are numbered as {@link GameIoReadHandler} numbers them: the
 * offset within $C060-$C067.
 * <p>
 * <b>Threading:</b> emulator state, owned by the emulation thread, the
 * same as {@link GameButtons}; host key handlers post their changes to
 * the emulation loop.
 */
public final class ShiftKeyMod {

    /** No input: a key this wiring leaves unconnected. */
    private static final int NONE = -1;

    /** Which game I/O inputs, if any, the Shift and Control keys are wired to. */
    public enum Wiring {
        /** No modification: every game I/O input belongs to the game connector. */
        NOT_CONNECTED("Not connected", NONE, NONE),
        /** Shift on pushbutton 2 ($C063). */
        SW2("SW2 = Shift", 3, NONE),
        /** Shift on paddle 2 ($C066), Control on paddle 3 ($C067). */
        PADDL2_PADDL3("PADDL2 = Shift, PADDL3 = Ctrl", 6, 7),
        /** Shift on pushbutton 0 ($C061), Control on pushbutton 1 ($C062). */
        SW0_SW1("SW0 = Shift, SW1 = Ctrl", 1, 2);

        private final String label;
        private final int shiftInput;
        private final int controlInput;

        Wiring(String label, int shiftInput, int controlInput) {
            this.label = label;
            this.shiftInput = shiftInput;
            this.controlInput = controlInput;
        }

        /** @return the wiring's name as MAME's machine configuration shows it */
        public String label() {
            return label;
        }
    }

    private Wiring wiring = Wiring.NOT_CONNECTED;
    private boolean shiftHeld;
    private boolean controlHeld;

    /**
     * Rewires the modification. Starts {@link Wiring#NOT_CONNECTED}: a
     * stock machine has no mod until one is installed.
     *
     * @param wiring the new wiring
     */
    public void setWiring(Wiring wiring) {
        this.wiring = wiring;
    }

    /** @return the current wiring */
    public Wiring wiring() {
        return wiring;
    }

    /**
     * Sets whether the host's Shift key is held.
     *
     * @param held true while held
     */
    public void setShiftHeld(boolean held) {
        shiftHeld = held;
    }

    /**
     * Sets whether the host's Control key is held.
     *
     * @param held true while held
     */
    public void setControlHeld(boolean held) {
        controlHeld = held;
    }

    /**
     * Whether the current wiring connects a key to {@code input}.
     *
     * @param input offset within $C060-$C067
     * @return true if a read of {@code input} should come from {@link #read}
     */
    boolean drives(int input) {
        return input == wiring.shiftInput || input == wiring.controlInput;
    }

    /**
     * Reads a wired input the way the hardware reports it.
     *
     * @param input offset within $C060-$C067, one {@link #drives} accepts
     * @return 0x00 while the wired key is held, 0x80 while it's up
     */
    int read(int input) {
        boolean held = (input == wiring.shiftInput) ? shiftHeld : controlHeld;
        return held ? 0x00 : 0x80;
    }
}
