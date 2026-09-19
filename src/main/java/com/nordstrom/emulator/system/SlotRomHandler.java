package com.nordstrom.emulator.system;

/**
 * Dispatches the aggregate $C100-$C7FF window (256 bytes/slot, slots
 * 1-7) to each slot's own {@link SlotCard#readRom}/{@link SlotCard#writeRom},
 * and notes every access with the shared {@link ExpansionRomArbiter} --
 * accessing a slot's own ROM is what sets that slot's expansion-ROM
 * latch on real hardware, if it has one at all.
 */
final class SlotRomHandler implements AddressRangeHandler {

    private final SlotCard[] slots;
    private final ExpansionRomArbiter arbiter;
    private final FloatingBus floatingBus;

    SlotRomHandler(SlotCard[] slots, ExpansionRomArbiter arbiter, FloatingBus floatingBus) {
        this.slots = slots;
        this.arbiter = arbiter;
        this.floatingBus = floatingBus;
    }

    @Override
    public int read(int offset) {
        int slotNum = slotNumFor(offset);
        SlotCard card = slots[slotNum];
        if (card == null) {
            return floatingBus.read();
        }
        arbiter.noteOwnRomAccessed(slotNum);
        return card.readRom(offset % 0x100);
    }

    @Override
    public void write(int offset, int value) {
        int slotNum = slotNumFor(offset);
        SlotCard card = slots[slotNum];
        if (card == null) {
            return; // write with nothing present: no effect, matching real floating-bus hardware
        }
        arbiter.noteOwnRomAccessed(slotNum);
        card.writeRom(offset % 0x100, value);
    }

    private int slotNumFor(int offset) {
        return (offset / 0x100) + 1;
    }
}
