package com.nordstrom.emulator.system;

/**
 * The Apple II+'s built-in Language Card RAM and its control switches at
 * $C080-$C08F, banked into $D000-$FFFF in place of the system ROM
 * normally mapped there. This class holds the shared state; it is not
 * itself an {@link AddressRangeHandler} -- {@link LanguageCardControlHandler}
 * and {@link LanguageCardMemoryHandler} are the two registered views onto
 * it, one per address range, the same shared-state-plus-two-handlers
 * shape already used for the expansion ROM window's
 * {@link ExpansionRomArbiter}.
 * <p>
 * Real hardware layout: two independently selectable 4KB RAM banks at
 * $D000-$DFFF (bank 1 and bank 2 -- only one is mapped at a time), plus
 * a single, non-banked 8KB RAM region at $E000-$FFFF. The card's own
 * built-in ROM ({@link LanguageCardRom}) provides the read-source ROM
 * side -- Programmer's Aid #1, Integer BASIC, and the Autostart Monitor
 * -- genuinely permanent and never writable, independent of bank
 * selection (bank selection only matters for the RAM side).
 * <p>
 * Every access to $C080-$C08F immediately updates two independent
 * pieces of state, by a fixed truth table based on the low bits of the
 * offset (0-15):
 * <ul>
 *   <li>READ SOURCE: RAM when bits 0 and 1 are equal (offsets
 *       0,3,4,7,8,B,C,F), ROM otherwise.</li>
 *   <li>BANK SELECT (only relevant to $D000-$DFFF, and only for the RAM
 *       side): bank 1 when bit 3 is set, bank 2 otherwise.</li>
 * </ul>
 * WRITE ENABLE is a separate, genuine hardware quirk: writing is only
 * permitted after TWO CONSECUTIVE qualifying accesses -- a READ, not a
 * write, of one of the four odd-numbered "write-enable" offsets ($C081,
 * $C083, $C089, $C08B and their bit-2 duplicates $C085, $C087, $C08D,
 * $C08F). Any other access -- a write of any kind, or a read of one of
 * the eight even-numbered offsets -- resets the sequence back to the
 * start. Writes always target the RAM banks regardless of read source,
 * and are silently ignored while not enabled -- matching real
 * write-protected-RAM behavior, and never affecting the ROM side, which
 * cannot be written under any circumstance.
 */
public final class LanguageCard {

    private enum WriteState { PROTECTED_IDLE, PROTECTED_ARMED, ENABLED }

    private boolean readRam;  // false = ROM, true = RAM
    private boolean bank1;    // false = bank 2, true = bank 1 (only matters for $D000-$DFFF)
    private WriteState writeState = WriteState.PROTECTED_IDLE;

    private final byte[] bank1Ram = new byte[0x1000]; // $D000-$DFFF, bank 1
    private final byte[] bank2Ram = new byte[0x1000]; // $D000-$DFFF, bank 2
    private final byte[] upperRam = new byte[0x2000]; // $E000-$FFFF, single bank

    void applyControlAccess(int offset, boolean isWrite) {
        readRam = ((offset ^ (offset >> 1)) & 1) == 0;
        bank1 = (offset & 0x08) != 0;

        boolean writeEnableEligible = (offset & 0x01) != 0;
        if (!isWrite && writeEnableEligible) {
            writeState = switch (writeState) {
                case PROTECTED_IDLE -> WriteState.PROTECTED_ARMED;
                case PROTECTED_ARMED, ENABLED -> WriteState.ENABLED;
            };
        } else {
            writeState = WriteState.PROTECTED_IDLE;
        }
    }

    int readMemory(int offset) {
        return readRam ? (ramAt(offset) & 0xFF) : LanguageCardRom.read(offset);
    }

    void writeMemory(int offset, int value) {
        if (writeState != WriteState.ENABLED) {
            return; // silently ignored -- write-protected, exactly as designed
        }
        setRamAt(offset, (byte) value);
    }

    private byte ramAt(int offset) {
        return offset < 0x1000 ? (bank1 ? bank1Ram : bank2Ram)[offset] : upperRam[offset - 0x1000];
    }

    private void setRamAt(int offset, byte value) {
        if (offset < 0x1000) {
            (bank1 ? bank1Ram : bank2Ram)[offset] = value;
        } else {
            upperRam[offset - 0x1000] = value;
        }
    }

    /** Package-visible for tests and the not-yet-built language-card-aware disassembler/debugger. */
    boolean isReadingRam() {
        return readRam;
    }

    /** Package-visible for tests. */
    boolean isBank1Selected() {
        return bank1;
    }

    /** Package-visible for tests. */
    boolean isWriteEnabled() {
        return writeState == WriteState.ENABLED;
    }
}
