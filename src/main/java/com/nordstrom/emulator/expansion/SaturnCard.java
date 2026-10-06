package com.nordstrom.emulator.expansion;

import com.nordstrom.emulator.system.PowerOnRam;
import com.nordstrom.emulator.system.SlotCard;

import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;

/**
 * The Saturn Systems 128K RAM card: eight 16K banks, each laid out like a
 * Language Card -- two 4K sub-banks (A and B) sharing {@code $D000}-{@code $DFFF},
 * plus an 8K region at {@code $E000}-{@code $FFFF} -- one bank visible at a
 * time. Specified from the 1982 Saturn Systems Operations Manual (chapter 9);
 * see TODO.md / HARDWARE-REFERENCE.md.
 * <p>
 * Control addresses {@code $C0N0}-{@code $C0NF} (N = 8 + slot), with address
 * line A2 choosing what an access does:
 * <ul>
 *   <li><b>A2 = 0</b> ({@code $C0N0}-{@code $C0N3}, {@code $C0N8}-{@code $C0NB}):
 *       state select, by the Language Card's own truth table -- sub-bank A or
 *       B (A3), RAM or ROM read, write enable. Bank A here is the Language
 *       Card's "bank 2".</li>
 *   <li><b>A2 = 1</b> ({@code $C0N4}-{@code $C0N7}, {@code $C0NC}-{@code $C0NF}):
 *       select 16K bank 1-4 or 5-8. Nothing else changes.</li>
 * </ul>
 * One deliberate difference from {@link LanguageCard}: the double access
 * that write-enables RAM counts <em>reads or writes</em> to a write-enable
 * address ("accomplished by either reading or writing", per the manual),
 * where the Language Card counts reads only.
 * <p>
 * Works in any slot, as on real hardware; it has no ROM of its own, so its
 * {@code $Cn00} page reads the floating bus. In slot 0 its control range is
 * {@code $C080}-{@code $C08F}, the same as a Language Card's -- though
 * Saturn's own RAM test only accepts slots 1-7.
 */
public final class SaturnCard implements SlotCard {

    private enum WriteState { PROTECTED_IDLE, PROTECTED_ARMED, ENABLED }

    static final int BANKS = 8;
    private static final int SUB_BANK_SIZE = 0x1000; // $D000-$DFFF
    private static final int UPPER_SIZE = 0x2000;    // $E000-$FFFF

    private boolean readRam;  // power-up default ($C0N2): ROM read
    private boolean subBankB; // power-up default: sub-bank A
    private int bank;         // power-up default: bank 1 (index 0)
    private WriteState writeState = WriteState.PROTECTED_IDLE;

    // DRAM on the real card, so it powers up random like the motherboard's -- see PowerOnRam.
    private final byte[][] subBankA = new byte[BANKS][];
    private final byte[][] subBankBRam = new byte[BANKS][];
    private final byte[][] upper = new byte[BANKS][];

    /** Creates the card with all 128K in DRAM's random power-up state; {@link #configure} follows. */
    public SaturnCard() {
        for (int i = 0; i < BANKS; i++) {
            subBankA[i] = PowerOnRam.allocate(SUB_BANK_SIZE);
            subBankBRam[i] = PowerOnRam.allocate(SUB_BANK_SIZE);
            upper[i] = PowerOnRam.allocate(UPPER_SIZE);
        }
    }

    @Override
    public String getShortName() {
        return "saturn128";
    }

    @Override
    public Set<String> getSupportedParameters() {
        return Set.of();
    }

    @Override
    public void configure(Properties props) {
        // no settings
    }

    @Override
    public int readIoSwitch(int offset) {
        applyControlAccess(offset);
        return 0; // software never inspects this, same as the Language Card
    }

    @Override
    public void writeIoSwitch(int offset, int value) {
        applyControlAccess(offset);
    }

    @Override
    public boolean hasRom() {
        return false;
    }

    @Override
    public int readRom(int offset) {
        throw new UnsupportedOperationException("The Saturn card has no ROM -- hasRom() is false, so this is never called");
    }

    @Override
    public boolean wantsUpperMemory() {
        return true;
    }

    @Override
    public OptionalInt readUpperMemory(int offset) {
        if (!readRam) {
            return OptionalInt.empty(); // not intercepting -- the caller falls through to the system ROM
        }
        return OptionalInt.of(ramFor(offset)[index(offset)] & 0xFF);
    }

    @Override
    public void writeUpperMemory(int offset, int value) {
        if (writeState == WriteState.ENABLED) {
            ramFor(offset)[index(offset)] = (byte) value;
        }
    }

    /** Reads and writes alike: on this card either kind of access counts. */
    private void applyControlAccess(int offset) {
        if ((offset & 0x04) != 0) {
            bank = (offset & 0x03) + ((offset & 0x08) != 0 ? 4 : 0);
            return;
        }
        readRam = ((offset ^ (offset >> 1)) & 1) == 0;
        subBankB = (offset & 0x08) != 0;
        boolean writeEnableEligible = (offset & 0x01) != 0;
        if (writeEnableEligible) {
            writeState = switch (writeState) {
                case PROTECTED_IDLE -> WriteState.PROTECTED_ARMED;
                case PROTECTED_ARMED, ENABLED -> WriteState.ENABLED;
            };
        } else {
            writeState = WriteState.PROTECTED_IDLE;
        }
    }

    private byte[] ramFor(int offset) {
        if (offset >= SUB_BANK_SIZE) {
            return upper[bank];
        }
        return subBankB ? subBankBRam[bank] : subBankA[bank];
    }

    private static int index(int offset) {
        return offset >= SUB_BANK_SIZE ? offset - SUB_BANK_SIZE : offset;
    }

    /** Package-visible for tests. */
    boolean isReadingRam() {
        return readRam;
    }

    /** Package-visible for tests. */
    boolean isSubBankB() {
        return subBankB;
    }

    /** Package-visible for tests: the selected 16K bank, 1-8. */
    int selectedBank() {
        return bank + 1;
    }

    /** Package-visible for tests. */
    boolean isWriteEnabled() {
        return writeState == WriteState.ENABLED;
    }
}
