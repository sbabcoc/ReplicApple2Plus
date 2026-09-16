package com.nordstrom.emulator.system;

import java.util.OptionalInt;

/**
 * Dispatches $D000-$FFFF to slot 0's card's {@link SlotCard#readSlotZeroBank}/
 * {@link SlotCard#writeSlotZeroBank}, falling through to {@link SystemRom}
 * when the card reports it isn't intercepting a given read. This is
 * where that fallback belongs -- motherboard-level dispatch, not the
 * card itself, which has no business knowing the Apple II+'s own ROM
 * contents just to say "not me." Only registered when a card actually
 * occupies slot 0 and returns true from {@link SlotCard#wantsSlotZeroBanking}
 * -- see {@link MotherboardBus}'s constructor for what's registered
 * here otherwise.
 */
final class SlotZeroBankingHandler implements AddressRangeHandler {

    private final SlotCard card;

    SlotZeroBankingHandler(SlotCard card) {
        this.card = card;
    }

    @Override
    public int read(int offset) {
        OptionalInt value = card.readSlotZeroBank(offset);
        return value.isPresent() ? value.getAsInt() : SystemRom.read(offset);
    }

    @Override
    public void write(int offset, int value) {
        card.writeSlotZeroBank(offset, value);
    }
}
