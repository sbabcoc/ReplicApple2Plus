package com.nordstrom.emulator.expansion;

/**
 * What {@link Disk2Controller.Drive} and {@link Disk2LogicSequencer}
 * actually need from a loaded disk image, regardless of the file
 * format it came from. {@link WozDiskImage} exposes a real, captured
 * bitstream; {@link DskDiskImage} synthesizes an equivalent one from
 * sector data on the fly -- neither the drive nor the sequencer needs
 * to know or care which.
 */
interface DiskImage {

    /**
     * @return whether this disk is write-protected
     */
    boolean isWriteProtected();

    /**
     * @param quarterTrack 0-159 (track number x4, plus 0-3 for the quarter-track offset within it)
     * @return a bit stream over that track's data
     */
    TrackBitStream trackAt(int quarterTrack);
}
