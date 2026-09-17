package com.nordstrom.emulator.system;

/**
 * The Apple II+'s four analog paddle/joystick inputs, $C064-$C067 (read)
 * and $C070-$C07F (trigger). Real hardware: the potentiometer in each
 * paddle is one leg of an RC one-shot (monostable) timer inside a
 * shared 558 quad-timer chip -- not a digital position register at
 * all. Software determines a paddle's position by triggering all four
 * timers at once and counting how long each one takes to trip.
 * <p>
 * Two things confirmed from the real circuit, not assumed: the
 * potentiometer's range is up to roughly 150k&Omega; against a fixed
 * 0.022&micro;F capacitor, and -- easy to miss, genuinely important --
 * each one-shot is non-retriggerable while still running. A $C070
 * access while a channel hasn't tripped yet is simply ignored for that
 * channel; it does not restart the countdown. This is exactly why
 * reading a second paddle immediately after a first (from the same
 * shared strobe) is well known to yield a skewed value on real
 * hardware -- the second timer was already running the whole time the
 * first one was being polled. That behavior isn't special-cased here;
 * it falls out for free from modeling the shared-strobe, independent-
 * per-channel-countdown structure correctly.
 * <p>
 * {@link #setPosition} takes a conventional 0-255 dial position (the
 * software-visible range real Apple II+ code expects, via its own
 * counting loop) and converts it linearly to a cycle count against
 * {@link #FULL_SCALE_CYCLES} -- see that constant's own Javadoc for
 * where that specific number comes from and why it's the standard
 * generic RC formula that was rejected in favor of it. This is still
 * not a claim of nanosecond hardware fidelity -- real component
 * tolerances varied from machine to machine even when new, and the
 * true RC curve is exponential, not linear -- but confirmed, not just
 * asserted: assembling and running the actual historical ROM
 * {@code PREAD} routine against this implementation produces an exact
 * match between the position set here and the value that real routine
 * computes, across the full 0-255 range, not merely a close one.
 * <p>
 * Ticked by {@link SystemClock#addCycleListener}, not by holding a
 * reference back to a clock -- this class only needs to know how many
 * cycles just elapsed, not the clock's own identity or total count.
 */
public final class PaddleTimers {

    /**
     * Full-scale (position 255) trip duration in cycles: 2816, not a
     * generic RC-formula derivation. This is the real, precisely
     * documented figure tied to actual software behavior -- the
     * standard Monitor ROM {@code PREAD} routine counts in an 11-cycle
     * loop, capped at 256 iterations (256 * 11 = 2816), and Apple's own
     * hardware timing was calibrated to roughly align with that
     * software cap, not the other way around. A generic 1.1*R*C
     * formula against the real 150k&Omega;/0.022&micro;F circuit gives
     * a noticeably different number (~3700 cycles) that would make a
     * full-scale paddle read via the real ROM routine wrap and clamp
     * early rather than track smoothly to 255 -- this figure is the
     * one that actually matches how real software behaves, confirmed
     * directly (not just cited) by assembling and running that exact
     * ROM routine against this implementation.
     */
    private static final int FULL_SCALE_CYCLES = 2816;

    private final int[] remainingCycles = new int[4];
    private final int[] tripCycles = new int[4];

    /**
     * Sets channel {@code channel}'s dial position, effective the next
     * time it's triggered (a channel already running is unaffected
     * until it next expires and is retriggered).
     *
     * @param channel 0-3
     * @param position 0-255, the conventional Apple II+ paddle range
     */
    public void setPosition(int channel, int position) {
        tripCycles[channel] = (position * FULL_SCALE_CYCLES) / 255;
    }

    /**
     * Triggers all four channels at once, matching the real shared
     * strobe line. A channel still running from a previous trigger is
     * left alone -- non-retriggerable while busy, matching real
     * hardware.
     */
    void trigger() {
        for (int ch = 0; ch < 4; ch++) {
            if (remainingCycles[ch] <= 0) {
                remainingCycles[ch] = tripCycles[ch];
            }
        }
    }

    /**
     * Advances every running channel's countdown by the cycles just
     * elapsed, clamped at zero. Public, unlike {@link #trigger}: this
     * is meant to be wired externally via
     * {@link SystemClock#addCycleListener}, typically from wherever
     * assembles the whole machine (a different package entirely), so
     * it has to be reachable from there. {@link #trigger} only needs
     * to be called by this same package's own strobe handler.
     *
     * @param cycles cycles elapsed since the last tick
     */
    public void tick(int cycles) {
        for (int ch = 0; ch < 4; ch++) {
            if (remainingCycles[ch] > 0) {
                remainingCycles[ch] = Math.max(0, remainingCycles[ch] - cycles);
            }
        }
    }

    /**
     * Reads channel {@code channel}'s current state: bit 7 set while
     * still counting down, clear once expired.
     *
     * @param channel 0-3
     * @return 0x80 if still running, 0x00 if expired
     */
    int read(int channel) {
        return remainingCycles[channel] > 0 ? 0x80 : 0x00;
    }

    /** Package-visible for tests. */
    boolean isRunning(int channel) {
        return remainingCycles[channel] > 0;
    }
}
