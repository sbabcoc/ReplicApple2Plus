package com.nordstrom.emulator.system;

/**
 * Dispatches $C060-$C06F. Offsets 4-7 ($C064-$C067) go to
 * {@link PaddleTimers#read} for channels 0-3. Everything else in this
 * block -- $C060 (cassette input) and $C061-$C063 (joystick/paddle
 * pushbuttons) -- is a separate, still-undesigned subsystem and stays
 * a named gap; it doesn't share anything with the paddle timers beyond
 * living in the same 16-byte address block.
 */
final class PaddleReadHandler implements AddressRangeHandler {

    private static final String GAP =
        "$C060 (cassette input) and $C061-$C063 (joystick/paddle pushbuttons) are not yet implemented";

    private final PaddleTimers paddles;

    PaddleReadHandler(PaddleTimers paddles) {
        this.paddles = paddles;
    }

    @Override
    public int read(int offset) {
        if (offset >= 4 && offset <= 7) {
            return paddles.read(offset - 4);
        }
        throw new UnsupportedOperationException(GAP);
    }

    @Override
    public void write(int offset, int value) {
        if (offset >= 4 && offset <= 7) {
            return; // real hardware has no write path for a paddle read -- silently ignored
        }
        throw new UnsupportedOperationException(GAP);
    }
}
