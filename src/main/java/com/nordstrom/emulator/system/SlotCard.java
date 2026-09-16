package com.nordstrom.emulator.system;

import com.nordstrom.emulator.InterruptLines;

import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * A peripheral card occupying one motherboard slot (0-7). Mirrors the real
 * Apple II+ slot contract: a fixed $C0n0-$C0nF I/O switch region, a fixed
 * $Cn00-$CnFF ROM region, and optional participation in the shared
 * $C800-$CFFF expansion ROM window that only one slot can own at a time.
 * <p>
 * Slot 0 is electrically special and every other slot's card can ignore
 * this paragraph: real hardware's /IOSEL and /IOSTRB signals -- which
 * drive the $Cn00-$CnFF ROM window and $C800-$CFFF expansion window
 * respectively -- are not connected to slot 0 at all. A card occupying
 * slot 0 still gets its own $C0n0-$C0nF I/O switches (there, {@code n}
 * is always 0) via the normal {@link #readIoSwitch}/{@link #writeIoSwitch}
 * methods below, but never has {@link #readRom}/{@link #writeRom} or the
 * expansion-ROM methods called -- there is no ROM window for slot 0 to
 * have. What slot 0 gets instead, uniquely, is the ability to bank-switch
 * $D000-$FFFF -- see {@link #wantsSlotZeroBanking}.
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
 * is a second source of truth that can silently drift from the first.
 * Because construction is genuinely no-arg, discovering this metadata is
 * safe and cheap: construct a throwaway instance, ask it two questions,
 * discard it.
 */
public interface SlotCard {

    /**
     * A short config-file alias for this card type, or null (the
     * default) for none -- a card with no short name is still loadable,
     * just by its fully-qualified class name only.
     *
     * @return the short name, or null for none
     */
    default String getShortName() {
        return null;
    }

    /**
     * The configuration keys this card recognizes, or null (the default)
     * to accept anything with no validation. When non-null, both a
     * config file supplying an unrecognized key and this card's own
     * {@link #configure} querying an undeclared one fail loudly rather
     * than silently.
     *
     * @return the recognized parameter names, or null for no validation
     */
    default Set<String> getSupportedParameters() {
        return null;
    }

    /**
     * Called exactly once, immediately after construction and before any
     * other method, with this card's own settings (any slot-number
     * prefix already stripped).
     *
     * @param props this card's own configuration properties
     */
    void configure(Properties props);

    /**
     * Called once, after {@link #configure}, with this card's narrow
     * view of the CPU's interrupt lines. Optional -- most cards never
     * raise an interrupt and simply don't override this. A card that
     * does keeps the reference (typically in a field) and calls
     * {@link InterruptLines#raiseIrq}/{@link InterruptLines#raiseNmi}
     * whenever its own hardware condition would assert one, and the
     * matching {@code lower*} call once that condition clears -- see
     * {@link InterruptLines}'s own Javadoc for why this is a real
     * least-privilege boundary rather than just an API nicety.
     *
     * @param lines this card's handle for asserting/releasing IRQ and NMI
     */
    default void connectInterruptLines(InterruptLines lines) {
        // no-op by default -- most cards never raise an interrupt at all
    }

    /**
     * Reads from this card's I/O switch region, offset 0-15 within $C0n0-$C0nF.
     *
     * @param offset 0-15 within this card's I/O switch region
     * @return the byte at that offset
     */
    int readIoSwitch(int offset);

    /**
     * Writes to this card's I/O switch region, offset 0-15 within $C0n0-$C0nF.
     *
     * @param offset 0-15 within this card's I/O switch region
     * @param value the byte to write
     */
    void writeIoSwitch(int offset, int value);

    /**
     * Reads from this card's own ROM, offset 0-255 within $Cn00-$CnFF.
     *
     * @param offset 0-255 within this card's ROM
     * @return the byte at that offset
     */
    int readRom(int offset);

    /**
     * Writes to this card's own ROM space. Most cards are read-only here.
     *
     * @param offset 0-255 within this card's ROM
     * @param value the byte to write
     */
    default void writeRom(int offset, int value) {
        // no-op by default
    }

    /**
     * Whether this card ever claims the shared $C800-$CFFF expansion ROM window. Most cards never do.
     *
     * @return true if this card can own the expansion ROM window
     */
    default boolean wantsExpansionRom() {
        return false;
    }

    /**
     * Reads from the shared expansion ROM window, offset 0-2047 within
     * $C800-$CFFF. Only called while this card owns it.
     *
     * @param offset 0-2047 within the expansion ROM window
     * @return the byte at that offset
     */
    default int readExpansionRom(int offset) {
        throw new UnsupportedOperationException(getClass().getName() + " does not claim the expansion ROM window");
    }

    /**
     * Writes to the shared expansion ROM window. Only called while this card owns it.
     *
     * @param offset 0-2047 within the expansion ROM window
     * @param value the byte to write
     */
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
     *
     * @return this card's removable drives, or an empty list if it has none
     */
    default List<RemovableMediaDrive> removableDrives() {
        return List.of();
    }

    /**
     * Whether this card, occupying slot 0, bank-switches $D000-$FFFF.
     * Meaningless for any other slot -- ignored there. Most cards never
     * do this; it's a capability unique to slot 0's special wiring, not
     * something any slot's card could opt into.
     *
     * @return true if this card bank-switches $D000-$FFFF from slot 0
     */
    default boolean wantsSlotZeroBanking() {
        return false;
    }

    /**
     * Reads within $D000-$FFFF, offset 0-$2FFF. Only called while this
     * card occupies slot 0 and {@link #wantsSlotZeroBanking} is true.
     *
     * @param offset 0-$2FFF within $D000-$FFFF
     * @return the byte at that offset
     */
    default int readSlotZeroBank(int offset) {
        throw new UnsupportedOperationException(getClass().getName() + " does not provide slot-0 banking");
    }

    /**
     * Writes within $D000-$FFFF. Only called while this card occupies
     * slot 0 and {@link #wantsSlotZeroBanking} is true.
     *
     * @param offset 0-$2FFF within $D000-$FFFF
     * @param value the byte to write
     */
    default void writeSlotZeroBank(int offset, int value) {
        // no-op by default
    }
}
