package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ---- nothing plugged in: an open circuit whose timer never trips ----

    @Test
    void everyChannelStartsUnpluggedAndOnceTriggeredStaysHighForever() {
        PaddleTimers paddles = new PaddleTimers();
        for (int ch = 0; ch < 4; ch++) {
            assertEquals(0x00, paddles.read(ch), "idle before any trigger, channel " + ch);
        }
        paddles.trigger();
        paddles.tick(1_000_000);
        for (int ch = 0; ch < 4; ch++) {
            assertEquals(0x80, paddles.read(ch),
                "with nothing plugged in the capacitor never charges, so channel " + ch + " never trips");
            assertTrue(paddles.isRunning(ch));
        }
    }

    @Test
    void anUnpluggedChannelIsNotTheSameAsOneAtFullScale() {
        PaddleTimers paddles = new PaddleTimers();
        paddles.setPosition(0, 255);   // plugged in, at the far end of its travel
        paddles.trigger();             // channel 1 is left unplugged
        paddles.tick(2816);
        assertEquals(0x00, paddles.read(0), "full scale trips at exactly 2816 cycles");
        assertEquals(0x80, paddles.read(1), "an open circuit does not, however long you wait");
        paddles.tick(100_000);
        assertEquals(0x80, paddles.read(1));
    }

    @Test
    void unpluggingAPluggedInPaddleMakesItAnOpenCircuit() {
        PaddleTimers paddles = new PaddleTimers();
        paddles.setPosition(0, 10);
        paddles.disconnect(0);
        paddles.trigger();
        paddles.tick(1_000_000);
        assertEquals(0x80, paddles.read(0));
    }

    @Test
    void unpluggingMidCountdownStopsTheChargingAndLeavesTheOutputHigh() {
        PaddleTimers paddles = new PaddleTimers();
        paddles.setPosition(0, 255);
        paddles.trigger();
        paddles.tick(100);
        assertEquals(0x80, paddles.read(0), "still counting");
        paddles.disconnect(0);
        paddles.tick(100_000);
        assertEquals(0x80, paddles.read(0), "the resistor is gone: it never finishes charging");
    }

    @Test
    void pluggingInWhileSittingHighStartsChargingFromThatMoment() {
        PaddleTimers paddles = new PaddleTimers();
        paddles.trigger();             // unplugged: goes high and stays there
        paddles.tick(5000);
        assertEquals(0x80, paddles.read(0));

        paddles.setPosition(0, 10);    // 10 * 2816 / 255 = 110 cycles
        paddles.tick(109);
        assertEquals(0x80, paddles.read(0), "not yet");
        paddles.tick(1);
        assertEquals(0x00, paddles.read(0), "trips 110 cycles after being plugged in");
    }

    @Test
    void channelsArePluggedInIndependently() {
        PaddleTimers paddles = new PaddleTimers();
        paddles.setPosition(1, 20);
        paddles.trigger();
        paddles.tick(3000);
        assertEquals(0x80, paddles.read(0), "channel 0: nothing plugged in");
        assertEquals(0x00, paddles.read(1), "channel 1: a paddle that has long since tripped");
        assertEquals(0x80, paddles.read(2), "channel 2: nothing plugged in");
        assertEquals(0x80, paddles.read(3), "channel 3: nothing plugged in");
    }

    @Test
    void aPaddlePluggedBackInAfterBeingUnpluggedBehavesNormallyAgain() {
        PaddleTimers paddles = new PaddleTimers();
        paddles.setPosition(0, 128);
        paddles.disconnect(0);
        paddles.setPosition(0, 0);
        paddles.trigger();
        assertEquals(0x00, paddles.read(0), "position 0 expires immediately, as it always did");
    }
}
