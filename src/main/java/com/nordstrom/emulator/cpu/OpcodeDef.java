package com.nordstrom.emulator.cpu;

/**
 * Opcode table entry. Cycle accounting -- including any page-crossing
 * penalty -- is computed inside the executor itself as a branchless
 * formula (baseCycles + penalty * operand.pageCrossedBit()), so the
 * dispatch loop in Cpu6502.step() never needs an if-statement to decide
 * whether a penalty applies.
 *
 * @param mnemonic the instruction's assembly mnemonic (e.g. "ADC"), for diagnostics/disassembly only
 * @param mode     the addressing mode this specific opcode byte uses
 * @param executor the instruction's behavior; see {@link InstructionExecutor}
 */
record OpcodeDef(String mnemonic, AddressingMode mode, InstructionExecutor executor) {
    /** One opcode's behavior: mutate CPU/memory state as needed, then report how many cycles it actually took. */
    @FunctionalInterface
    interface InstructionExecutor {
        /** Executes the instruction and returns the total cycle count actually taken. */
        int execute(Cpu6502 cpu, ResolvedOperand operand);
    }
}
