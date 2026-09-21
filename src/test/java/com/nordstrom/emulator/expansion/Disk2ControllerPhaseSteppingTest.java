package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks in {@link Disk2Controller}'s real phase-stepper algorithm --
 * confirmed against two independent sources when originally built: a
 * step occurs only when a phase that was genuinely on is turned off
 * while exactly one neighbor is on, with direction determined by which
 * neighbor. This is real, easy-to-get-subtly-wrong hardware behavior
 * (both-neighbors-on, neither-on, and redundant-off all have to
 * correctly produce no movement, not just the two "normal" stepping
 * cases), not something a passing glance at the code would catch a
 * regression in.
 * <p>
 * Each such clean transition moves the head by 2 quarter-tracks, not
 * 1 -- confirmed against real Apple documentation ("Beneath Apple
 * DOS": the disk arm is positionable over 70 "phases" across 35
 * tracks, 2 per track) and directly against a real Virtual ][ trace
 * of an actual disk's boot sequence, where a fixed seek target
 * produced exactly double this project's own resulting head travel
 * before this magnitude was corrected.
 */
class Disk2ControllerPhaseSteppingTest {

    @Test
    void turningAPhaseOnAloneNeverSteps() {
        Disk2Controller disk = new Disk2Controller();
        int start = disk.drive(0).quarterTrack();

        disk.writeIoSwitch(0x1, 0); // phase 0 on

        assertEquals(start, disk.drive(0).quarterTrack());
    }

    @Test
    void bothPhasesOnWithNoTurnOffDoesNotStep() {
        Disk2Controller disk = new Disk2Controller();
        int start = disk.drive(0).quarterTrack();

        disk.writeIoSwitch(0x1, 0); // phase 0 on
        disk.writeIoSwitch(0x3, 0); // phase 1 on -- both on, nothing turned off yet

        assertEquals(start, disk.drive(0).quarterTrack());
    }

    @Test
    void turningOffWithNextNeighborOnStepsInward() {
        Disk2Controller disk = new Disk2Controller();
        int start = disk.drive(0).quarterTrack();

        disk.writeIoSwitch(0x1, 0); // phase 0 on
        disk.writeIoSwitch(0x3, 0); // phase 1 on
        disk.writeIoSwitch(0x0, 0); // phase 0 off, next-neighbor (1) on -> step inward

        assertEquals(start + 2, disk.drive(0).quarterTrack());
    }

    @Test
    void continuingTheSequenceStepsInwardAgain() {
        Disk2Controller disk = new Disk2Controller();
        int start = disk.drive(0).quarterTrack();

        disk.writeIoSwitch(0x1, 0);
        disk.writeIoSwitch(0x3, 0);
        disk.writeIoSwitch(0x0, 0); // -> start + 2
        disk.writeIoSwitch(0x5, 0); // phase 2 on
        disk.writeIoSwitch(0x2, 0); // phase 1 off, next-neighbor (2) on -> step inward again

        assertEquals(start + 4, disk.drive(0).quarterTrack());
    }

    @Test
    void turningOffWithPreviousNeighborOnStepsOutward() {
        Disk2Controller disk = new Disk2Controller();
        // Move inward 3 times first so there's room to step back outward
        disk.writeIoSwitch(0x1, 0); disk.writeIoSwitch(0x3, 0); disk.writeIoSwitch(0x0, 0); // -> +2
        disk.writeIoSwitch(0x5, 0); disk.writeIoSwitch(0x2, 0); // -> +4
        disk.writeIoSwitch(0x7, 0); disk.writeIoSwitch(0x4, 0); // -> +6
        int beforeOutwardStep = disk.drive(0).quarterTrack();

        disk.writeIoSwitch(0x5, 0); // phase 2 on (previous/counter-clockwise neighbor of 3)
        disk.writeIoSwitch(0x6, 0); // phase 3 off, previous-neighbor (2) on -> step outward

        assertEquals(beforeOutwardStep - 2, disk.drive(0).quarterTrack());
    }

    @Test
    void bothNeighborsOnProducesNoStep() {
        Disk2Controller disk = new Disk2Controller();
        int start = disk.drive(0).quarterTrack();

        disk.writeIoSwitch(0x3, 0); // phase 1 on
        disk.writeIoSwitch(0x1, 0); // phase 0 on (previous neighbor of 1)
        disk.writeIoSwitch(0x5, 0); // phase 2 on (next neighbor of 1) -- both neighbors of 1 now on
        disk.writeIoSwitch(0x2, 0); // phase 1 off, with BOTH neighbors on -> no step

        assertEquals(start, disk.drive(0).quarterTrack());
    }

    @Test
    void neitherNeighborOnProducesNoStep() {
        Disk2Controller disk = new Disk2Controller();
        int start = disk.drive(0).quarterTrack();

        disk.writeIoSwitch(0x1, 0); // phase 0 on, alone
        disk.writeIoSwitch(0x0, 0); // phase 0 off, neither neighbor (1 or 3) on -> no step

        assertEquals(start, disk.drive(0).quarterTrack());
    }

    @Test
    void turningOffAnAlreadyOffPhaseNeverSteps() {
        Disk2Controller disk = new Disk2Controller();
        int start = disk.drive(0).quarterTrack();

        disk.writeIoSwitch(0x3, 0); // phase 1 on
        disk.writeIoSwitch(0x0, 0); // phase 0 off -- but phase 0 was never on, not a real transition

        assertEquals(start, disk.drive(0).quarterTrack());
    }

    @Test
    void positionIsClampedAtZero() {
        Disk2Controller disk = new Disk2Controller();

        // At position 0, phase 3 is a valid "previous neighbor" of phase 0 -- try stepping outward from 0
        disk.writeIoSwitch(0x1, 0); // phase 0 on
        disk.writeIoSwitch(0x7, 0); // phase 3 on
        disk.writeIoSwitch(0x0, 0); // phase 0 off, previous-neighbor (3) on -> would go to -1

        assertEquals(0, disk.drive(0).quarterTrack());
    }

    @Test
    void steppingAffectsOnlyTheSelectedDrive() {
        Disk2Controller disk = new Disk2Controller();
        disk.writeIoSwitch(0xB, 0); // select drive 2 (index 1)

        disk.writeIoSwitch(0x1, 0);
        disk.writeIoSwitch(0x3, 0);
        disk.writeIoSwitch(0x0, 0); // step drive 2 inward

        assertEquals(2, disk.drive(1).quarterTrack());
        assertEquals(0, disk.drive(0).quarterTrack());
    }
}
