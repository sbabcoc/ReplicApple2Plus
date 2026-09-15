package com.nordstrom.emulator.system;

/**
 * Dispatches the aggregate $C090-$C0FF window (16 bytes/slot, slots 1-7)
 * to each slot's own {@link SlotCard#readIoSwitch}/{@link SlotCard#writeIoSwitch}.
 */
final class SlotIoHandler implements AddressRangeHandler {

    private static final String FLOATING_BUS_GAP =
        "No card is present at this address, and floating-bus emulation "
        + "(returning the video scanner's last byte) depends on the not-yet-built video scanner";

    private final SlotCard[] slots;

    SlotIoHandler(SlotCard[] slots) {
        this.slots = slots;
    }

    @Override
    public int read(int offset) {
        return cardFor(offset).readIoSwitch(offset % 0x10);
    }

    @Override
    public void write(int offset, int value) {
        cardFor(offset).writeIoSwitch(offset % 0x10, value);
    }

    private SlotCard cardFor(int offset) {
        int slotNum = (offset / 0x10) + 1;
        SlotCard card = slots[slotNum];
        if (card == null) {
            throw new UnsupportedOperationException(FLOATING_BUS_GAP);
        }
        return card;
    }
}
