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

    /** LSS ticks per bit cell -- 8, matching {@code Disk2Controller}. */
    private static final int TICKS_PER_CELL = 8;

    /**
     * Drives bytes through the real write protocol with DOS 3.3's own
     * write-loop timing -- LOAD mode (Q6=1,Q7=1) for {@code loadTicks}
     * LSS ticks, then SHIFT-FOR-WRITE (Q6=0,Q7=1) for the rest of each
     * 64-tick (32-CPU-cycle) byte period -- and decodes
     * {@link Disk2LogicSequencer#writeSignal} the way real hardware does:
     * a bit cell containing a level change is a 1, one without is a 0.
     * Cells are counted from the tick Q7 first goes high, which here is
     * also the tick the first byte's LOAD window opens.
     *
     * @param bytes     the bytes to write, in order
     * @param loadTicks LSS ticks spent in LOAD mode at the start of each byte period
     * @return the decoded bitstream, one character per bit cell
     */
    private static String writeWithDosTiming(int[] bytes, int loadTicks) {
        Disk2LogicSequencer sequencer = new Disk2LogicSequencer();
        sequencer.setQ7(true);
        int writeLine = sequencer.writeSignal();
        boolean flux = false;
        int tick = 0;
        StringBuilder cells = new StringBuilder();
        for (int value : bytes) {
            sequencer.setWriteDataRegister(value);
            for (int t = 0; t < 8 * TICKS_PER_CELL; t++) {
                sequencer.setQ6(t < loadTicks);
                sequencer.tick(0);
                if (sequencer.writeSignal() != writeLine) {
                    writeLine = sequencer.writeSignal();
                    flux = true;
                }
                if (++tick % TICKS_PER_CELL == 0) {
                    cells.append(flux ? '1' : '0');
                    flux = false;
                }
            }
        }
        return cells.toString();
    }

    private static String bits(int value) {
        return String.format("%8s", Integer.toBinaryString(value)).replace(' ', '0');
    }

    /**
     * The diagnosed INIT HELLO,D2 failure, pinned at the sequencer
     * level: under DOS's real write-loop timing (a reload once every 32
     * CPU cycles), every byte must reach the disk as exactly 8 bits.
     * The previous model only produced a written bit on a shift action,
     * and the cell during which the LSS performs LD instead of a shift
     * produced none -- so every byte went out as 7 bits with bit 0 lost
     * (96 96 96... read back as 97 B9 E5 CB).
     * <p>
     * Exercised across every LOAD-window length from 3 to 10 LSS ticks.
     * DOS's real window is one 4- or 5-cycle instruction (8 to 10 ticks,
     * depending on which side of the soft-switch access an
     * instruction-granularity clock charges the cycles), so this covers
     * it either way with margin below. Outside this range the protocol
     * itself breaks, not the model: 1-2 ticks is too short for the LD
     * action to fire at all, and 11+ ticks spans an extra cell boundary
     * in LOAD mode, writing bit 7 twice -- neither happens with DOS.
     */
    @Test
    void everyByteReachesTheDiskAsExactlyEightBitsUnderDosWriteTiming() {
        int[] bytes = {0xFF, 0xD5, 0xAA, 0x96, 0x96, 0x96, 0x00, 0xDE, 0xAA, 0xEB, 0xFF};
        StringBuilder expected = new StringBuilder();
        for (int value : bytes) {
            expected.append(bits(value));
        }
        for (int loadTicks = 3; loadTicks <= 10; loadTicks++) {
            assertEquals(expected.toString(), writeWithDosTiming(bytes, loadTicks),
                "bytes must go out as exactly 8 bits each, MSB first (load window " + loadTicks + " ticks)");
        }
    }

    /**
     * Direct contradiction of the old model's "load mode never writes"
     * rule: real hardware keeps driving the write line in BOTH Q7=1
     * modes, so a cell spent entirely in LOAD mode with the latch's MSB
     * set still puts a 1 on the disk.
     */
    @Test
    void loadModeStillDrivesTheWriteLine() {
        Disk2LogicSequencer sequencer = new Disk2LogicSequencer();
        sequencer.setWriteDataRegister(0x80);
        sequencer.setQ6(true);
        sequencer.setQ7(true); // LOAD mode, held throughout
        int writeLine = sequencer.writeSignal();
        int transitions = 0;
        for (int t = 0; t < 10 * TICKS_PER_CELL; t++) {
            sequencer.tick(0);
            if (sequencer.writeSignal() != writeLine) {
                writeLine = sequencer.writeSignal();
                transitions++;
            }
        }
        assertTrue(transitions >= 9,
            "a latch with its MSB set should produce a transition in (nearly) every cell even while held in LOAD mode, saw "
            + transitions + " in 10 cells");
    }

    @Test
    void anAllZerosLatchNeverMovesTheWriteLine() {
        // Zero is the case a broken model can "pass" by accident -- e.g.
        // one that never writes anything at all. Paired with the test
        // above, this confirms transitions really track the latch's MSB.
        Disk2LogicSequencer sequencer = new Disk2LogicSequencer();
        sequencer.setWriteDataRegister(0x00);
        sequencer.setQ7(true);
        sequencer.setQ6(true);
        for (int t = 0; t < 4; t++) {
            sequencer.tick(0); // let the LD action fire
        }
        sequencer.setQ6(false);
        int writeLine = sequencer.writeSignal();
        for (int t = 0; t < 10 * TICKS_PER_CELL; t++) {
            sequencer.tick(0);
            assertEquals(writeLine, sequencer.writeSignal(), "shifting out zeros must never toggle the write line");
        }
    }
}
