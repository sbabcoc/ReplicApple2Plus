package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in {@link Disk2Controller#tick}'s full, real-hardware-ratio
 * timing: 2 LSS ticks per CPU cycle elapsed, sampling one real
 * bitstream bit every 8 LSS ticks (matching the real 4-CPU-cycle-per-bit
 * data rate, confirmed independently against two sources when
 * originally built). This is the actual integration point that makes
 * disk reading work at all -- {@link Disk2LogicSequencerTest} already
 * locks in the sequencer's own correctness in isolation, and
 * {@link WozDiskImageTest} locks in the bitstream parsing, but neither
 * proves the two are actually wired together at the right rate.
 */
class Disk2ControllerTickTest {

    /** A direct Java port of the same reference logic {@code Disk2LogicSequencerTest} uses, driven at the real 2-ticks-per-cycle, 1-real-bit-per-8-ticks ratio {@code Disk2Controller.tick} itself implements. */
    private static int referenceTickLatch(int[] stateHolder, int latch, int pulse) {
        int state = stateHolder[0];
        int s0 = state & 1;
        int s1 = (state >> 1) & 1;
        int s2 = (state >> 2) & 1;
        int s3 = (state >> 3) & 1;
        int highBit = (latch >> 7) & 1;
        int pulseInverted = pulse ^ 1;
        int address = (s3 << 7) | (s2 << 6) | (s0 << 5) | (pulseInverted << 4) | (0 << 3) | (0 << 2) | (highBit << 1) | s1;
        int output = DiskLogicSequencerRom.read(address);
        stateHolder[0] = (output >> 4) & 0xF;
        int action = output & 0xF;
        return switch (action) {
            case 0x0 -> 0;
            case 0x8 -> latch;
            case 0x9 -> (latch << 1) & 0xFF;
            case 0xD -> ((latch << 1) | 1) & 0xFF;
            default -> throw new IllegalStateException("unexpected action " + action + " in read mode");
        };
    }

    @Test
    void tickDrivesTheSequencerAtTheRealHardwareRatioMatchingAReferenceImplementation(@TempDir Path tempDir)
            throws IOException {
        Disk2Controller controller = new Disk2Controller();
        controller.drive(0).insert(WozTestFixtures.buildSyntheticWozFile(tempDir, true));
        controller.writeIoSwitch(0x9, 0); // motor on
        controller.writeIoSwitch(0xA, 0); // select drive 1
        controller.writeIoSwitch(0xC, 0); // Q6 low
        controller.writeIoSwitch(0xE, 0); // Q7 low -- read mode

        // Compute the reference trajectory using the exact same bit source and timing ratio.
        String trackBits = WozTestFixtures.TRACK_0_BITS;
        int[] bitPos = {0};
        Random random = new Random(99);
        int[] chunkChoices = {2, 3, 4, 5, 6, 7};
        int totalCpuCycles = 300;

        int[] refState = {0};
        int refLatch = 0;
        int cyclesDelivered = 0;
        while (cyclesDelivered < totalCpuCycles) {
            int chunk = chunkChoices[random.nextInt(chunkChoices.length)];
            chunk = Math.min(chunk, totalCpuCycles - cyclesDelivered);

            controller.tick(chunk);

            for (int i = 0; i < chunk * 2; i++) {
                // Real tick() samples a bit every 8th LSS tick, aligned to its own internal counter --
                // this reference must track the same alignment across calls, not just within one.
                int lssTickIndexWithinRun = (cyclesDelivered * 2) + i;
                int pulse = 0;
                if (lssTickIndexWithinRun % 8 == 0) {
                    pulse = Character.getNumericValue(trackBits.charAt(bitPos[0]));
                    bitPos[0] = (bitPos[0] + 1) % trackBits.length();
                }
                refLatch = referenceTickLatch(refState, refLatch, pulse);
            }
            cyclesDelivered += chunk;

            assertEquals(refLatch, controller.readIoSwitch(0xC),
                "latch mismatch after " + cyclesDelivered + " cycles");
        }
    }

    @Test
    void tickDoesNothingWhileMotorIsOff(@TempDir Path tempDir) throws IOException {
        Disk2Controller controller = new Disk2Controller();
        controller.drive(0).insert(WozTestFixtures.buildSyntheticWozFile(tempDir, true));
        controller.writeIoSwitch(0x8, 0); // motor explicitly off
        controller.writeIoSwitch(0xC, 0);
        controller.writeIoSwitch(0xE, 0);

        int before = controller.readIoSwitch(0xC);
        controller.tick(1000);
        int after = controller.readIoSwitch(0xC);

        assertEquals(before, after);
        assertEquals(0, after);
    }

    @Test
    void emptyUnselectedDriveNeverThrows(@TempDir Path tempDir) throws IOException {
        Disk2Controller controller = new Disk2Controller();
        controller.drive(0).insert(WozTestFixtures.buildSyntheticWozFile(tempDir, true));
        // drive 1 (index 1) has no disk inserted at all
        controller.writeIoSwitch(0x9, 0); // motor on
        controller.writeIoSwitch(0xB, 0); // select the EMPTY drive
        controller.writeIoSwitch(0xC, 0);
        controller.writeIoSwitch(0xE, 0);

        controller.tick(200);
        int latch = controller.readIoSwitch(0xC);

        assertTrue(latch >= 0 && latch <= 255);
    }

    @Test
    void writeProtectedDiskInSenseModeEventuallyShowsAllOnes(@TempDir Path tempDir) throws IOException {
        Disk2Controller controller = new Disk2Controller();
        controller.drive(0).insert(WozTestFixtures.buildSyntheticWozFile(tempDir, true)); // write-protected
        controller.writeIoSwitch(0x9, 0); // motor on
        controller.writeIoSwitch(0xA, 0); // select drive 1
        controller.writeIoSwitch(0xD, 0); // Q6 high
        controller.writeIoSwitch(0xE, 0); // Q7 low -- Q6=1,Q7=0 = sense mode

        controller.tick(400); // enough ticks to guarantee at least one sense action fires

        assertEquals(0xFF, controller.readIoSwitch(0xC));
    }

    @Test
    void notWriteProtectedDiskInSenseModeIsNotPermanentlyStuckAtAllOnes(@TempDir Path tempDir) throws IOException {
        Disk2Controller controller = new Disk2Controller();
        controller.drive(0).insert(WozTestFixtures.buildSyntheticWozFile(tempDir, false)); // NOT write-protected
        controller.writeIoSwitch(0x9, 0); // motor on
        controller.writeIoSwitch(0xA, 0); // select drive 1
        controller.writeIoSwitch(0xD, 0); // Q6 high
        controller.writeIoSwitch(0xE, 0); // Q7 low -- sense mode

        boolean sawSomethingOtherThanAllOnes = false;
        for (int i = 0; i < 20; i++) {
            controller.tick(20);
            if (controller.readIoSwitch(0xC) != 0xFF) {
                sawSomethingOtherThanAllOnes = true;
            }
        }

        assertTrue(sawSomethingOtherThanAllOnes,
            "a non-write-protected disk's sense mode should not be permanently stuck at 0xFF "
            + "the way a write-protected one correctly is");
    }
}
