package com.nordstrom.emulator.system;

/**
 * Dispatches $C080-$C08F to slot 0's card. Unlike {@link SlotIoHandler},
 * which splits $C090-$C0FF across seven slots by offset arithmetic, this
 * always routes to the one card occupying slot 0 -- there's nothing to
 * compute.
 */
final class SlotZeroIoHandler implements AddressRangeHandler {

    private static final String FLOATING_BUS_GAP =
        "No card is present at this address, and floating-bus emulation "
        + "(returning the video scanner's last byte) depends on the not-yet-built video scanner";

    private final SlotCard card; // slot 0's card, or null if empty

    SlotZeroIoHandler(SlotCard card) {
        this.card = card;
    }

    @Override
    public int read(int offset) {
        requireCard();
        return card.readIoSwitch(offset);
    }

    @Override
    public void write(int offset, int value) {
        requireCard();
        card.writeIoSwitch(offset, value);
    }

    private void requireCard() {
        if (card == null) {
            throw new UnsupportedOperationException(FLOATING_BUS_GAP);
        }
    }
}
