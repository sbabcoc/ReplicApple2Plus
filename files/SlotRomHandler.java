package com.nordstrom.emulator.system;

/**
 * Dispatches the aggregate $C100-$C7FF window (256 bytes/slot, slots
 * 1-7) to each slot's own {@link SlotCard#readRom}/{@link SlotCard#writeRom},
 * and notes every access with the shared {@link ExpansionRomArbiter} --
 * accessing a slot's own ROM is what sets that slot's expansion-ROM
 * latch on real hardware, if it has one at all.
 */
final class SlotRomHandler implements AddressRangeHandler {

    private static final String FLOATING_BUS_GAP =
        "No card is present at this address, and floating-bus emulation "
        + "(returning the video scanner's last byte) depends on the not-yet-built video scanner";

    private final SlotCard[] slots;
    private final ExpansionRomArbiter arbiter;

    SlotRomHandler(SlotCard[] slots, ExpansionRomArbiter arbiter) {
        this.slots = slots;
        this.arbiter = arbiter;
    }

    @Override
    public int read(int offset) {
        int slotNum = slotNumFor(offset);
        SlotCard card = requireCard(slotNum);
        arbiter.noteOwnRomAccessed(slotNum);
        return card.readRom(offset % 0x100);
    }

    @Override
    public void write(int offset, int value) {
        int slotNum = slotNumFor(offset);
        SlotCard card = requireCard(slotNum);
        arbiter.noteOwnRomAccessed(slotNum);
        card.writeRom(offset % 0x100, value);
    }

    private int slotNumFor(int offset) {
        return (offset / 0x100) + 1;
    }

    private SlotCard requireCard(int slotNum) {
        SlotCard card = slots[slotNum];
        if (card == null) {
            throw new UnsupportedOperationException(FLOATING_BUS_GAP);
        }
        return card;
    }
}
