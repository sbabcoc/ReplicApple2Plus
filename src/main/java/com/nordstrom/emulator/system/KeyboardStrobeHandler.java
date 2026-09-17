package com.nordstrom.emulator.system;

/** Dispatches $C010-$C01F to {@link KeyboardRegister}'s strobe-clear side. */
final class KeyboardStrobeHandler implements AddressRangeHandler {

    private final KeyboardRegister keyboard;

    KeyboardStrobeHandler(KeyboardRegister keyboard) {
        this.keyboard = keyboard;
    }

    @Override
    public int read(int offset) {
        return keyboard.readStrobeClear(offset);
    }

    @Override
    public void write(int offset, int value) {
        keyboard.writeStrobeClear(offset, value);
    }
}
