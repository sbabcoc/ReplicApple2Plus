package com.nordstrom.emulator.system;

/**
 * Dispatches $C060-$C06F -- the real Apple II+ "game I/O" switch block:
 * $C060 (cassette input), $C061-$C063 (joystick/paddle pushbuttons), and
 * $C064-$C067 (paddle analog reads, to {@link PaddleTimers#read}). Named
 * for the whole block, not just the one piece currently implemented --
 * cassette input and the pushbuttons are still real, named gaps, but
 * they belong to this same class once built, not a separate one; a
 * name scoped to "paddle" alone would have needed renaming the moment
 * either of those landed.
 * <p>
 * A {@code switch}, not a range check: this reads directly as "offset 0
 * does X, offsets 1-3 do Y, offsets 4-7 do Z" -- matching how this
 * block's offsets are actually organized in real hardware -- and it's
 * exactly the shape that grows correctly as cassette input and the
 * pushbuttons get their own real cases later, unlike a range check,
 * which would need a new comparison bolted on for each addition.
 */
final class GameIoReadHandler implements AddressRangeHandler {

    private static final String CASSETTE_GAP = "$C060 (cassette input) is not yet implemented";
    private static final String PUSHBUTTON_GAP = "$C061-$C063 (joystick/paddle pushbuttons) are not yet implemented";
    private static final String UNUSED_GAP = "$C068-$C06F are unused/mirrored offsets, not yet modeled";

    private final PaddleTimers paddles;

    GameIoReadHandler(PaddleTimers paddles) {
        this.paddles = paddles;
    }

    @Override
    public int read(int offset) {
        return switch (offset) {
            case 0 -> throw new UnsupportedOperationException(CASSETTE_GAP);
            case 1, 2, 3 -> throw new UnsupportedOperationException(PUSHBUTTON_GAP);
            case 4, 5, 6, 7 -> paddles.read(offset - 4);
            default -> throw new UnsupportedOperationException(UNUSED_GAP);
        };
    }

    @Override
    public void write(int offset, int value) {
        switch (offset) {
            case 0 -> throw new UnsupportedOperationException(CASSETTE_GAP);
            case 1, 2, 3 -> throw new UnsupportedOperationException(PUSHBUTTON_GAP);
            case 4, 5, 6, 7 -> { /* real hardware has no write path for a paddle read -- silently ignored */ }
            default -> throw new UnsupportedOperationException(UNUSED_GAP);
        }
    }
}
