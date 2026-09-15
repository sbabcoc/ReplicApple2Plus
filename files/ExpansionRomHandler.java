package com.nordstrom.emulator.system;

/** Dispatches the shared $C800-$CFFF expansion ROM window, entirely via the shared {@link ExpansionRomArbiter} -- this class holds no logic of its own beyond translating offset back to absolute address. */
final class ExpansionRomHandler implements AddressRangeHandler {

    private final ExpansionRomArbiter arbiter;

    ExpansionRomHandler(ExpansionRomArbiter arbiter) {
        this.arbiter = arbiter;
    }

    @Override
    public int read(int offset) {
        return arbiter.access(0xC800 + offset, -1);
    }

    @Override
    public void write(int offset, int value) {
        arbiter.access(0xC800 + offset, value);
    }
}
