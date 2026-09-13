package com.nordstrom.emulator.cpu;

import static com.nordstrom.emulator.cpu.Status6502.*;

/**
 * ADC/SBC/CMP/BIT arithmetic for the NMOS 6502, including decimal-mode flag
 * quirks. The ADC/SBC decimal logic is traced from Bruce Clark's
 * public-domain reference algorithm (see 6502_decimal_test.a65, ADD/SUB1,
 * cputype=0).
 * <p>
 * Every conditional in the original trace-through has been replaced with
 * an unconditional bitwise/arithmetic equivalent. Two recurring idioms:
 * <ul>
 *   <li>{@code x >>> 31} on a value that is negative exactly when some
 *       condition holds -- this IS the condition, as a 0/1 int, with no
 *       comparison operator at all. Used for "did this go negative"
 *       (borrow) and, complemented as {@code 1 - (x >>> 31)}, for
 *       "is this >= 0" (i.e. "is this >= threshold" once threshold is
 *       subtracted first).</li>
 *   <li>Multiplying a correction by a 0/1 bit instead of branching to
 *       apply it -- {@code value + CORRECTION * bit} is either
 *       {@code value} or {@code value + CORRECTION} with no branch.</li>
 * </ul>
 * Both are how the real hardware works, not just a coding trick: carry
 * and borrow ARE single wires in an adder/subtractor circuit, not
 * decisions -- this mirrors that directly rather than approximating it
 * with an if-statement and asking the JIT to guess well.
 */
final class Arithmetic6502 {

    private Arithmetic6502() {}

    /** Adds {@code operand} (plus the current carry) into {@code a}; updates N, V, Z, C; returns the new accumulator value. */
    static int adc(int a, int operand, Status6502 status) {
        int carryIn = status.carryBit();
        return status.isSet(DECIMAL) ? adcDecimal(a, operand, carryIn, status)
                                      : adcBinary(a, operand, carryIn, status);
    }

    /** Subtracts {@code operand} (plus the current borrow) from {@code a}; updates N, V, Z, C; returns the new accumulator value. */
    static int sbc(int a, int operand, Status6502 status) {
        int carryIn = status.carryBit();
        int binaryResult = sbcBinary(a, operand, carryIn, status); // always computed -- flags always come from here
        return status.isSet(DECIMAL) ? sbcDecimalAccumulator(a, operand, carryIn) : binaryResult;
    }

    // ---- binary mode (also the mechanism behind SBC's decimal-mode flags) ----

    /** Ordinary 8-bit addition with carry-in; sets all four flags from the actual binary result. */
    private static int adcBinary(int a, int operand, int carryIn, Status6502 status) {
        int sum = a + operand + carryIn;
        int result = sum & 0xFF;
        int overflowBit = ((~(a ^ operand) & (a ^ result)) & 0x80) >>> 1; // isolate the sign-comparison bit (7), then shift into OVERFLOW (6)
        int carryBit = (sum >> 8) & CARRY;                            // carry-out bit IS the carry flag bit
        status.applyFlags(NEGATIVE | ZERO | OVERFLOW | CARRY, nzBits(result) | overflowBit | carryBit);
        return result;
    }

    /** Ordinary 8-bit subtraction with borrow-in; sets all four flags from the actual binary result -- also the sole source of SBC's flags in decimal mode. */
    private static int sbcBinary(int a, int operand, int carryIn, Status6502 status) {
        int diff = a - operand - (1 - carryIn);
        int result = diff & 0xFF;
        int overflowBit = (((a ^ operand) & (a ^ diff)) & 0x80) >>> 1;
        int carryBit = (~diff) >>> 31; // 1 iff diff >= 0 -- SBC's carry means "no borrow"
        status.applyFlags(NEGATIVE | ZERO | OVERFLOW | CARRY, nzBits(result) | overflowBit | carryBit);
        return result;
    }

    // ---- decimal mode: accumulator per Clark's ADD/SUB1; ADC flags per his A6502 dispatch ----

    /**
     * NMOS decimal-mode ADC. The accumulator result follows Clark's ADD
     * routine; N and V are captured from the intermediate, pre-correction
     * result (a genuine hardware quirk, not a simplification); Z comes
     * from the equivalent binary addition; C comes from the decimal
     * computation itself.
     */
    private static int adcDecimal(int a, int operand, int carryIn, Status6502 status) {
        int n1h = a & 0xF0;
        int n2h = operand & 0xF0;

        int alRaw = (a & 0x0F) + (operand & 0x0F) + carryIn;      // range 0..19
        int lowCarry = 1 - ((alRaw - 0x0A) >>> 31);               // 1 iff alRaw >= 0x0A
        int al = (alRaw + 6 * lowCarry) & 0x0F;                   // no-op when lowCarry=0

        int partial = n1h | al; // al < 0x10 either way
        int n2hAdj = n2h + 0x0F * lowCarry;
        int rawSum = partial + n2hAdj + lowCarry;
        int intermediate = rawSum & 0xFF;
        int intermediateCarryBit = (rawSum >> 8) & 1;

        // N and V captured HERE -- before the final +$60 correction.
        int negativeBit = intermediate & NEGATIVE;
        int overflowBit = ((~(a ^ operand) & (a ^ intermediate)) & 0x80) >>> 1;

        int needsCorrection = intermediateCarryBit | (1 - ((intermediate - 0xA0) >>> 31)); // OR of two 0/1 bits
        int result = (intermediate + 0x60 * needsCorrection) & 0xFF;
        int carryBit = needsCorrection; // already at CARRY's bit position (0x01)

        // Z from the equivalent binary addition, per the reference.
        int binSum = (a + operand + carryIn) & 0xFF;
        int zeroBit = zeroOnlyBit(binSum);

        status.applyFlags(NEGATIVE | OVERFLOW | ZERO | CARRY, negativeBit | overflowBit | zeroBit | carryBit);
        return result;
    }

    /**
     * NMOS decimal-mode SBC accumulator result, per Clark's SUB1 routine.
     * Does not touch any flag -- {@link #sbc} always sources N, V, Z, C
     * from {@link #sbcBinary} regardless of decimal mode.
     */
    private static int sbcDecimalAccumulator(int a, int operand, int carryIn) {
        int n1h = a & 0xF0;
        int n2h = operand & 0xF0;

        int alRaw = (a & 0x0F) - (operand & 0x0F) - (1 - carryIn); // range -10..9
        int lowBorrow = alRaw >>> 31;                               // 1 iff alRaw < 0
        int al = (alRaw - 6 * lowBorrow) & 0x0F;

        int partial = n1h | al;
        int n2hAdj = n2h + 0x0F * lowBorrow;
        int diff = partial - n2hAdj - lowBorrow;
        int needsCorrection = diff >>> 31; // 1 iff diff < 0

        return (diff - 0x60 * needsCorrection) & 0xFF;
    }

    /**
     * CMP/CPX/CPY: N, Z, C from an unsigned {@code register - operand}.
     * Deliberately NOT built on {@link #sbcBinary}: a compare sets no
     * overflow flag and takes no carry-in from the status register at
     * all (unlike SBC, it is never decimal and never incorporates a
     * borrow) -- sharing code with SBC here would tie CMP's correctness
     * to SBC's carry-in handling for no real benefit.
     */
    static void compare(int register, int operand, Status6502 status) {
        int diff = register - operand;
        int result = diff & 0xFF;
        int carryBit = (~diff) >>> 31; // 1 iff register >= operand (unsigned)
        status.applyFlags(NEGATIVE | ZERO | CARRY, nzBits(result) | carryBit);
    }

    /**
     * BIT: N and V come directly from the OPERAND's own bits 7 and 6 --
     * not from any computed result, and not from the accumulator at all.
     * Z is the only flag that reflects a computation ({@code a & operand}).
     * Neither {@code a} nor the operand is modified. NEGATIVE (0x80) and
     * OVERFLOW (0x40) happen to be exactly bits 7 and 6, so masking the
     * operand directly against those constants IS the extraction -- no
     * shift required.
     */
    static void bitTest(int a, int operand, Status6502 status) {
        int nvBits = operand & (NEGATIVE | OVERFLOW);
        int zeroBit = zeroOnlyBit(a & operand);
        status.applyFlags(NEGATIVE | OVERFLOW | ZERO, nvBits | zeroBit);
    }

    /**
     * ARR (illegal): A := A & operand, then rotated right through Carry.
     * Dispatches to separate binary/decimal implementations, exactly like
     * {@link #adc}/{@link #sbc} -- ARR's flag behavior genuinely differs
     * by mode, not just its accumulator result.
     * <p>
     * Both traced from the "64doc" reference (John West &amp; Marko
     * M&auml;kel&auml;, 1994; see nesdev.org/6502_cpu.txt), whose
     * decimal-mode derivation is validated against real C64/VIC-20/C128D
     * hardware via included 6502 test programs, not merely asserted --
     * this replaced an earlier version of this method that threw rather
     * than guess at the decimal case without such a source.
     */
    static int arr(int a, int operand, Status6502 status) {
        return status.isSet(DECIMAL) ? arrDecimal(a, operand, status) : arrBinary(a, operand, status);
    }

    /**
     * Binary-mode ARR. N and Z come from the rotated result exactly as a
     * plain ROR would. C and V, however, come from bits 6 and 5 of the
     * ROTATED result -- not from a normal ROR's carry-out -- reflecting
     * internal ADC circuitry this opcode shares with real ADC/SBC.
     */
    private static int arrBinary(int a, int operand, Status6502 status) {
        int anded = a & operand;
        int carryIn = status.carryBit();
        int result = (anded >>> 1) | (carryIn << 7);
        int carryBit = (result >> 6) & CARRY;
        int overflowBit = (((result >> 6) ^ (result >> 5)) & 1) << 6;
        status.applyFlags(NEGATIVE | OVERFLOW | ZERO | CARRY, nzBits(result) | overflowBit | carryBit);
        return result;
    }

    /**
     * Decimal-mode ARR -- a genuinely different flag derivation from
     * binary mode, not a variant of it: N comes from the CARRY-IN (not
     * the result's sign bit), Z comes from the rotated value BEFORE the
     * BCD fixups below are applied (not from the final returned
     * accumulator value), and the accumulator gets independent low- and
     * high-nybble BCD corrections whose second stage also determines the
     * outgoing Carry.
     * <p>
     * Traced directly from the reference C implementation in West &amp;
     * M&auml;kel&auml;'s 64doc, reproduced here structurally unchanged:
     * <pre>
     *   t = A &amp; s;
     *   AH = t &gt;&gt; 4;  AL = t &amp; 15;
     *   N = C;
     *   Z = !(A = (t &gt;&gt; 1) | (C &lt;&lt; 7));
     *   V = (t ^ A) &amp; 64;
     *   if (AL + (AL &amp; 1) &gt; 5) A = (A &amp; 0xF0) | ((A + 6) &amp; 0xF);
     *   if (C = AH + (AH &amp; 1) &gt; 5) A = (A + 0x60) &amp; 0xFF;
     * </pre>
     */
    private static int arrDecimal(int a, int operand, Status6502 status) {
        int t = a & operand;
        int ah = t >> 4;
        int al = t & 0x0F;
        int oldCarry = status.carryBit();

        int rotated = (t >>> 1) | (oldCarry << 7);
        int overflowBit = (t ^ rotated) & OVERFLOW; // already at bit 6
        int zeroBit = zeroOnlyBit(rotated);          // from the PRE-fixup value, per the reference
        int negativeBit = oldCarry << 7;             // N from the incoming carry, not the result

        int result = rotated;
        int lowFixup = 1 - (((al + (al & 1)) - 6) >>> 31); // 1 iff al+(al&1) > 5
        result = (result & 0xF0) | ((result + 6 * lowFixup) & 0x0F);

        int carryBit = 1 - (((ah + (ah & 1)) - 6) >>> 31); // 1 iff ah+(ah&1) > 5
        result = (result + 0x60 * carryBit) & 0xFF;

        status.applyFlags(NEGATIVE | OVERFLOW | ZERO | CARRY, negativeBit | overflowBit | zeroBit | carryBit);
        return result;
    }

    // ---- shifts/rotates: no reference algorithm needed here -- the bit
    // movements are simple and unambiguous. None of the four touch V.

    /** ASL: shifts left; bit 7 becomes the new Carry, bit 0 fills with 0. */
    static int shiftLeft(int value, Status6502 status) {
        int carryBit = (value >> 7) & CARRY; // bit 7, shifted into position 0 -- already matches CARRY's mask
        int result = (value << 1) & 0xFF;
        status.applyFlags(NEGATIVE | ZERO | CARRY, nzBits(result) | carryBit);
        return result;
    }

    /** LSR: shifts right; bit 0 becomes the new Carry, bit 0 of the mask -- and bit 7 always fills with 0, so N is always cleared afterward. */
    static int shiftRight(int value, Status6502 status) {
        int carryBit = value & CARRY;
        int result = value >>> 1;
        status.applyFlags(NEGATIVE | ZERO | CARRY, nzBits(result) | carryBit);
        return result;
    }

    /** ROL: shifts left through Carry; bit 7 becomes the new Carry, the old Carry fills bit 0. */
    static int rotateLeft(int value, Status6502 status) {
        int oldCarry = status.carryBit();
        int carryBit = (value >> 7) & CARRY;
        int result = ((value << 1) | oldCarry) & 0xFF;
        status.applyFlags(NEGATIVE | ZERO | CARRY, nzBits(result) | carryBit);
        return result;
    }

    /** ROR: shifts right through Carry; bit 0 becomes the new Carry, the old Carry fills bit 7. */
    static int rotateRight(int value, Status6502 status) {
        int oldCarry = status.carryBit();
        int carryBit = value & CARRY;
        int result = (value >>> 1) | (oldCarry << 7);
        status.applyFlags(NEGATIVE | ZERO | CARRY, nzBits(result) | carryBit);
        return result;
    }
}
