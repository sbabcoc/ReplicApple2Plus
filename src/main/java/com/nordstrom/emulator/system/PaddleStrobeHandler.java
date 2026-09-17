package com.nordstrom.emulator.system;

/**
 * Dispatches $C070-$C07F to {@link PaddleTimers#trigger}. Any access,
 * read or write, anywhere in this 16-byte window fires the shared
 * strobe line for all four channels at once, matching real hardware --
 * there's no per-offset distinction here, the same reasoning already
 * applied to {@link SpeakerToggle}.
 */
final class PaddleStrobeHandler implements AddressRangeHandler {

    private final PaddleTimers paddles;

    PaddleStrobeHandler(PaddleTimers paddles) {
        this.paddles = paddles;
    }

    @Override
    public int read(int offset) {
        paddles.trigger();
        return 0; // harmless -- real software never inspects this
    }

    @Override
    public void write(int offset, int value) {
        paddles.trigger();
    }
}
