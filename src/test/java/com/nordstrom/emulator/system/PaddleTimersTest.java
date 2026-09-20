package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in {@link PaddleTimers}' RC countdown behavior, including the
 * two trickiest, easy-to-get-wrong details: that a channel is
 * non-retriggerable while running (real 555/558 hardware behavior),
 * and the real, documented shared-strobe quirk where polling one
 * paddle skews another's reading. The strongest original
 * confirmation -- driving the actual historical ROM {@code PREAD}
 * routine through the real CPU core -- lives in
 * {@code com.nordstrom.emulator.cpu.PaddleTimersPreadIntegrationTest},
 * since it needs package-private {@link com.nordstrom.emulator.cpu.Cpu6502}
 * field access this class's own package doesn't have.
 */
class PaddleTimersTest {

    /** Confirmed against the real historical ROM PREAD routine (see the sibling integration test). */
    private static final int FULL_SCALE_CYCLES = 2816;

    @Test
    void position0ExpiresImmediately() {
        PaddleTimers paddles = new PaddleTimers();
        paddles.setPosition(0, 0);
        paddles.trigger();

        assertFalse(paddles.isRunning(0));
    }

    @Test
    void position255RunsForExactlyFullScaleCycles() {
        PaddleTimers paddles = new PaddleTimers();
        paddles.setPosition(1, 255);
        paddles.trigger();

        paddles.tick(FULL_SCALE_CYCLES - 1);
        assertTrue(paddles.isRunning(1), "should still be running 1 cycle before full scale");

        paddles.tick(1);
        assertFalse(paddles.isRunning(1), "should expire exactly at full scale");
    }

    @Test
    void ignoredRetriggerDoesNotResetTheOriginalCountdown() {
        PaddleTimers paddles = new PaddleTimers();
        paddles.setPosition(0, 200);
        paddles.trigger();
        paddles.tick(100);
        paddles.trigger(); // re-trigger while still running -- must be ignored

        int originalTripCycles = (200 * FULL_SCALE_CYCLES) / 255;
        int remainingToOriginalExpiry = originalTripCycles - 100;
        paddles.tick(remainingToOriginalExpiry - 1);
        assertTrue(paddles.isRunning(0), "should still be running 1 cycle before the ORIGINAL expiry");

        paddles.tick(1);
        assertFalse(paddles.isRunning(0), "should expire exactly on the original schedule, not delayed");
    }

    @Test
    void sharedStrobeMeansPollingOneChannelConsumesAnothersCountdownToo() {
        // The real, documented Apple II quirk: reading a second paddle right after a first gives a
        // skewed value, because both timers started from the same strobe -- time spent polling the
        // first eats into the second's remaining count. This isn't special-cased; it falls out of
        // correctly modeling the shared-strobe, independent-per-channel-countdown structure.
        PaddleTimers paddles = new PaddleTimers();
        paddles.setPosition(0, 10);
        paddles.setPosition(1, 250);
        paddles.trigger();

        int trip0 = (10 * FULL_SCALE_CYCLES) / 255;
        int trip1 = (250 * FULL_SCALE_CYCLES) / 255;

        paddles.tick(trip0);
        assertFalse(paddles.isRunning(0), "channel 0 should have expired");
        assertTrue(paddles.isRunning(1), "channel 1 should still be running, but its countdown already advanced");

        paddles.tick(trip1 - trip0 - 1);
        assertTrue(paddles.isRunning(1), "channel 1 still running 1 cycle before its true expiry from the shared start");

        paddles.tick(1);
        assertFalse(paddles.isRunning(1), "channel 1 expires exactly at the shared-start-relative point");
    }
}
