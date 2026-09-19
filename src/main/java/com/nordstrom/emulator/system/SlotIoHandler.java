package com.nordstrom.emulator.system;

/**
 * Dispatches the aggregate $C090-$C0FF window (16 bytes/slot, slots 1-7)
 * to each slot's own {@link SlotCard#readIoSwitch}/{@link SlotCard#writeIoSwitch}.
 */
final class SlotIoHandler implements AddressRangeHandler {

    private final SlotCard[] slots;
    private final FloatingBus floatingBus;

    SlotIoHandler(SlotCard[] slots, FloatingBus floatingBus) {
        this.slots = slots;
        this.floatingBus = floatingBus;
    }

    @Override
    public int read(int offset) {
        SlotCard card = slots[slotNumFor(offset)];
        if (card == null) {
            return floatingBus.read();
        }
        return card.readIoSwitch(offset % 0x10);
    }

    @Override
    public void write(int offset, int value) {
        SlotCard card = slots[slotNumFor(offset)];
        if (card != null) {
            card.writeIoSwitch(offset % 0x10, value);
        }
        // write with nothing present: no effect, matching real floating-bus hardware
    }

    private int slotNumFor(int offset) {
        return (offset / 0x10) + 1;
    }
}
