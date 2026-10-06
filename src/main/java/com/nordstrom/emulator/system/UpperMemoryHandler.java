package com.nordstrom.emulator.system;

import java.util.OptionalInt;

/**
 * Dispatches $D000-$FFFF to the upper-memory card's {@link SlotCard#readUpperMemory}/
 * {@link SlotCard#writeUpperMemory}, falling through to {@link SystemRom}
 * when the card reports it isn't intercepting a given read. This is
 * where that fallback belongs -- motherboard-level dispatch, not the
 * card itself, which has no business knowing the Apple II+'s own ROM
 * contents just to say "not me." Only registered when a card (in any
 * slot) returns true from {@link SlotCard#wantsUpperMemory}
 * -- see {@link MotherboardBus}'s constructor for what's registered
 * here otherwise.
 */
final class UpperMemoryHandler implements AddressRangeHandler {

    private final SlotCard card;

    UpperMemoryHandler(SlotCard card) {
        this.card = card;
    }

    @Override
    public int read(int offset) {
        OptionalInt value = card.readUpperMemory(offset);
        return value.isPresent() ? value.getAsInt() : SystemRom.read(offset);
    }

    @Override
    public void write(int offset, int value) {
        card.writeUpperMemory(offset, value);
    }
}
