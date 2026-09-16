package com.nordstrom.emulator.system;

/**
 * Dispatches $D000-$FFFF to {@link SystemRom} directly -- registered
 * when no card occupies slot 0, or the card there doesn't bank-switch
 * (see {@link MotherboardBus}'s constructor). Writes are silently
 * ignored, matching real ROM's lack of write circuitry, the same
 * reasoning already applied to {@code DiskBootRom}.
 */
final class SystemRomHandler implements AddressRangeHandler {

    @Override
    public int read(int offset) {
        return SystemRom.read(offset);
    }

    @Override
    public void write(int offset, int value) {
        // real ROM has no write circuitry -- silently ignored
    }
}
