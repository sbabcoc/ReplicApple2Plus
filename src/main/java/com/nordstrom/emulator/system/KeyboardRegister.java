package com.nordstrom.emulator.system;

/**
 * The Apple II+'s keyboard register: $C000 (read the last key pressed)
 * and $C010-$C01F (clear the "new key available" strobe). Registered
 * as two separate {@link AddressRangeHandler} views onto this shared
 * state -- {@link KeyboardDataHandler} for $C000, {@link KeyboardStrobeHandler}
 * for $C010-$C01F -- the same shared-state-plus-handlers shape already
 * used for the expansion ROM window's {@code ExpansionRomArbiter}.
 * <p>
 * Real hardware: $C000 bit 7 is the strobe -- set when a key is
 * pressed, cleared by ANY access (read or write) to $C010-$C01F. Bits
 * 0-6 hold the ASCII value of the last key pressed, and REMAIN there
 * even after the strobe is cleared (reading $C000 again returns the
 * SAME character with bit 7 now 0, not a blanked value) -- confirmed
 * consistently across every source checked, including the widely-used
 * cc65 toolchain's own documented keyboard-polling idiom.
 * <p>
 * $C010's OWN return value is deliberately NOT modeled as a live "any
 * key currently down" flag. That specific behavior is well documented
 * for the IIe and later, but is genuinely inconsistent on the original
 * Apple II and II+ this project emulates -- multiple sources, including
 * a real, filed hardware-behavior bug report against a well-known
 * emulator, treat live AKD-on-$C010 as a IIe-specific feature that
 * should not be present on II/II+ at all. Reading $C010 here returns a
 * harmless 0; real II+ software conventionally uses $C010 only for its
 * clear-strobe side effect (commonly via a bare {@code STA}, discarding
 * any return value entirely), never for the value itself.
 * <p>
 * Deliberately does not model keyboard autorepeat (a physically-held
 * key on real hardware re-triggers the strobe roughly 10 times per
 * second) -- that is a timing-dependent behavior tied to real elapsed
 * time, not something this class can honestly model without a driving
 * clock behind it. {@link #keyPressed} fires the strobe exactly once
 * per call; autorepeat, if ever added, belongs in whatever drives real
 * key events into this class, not here.
 * <p>
 * Deliberately has no dependency on any specific UI toolkit -- no AWT
 * or Swing import anywhere in this class. Whatever eventually captures
 * real keystrokes calls {@link #keyPressed} with a plain ASCII value;
 * this class has no opinion about where that call comes from.
 */
public final class KeyboardRegister {

    private int lastKey;
    private boolean strobe;

    /**
     * Records a key press, ready to be read at $C000.
     *
     * @param asciiValue the key's ASCII value; only bits 0-6 are meaningful
     */
    public void keyPressed(int asciiValue) {
        lastKey = asciiValue & 0x7F;
        strobe = true;
    }

    int readData(int offset) {
        return lastKey | (strobe ? 0x80 : 0x00);
    }

    void writeData(int offset, int value) {
        // real hardware has no write path for $C000 -- silently ignored
    }

    int readStrobeClear(int offset) {
        clearStrobe();
        return 0; // harmless -- see class Javadoc on why this isn't a live AKD flag
    }

    void writeStrobeClear(int offset, int value) {
        clearStrobe();
    }

    private void clearStrobe() {
        strobe = false;
    }

    /** Package-visible for tests. */
    boolean isStrobeSet() {
        return strobe;
    }
}
