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

    /** Removes whatever media is currently loaded, if any -- the drive becomes empty, matching a real drive with its door open and nothing inside. */
    void eject();

    /** @return true if media is currently loaded */
    boolean isPresent();

    /** @return the path of the currently loaded image, if any -- for a host UI to display, e.g. "Drive 1: disk1.woz" */
    Optional<Path> currentImagePath();
}
