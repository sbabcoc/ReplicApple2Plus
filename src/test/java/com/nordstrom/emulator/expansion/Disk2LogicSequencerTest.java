package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Writes a real byte through the real LOAD-then-SHIFT protocol
     * (Q6=1,Q7=1 to load, then Q6=0,Q7=1 to shift it out) and confirms
     * {@link Disk2LogicSequencer#tick}'s return value assembles back into
     * the exact original byte, MSB first. This is the permanent record
     * of the investigation that found the actual write mechanism:
     * confirmed directly by tracing this same sequence against the real
     * ROM data and cross-checking against a2kit's independent model
     * (itself confirmed byte-for-byte identical to this project's own
     * ROM table across all 256 entries) before trusting it -- not
     * assumed from the mechanism's general description alone, which
     * turned out to describe a different (and, for this ROM table,
     * incorrect) detail than what's actually implemented here.
     *
     * @param byteToWrite the byte to drive through the real write sequence
     */
    private static void assertByteWritesCorrectly(int byteToWrite) {
        Disk2LogicSequencer sequencer = new Disk2LogicSequencer();

        // LOAD mode: Q6=1, Q7=1
        sequencer.setQ6(true);
        sequencer.setQ7(true);
        sequencer.setWriteDataRegister(byteToWrite);
        for (int i = 0; i < 16 && sequencer.latch() != byteToWrite; i++) {
            sequencer.tick(0);
        }
        assertEquals(byteToWrite, sequencer.latch(), "the byte should be loaded into the latch within a few ticks");

        // Switch to actual WRITE/SHIFT mode: Q6=0, Q7=1
        sequencer.setQ6(false);

        StringBuilder writtenBits = new StringBuilder();
        for (int i = 0; i < 128 && writtenBits.length() < 8; i++) {
            int bit = sequencer.tick(0);
            if (bit != -1) {
                writtenBits.append(bit);
            }
        }

        String expected = String.format("%8s", Integer.toBinaryString(byteToWrite)).replace(' ', '0');
        assertEquals(expected, writtenBits.toString(),
            "the bits reported as written should reassemble into the original byte, MSB first");
    }

    @Test
    void writingKnownByteD5ThroughTheRealLoadThenShiftProtocolProducesTheCorrectBits() {
        assertByteWritesCorrectly(0xD5); // 11010101 -- the famous address-prologue byte
    }

    @Test
    void writingAllOnesByteProducesAllOnes() {
        assertByteWritesCorrectly(0xFF);
    }

    @Test
    void writingAllZerosByteStillProducesEightBitsOfOutput() {
        // Zero is the hardest case to get right by accident: a bug that
        // silently never reports a written bit at all would also "pass"
        // a test that only checked the bit VALUES, not that exactly 8
        // bits were reported as written in the first place.
        assertByteWritesCorrectly(0x00);
    }

    @Test
    void writtenBitIsMinusOneDuringReadModeEvenWhenAShiftOccurs() {
        // SL0/SL1 actions also occur during normal reading -- that's how
        // incoming bits get assembled into the latch. Confirms tick()
        // doesn't report a "written" bit during read mode just because
        // the same shift actions happen to fire there too.
        Disk2LogicSequencer sequencer = new Disk2LogicSequencer();
        sequencer.setQ6(false);
        sequencer.setQ7(false); // normal read mode

        boolean sawAWrittenBitDuringReadMode = false;
        for (int i = 0; i < 200; i++) {
            if (sequencer.tick(1) != -1) {
                sawAWrittenBitDuringReadMode = true;
            }
        }
        assertFalse(sawAWrittenBitDuringReadMode, "read mode should never report a written bit, even though it shifts the latch too");
    }

    @Test
    void writtenBitIsMinusOneDuringLoadModeAndDuringWriteProtectSenseMode() {
        Disk2LogicSequencer sequencer = new Disk2LogicSequencer();
        sequencer.setWriteDataRegister(0xAA);

        sequencer.setQ6(true);
        sequencer.setQ7(true); // LOAD mode
        for (int i = 0; i < 50; i++) {
            assertEquals(-1, sequencer.tick(0), "LOAD mode should never report a written bit");
        }

        sequencer.setQ6(true);
        sequencer.setQ7(false); // SENSE mode
        for (int i = 0; i < 50; i++) {
            assertEquals(-1, sequencer.tick(0), "SENSE mode should never report a written bit");
        }
    }
}
