package com.nordstrom.emulator.system;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One removable-media drive belonging to some {@link SlotCard} -- e.g. one
 * of a Disk II controller's two drives. Deliberately per-drive rather than
 * per-card, since a single card can expose more than one independently
 * swappable drive.
 * <p>
 * {@link #insert} is the ONE code path for loading media into this drive,
 * whether that happens at boot time (a card's constructor reading its
 * initial {@code driveN=} config value) or from a live host UI swapping
 * disks while the emulator runs. Real Apple II hardware doesn't
 * distinguish these either -- there's no special "initial" way to load a
 * disk versus a "runtime" way; you always just open the drive and put a
 * disk in it.
 * <p>
 * Swapping media while the drive is actively spinning is intentionally
 * NOT prevented here, because real hardware doesn't prevent it either --
 * ejecting a disk mid-write on a real Disk II corrupts whatever was being
 * written, and this drive should be equally capable of that same failure
 * mode, not artificially safer than the thing it's emulating. A host UI
 * is free to warn a user before swapping while a drive is active; that's
 * a host-level courtesy, not a constraint this interface should enforce.
 */
public interface RemovableMediaDrive {

    /**
     * Loads new media into this drive, replacing whatever (if anything) was previously loaded.
     *
     * @param imagePath path to the disk image to load
     * @throws IOException if the image can't be read
     */
    void insert(Path imagePath) throws IOException;

    /**
     * Creates a new, blank WOZ disk image file at {@code path} and
     * inserts it into this drive directly, replacing whatever (if
     * anything) was previously loaded -- the same as {@link #insert}, but
     * for media that doesn't exist yet rather than an existing file.
     * <p>
     * Not simply {@code createBlank} followed by a separate
     * {@link #insert} call: the disk starts out entirely unformatted,
     * with every track reading as fresh, genuine randomness until real
     * software actually formats it, and that "still unformatted" state
     * is tracked only in memory -- going through a second, separate load
     * from the file this just wrote would immediately and silently lose
     * it. DSK images aren't offered here: this project's DSK write
     * support doesn't exist yet, so a blank DSK file would be a format
     * nothing could actually write to.
     *
     * @param path where to write the new file -- must not already exist
     * @throws IOException if the file can't be created
     */
    void insertNewBlankDisk(Path path) throws IOException;

    /**
     * Ejects the currently loaded disk, first persisting any unpersisted
     * writes to it.
     *
     * @throws IOException if those writes exist but can't be persisted --
     *         the disk is NOT ejected in that case, so the in-memory
     *         state (and the chance to retry) isn't lost along with it
     */
    void eject() throws IOException;

    /** @return true if media is currently loaded */
    boolean isPresent();

    /** @return the path of the currently loaded image, if any -- for a host UI to display, e.g. "Drive 1: disk1.woz" */
    Optional<Path> currentImagePath();

    /**
     * Whether the currently loaded disk is write-protected.
     *
     * @return true if write-protected, or false if nothing is loaded --
     *         there's nothing to protect in an empty drive, matching how
     *         {@link #isPresent} is the real signal a host UI should check
     *         before deciding whether to even show a write-protect control
     *         as enabled
     */
    boolean isWriteProtected();

    /**
     * Sets the currently loaded disk's write-protect state, persisting
     * it to the host disk image file -- see whichever concrete disk
     * image class is actually loaded for the format-specific mechanism.
     *
     * @param protect true to write-protect the loaded disk, false to allow writes
     * @throws IOException if the change can't be persisted
     * @throws IllegalStateException if no disk is currently loaded
     */
    void setWriteProtected(boolean protect) throws IOException;
}
