package com.nordstrom.emulator.cpu;

/**
 * NMOS 6502 processor status register ("P"), modeled as a single packed
 * int whose bit layout matches the real hardware exactly:
 *
 * <pre>
 *   bit:  7 6 5 4 3 2 1 0
 *         N V 1 B D I Z C
 * </pre>
 *
 * Bit 5 is not a real flag -- there is no physical storage for it on real
 * silicon, and it always reads back as 1. Bit 4 (B) is not stored either:
 * it is synthesized only at the moment the status is pushed to the stack
 * (1 for PHP/BRK, 0 for a hardware IRQ/NMI push), and PLP/RTI pulling a
 * value back in has no behavioral effect from that bit.
 */
final class Status6502 {

    /** Bit 0: set when an operation carries out (add) or does not borrow (subtract/compare). */
    static final int CARRY        = 0x01;
    /** Bit 1: set when a result is zero. */
    static final int ZERO         = 0x02;
    /** Bit 2: set to mask hardware IRQs (NMI is unaffected). */
    static final int IRQ_DISABLE  = 0x04;
    /** Bit 3: selects BCD arithmetic for ADC/SBC. */
    static final int DECIMAL      = 0x08;
    /** Bit 4: not a real stored flag -- see class javadoc. */
    static final int BREAK        = 0x10;
    /** Bit 5: not a real stored flag -- see class javadoc. */
    static final int UNUSED       = 0x20;
    /** Bit 6: signed arithmetic overflow. */
    static final int OVERFLOW     = 0x40;
    /** Bit 7: sign bit of the result (bit 7 of the value). */
    static final int NEGATIVE     = 0x80;

    // Precomputed N|Z contribution for every possible 8-bit result -- avoids
    // a runtime comparison against zero on every single flag-setting
    // instruction, which is most of them.
    private static final int[] NZ_TABLE = buildNzTable();

    private static int[] buildNzTable() {
        int[] t = new int[256];
        for (int v = 0; v < 256; v++) {
            t[v] = (v & NEGATIVE) | zeroOnlyBit(v);
        }
        return t;
    }

    /** The N|Z bits (already at their correct mask positions) for a given 8-bit result. */
    static int nzBits(int result8) {
        return NZ_TABLE[result8 & 0xFF];
    }

    /** Just the Z bit, at its correct mask position, for an arbitrary (not necessarily 8-bit-masked) value -- used where N doesn't come from the same value Z does (e.g. BIT). */
    static int zeroOnlyBit(int value) {
        return (1 - ((value | -value) >>> 31)) * ZERO;
    }

    // Power-on state: IRQ_DISABLE set, UNUSED always set, everything else clear.
    private int bits = UNUSED | IRQ_DISABLE;

    /** Whether every bit in {@code mask} is currently set. */
    boolean isSet(int mask) {
        return (bits & mask) != 0;
    }

    /** Unconditionally set exactly these bits -- for instructions with a fixed target state (SEC, SEI, SED). */
    void setFlag(int mask) {
        bits |= mask;
    }

    /** Unconditionally clear exactly these bits -- for CLC, CLI, CLD. */
    void clearFlag(int mask) {
        bits &= ~mask;
    }

    /**
     * Unconditional merge: replaces the bits under {@code mask} with
     * {@code bits}, which the caller must have already computed at the
     * correct mask positions (typically via bitwise arithmetic, not a
     * boolean-to-int conversion). No branch of any kind here -- the caller
     * did the deciding, arithmetically, before this is invoked.
     */
    void applyFlags(int mask, int newBits) {
        bits = (bits & ~mask) | (newBits & mask);
    }

    /** Sets N and Z from an 8-bit result; leaves every other flag untouched. */
    void updateNZ(int result8) {
        applyFlags(NEGATIVE | ZERO, nzBits(result8));
    }

    /** CARRY's mask is 0x01, so masking directly yields the bit as 0/1 -- no boolean, no ternary. */
    int carryBit() {
        return bits & CARRY;
    }

    /** For any single-bit mask, the flag's value as a clean 0/1 -- used by branch instructions, which need to test an arbitrary flag as an int rather than a boolean. */
    int bitValue(int mask) {
        return (bits & mask) >>> Integer.numberOfTrailingZeros(mask);
    }

    /** The byte as pushed by PHP or BRK -- B is forced to 1, UNUSED forced to 1. */
    int toPushedByteSoftware() {
        return bits | BREAK | UNUSED;
    }

    /** The byte as pushed by a hardware IRQ or NMI -- B is forced to 0, UNUSED forced to 1. */
    int toPushedByteHardware() {
        return (bits & ~BREAK) | UNUSED;
    }

    /** PLP / RTI: load all 8 bits back. The pulled B bit is accepted but has no behavioral effect. */
    void fromPulledByte(int value) {
        bits = value | UNUSED;
    }
}
