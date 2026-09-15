package com.nordstrom.emulator.system;

/**
 * A placeholder for a region whose real implementation doesn't exist
 * yet -- throws, naming the gap, rather than silently returning a
 * guessed value. Swapping this for the real handler later means
 * changing exactly one registration line in {@link MotherboardBus};
 * nothing about {@link AddressSpace} or any other region's handler ever
 * needs to change.
 */
final class NotYetImplementedHandler implements AddressRangeHandler {

    private final String gap;

    /** {@code gap} is used verbatim as the thrown exception's message. */
    NotYetImplementedHandler(String gap) {
        this.gap = gap;
    }

    @Override
    public int read(int offset) {
        throw new UnsupportedOperationException(gap);
    }

    @Override
    public void write(int offset, int value) {
        throw new UnsupportedOperationException(gap);
    }
}
