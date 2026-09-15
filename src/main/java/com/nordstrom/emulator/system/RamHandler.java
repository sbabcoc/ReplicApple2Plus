package com.nordstrom.emulator.system;

/**
 * Flat RAM -- the simplest possible {@link AddressRangeHandler}, just an
 * array. Backed by a real {@code byte[]}, not {@code int[]}: this is raw
 * storage, not a value in active use, so the 4x memory reduction (1 byte
 * per cell instead of 4) is genuinely free -- unlike the CPU core or the
 * {@link AddressRangeHandler} contract itself, where Java's SIGNED byte
 * would force constant {@code & 0xFF} masking on every comparison,
 * arithmetic operation, and hex-formatted message throughout an already
 * large, already-verified codebase, for no benefit beyond what this
 * class's own {@link #read}/{@link #write} boundary already provides.
 * The unsigned 0-255 conversion happens in exactly these two methods --
 * nothing else in this project ever needs to know this array holds
 * signed bytes rather than plain ints.
 */
final class RamHandler implements AddressRangeHandler {

    private final byte[] ram;

    /** Allocates {@code size} bytes of RAM. */
    RamHandler(int size) {
        ram = new byte[size];
    }

    @Override
    public int read(int offset) {
        return ram[offset] & 0xFF; // undo Java's sign extension -- this array holds unsigned bytes
    }

    @Override
    public void write(int offset, int value) {
        ram[offset] = (byte) value; // truncates to the low 8 bits, matching the physical 8-bit bus
    }
}
