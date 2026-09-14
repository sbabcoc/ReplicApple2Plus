package com.nordstrom.emulator.system;

import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * A peripheral card occupying one motherboard slot (1-7). Mirrors the real
 * Apple II+ slot contract: a fixed $C0n0-$C0nF I/O switch region, a fixed
 * $Cn00-$CnFF ROM region, and optional participation in the shared
 * $C800-$CFFF expansion ROM window that only one slot can own at a time.
 * <p>
 * Every implementation must have a public NO-ARG constructor, and use
 * {@link #configure} (called exactly once, immediately after
 * construction, before any other method) to receive its own settings.
 * This is what makes
 * {@code META-INF/services/com.nordstrom.emulator.system.SlotCard} a
 * genuine, ordinary Java SPI declaration file -- just class names, no
 * special syntax -- rather than one that merely resembles the convention.
 * {@link java.util.ServiceLoader} requires exactly this shape.
 * <p>
 * A card's short config-file name ({@link #getShortName}) and recognized
 * parameters ({@link #getSupportedParameters}) are declared as real code
 * on the card itself, not as hand-typed comments in a separate file.
 * That's deliberate: a comment living apart from the class it describes
 * is a second source of truth that can silently drift from the first --
 * exactly the problem this project spent real effort detecting after the
 * fact in an earlier version of this design, before removing the second
 * source of truth entirely instead. Because construction is genuinely
 * no-arg now, discovering this metadata is safe and cheap: construct a
 * throwaway instance, ask it two questions, discard it.
 */
public interface SlotCard {

    /** A short config-file alias for this card type, or null (the default) for none -- a card with no short name is still loadable, just by its fully-qualified class name only. */
    default String getShortName() {
        return null;
    }

    /** The configuration keys this card recognizes, or null (the default) to accept anything with no validation. When non-null, both a config file supplying an unrecognized key and this card's own {@link #configure} querying an undeclared one fail loudly rather than silently. */
    default Set<String> getSupportedParameters() {
        return null;
    }

    /** Called exactly once, immediately after construction and before any other method, with this card's own settings (any slot-number prefix already stripped). */
    void configure(Properties props);

    /** Reads from this card's I/O switch region, offset 0-15 within $C0n0-$C0nF. */
    int readIoSwitch(int offset);

    /** Writes to this card's I/O switch region, offset 0-15 within $C0n0-$C0nF. */
    void writeIoSwitch(int offset, int value);

    /** Reads from this card's own ROM, offset 0-255 within $Cn00-$CnFF. */
    int readRom(int offset);

    /** Writes to this card's own ROM space. Most cards are read-only here. */
    default void writeRom(int offset, int value) {
        // no-op by default
    }

    /** Whether this card ever claims the shared $C800-$CFFF expansion ROM window. Most cards never do. */
    default boolean wantsExpansionRom() {
        return false;
    }

    /** Reads from the shared expansion ROM window, offset 0-2047 within $C800-$CFFF. Only called while this card owns it. */
    default int readExpansionRom(int offset) {
        throw new UnsupportedOperationException(getClass().getName() + " does not claim the expansion ROM window");
    }

    /** Writes to the shared expansion ROM window. Only called while this card owns it. */
    default void writeExpansionRom(int offset, int value) {
        // no-op by default
    }

    /** Fires whenever ANY access to $CFFF occurs, on any slot -- real hardware resets expansion-ROM ownership unconditionally on that address, regardless of which card (if any) currently owns the window. */
    default void onExpansionRomReleased() {
        // no-op by default
    }

    /**
     * Removable media drives this card exposes, if any (e.g. a Disk II
     * controller's two drives). Empty for cards with no removable media
     * -- most cards. Lets a host UI enumerate every drive across every
     * installed card generically, without needing to know about
     * DiskIIController (or any other specific card type) by name.
     */
    default List<RemovableMediaDrive> removableDrives() {
        return List.of();
    }
}
