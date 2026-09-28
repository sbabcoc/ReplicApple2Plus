package com.nordstrom.emulator.system;

/**
 * Dispatches $C060-$C06F -- the real Apple II+ "game I/O" switch block:
 * $C060 (cassette input), $C061-$C063 (joystick/paddle pushbuttons, to
 * {@link GameButtons}), and $C064-$C067 (paddle analog reads, to
 * {@link PaddleTimers#read}).
 * <p>
 * A {@code switch}, not a range check: this reads directly as "offset 0
 * does X, offsets 1-3 do Y, offsets 4-7 do Z" -- matching how this
 * block's offsets are actually organized in real hardware.
 * <p>
 * <b>Simplifications, stated plainly:</b>
 * <ul>
 *   <li>$C060 (cassette input) always reads 0, "no signal": there is no
 *       cassette interface here to supply one. Real hardware with
 *       nothing plugged in reads whatever the input happens to float
 *       to; software that depends on that is not something this models.</li>
 *   <li>$C068-$C06F are modeled as mirrors of $C060-$C067, on the
 *       reasoning that the switch multiplexer selects on the low three
 *       address bits alone, so the upper half of the block reads the
 *       same as the lower. That was not cross-checked against a primary
 *       source in this project. If a real program ever depends on
 *       different behavior there, {@code offset & 7} below is the line
 *       to revisit.</li>
 *   <li>Reads return bit 7 only (0x80 or 0x00), the same convention
 *       {@link PaddleTimers#read} already used. Real hardware puts
 *       floating-bus data in the other seven bits; not modeled here.</li>
 * </ul>
 * Writes to any offset are silently ignored: real hardware has no write
 * path to a switch that is only ever read.
 */
final class GameIoReadHandler implements AddressRangeHandler {

    private final PaddleTimers paddles;
    private final GameButtons buttons;

    GameIoReadHandler(PaddleTimers paddles, GameButtons buttons) {
        this.paddles = paddles;
        this.buttons = buttons;
    }

    @Override
    public int read(int offset) {
        return switch (offset & 7) { // $C068-$C06F mirror $C060-$C067 -- see class Javadoc
            case 0 -> 0x00; // cassette input: no cassette modeled, always "no signal"
            case 1, 2, 3 -> buttons.read((offset & 7) - 1);
            default -> paddles.read((offset & 7) - 4); // 4-7
        };
    }

    @Override
    public void write(int offset, int value) {
        // real hardware has no write path to a read-only switch -- silently ignored
    }
}
