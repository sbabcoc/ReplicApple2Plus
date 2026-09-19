package com.nordstrom.emulator.system;

/**
 * Dispatches $C080-$C08F to slot 0's card. Unlike {@link SlotIoHandler},
 * which splits $C090-$C0FF across seven slots by offset arithmetic, this
 * always routes to the one card occupying slot 0 -- there's nothing to
 * compute.
 */
final class SlotZeroIoHandler implements AddressRangeHandler {

    private final SlotCard card; // slot 0's card, or null if empty
    private final FloatingBus floatingBus;

    SlotZeroIoHandler(SlotCard card, FloatingBus floatingBus) {
        this.card = card;
        this.floatingBus = floatingBus;
    }

    @Override
    public int read(int offset) {
        if (card == null) {
            return floatingBus.read();
        }
        return card.readIoSwitch(offset);
    }

    @Override
    public void write(int offset, int value) {
        if (card != null) {
            card.writeIoSwitch(offset, value);
        }
        // write with nothing present: no effect, matching real floating-bus hardware
    }
}
