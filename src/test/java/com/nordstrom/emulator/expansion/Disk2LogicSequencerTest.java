package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in {@link Disk2LogicSequencer}'s behavior against a parallel
 * reference implementation of a2kit's independent, real-world Rust LSS
 * logic -- the same reference used to originally discover that this
 * project's own, previously-documented address-bit wiring
 * (see {@link DiskLogicSequencerRom}'s own Javadoc) was wrong. That
 * discovery only had value at the moment it was made unless it's
 * pinned down here: nothing about {@link Disk2LogicSequencerRom}'s 256
 * bytes or {@link Disk2LogicSequencer}'s address assembly would need to
 * change for this test to start silently passing on a broken mapping
 * again.
 */
class Disk2LogicSequencerTest {

    /** A direct Java port of the Python cross-check used to originally find the correct address mapping. */
    private static int[] referenceTick(int state, int latch, boolean q6, boolean q7, int pulse,
            boolean writeProtected, int writeData) {
        int s0 = state & 1;
        int s1 = (state >> 1) & 1;
        int s2 = (state >> 2) & 1;
        int s3 = (state >> 3) & 1;
        int highBit = (latch >> 7) & 1;
        int q6Bit = q6 ? 1 : 0;
        int q7Bit = q7 ? 1 : 0;
        int pulseInverted = pulse ^ 1;
        int address = (s3 << 7) | (s2 << 6) | (s0 << 5) | (pulseInverted << 4)
            | (q7Bit << 3) | (q6Bit << 2) | (highBit << 1) | s1;
        int output = DiskLogicSequencerRom.read(address);
        int nextState = (output >> 4) & 0xF;
        int action = output & 0xF;
        int newLatch = switch (action) {
            case 0x0 -> 0;
            case 0x8 -> latch;
            case 0x9 -> (latch << 1) & 0xFF;
            case 0xA -> writeProtected ? 0xFF : (latch >> 1);
            case 0xB -> writeData;
            case 0xD -> ((latch << 1) | 1) & 0xFF;
            default -> throw new IllegalStateException("illegal action " + action);
        };
        return new int[] {nextState, newLatch};
    }

    @Test
    void matchesReferenceImplementationAcross2000RandomPulsesInReadMode() {
        Disk2LogicSequencer sequencer = new Disk2LogicSequencer();
        sequencer.setQ6(false);
        sequencer.setQ7(false);

        Random random = new Random(42);
        int refState = 0;
        int refLatch = 0;
        for (int i = 0; i < 2000; i++) {
            int pulse = random.nextInt(2);
            sequencer.tick(pulse);
            int[] ref = referenceTick(refState, refLatch, false, false, pulse, false, 0);
            refState = ref[0];
            refLatch = ref[1];
            assertEquals(refLatch, sequencer.latch(), "latch mismatch at pulse " + i);
        }
    }

    @Test
    void matchesReferenceAcrossAllFourQ6Q7ModesIncludingWriteProtectSense() {
        Random random = new Random(7);
        int[][] pulseSets = new int[4][500];
        boolean[] q6Values = {false, false, true, true};
        boolean[] q7Values = {false, true, false, true};
        int writeData = 0xAB;

        for (int mode = 0; mode < 4; mode++) {
            for (int i = 0; i < 500; i++) {
                pulseSets[mode][i] = random.nextInt(2);
            }
        }

        for (int mode = 0; mode < 4; mode++) {
            Disk2LogicSequencer sequencer = new Disk2LogicSequencer();
            sequencer.setQ6(q6Values[mode]);
            sequencer.setQ7(q7Values[mode]);
            sequencer.setWriteDataRegister(writeData);

            int refState = 0;
            int refLatch = 0;
            for (int pulse : pulseSets[mode]) {
                sequencer.tick(pulse);
                int[] ref = referenceTick(refState, refLatch, q6Values[mode], q7Values[mode], pulse, false, writeData);
                refState = ref[0];
                refLatch = ref[1];
                assertEquals(refLatch, sequencer.latch(),
                    "mismatch in mode Q6=" + q6Values[mode] + " Q7=" + q7Values[mode]);
            }
        }
    }

    @Test
    void writeProtectedDiskInSenseModeEventuallyShowsAllOnes() {
        Disk2LogicSequencer sequencer = new Disk2LogicSequencer();
        sequencer.setQ6(true);
        sequencer.setQ7(false); // sense mode
        sequencer.setWriteProtected(true);

        for (int i = 0; i < 500; i++) {
            sequencer.tick(0);
        }

        assertEquals(0xFF, sequencer.latch(), "write-protected disk's sense mode should show 0xFF");
    }

    @Test
    void loadModeLoadsTheWriteDataRegisterIntoTheLatch() {
        Disk2LogicSequencer sequencer = new Disk2LogicSequencer();
        sequencer.setQ6(true);
        sequencer.setQ7(true); // load mode
        sequencer.setWriteDataRegister(0x5A);

        boolean sawLoadedValue = false;
        for (int i = 0; i < 100; i++) {
            sequencer.tick(0);
            if (sequencer.latch() == 0x5A) {
                sawLoadedValue = true;
            }
        }

        assertTrue(sawLoadedValue, "load mode should eventually place the write-data register into the latch");
    }

    @Test
    void neverThrowsAcrossADenseSweepOfAllFourModes() {
        // Every reachable (state, Q6, Q7, highBit, pulse) combination in the real ROM decodes to a
        // valid action -- confirmed by running many pulses across all 4 modes without exception.
        // If the ROM data or address mapping were ever corrupted, tick() would throw
        // IllegalStateException (see its own Javadoc) rather than silently misbehave.
        Disk2LogicSequencer sequencer = new Disk2LogicSequencer();
        Random random = new Random(123);
        boolean[] q6Values = {false, false, true, true};
        boolean[] q7Values = {false, true, false, true};

        assertDoesNotThrow(() -> {
            for (int mode = 0; mode < 4; mode++) {
                sequencer.setQ6(q6Values[mode]);
                sequencer.setQ7(q7Values[mode]);
                for (int i = 0; i < 5000; i++) {
                    sequencer.tick(random.nextInt(2));
                }
            }
        });
    }
}
