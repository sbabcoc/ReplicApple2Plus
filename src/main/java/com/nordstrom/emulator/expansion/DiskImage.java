package com.nordstrom.emulator.expansion;

import java.io.IOException;

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
     * Sets this disk's write-protect state, persisting it to the host
     * disk image file -- not just an in-memory flag. The exact
     * mechanism is format-specific: see {@link WozDiskImage} and
     * {@link DskDiskImage}'s own implementations for what each
     * actually does.
     *
     * @param protect true to write-protect this disk, false to allow writes
     * @throws IOException if the change can't be persisted
     */
    void setWriteProtected(boolean protect) throws IOException;

    /**
     * @param quarterTrack 0-159 (track number x4, plus 0-3 for the quarter-track offset within it)
     * @return a bit stream over that track's data
     */
    TrackBitStream trackAt(int quarterTrack);

    /**
     * Persists any writes made since this image was loaded (or since the
     * last {@code persist()}) back to the host disk image file. A no-op,
     * including for a format that doesn't support writing at all yet, if
     * nothing has actually changed since the last call -- so a caller
     * can call this at every natural "about to lose easy access to
     * current state" point (a track change, an eject, an exit) without
     * needing to track for itself whether anything is actually dirty.
     *
     * @throws IOException if the change can't be persisted
     */
    void persist() throws IOException;

    /**
     * Marks this image as having unpersisted changes -- called by
     * {@link Disk2Controller} exactly when it actually calls
     * {@link TrackBitStream#writeBit}, so {@link #persist} knows there
     * is real work to do rather than rewriting an unchanged file on
     * every track seek, including ones that happen during ordinary
     * reading with no writes involved at all.
     */
    void markDirty();
}
