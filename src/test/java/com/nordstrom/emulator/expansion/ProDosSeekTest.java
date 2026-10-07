package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The head must step according to where it actually is, whatever phases
 * were touched before. ProDOS 2.4.3's kernel seek (recorded from a real
 * boot) went one track short under an earlier stepper model that kept a
 * separately remembered "current phase": after a phase opposite the head
 * had been energized, that model adopted it as its reference without the
 * head moving, so the kernel's first inward step went outward instead.
 */
class ProDosSeekTest {

    private static final int PHASE_OFF = 0x0, PHASE_ON = 0x1; // + 2 x phase number

    private static void phase(Disk2Controller disk, int phase, boolean on) {
        disk.writeIoSwitch(phase * 2 + (on ? PHASE_ON : PHASE_OFF), 0);
    }

    /** Moves the head inward to half-track {@code halfTrack} with DOS-style overlapped steps, then turns every phase off. */
    private static void seekOutwardTo(Disk2Controller disk, int halfTrack) {
        phase(disk, 0, true);
        for (int h = 1; h <= halfTrack; h++) {
            phase(disk, h % 4, true);
            phase(disk, (h - 1) % 4, false);
        }
        phase(disk, halfTrack % 4, false);
    }

    @Test
    void anOppositePhaseNeitherMovesTheHeadNorMisleadsTheNextStep() {
        Disk2Controller disk = new Disk2Controller();
        seekOutwardTo(disk, 10); // track 5: half-track 10, over phase 2
        assertEquals(20, disk.drive(0).quarterTrack());

        phase(disk, 0, true);  // the magnet opposite the head: no pull either way
        phase(disk, 0, false);
        assertEquals(20, disk.drive(0).quarterTrack(), "an opposite magnet doesn't move the head");

        phase(disk, 1, true); // phase 1 is the head's inward neighbor
        assertEquals(18, disk.drive(0).quarterTrack(), "the head steps toward the energized neighbor -- inward");
    }

    @Test
    void proDosSeekFromTrackFiveReachesTrackZero() {
        Disk2Controller disk = new Disk2Controller();
        seekOutwardTo(disk, 10);
        phase(disk, 0, true); // as ProDOS's boot left it: the opposite phase touched last
        phase(disk, 0, false);

        // The kernel's seek to track 0, as recorded phase by phase: each new phase on, then the previous one off.
        int[] phases = {1, 0, 3, 2, 1, 0, 3, 2, 1, 0};
        int previous = -1;
        for (int p : phases) {
            phase(disk, p, true);
            if (previous >= 0) {
                phase(disk, previous, false);
            }
            previous = p;
        }
        assertEquals(0, disk.drive(0).quarterTrack(), "track 0, where ProDOS was seeking");
    }
}
