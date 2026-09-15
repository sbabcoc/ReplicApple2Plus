package com.nordstrom.emulator.system;

/** Dispatches the shared $C800-$CFFF expansion ROM window, entirely via the shared {@link ExpansionRomArbiter} -- a pure pass-through of the local offset, with no knowledge of where this window sits in the full address space. */
final class ExpansionRomHandler implements AddressRangeHandler {

    private final ExpansionRomArbiter arbiter;

    ExpansionRomHandler(ExpansionRomArbiter arbiter) {
        this.arbiter = arbiter;
    }

    @Override
    public int read(int offset) {
        return arbiter.access(offset, -1);
    }

    @Override
    public void write(int offset, int value) {
        arbiter.access(offset, value);
    }
}
