package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * Drives a real byte through the real soft switches end to end --
     * LOAD mode (Q6=1,Q7=1, the real {@code STA $C08D,X} equivalent),
     * then WRITE/SHIFT mode (Q6=0,Q7=1) -- and confirms it actually
     * lands in the track's real bit data, read back via a fresh
     * {@link TrackBitStream} positioned at the same spot.
     * <p>
     * This caught a real bug before delivery, not a theoretical one:
     * {@link Disk2Controller#tick} was unconditionally reading a pulse
     * bit from the stream every 8th tick regardless of mode, which
     * advanced the stream's position a second time on top of the
     * write's own advance whenever a bit was also written that same
     * tick -- silently writing every bit to the wrong (every-other)
     * position instead of consecutive ones. A test this level of
     * integration is exactly what catching that required: neither
     * {@code Disk2LogicSequencerTest} (which only checks the bits
     * {@code tick()} reports as written, never where they land in a
     * real stream) nor a unit test of {@code TrackBitStream.writeBit}
     * alone (which has no notion of "every 8 ticks" at all) could have
     * found it on its own.
     */
    @Test
    void writingARealByteThroughTheRealSoftSwitchesLandsInTheTrackData(@TempDir Path tempDir) throws IOException {
        Disk2Controller controller = new Disk2Controller();
        Path wozFile = WozTestFixtures.buildSyntheticWozFile(tempDir, false); // NOT write-protected
        controller.drive(0).insert(wozFile);
        controller.writeIoSwitch(0x9, 0); // motor on
        controller.writeIoSwitch(0xA, 0); // select drive 1

        int byteToWrite = 0xD5; // 11010101

        // LOAD mode: Q6=1, Q7=1 -- real software's STA $C08D,X equivalent
        controller.writeIoSwitch(0xF, 0); // Q7 high
        controller.writeIoSwitch(0xD, byteToWrite); // Q6 high, and this value becomes the write-data register
        controller.tick(8); // a handful of CPU cycles -- plenty for the LD action to fire (confirmed to need only ~2 LSS ticks)

        // Switch to actual WRITE/SHIFT mode: Q6=0, Q7=1
        controller.writeIoSwitch(0xC, 0); // Q6 low

        // 32 CPU cycles = 64 LSS ticks = 8 bit-cells = one full byte's worth of shift-out time.
        controller.tick(32);

        // Re-reads via a FRESH TrackBitStream from the SAME, LIVE
        // WozDiskImage instance the write actually went through
        // (controller.drive(0).diskImage(), not a separate load() of the
        // file) -- confirms the write landed in that image's own
        // persistent backing array (see WozDiskImage.trackAt's own
        // Javadoc on why a fresh wrapper object still sees it), not just
        // something visible through the one TrackBitStream instance
        // Disk2Controller happened to be holding onto during the write
        // itself. Persisting this back to the host FILE is a separate,
        // later concern (see the project's own TODO) -- not tested here.
        TrackBitStream track = controller.drive(0).diskImage().trackAt(0);

        // The bits were written starting wherever track 0's read position
        // happened to be left after the LOAD-mode ticks above (which
        // never read the stream at all, since write modes skip that --
        // see Disk2Controller.tick's own comment) -- so they start at
        // position 0, the stream's untouched starting point.
        track.seekTo(0);
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            actual.append(track.nextBit());
        }
        assertEquals("11010101", actual.toString(),
            "the byte written through the real soft switches should land as consecutive bits starting at position 0, "
            + "not skip every other position the way the double-advance bug caused");
    }

    @Test
    void writingToAWriteProtectedDiskDoesNotActuallyChangeTheTrackData(@TempDir Path tempDir) throws IOException {
        Disk2Controller controller = new Disk2Controller();
        Path wozFile = WozTestFixtures.buildSyntheticWozFile(tempDir, true); // WRITE-PROTECTED
        controller.drive(0).insert(wozFile);
        controller.writeIoSwitch(0x9, 0); // motor on
        controller.writeIoSwitch(0xA, 0); // select drive 1

        // Capture the track's original bits before attempting to write.
        TrackBitStream before = controller.drive(0).diskImage().trackAt(0);
        before.seekTo(0);
        StringBuilder original = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            original.append(before.nextBit());
        }

        int byteToWrite = (original.charAt(0) == '1') ? 0x00 : 0xFF; // deliberately different from whatever is already there
        controller.writeIoSwitch(0xF, 0);
        controller.writeIoSwitch(0xD, byteToWrite);
        controller.tick(8);
        controller.writeIoSwitch(0xC, 0);
        controller.tick(32);

        TrackBitStream after = controller.drive(0).diskImage().trackAt(0);
        after.seekTo(0);
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            actual.append(after.nextBit());
        }
        assertEquals(original.toString(), actual.toString(),
            "a write-protected disk's track data must be completely unchanged by a write attempt, "
            + "matching how a real write-protect notch physically prevents the write at the drive");
    }

    /** Writes a known byte to track 0 through the real soft switches -- shared by several tests below. */
    private static void writeKnownByteToTrack0(Disk2Controller controller, int byteToWrite) {
        controller.writeIoSwitch(0xF, 0); // Q7 high
        controller.writeIoSwitch(0xD, byteToWrite); // Q6 high, loads the write-data register
        controller.tick(8);
        controller.writeIoSwitch(0xC, 0); // Q6 low -- shift-for-write mode
        controller.tick(32);
        controller.writeIoSwitch(0xE, 0); // Q7 low -- back to read mode, so a track step below behaves normally
    }

    @Test
    void movingTheHeadAwayFromAWrittenTrackPersistsItToTheHostFile(@TempDir Path tempDir) throws IOException {
        Disk2Controller controller = new Disk2Controller();
        Path wozFile = WozTestFixtures.buildSyntheticWozFile(tempDir, false);
        controller.drive(0).insert(wozFile);
        controller.writeIoSwitch(0x9, 0); // motor on
        controller.writeIoSwitch(0xA, 0); // select drive 1

        controller.drive(0).currentTrackStream(); // establishes streamedQuarterTrack at 0 before any write
        writeKnownByteToTrack0(controller, 0xD5);

        // Not yet persisted -- a fresh reload should still show the
        // original, unwritten track data at this point.
        WozDiskImage beforeStep = WozDiskImage.load(wozFile);
        TrackBitStream beforeTrack = beforeStep.trackAt(0);
        StringBuilder beforeBits = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            beforeBits.append(beforeTrack.nextBit());
        }
        assertEquals(WozTestFixtures.TRACK_0_BITS.substring(0, 8), beforeBits.toString(),
            "the host file should not reflect the write yet -- persistence happens on track change, not immediately");

        // Step the head to a different track -- this is the trigger.
        controller.drive(0).step(+1);
        controller.drive(0).currentTrackStream(); // actually triggers the track-change persist

        WozDiskImage afterStep = WozDiskImage.load(wozFile);
        TrackBitStream afterTrack = afterStep.trackAt(0);
        StringBuilder afterBits = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            afterBits.append(afterTrack.nextBit());
        }
        assertEquals("11010101", afterBits.toString(),
            "moving the head away from the written track should have persisted it to the host file");
    }

    @Test
    void ejectingAWrittenDiskPersistsItFirst(@TempDir Path tempDir) throws IOException {
        Disk2Controller controller = new Disk2Controller();
        Path wozFile = WozTestFixtures.buildSyntheticWozFile(tempDir, false);
        controller.drive(0).insert(wozFile);
        controller.writeIoSwitch(0x9, 0);
        controller.writeIoSwitch(0xA, 0);

        controller.drive(0).currentTrackStream();
        writeKnownByteToTrack0(controller, 0xFF);

        controller.drive(0).eject();

        WozDiskImage reloaded = WozDiskImage.load(wozFile);
        TrackBitStream track = reloaded.trackAt(0);
        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            bits.append(track.nextBit());
        }
        assertEquals("11111111", bits.toString(), "ejecting should have persisted the pending write first");
    }

    @Test
    void ejectDoesNotActuallyEjectWhenPersistFails(@TempDir Path tempDir) throws IOException {
        Disk2Controller controller = new Disk2Controller();
        Path wozFile = WozTestFixtures.buildSyntheticWozFile(tempDir, false);
        controller.drive(0).insert(wozFile);
        controller.writeIoSwitch(0x9, 0);
        controller.writeIoSwitch(0xA, 0);

        controller.drive(0).currentTrackStream();
        writeKnownByteToTrack0(controller, 0xAA); // marks the image dirty, so persist() will actually try to read/write the file

        Files.delete(wozFile); // simulates the file becoming inaccessible -- persist() will throw reading it

        assertThrows(IOException.class, () -> controller.drive(0).eject());
        assertTrue(controller.drive(0).isPresent(),
            "the disk must still be considered present after a failed eject -- "
            + "the in-memory write (and the only reference to it) must not be discarded along with a failed persist");
    }

    @Test
    void insertNewBlankDiskPreservesVirginStateThroughTheRealDrive(@TempDir Path tempDir) throws IOException {
        // The whole point of Drive.insertNewBlankDisk existing as its own
        // method, rather than createBlank() followed by the ordinary
        // insert(path): confirms that path through the REAL Disk2Controller
        // actually preserves genuine per-read randomization on an
        // unformatted track, not just that WozDiskImage.createBlank()
        // does in isolation.
        Disk2Controller controller = new Disk2Controller();
        Path path = tempDir.resolve("blank.woz");

        controller.drive(0).insertNewBlankDisk(path);

        assertTrue(controller.drive(0).isPresent());
        assertFalse(controller.drive(0).isWriteProtected());

        TrackBitStream firstRead = controller.drive(0).diskImage().trackAt(0);
        TrackBitStream secondRead = controller.drive(0).diskImage().trackAt(0);
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            first.append(firstRead.nextBit());
            second.append(secondRead.nextBit());
        }
        assertFalse(first.toString().equals(second.toString()),
            "a disk inserted via insertNewBlankDisk must read as genuine randomness on its unformatted tracks, "
            + "not whatever would happen if it had been silently reloaded from disk via plain insert() instead");
    }

    @Test
    void writingToANewBlankDiskThroughTheRealSoftSwitchesFormatsItCorrectly(@TempDir Path tempDir) throws IOException {
        // The actual, real-world use case end to end: insert a blank
        // disk, then "format" track 0 by writing a real byte to it
        // through the same soft switches any real software (DOS 3.3's
        // INIT included) would use -- confirming the whole chain from
        // insertNewBlankDisk through Disk2LogicSequencer's real write
        // mechanism works together, not just each piece in isolation.
        Disk2Controller controller = new Disk2Controller();
        Path path = tempDir.resolve("blank.woz");
        controller.drive(0).insertNewBlankDisk(path);
        controller.writeIoSwitch(0x9, 0); // motor on
        controller.writeIoSwitch(0xA, 0); // select drive 1

        writeKnownByteToTrack0(controller, 0xD5);

        TrackBitStream track = controller.drive(0).diskImage().trackAt(0);
        track.seekTo(0);
        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            bits.append(track.nextBit());
        }
        assertEquals("11010101", bits.toString());

        // And a second read must be stable now -- no longer random,
        // confirming the track actually, permanently left weak-bit mode.
        TrackBitStream trackAgain = controller.drive(0).diskImage().trackAt(0);
        trackAgain.seekTo(0);
        StringBuilder bitsAgain = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            bitsAgain.append(trackAgain.nextBit());
        }
        assertEquals(bits.toString(), bitsAgain.toString());
    }
}
