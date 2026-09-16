package com.nordstrom.emulator.system;

import java.util.ArrayList;
import java.util.List;

/**
 * The actual expansion-ROM latch/bus-conflict logic, shared between
 * {@link SlotRomHandler} (which sets a slot's latch when its own ROM is
 * accessed) and {@link ExpansionRomHandler} (which dispatches
 * $C800-$CFFF accesses against whichever latches are currently set).
 * Neither handler -- nor {@link AddressSpace} -- needs to know anything
 * about how real Apple II+ hardware arbitrates this shared window; that
 * knowledge lives entirely here, in one place.
 * <p>
 * There is no motherboard-level arbitration on real hardware at all,
 * only a per-card latch: accessing a slot's own ROM page sets THAT
 * slot's latch, never clearing any other slot's. More than one card's
 * latch being set at once is a genuine, historically-documented bus
 * conflict (Apple's own technical notes describe exactly this between
 * the Super Serial Card and a UniDisk controller), not a case this
 * emulator can safely fabricate a deterministic result for -- see
 * {@link #access} below. Only an access to exactly $CFFF resets every
 * currently-set latch, simultaneously, regardless of how many there are.
 * <p>
 * This class operates entirely in terms of the LOCAL offset within the
 * $C800-$CFFF window (0-$7FF), never an absolute CPU address -- $CFFF is
 * simply {@link #RELEASE_OFFSET}, the last offset in that window.
 */
final class ExpansionRomArbiter {

    /** The local offset corresponding to $CFFF -- the last byte of the 2048-byte $C800-$CFFF window, and the release trigger for every currently-set latch. */
    private static final int RELEASE_OFFSET = 0x7FF;

    private static final String FLOATING_BUS_GAP =
        "No card is present at this address, and floating-bus emulation "
        + "(returning the video scanner's last byte) depends on the not-yet-built video scanner";

    private final SlotCard[] slots;
    private final boolean[] latch = new boolean[8]; // index 0 unused; slots are 1-7

    ExpansionRomArbiter(SlotCard[] slots) {
        this.slots = slots;
    }

    /** Called whenever slot {@code slotNum}'s own ROM is accessed -- sets its latch if it wants the expansion window. Never clears any other slot's latch. */
    void noteOwnRomAccessed(int slotNum) {
        SlotCard card = slots[slotNum];
        if (card == null) {
            throw new IllegalStateException("noteOwnRomAccessed(" + slotNum + ") called for an empty slot -- "
                + "this indicates a bug in the caller, which should have confirmed a card is present first");
        }
        if (card.wantsExpansionRom()) {
            latch[slotNum] = true;
        }
    }

    /**
     * Dispatches an access at {@code offset} (0-$7FF) within the
     * expansion ROM window. {@code value == -1} means a read; any other
     * value is the byte to write.
     */
    int access(int offset, int value) {
        List<Integer> latched = latchedSlots();

        int result = 0;
        if (latched.isEmpty()) {
            if (offset != RELEASE_OFFSET) {
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
            // bus conflict on real hardware, not something this emulator
            // can safely fabricate a result for.
            throw new IllegalStateException("Expansion ROM bus conflict at offset $" + Integer.toHexString(offset)
                + " of the expansion ROM window: slots " + latched + " are simultaneously latched. On real "
                + "hardware, multiple cards would be driving the bus at once here -- a documented failure mode, "
                + "not a deterministic outcome. Software is expected to access $CFFF before switching "
                + "expansion-ROM cards; this indicates it did not.");
        }

        if (offset == RELEASE_OFFSET) {
            for (int slotNum : latched) {
                slots[slotNum].onExpansionRomReleased();
                latch[slotNum] = false;
            }
        }

        return result;
    }

    private List<Integer> latchedSlots() {
        List<Integer> result = new ArrayList<>();
        for (int slotNum = 1; slotNum <= 7; slotNum++) {
            if (latch[slotNum]) {
                result.add(slotNum);
            }
        }
        return result;
    }
}
