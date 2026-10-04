package com.nordstrom.emulator.system;

/**
 * Test-only bridge to {@link KeyboardRegister}'s package-private bus side,
 * so tests outside this package can play the part of software reading
 * $C000 and clearing the strobe at $C010 without widening production
 * visibility for it.
 */
public final class KeyboardTestAccess {

    private KeyboardTestAccess() {}

    /** @return what software reading $C000 would see (bit 7 = strobe) */
    public static int readKbd(KeyboardRegister keyboard) {
        return keyboard.readData(0);
    }

    /** Software touching $C010. */
    public static void clearStrobe(KeyboardRegister keyboard) {
        keyboard.readStrobeClear(0);
    }
}
