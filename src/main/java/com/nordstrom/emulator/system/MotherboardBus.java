package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;

import java.util.ArrayList;
import java.util.List;

/**
 * The Apple II+'s real memory bus. Dispatches:
 * <ul>
 *   <li>$0000-$BFFF: main RAM</li>
 *   <li>$C090-$C0FF: per-slot I/O switches, 16 bytes/slot for slots 1-7 -- {@link SlotCard#readIoSwitch}/{@link SlotCard#writeIoSwitch}</li>
 *   <li>$C100-$C7FF: per-slot ROM, 256 bytes/slot for slots 1-7 -- {@link SlotCard#readRom}/{@link SlotCard#writeRom}</li>
 *   <li>$C800-$CFFF: the shared expansion ROM window -- see below</li>
 * </ul>
 * Everything else -- $C000-$C04F and $C060-$C07F (general/keyboard
 * switches), $C050-$C05F (video), $C080-$C08F (language card control),
 * and $D000-$FFFF (language card banking over the system ROM) -- throws,
 * naming the specific subsystem that doesn't exist yet, rather than
 * silently returning a guessed value. An unpopulated slot, or the
 * expansion window when no slot currently has it latched, throws too:
 * the hardware-correct answer there is a "floating bus" read (whatever
 * the video scanner last put on the bus), which depends on a video
 * scanner that hasn't been built either.
 * <p>
 * <b>Expansion ROM: there is no motherboard-level arbitration at all.</b>
 * Real Apple II+ hardware provides no electrical mechanism that resolves
 * contention for $C800-$CFFF -- it's a per-card latch, not a slot-select
 * bus. Each expansion-ROM-capable card has its own flip-flop, SET by
 * accessing that card's own $Cn00-$CnFF page, and RESET (on every card,
 * simultaneously) only by an access to exactly $CFFF. Accessing a
 * DIFFERENT slot's own ROM page does NOT clear any other slot's latch --
 * it only sets that slot's own. So if software fails to touch $CFFF
 * before switching to a different expansion-ROM card, more than one
 * card's latch can be set at once, and on real hardware BOTH cards
 * attempt to drive the data bus for $C800-$CFFF simultaneously -- a
 * genuine, historically-documented failure mode (Apple's own technical
 * notes describe exactly this happening between the Super Serial Card
 * and a UniDisk controller, when the SSC's firmware failed to clear
 * $C800 before switching itself in). This class models that directly:
 * {@link #expansionRomLatch} is a set, not a single "owner," and a
 * subsequent access while more than one slot is latched throws a
 * distinctly-named bus-conflict exception rather than fabricating a
 * result real hardware has no clean answer for either. An earlier
 * version of this class modeled a single "owner" that a new access
 * simply displaced -- that was an unverified assumption, corrected here
 * against a sourced description of the real per-card latch mechanism
 * (Apple's own reference manual, via applelogic.org, and the documented
 * SSC/UniDisk conflict).
 */
public final class MotherboardBus implements MemoryBus {

    private static final String GENERAL_SWITCHES_GAP =
        "General/keyboard soft switches ($C000-$C04F, $C060-$C07F) are not yet implemented";
    private static final String VIDEO_GAP =
        "VideoSoftSwitches ($C050-$C05F) is not yet implemented";
    private static final String LANGUAGE_CARD_GAP =
        "LanguageCard ($C080-$C08F control switches, $D000-$FFFF banking) is not yet implemented";
    private static final String FLOATING_BUS_GAP =
        "No card is present at this address, and floating-bus emulation "
        + "(returning the video scanner's last byte) depends on the not-yet-built video scanner";

    /** Main RAM, occupying $0000-$BFFF of this array; the rest of the array is unused (everything from $C000 up is dispatched, never stored here directly). */
    private final int[] ram = new int[0xC000];
    /** Index 0 unused; slots are 1-7. A null entry means that slot is unpopulated. */
    private final SlotCard[] slots;

    // Index 0 unused. true = that slot's expansion-ROM latch is currently
    // set. Deliberately a per-slot set, not a single "owner" -- see class
    // Javadoc for why more than one can legitimately be true at once.
    private final boolean[] expansionRomLatch = new boolean[8];

    /**
     * Wires this bus to {@code slots} (length 8, index 0 unused) -- typically the array {@link SlotCardLoader#load} just populated.
     *
     * @param slots the machine's populated slots, length 8, index 0 unused
     */
    public MotherboardBus(SlotCard[] slots) {
        if (slots.length != 8) {
            throw new IllegalArgumentException("slots must have length 8 (index 0 unused, slots are 1-7)");
        }
        this.slots = slots;
    }

    /** Reads one byte, dispatching by address range per this class's Javadoc. */
    @Override
    public int read(int address) {
        address &= 0xFFFF;
        if (address < 0xC000) {
            return ram[address];
        }
        if (address <= 0xC08F) {
            throw softSwitchGap(address);
        }
        if (address <= 0xC0FF) {
            return ioSwitch(address, -1);
        }
        if (address <= 0xC7FF) {
            return slotRom(address, -1);
        }
        if (address <= 0xCFFF) {
            return expansionRom(address, -1);
        }
        throw new UnsupportedOperationException(LANGUAGE_CARD_GAP);
    }

    /** Writes one byte, dispatching by address range per this class's Javadoc. */
    @Override
    public void write(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;
        if (address < 0xC000) {
            ram[address] = value;
            return;
        }
        if (address <= 0xC08F) {
            throw softSwitchGap(address);
        }
        if (address <= 0xC0FF) {
            ioSwitch(address, value);
            return;
        }
        if (address <= 0xC7FF) {
            slotRom(address, value);
            return;
        }
        if (address <= 0xCFFF) {
            expansionRom(address, value);
            return;
        }
        throw new UnsupportedOperationException(LANGUAGE_CARD_GAP);
    }

    /** Builds the right "not yet implemented" exception for an address in $C000-$C08F, naming the specific missing subsystem. */
    private static UnsupportedOperationException softSwitchGap(int address) {
        if (address >= 0xC050 && address <= 0xC05F) {
            return new UnsupportedOperationException(VIDEO_GAP);
        }
        if (address >= 0xC080) {
            return new UnsupportedOperationException(LANGUAGE_CARD_GAP);
        }
        return new UnsupportedOperationException(GENERAL_SWITCHES_GAP);
    }

    /** {@code value == -1} means this is a read; any other value is the byte to write. */
    private int ioSwitch(int address, int value) {
        int offsetFromBase = address - 0xC090;
        int slotNum = (offsetFromBase / 0x10) + 1;
        int offset = offsetFromBase % 0x10;
        SlotCard card = slots[slotNum];
        if (card == null) {
            throw new UnsupportedOperationException(FLOATING_BUS_GAP);
        }
        if (value < 0) {
            return card.readIoSwitch(offset);
        }
        card.writeIoSwitch(offset, value);
        return 0;
    }

    /** {@code value == -1} means this is a read; any other value is the byte to write. */
    private int slotRom(int address, int value) {
        int offsetFromBase = address - 0xC100;
        int slotNum = (offsetFromBase / 0x100) + 1;
        int offset = offsetFromBase % 0x100;
        SlotCard card = slots[slotNum];
        if (card == null) {
            throw new UnsupportedOperationException(FLOATING_BUS_GAP);
        }

        // Real /IOSEL behavior: accessing this slot's own ROM sets ITS
        // OWN latch, if it has expansion-ROM circuitry at all. This never
        // clears any other slot's latch -- see class Javadoc.
        if (card.wantsExpansionRom()) {
            expansionRomLatch[slotNum] = true;
        }

        if (value < 0) {
            return card.readRom(offset);
        }
        card.writeRom(offset, value);
        return 0;
    }

    /** {@code value == -1} means this is a read; any other value is the byte to write. */
    private int expansionRom(int address, int value) {
        int offset = address - 0xC800;
        List<Integer> latched = latchedSlots();

        int result = 0;
        if (latched.isEmpty()) {
            if (address != 0xCFFF) {
                throw new UnsupportedOperationException(FLOATING_BUS_GAP);
            }
            // $CFFF with nothing latched: nothing to release, but the
            // access itself is still valid -- real hardware doesn't fault
            // here, it just has nothing to do.
        } else if (latched.size() == 1) {
            SlotCard owner = slots[latched.get(0)];
            if (value < 0) {
                result = owner.readExpansionRom(offset);
            } else {
                owner.writeExpansionRom(offset, value);
            }
        } else {
            // More than one slot latched at once -- a genuine electrical
            // bus conflict on real hardware (see class Javadoc), not
            // something this emulator can safely fabricate a result for.
            throw new IllegalStateException("Expansion ROM bus conflict at $" + Integer.toHexString(address)
                + ": slots " + latched + " are simultaneously latched. On real hardware, multiple cards "
                + "would be driving the bus at once here -- a documented failure mode, not a deterministic "
                + "outcome. Software is expected to access $CFFF before switching expansion-ROM cards; "
                + "this indicates it did not.");
        }

        if (address == 0xCFFF) {
            for (int slotNum : latched) {
                slots[slotNum].onExpansionRomReleased();
                expansionRomLatch[slotNum] = false;
            }
        }

        return result;
    }

    /** Every slot number (1-7) whose expansion-ROM latch is currently set. */
    private List<Integer> latchedSlots() {
        List<Integer> latched = new ArrayList<>();
        for (int slotNum = 1; slotNum <= 7; slotNum++) {
            if (expansionRomLatch[slotNum]) {
                latched.add(slotNum);
            }
        }
        return latched;
    }
}
