package com.nordstrom.emulator.system;

/** Dispatches $C000-$C00F to {@link KeyboardRegister}'s data side. */
final class KeyboardDataHandler implements AddressRangeHandler {

    private final KeyboardRegister keyboard;

    KeyboardDataHandler(KeyboardRegister keyboard) {
        this.keyboard = keyboard;
    }

    @Override
    public int read(int offset) {
        return keyboard.readData(offset);
    }

    @Override
    public void write(int offset, int value) {
        keyboard.writeData(offset, value);
    }
}
