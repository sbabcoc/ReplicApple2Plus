package com.nordstrom.emulator.system;

/** Flat RAM -- the simplest possible {@link AddressRangeHandler}, just an array. */
final class RamHandler implements AddressRangeHandler {

    private final int[] ram;

    /** Allocates {@code size} bytes of RAM. */
    RamHandler(int size) {
        ram = new int[size];
    }

    @Override
    public int read(int offset) {
        return ram[offset];
    }

    @Override
    public void write(int offset, int value) {
        ram[offset] = value;
    }
}
