package com.nordstrom.emulator.system;

/**
 * Dispatches $D000-$FFFF to slot 0's card's {@link SlotCard#readSlotZeroBank}/
 * {@link SlotCard#writeSlotZeroBank}. Only registered when a card
 * actually occupies slot 0 and returns true from
 * {@link SlotCard#wantsSlotZeroBanking} -- see {@link MotherboardBus}'s
 * constructor for what's registered here otherwise.
 */
final class SlotZeroBankingHandler implements AddressRangeHandler {

    private final SlotCard card;

    SlotZeroBankingHandler(SlotCard card) {
        this.card = card;
    }

    @Override
    public int read(int offset) {
        return card.readSlotZeroBank(offset);
    }

    @Override
    public void write(int offset, int value) {
        card.writeSlotZeroBank(offset, value);
    }
}
