package com.nordstrom.emulator.cpu;

/**
 * Outcome of resolving one addressing mode against the current PC/registers.
 *
 * @param address        the effective memory address, or -1 if this mode has none (IMPLIED, ACCUMULATOR, IMMEDIATE)
 * @param immediateValue the operand byte itself for IMMEDIATE mode, or -1 otherwise
 * @param pageCrossedBit 0 or 1 (not a boolean) -- computed by the resolver as a literal carry-out bit
 *                       (see {@link AddressingModeResolver}), so it can be multiplied directly into a
 *                       cycle-count formula with no branch: {@code baseCycles + penalty * pageCrossedBit}
 * @param bytesConsumed  how many operand bytes this mode reads following the opcode byte itself
 */
record ResolvedOperand(int address, int immediateValue, int pageCrossedBit, int bytesConsumed) {

    /** A memory-addressed operand (every mode except IMPLIED/ACCUMULATOR/IMMEDIATE). */
    static ResolvedOperand ofAddress(int address, int pageCrossedBit, int bytesConsumed) {
        return new ResolvedOperand(address, -1, pageCrossedBit, bytesConsumed);
    }

    /** An IMMEDIATE operand: the value itself, one operand byte, never a page-cross candidate. */
    static ResolvedOperand ofImmediate(int value) {
        return new ResolvedOperand(-1, value, 0, 1);
    }

    /** IMPLIED or ACCUMULATOR: no operand byte, no address, no value to fetch. */
    static ResolvedOperand none() {
        return new ResolvedOperand(-1, -1, 0, 0);
    }
}
