package com.nordstrom.emulator.system;

/**
 * The Apple II+'s speaker toggle, $C030-$C03F. Real hardware: a single
 * flip-flop driving the speaker cone through a simple amplifier. ANY
 * access, read or write, at ANY offset in this 16-byte window flips
 * it -- unlike {@link VideoSoftSwitches}' eight distinct per-offset
 * flags, there is only one flag here, and every offset in the range
 * addresses the same flip-flop (there is exactly one speaker).
 * Software produces tones by accessing this address repeatedly at
 * precisely-timed intervals (a CPU cycle-counted delay loop), moving
 * the physical speaker cone back and forth at audio frequencies --
 * there is no "frequency" or "volume" register at all, just this one
 * toggle and the software-controlled timing between accesses.
 * <p>
 * Reads return a harmless 0; real software accessing this address does
 * so purely for the toggle side effect and never inspects the returned
 * byte, the same reasoning already applied to {@link VideoSoftSwitches}
 * and {@code LanguageCard}'s control switches.
 * <p>
 * Deliberately does not attempt real audio synthesis -- there is
 * nothing yet to consume it (no {@link SystemClock} hook, no audio
 * output). This tracks only the flag's current state, matching
 * {@link VideoSoftSwitches}' own minimal precedent, rather than
 * guessing what additional state a future audio consumer might want
 * before one exists.
 */
public final class SpeakerToggle implements AddressRangeHandler {

    private boolean speakerHigh;

    @Override
    public int read(int offset) {
        toggle();
        return 0;
    }

    @Override
    public void write(int offset, int value) {
        toggle();
    }

    private void toggle() {
        speakerHigh = !speakerHigh;
    }

    /** Package-visible for tests and a future audio consumer. */
    boolean isSpeakerHigh() {
        return speakerHigh;
    }
}
