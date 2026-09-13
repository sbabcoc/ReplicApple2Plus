package com.nordstrom.emulator.cpu;

import static com.nordstrom.emulator.cpu.AddressingMode.*;

/**
 * The 256-entry NMOS 6502 opcode dispatch table. Populated incrementally;
 * unfilled slots are null and throw on execution (see Cpu6502.step).
 */
final class Opcodes {

    static final OpcodeDef[] TABLE = new OpcodeDef[256];

    /** Registers ADC for one opcode/mode: adds into A with carry, sets N/V/Z/C. */
    private static void adc(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("ADC", mode, (cpu, operand) -> {
            cpu.a = Arithmetic6502.adc(cpu.a, cpu.readOperand(operand), cpu.status);
            return cost.totalCycles(operand);
        });
    }

    /** Registers SBC for one opcode/mode: subtracts from A with borrow, sets N/V/Z/C. */
    private static void sbc(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("SBC", mode, (cpu, operand) -> {
            cpu.a = Arithmetic6502.sbc(cpu.a, cpu.readOperand(operand), cpu.status);
            return cost.totalCycles(operand);
        });
    }

    /** Registers LDA for one opcode/mode: loads A, sets N/Z only. */
    private static void lda(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("LDA", mode, (cpu, operand) -> {
            cpu.a = cpu.readOperand(operand);
            cpu.status.updateNZ(cpu.a);
            return cost.totalCycles(operand);
        });
    }

    /** Registers AND for one opcode/mode: ANDs into A, sets N/Z only. */
    private static void and(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("AND", mode, (cpu, operand) -> {
            cpu.a = cpu.a & cpu.readOperand(operand);
            cpu.status.updateNZ(cpu.a);
            return cost.totalCycles(operand);
        });
    }

    /** Registers ORA for one opcode/mode: ORs into A, sets N/Z only. */
    private static void ora(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("ORA", mode, (cpu, operand) -> {
            cpu.a = cpu.a | cpu.readOperand(operand);
            cpu.status.updateNZ(cpu.a);
            return cost.totalCycles(operand);
        });
    }

    /** Registers EOR for one opcode/mode: XORs into A, sets N/Z only. */
    private static void eor(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("EOR", mode, (cpu, operand) -> {
            cpu.a = cpu.a ^ cpu.readOperand(operand);
            cpu.status.updateNZ(cpu.a);
            return cost.totalCycles(operand);
        });
    }

    // ---- branches: RELATIVE mode. Cost is 2 cycles if not taken; +1 if
    // taken; +1 more if taken AND the target crosses a page. Whether to
    // actually jump is a genuine hardware latch-or-don't decision (the real
    // 6502 computes PC+offset unconditionally every time via the same ALU
    // path regardless of outcome, then either latches it into PC or
    // discards it) -- so, consistent with the rest of this codebase, the
    // "taken" bit is computed once as a 0/1 int and used arithmetically for
    // BOTH the PC update and the cycle count, rather than branching twice.

    /** Registers a branch that's taken when the given single-bit flag is SET (BCS, BEQ, BMI, BVS). */
    private static void branchIfSet(int opcode, String mnemonic, int flagMask) {
        TABLE[opcode] = new OpcodeDef(mnemonic, RELATIVE, (cpu, operand) ->
            branch(cpu, operand, cpu.status.bitValue(flagMask)));
    }

    /** Registers a branch that's taken when the given single-bit flag is CLEAR (BCC, BNE, BPL, BVC). */
    private static void branchIfClear(int opcode, String mnemonic, int flagMask) {
        TABLE[opcode] = new OpcodeDef(mnemonic, RELATIVE, (cpu, operand) ->
            branch(cpu, operand, 1 - cpu.status.bitValue(flagMask)));
    }

    /** Shared branch mechanics: {@code taken} is 0 or 1. PC is updated by adding {@code taken} times the wrapped delta to the target -- a no-op when not taken, the full jump when taken, no separate conditional for either. */
    private static int branch(Cpu6502 cpu, ResolvedOperand operand, int taken) {
        cpu.pc = (cpu.pc + taken * ((operand.address() - cpu.pc) & 0xFFFF)) & 0xFFFF;
        return 2 + taken * (1 + operand.pageCrossedBit());
    }

    // ---- flag instructions: direct, unconditional bit set/clear. No SEV --
    // the 6502 has no instruction to explicitly set the overflow flag.

    private static void setFlagOp(int opcode, String mnemonic, int flagMask) {
        TABLE[opcode] = new OpcodeDef(mnemonic, IMPLIED, (cpu, operand) -> {
            cpu.status.setFlag(flagMask);
            return 2;
        });
    }

    private static void clearFlagOp(int opcode, String mnemonic, int flagMask) {
        TABLE[opcode] = new OpcodeDef(mnemonic, IMPLIED, (cpu, operand) -> {
            cpu.status.clearFlag(flagMask);
            return 2;
        });
    }

    /** Registers STA for one opcode/mode: stores A, sets no flags. */
    private static void sta(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.WRITE);
        TABLE[opcode] = new OpcodeDef("STA", mode, (cpu, operand) -> {
            cpu.writeResult(operand, cpu.a); // STA sets no flags at all
            return cost.totalCycles(operand); // pageCrossPenalty is always 0 for WRITE, so this is just baseCycles -- kept uniform rather than special-cased
        });
    }

    /** Registers LDX for one opcode/mode: loads X, sets N/Z only. */
    private static void ldx(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("LDX", mode, (cpu, operand) -> {
            cpu.x = cpu.readOperand(operand);
            cpu.status.updateNZ(cpu.x);
            return cost.totalCycles(operand);
        });
    }

    /** Registers LDY for one opcode/mode: loads Y, sets N/Z only. */
    private static void ldy(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("LDY", mode, (cpu, operand) -> {
            cpu.y = cpu.readOperand(operand);
            cpu.status.updateNZ(cpu.y);
            return cost.totalCycles(operand);
        });
    }

    /** Registers STX for one opcode/mode: stores X, sets no flags. */
    private static void stx(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.WRITE);
        TABLE[opcode] = new OpcodeDef("STX", mode, (cpu, operand) -> {
            cpu.writeResult(operand, cpu.x);
            return cost.totalCycles(operand);
        });
    }

    /** Registers STY for one opcode/mode: stores Y, sets no flags. */
    private static void sty(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.WRITE);
        TABLE[opcode] = new OpcodeDef("STY", mode, (cpu, operand) -> {
            cpu.writeResult(operand, cpu.y);
            return cost.totalCycles(operand);
        });
    }

    /** Registers CMP for one opcode/mode: compares A against the operand via Arithmetic6502.compare. */
    private static void cmp(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("CMP", mode, (cpu, operand) -> {
            Arithmetic6502.compare(cpu.a, cpu.readOperand(operand), cpu.status);
            return cost.totalCycles(operand);
        });
    }

    /** Registers CPX for one opcode/mode: compares X against the operand via Arithmetic6502.compare. */
    private static void cpx(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("CPX", mode, (cpu, operand) -> {
            Arithmetic6502.compare(cpu.x, cpu.readOperand(operand), cpu.status);
            return cost.totalCycles(operand);
        });
    }

    /** Registers CPY for one opcode/mode: compares Y against the operand via Arithmetic6502.compare. */
    private static void cpy(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("CPY", mode, (cpu, operand) -> {
            Arithmetic6502.compare(cpu.y, cpu.readOperand(operand), cpu.status);
            return cost.totalCycles(operand);
        });
    }

    /** Registers BIT for one opcode/mode: tests A against the operand via Arithmetic6502.bitTest. */
    private static void bit(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("BIT", mode, (cpu, operand) -> {
            Arithmetic6502.bitTest(cpu.a, cpu.readOperand(operand), cpu.status);
            return cost.totalCycles(operand);
        });
    }

    /** Registers INC for one opcode/mode: increments the addressed memory byte, sets N/Z only. */
    private static void inc(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("INC", mode, (cpu, operand) -> {
            int result = (cpu.readOperand(operand) + 1) & 0xFF;
            cpu.writeResult(operand, result);
            cpu.status.updateNZ(result);
            return cost.totalCycles(operand); // RMW penalty is always 0 -- see CycleCosts javadoc
        });
    }

    /** Registers DEC for one opcode/mode: decrements the addressed memory byte, sets N/Z only. */
    private static void dec(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("DEC", mode, (cpu, operand) -> {
            int result = (cpu.readOperand(operand) - 1) & 0xFF;
            cpu.writeResult(operand, result);
            cpu.status.updateNZ(result);
            return cost.totalCycles(operand);
        });
    }

    // ---- shifts/rotates: each has a distinct ACCUMULATOR-mode registration
    // (operates on cpu.a directly, no bus access, fixed 2 cycles) separate
    // from the memory-addressed registration, rather than one method
    // branching on mode -- keeps the mode-vs-register distinction a
    // registration-time choice of which method to call, not a runtime
    // conditional inside either lambda.

    /** Registers ASL A: shifts the accumulator itself left. */
    private static void aslAccumulator(int opcode) {
        TABLE[opcode] = new OpcodeDef("ASL", ACCUMULATOR, (cpu, operand) -> {
            cpu.a = Arithmetic6502.shiftLeft(cpu.a, cpu.status);
            return 2;
        });
    }

    /** Registers ASL for one memory-addressed mode: read-modify-write, shift left. */
    private static void asl(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("ASL", mode, (cpu, operand) -> {
            int result = Arithmetic6502.shiftLeft(cpu.readOperand(operand), cpu.status);
            cpu.writeResult(operand, result);
            return cost.totalCycles(operand);
        });
    }

    /** Registers LSR A: shifts the accumulator itself right. */
    private static void lsrAccumulator(int opcode) {
        TABLE[opcode] = new OpcodeDef("LSR", ACCUMULATOR, (cpu, operand) -> {
            cpu.a = Arithmetic6502.shiftRight(cpu.a, cpu.status);
            return 2;
        });
    }

    /** Registers LSR for one memory-addressed mode: read-modify-write, shift right. */
    private static void lsr(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("LSR", mode, (cpu, operand) -> {
            int result = Arithmetic6502.shiftRight(cpu.readOperand(operand), cpu.status);
            cpu.writeResult(operand, result);
            return cost.totalCycles(operand);
        });
    }

    /** Registers ROL A: rotates the accumulator itself left through Carry. */
    private static void rolAccumulator(int opcode) {
        TABLE[opcode] = new OpcodeDef("ROL", ACCUMULATOR, (cpu, operand) -> {
            cpu.a = Arithmetic6502.rotateLeft(cpu.a, cpu.status);
            return 2;
        });
    }

    /** Registers ROL for one memory-addressed mode: read-modify-write, rotate left through Carry. */
    private static void rol(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("ROL", mode, (cpu, operand) -> {
            int result = Arithmetic6502.rotateLeft(cpu.readOperand(operand), cpu.status);
            cpu.writeResult(operand, result);
            return cost.totalCycles(operand);
        });
    }

    /** Registers ROR A: rotates the accumulator itself right through Carry. */
    private static void rorAccumulator(int opcode) {
        TABLE[opcode] = new OpcodeDef("ROR", ACCUMULATOR, (cpu, operand) -> {
            cpu.a = Arithmetic6502.rotateRight(cpu.a, cpu.status);
            return 2;
        });
    }

    /** Registers ROR for one memory-addressed mode: read-modify-write, rotate right through Carry. */
    private static void ror(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("ROR", mode, (cpu, operand) -> {
            int result = Arithmetic6502.rotateRight(cpu.readOperand(operand), cpu.status);
            cpu.writeResult(operand, result);
            return cost.totalCycles(operand);
        });
    }

    // ==================================================================
    // ILLEGAL / UNDOCUMENTED OPCODES -- stable combos only. Reference:
    // oxyron.de/html/opcodes02.html, the community-standard table for
    // this. JAM/halt and the genuinely chip-unstable opcodes (ANE/XAA,
    // LXA, SHA/AHX, SHX, SHY, TAS, LAS) are deliberately deferred --
    // see the follow-up discussion, not this block.
    // ==================================================================

    // Illegal NOPs: perform the REAL memory read at the resolved address
    // (discarding the value) rather than skipping it -- on real hardware
    // the read happens regardless of whether the opcode uses the byte,
    // and if that address is a soft switch, the side effect still fires.
    // Implied-mode NOPs make no memory access at all, so they get a
    // fixed cost with no read.
    private static void illegalNopImplied(int opcode) {
        TABLE[opcode] = new OpcodeDef("NOP*", IMPLIED, (cpu, operand) -> 2);
    }

    private static void illegalNopRead(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("NOP*", mode, (cpu, operand) -> {
            cpu.readOperand(operand); // discarded -- the real bus access is the point
            return cost.totalCycles(operand);
        });
    }

    // SLO/RLA/SRE/RRA: each is a legal RMW instruction followed by a
    // legal accumulator instruction, run back to back on the SAME
    // memory value. Reusing Arithmetic6502's existing shift/rotate/adc
    // methods gets the flag interactions right for free -- e.g. RLA's
    // final N/Z legitimately come from the AND, not the ROL, simply
    // because updateNZ() is called second and overwrites them; SLO's
    // Carry legitimately survives from the ASL step, since the ORA
    // step that runs after it never touches Carry at all.

    private static void slo(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("SLO*", mode, (cpu, operand) -> {
            int shifted = Arithmetic6502.shiftLeft(cpu.readOperand(operand), cpu.status);
            cpu.writeResult(operand, shifted);
            cpu.a = cpu.a | shifted;
            cpu.status.updateNZ(cpu.a); // overwrites N/Z from the OR; the shift's Carry stands
            return cost.totalCycles(operand);
        });
    }

    private static void rla(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("RLA*", mode, (cpu, operand) -> {
            int rotated = Arithmetic6502.rotateLeft(cpu.readOperand(operand), cpu.status);
            cpu.writeResult(operand, rotated);
            cpu.a = cpu.a & rotated;
            cpu.status.updateNZ(cpu.a);
            return cost.totalCycles(operand);
        });
    }

    private static void sre(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("SRE*", mode, (cpu, operand) -> {
            int shifted = Arithmetic6502.shiftRight(cpu.readOperand(operand), cpu.status);
            cpu.writeResult(operand, shifted);
            cpu.a = cpu.a ^ shifted;
            cpu.status.updateNZ(cpu.a);
            return cost.totalCycles(operand);
        });
    }

    // RRA is the one member of this group where the two halves genuinely
    // interact: the ROR sets a new Carry from the memory value's old
    // bit 0, and that new Carry becomes the ADC's carry-IN. Calling
    // Arithmetic6502.adc() after rotateLeft has already updated status
    // gets this right with no extra plumbing -- adc() reads the current
    // carry itself. This also means RRA correctly inherits decimal-mode
    // ADC behavior when D is set, exactly as real hardware does.
    private static void rra(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("RRA*", mode, (cpu, operand) -> {
            int rotated = Arithmetic6502.rotateRight(cpu.readOperand(operand), cpu.status);
            cpu.writeResult(operand, rotated);
            cpu.a = Arithmetic6502.adc(cpu.a, rotated, cpu.status);
            return cost.totalCycles(operand);
        });
    }

    private static void dcp(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("DCP*", mode, (cpu, operand) -> {
            int decremented = (cpu.readOperand(operand) - 1) & 0xFF;
            cpu.writeResult(operand, decremented);
            Arithmetic6502.compare(cpu.a, decremented, cpu.status);
            return cost.totalCycles(operand);
        });
    }

    private static void isc(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ_MODIFY_WRITE);
        TABLE[opcode] = new OpcodeDef("ISC*", mode, (cpu, operand) -> {
            int incremented = (cpu.readOperand(operand) + 1) & 0xFF;
            cpu.writeResult(operand, incremented);
            cpu.a = Arithmetic6502.sbc(cpu.a, incremented, cpu.status);
            return cost.totalCycles(operand);
        });
    }

    /** SAX: stores A&X, computed as a genuine bus conflict on real hardware (both registers driving the bus at once) -- no flags affected. */
    private static void sax(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.WRITE);
        TABLE[opcode] = new OpcodeDef("SAX*", mode, (cpu, operand) -> {
            cpu.writeResult(operand, cpu.a & cpu.x);
            return cost.totalCycles(operand);
        });
    }

    /** LAX: loads the same byte into both A and X in one instruction; N/Z from that shared value. */
    private static void lax(int opcode, AddressingMode mode) {
        CycleCosts.CycleCost cost = CycleCosts.of(mode, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("LAX*", mode, (cpu, operand) -> {
            int value = cpu.readOperand(operand);
            cpu.a = value;
            cpu.x = value;
            cpu.status.updateNZ(value);
            return cost.totalCycles(operand);
        });
    }

    // ---- Stable illegal opcodes, immediate-only. All fixed at 2 cycles.

    /** ANC: A := A & imm, then Carry := bit 7 of the result -- as if an ASL or ROL had followed the AND (both encodings, $0B and $2B, are identical). */
    private static void anc(int opcode) {
        TABLE[opcode] = new OpcodeDef("ANC*", IMMEDIATE, (cpu, operand) -> {
            cpu.a = cpu.a & cpu.readOperand(operand);
            int carryBit = (cpu.a >> 7) & Status6502.CARRY;
            cpu.status.applyFlags(Status6502.NEGATIVE | Status6502.ZERO | Status6502.CARRY,
                Status6502.nzBits(cpu.a) | carryBit);
            return 2;
        });
    }

    /** ALR: A := A & imm, then logically shifted right -- reusing shiftRight() gets the resulting N/Z/C exactly right. */
    private static void alr(int opcode) {
        TABLE[opcode] = new OpcodeDef("ALR*", IMMEDIATE, (cpu, operand) -> {
            int anded = cpu.a & cpu.readOperand(operand);
            cpu.a = Arithmetic6502.shiftRight(anded, cpu.status);
            return 2;
        });
    }

    /**
     * ARR: both binary- and decimal-mode behavior are now implemented and
     * verified against the West/M&auml;kel&auml;n "64doc" reference -- see
     * {@link Arithmetic6502#arr}. An earlier version of this method threw
     * on decimal mode pending that verification.
     */
    private static void arr(int opcode) {
        TABLE[opcode] = new OpcodeDef("ARR*", IMMEDIATE, (cpu, operand) -> {
            cpu.a = Arithmetic6502.arr(cpu.a, cpu.readOperand(operand), cpu.status);
            return 2;
        });
    }

    /** AXS/SBX: X := (A & X) - imm, with N/Z/C set exactly as CMP would (a clean subtraction, not SBC -- no borrow-in, no V). */
    private static void axs(int opcode) {
        TABLE[opcode] = new OpcodeDef("AXS*", IMMEDIATE, (cpu, operand) -> {
            int combined = cpu.a & cpu.x;
            int value = cpu.readOperand(operand);
            Arithmetic6502.compare(combined, value, cpu.status);
            cpu.x = (combined - value) & 0xFF;
            return 2;
        });
    }

    /** LAS: despite its reputation, this one is NOT unstable -- oxyron.de carries no instability marker for it, and the 64doc reference explicitly says the "probably unreliable" claim from one source "does not seem to be the case." A: X: SP := memory & SP; N/Z from the result. */
    private static void las(int opcode) {
        CycleCosts.CycleCost cost = CycleCosts.of(ABSOLUTE_Y, AccessType.READ);
        TABLE[opcode] = new OpcodeDef("LAS*", ABSOLUTE_Y, (cpu, operand) -> {
            int value = cpu.readOperand(operand) & cpu.sp;
            cpu.a = value;
            cpu.x = value;
            cpu.sp = value;
            cpu.status.updateNZ(value);
            return cost.totalCycles(operand);
        });
    }

    /**
     * Genuinely chip-unstable opcodes: ANE/XAA, LXA, SHA, SHX, SHY, TAS.
     * Different real NMOS 6502/6510 chips disagree with each other on
     * these -- some behaviors are documented as temperature-dependent
     * even on a SINGLE chip. There is no canonical NMOS behavior to be
     * faithful to here, so encoding any one chip's typical result would
     * be a confident-looking wrong answer, not a more complete emulation.
     * Consistent with how ARR's decimal mode and JAM/KIL were each
     * handled on their own terms: this throws rather than guesses. Real
     * software essentially never depends on these -- they were explored
     * almost entirely on Commodore 64/Atari hardware, not the Apple II+,
     * whose copy-protection ecosystem got its uniqueness from the disk
     * controller quirks built earlier in this project instead.
     */
    private static void unstable(int opcode, String mnemonic, AddressingMode mode) {
        TABLE[opcode] = new OpcodeDef(mnemonic, mode, (cpu, operand) -> {
            throw new UnsupportedOperationException(mnemonic + " ($" + Integer.toHexString(opcode).toUpperCase()
                + "): chip-dependent and unstable across real NMOS 6502/6510 hardware -- no canonical "
                + "behavior exists to emulate; see Opcodes.unstable() javadoc");
        });
    }

    /**
     * JAM/KIL: halts the CPU permanently -- see {@link Cpu6502#jam}. All
     * twelve opcode encodings share identical behavior; unlike every
     * other illegal opcode here, this isn't a combination of two legal
     * operations, it's a real hardware dead end. The cycle count returned
     * here is a pragmatic placeholder (2, the typical implied-mode cost)
     * rather than a documented value -- the reference table lists
     * "Cycles: -" for JAM precisely because the concept stops being
     * meaningful once nothing can execute afterward.
     */
    private static void jam(int opcode) {
        TABLE[opcode] = new OpcodeDef("JAM*", IMPLIED, (cpu, operand) -> {
            cpu.jam();
            return 2;
        });
    }

    static {
        adc(0x69, IMMEDIATE);
        adc(0x65, ZERO_PAGE);
        adc(0x75, ZERO_PAGE_X);
        adc(0x6D, ABSOLUTE);
        adc(0x7D, ABSOLUTE_X);
        adc(0x79, ABSOLUTE_Y);
        adc(0x61, INDEXED_INDIRECT_X);
        adc(0x71, INDIRECT_INDEXED_Y);

        sbc(0xE9, IMMEDIATE);
        sbc(0xE5, ZERO_PAGE);
        sbc(0xF5, ZERO_PAGE_X);
        sbc(0xED, ABSOLUTE);
        sbc(0xFD, ABSOLUTE_X);
        sbc(0xF9, ABSOLUTE_Y);
        sbc(0xE1, INDEXED_INDIRECT_X);
        sbc(0xF1, INDIRECT_INDEXED_Y);

        lda(0xA9, IMMEDIATE);
        lda(0xA5, ZERO_PAGE);
        lda(0xB5, ZERO_PAGE_X);
        lda(0xAD, ABSOLUTE);
        lda(0xBD, ABSOLUTE_X);
        lda(0xB9, ABSOLUTE_Y);
        lda(0xA1, INDEXED_INDIRECT_X);
        lda(0xB1, INDIRECT_INDEXED_Y);

        and(0x29, IMMEDIATE);
        and(0x25, ZERO_PAGE);
        and(0x35, ZERO_PAGE_X);
        and(0x2D, ABSOLUTE);
        and(0x3D, ABSOLUTE_X);
        and(0x39, ABSOLUTE_Y);
        and(0x21, INDEXED_INDIRECT_X);
        and(0x31, INDIRECT_INDEXED_Y);

        ora(0x09, IMMEDIATE);
        ora(0x05, ZERO_PAGE);
        ora(0x15, ZERO_PAGE_X);
        ora(0x0D, ABSOLUTE);
        ora(0x1D, ABSOLUTE_X);
        ora(0x19, ABSOLUTE_Y);
        ora(0x01, INDEXED_INDIRECT_X);
        ora(0x11, INDIRECT_INDEXED_Y);

        eor(0x49, IMMEDIATE);
        eor(0x45, ZERO_PAGE);
        eor(0x55, ZERO_PAGE_X);
        eor(0x4D, ABSOLUTE);
        eor(0x5D, ABSOLUTE_X);
        eor(0x59, ABSOLUTE_Y);
        eor(0x41, INDEXED_INDIRECT_X);
        eor(0x51, INDIRECT_INDEXED_Y);

        branchIfClear(0x90, "BCC", Status6502.CARRY);
        branchIfSet(0xB0, "BCS", Status6502.CARRY);
        branchIfSet(0xF0, "BEQ", Status6502.ZERO);
        branchIfClear(0xD0, "BNE", Status6502.ZERO);
        branchIfClear(0x10, "BPL", Status6502.NEGATIVE);
        branchIfSet(0x30, "BMI", Status6502.NEGATIVE);
        branchIfClear(0x50, "BVC", Status6502.OVERFLOW);
        branchIfSet(0x70, "BVS", Status6502.OVERFLOW);

        setFlagOp(0x38, "SEC", Status6502.CARRY);
        clearFlagOp(0x18, "CLC", Status6502.CARRY);
        setFlagOp(0x78, "SEI", Status6502.IRQ_DISABLE);
        clearFlagOp(0x58, "CLI", Status6502.IRQ_DISABLE);
        setFlagOp(0xF8, "SED", Status6502.DECIMAL);
        clearFlagOp(0xD8, "CLD", Status6502.DECIMAL);
        clearFlagOp(0xB8, "CLV", Status6502.OVERFLOW); // no SEV on the 6502

        // Register transfers: all set N/Z from the destination EXCEPT TXS --
        // transferring TO the stack pointer is a control operation, not a
        // data load, and real hardware sets no flags for it.
        TABLE[0xAA] = new OpcodeDef("TAX", IMPLIED, (cpu, operand) -> {
            cpu.x = cpu.a;
            cpu.status.updateNZ(cpu.x);
            return 2;
        });
        TABLE[0xA8] = new OpcodeDef("TAY", IMPLIED, (cpu, operand) -> {
            cpu.y = cpu.a;
            cpu.status.updateNZ(cpu.y);
            return 2;
        });
        TABLE[0x8A] = new OpcodeDef("TXA", IMPLIED, (cpu, operand) -> {
            cpu.a = cpu.x;
            cpu.status.updateNZ(cpu.a);
            return 2;
        });
        TABLE[0x98] = new OpcodeDef("TYA", IMPLIED, (cpu, operand) -> {
            cpu.a = cpu.y;
            cpu.status.updateNZ(cpu.a);
            return 2;
        });
        TABLE[0xBA] = new OpcodeDef("TSX", IMPLIED, (cpu, operand) -> {
            cpu.x = cpu.sp;
            cpu.status.updateNZ(cpu.x);
            return 2;
        });
        TABLE[0x9A] = new OpcodeDef("TXS", IMPLIED, (cpu, operand) -> {
            cpu.sp = cpu.x; // no flags
            return 2;
        });

        // Stack ops. PHP/PLP move the FULL status byte, not individual
        // flags -- PHP always writes B=1 (see Status6502.toPushedByteSoftware).
        TABLE[0x48] = new OpcodeDef("PHA", IMPLIED, (cpu, operand) -> {
            cpu.push(cpu.a);
            return 3;
        });
        TABLE[0x68] = new OpcodeDef("PLA", IMPLIED, (cpu, operand) -> {
            cpu.a = cpu.pull();
            cpu.status.updateNZ(cpu.a);
            return 4;
        });
        TABLE[0x08] = new OpcodeDef("PHP", IMPLIED, (cpu, operand) -> {
            cpu.push(cpu.status.toPushedByteSoftware());
            return 3;
        });
        TABLE[0x28] = new OpcodeDef("PLP", IMPLIED, (cpu, operand) -> {
            cpu.status.fromPulledByte(cpu.pull());
            return 4;
        });

        TABLE[0xEA] = new OpcodeDef("NOP", IMPLIED, (cpu, operand) -> 2);

        // JMP doesn't read or write a data value -- the resolved address IS
        // the destination -- so it doesn't participate in CycleCosts at all;
        // its cost is fixed per addressing mode, hardcoded here.
        TABLE[0x4C] = new OpcodeDef("JMP", ABSOLUTE, (cpu, operand) -> {
            cpu.pc = operand.address();
            return 3;
        });
        TABLE[0x6C] = new OpcodeDef("JMP", INDIRECT, (cpu, operand) -> {
            cpu.pc = operand.address(); // the INDIRECT resolver already applies the NMOS page-wrap bug
            return 5;
        });

        // JSR pushes the address of its OWN LAST BYTE (pc-1 at this point,
        // since pc already advanced past both operand bytes) -- RTS adds
        // the 1 back after pulling. High byte pushed first, low byte
        // second, matching real 6502 push order for a 2-byte value.
        TABLE[0x20] = new OpcodeDef("JSR", ABSOLUTE, (cpu, operand) -> {
            int returnAddress = (cpu.pc - 1) & 0xFFFF;
            cpu.push((returnAddress >> 8) & 0xFF);
            cpu.push(returnAddress & 0xFF);
            cpu.pc = operand.address();
            return 6;
        });
        TABLE[0x60] = new OpcodeDef("RTS", IMPLIED, (cpu, operand) -> {
            int lo = cpu.pull();
            int hi = cpu.pull();
            cpu.pc = (((hi << 8) | lo) + 1) & 0xFFFF;
            return 6;
        });

        // BRK: its second byte is a signature byte the CPU still consumes
        // but ignores -- IMPLIED's resolver contributes 0 extra bytes, so
        // that skip is done explicitly here rather than in the resolver
        // (this is BRK-specific, not a property of IMPLIED mode itself).
        // Pushes PC (already past the signature byte), then status with
        // B=1, sets the interrupt-disable flag, and vectors through $FFFE --
        // the same vector a real hardware IRQ uses, which is exactly why
        // IRQ-handling software has to check the pushed B bit to tell BRK
        // and a real hardware interrupt apart.
        TABLE[0x00] = new OpcodeDef("BRK", IMPLIED, (cpu, operand) -> {
            cpu.pc = (cpu.pc + 1) & 0xFFFF;
            cpu.push((cpu.pc >> 8) & 0xFF);
            cpu.push(cpu.pc & 0xFF);
            cpu.push(cpu.status.toPushedByteSoftware());
            cpu.status.setFlag(Status6502.IRQ_DISABLE);
            cpu.maskInterruptsImmediately(); // BRK shares hardware interrupt-entry microcode -- no polling lag, unlike SEI
            cpu.pc = cpu.readVector(0xFFFE);
            return 7;
        });
        TABLE[0x40] = new OpcodeDef("RTI", IMPLIED, (cpu, operand) -> {
            cpu.status.fromPulledByte(cpu.pull());
            int lo = cpu.pull();
            int hi = cpu.pull();
            cpu.pc = (hi << 8) | lo; // no +1 -- unlike RTS, the pushed value here IS the correct resume address
            return 6;
        });

        illegalNopImplied(0x1A);
        illegalNopImplied(0x3A);
        illegalNopImplied(0x5A);
        illegalNopImplied(0x7A);
        illegalNopImplied(0xDA);
        illegalNopImplied(0xFA);
        illegalNopRead(0x80, IMMEDIATE);
        illegalNopRead(0x82, IMMEDIATE);
        illegalNopRead(0x89, IMMEDIATE);
        illegalNopRead(0xC2, IMMEDIATE);
        illegalNopRead(0xE2, IMMEDIATE);
        illegalNopRead(0x04, ZERO_PAGE);
        illegalNopRead(0x44, ZERO_PAGE);
        illegalNopRead(0x64, ZERO_PAGE);
        illegalNopRead(0x14, ZERO_PAGE_X);
        illegalNopRead(0x34, ZERO_PAGE_X);
        illegalNopRead(0x54, ZERO_PAGE_X);
        illegalNopRead(0x74, ZERO_PAGE_X);
        illegalNopRead(0xD4, ZERO_PAGE_X);
        illegalNopRead(0xF4, ZERO_PAGE_X);
        illegalNopRead(0x0C, ABSOLUTE);
        illegalNopRead(0x1C, ABSOLUTE_X);
        illegalNopRead(0x3C, ABSOLUTE_X);
        illegalNopRead(0x5C, ABSOLUTE_X);
        illegalNopRead(0x7C, ABSOLUTE_X);
        illegalNopRead(0xDC, ABSOLUTE_X);
        illegalNopRead(0xFC, ABSOLUTE_X);

        slo(0x07, ZERO_PAGE);
        slo(0x17, ZERO_PAGE_X);
        slo(0x03, INDEXED_INDIRECT_X);
        slo(0x13, INDIRECT_INDEXED_Y);
        slo(0x0F, ABSOLUTE);
        slo(0x1F, ABSOLUTE_X);
        slo(0x1B, ABSOLUTE_Y);

        rla(0x27, ZERO_PAGE);
        rla(0x37, ZERO_PAGE_X);
        rla(0x23, INDEXED_INDIRECT_X);
        rla(0x33, INDIRECT_INDEXED_Y);
        rla(0x2F, ABSOLUTE);
        rla(0x3F, ABSOLUTE_X);
        rla(0x3B, ABSOLUTE_Y);

        sre(0x47, ZERO_PAGE);
        sre(0x57, ZERO_PAGE_X);
        sre(0x43, INDEXED_INDIRECT_X);
        sre(0x53, INDIRECT_INDEXED_Y);
        sre(0x4F, ABSOLUTE);
        sre(0x5F, ABSOLUTE_X);
        sre(0x5B, ABSOLUTE_Y);

        rra(0x67, ZERO_PAGE);
        rra(0x77, ZERO_PAGE_X);
        rra(0x63, INDEXED_INDIRECT_X);
        rra(0x73, INDIRECT_INDEXED_Y);
        rra(0x6F, ABSOLUTE);
        rra(0x7F, ABSOLUTE_X);
        rra(0x7B, ABSOLUTE_Y);

        dcp(0xC7, ZERO_PAGE);
        dcp(0xD7, ZERO_PAGE_X);
        dcp(0xC3, INDEXED_INDIRECT_X);
        dcp(0xD3, INDIRECT_INDEXED_Y);
        dcp(0xCF, ABSOLUTE);
        dcp(0xDF, ABSOLUTE_X);
        dcp(0xDB, ABSOLUTE_Y);

        isc(0xE7, ZERO_PAGE);
        isc(0xF7, ZERO_PAGE_X);
        isc(0xE3, INDEXED_INDIRECT_X);
        isc(0xF3, INDIRECT_INDEXED_Y);
        isc(0xEF, ABSOLUTE);
        isc(0xFF, ABSOLUTE_X);
        isc(0xFB, ABSOLUTE_Y);

        // SAX has no zpx/aby/abx/izy forms -- only these four.
        sax(0x87, ZERO_PAGE);
        sax(0x97, ZERO_PAGE_Y);
        sax(0x83, INDEXED_INDIRECT_X);
        sax(0x8F, ABSOLUTE);

        // LAX has no immediate form here -- $AB ("LXA") is the unstable
        // immediate variant, deliberately deferred with the rest of that group.
        lax(0xA7, ZERO_PAGE);
        lax(0xB7, ZERO_PAGE_Y);
        lax(0xA3, INDEXED_INDIRECT_X);
        lax(0xB3, INDIRECT_INDEXED_Y);
        lax(0xAF, ABSOLUTE);
        lax(0xBF, ABSOLUTE_Y);

        anc(0x0B);
        anc(0x2B); // identical behavior, second historical encoding
        alr(0x4B);
        arr(0x6B);
        axs(0xCB);

        // $EB is a second, undocumented encoding of plain SBC immediate --
        // identical behavior to $E9, not a distinct combo instruction.
        TABLE[0xEB] = new OpcodeDef("SBC*", IMMEDIATE, (cpu, operand) -> {
            cpu.a = Arithmetic6502.sbc(cpu.a, cpu.readOperand(operand), cpu.status);
            return 2;
        });

        las(0xBB);

        unstable(0x8B, "ANE*", IMMEDIATE);
        unstable(0xAB, "LXA*", IMMEDIATE);
        unstable(0x93, "SHA*", INDIRECT_INDEXED_Y);
        unstable(0x9F, "SHA*", ABSOLUTE_Y);
        unstable(0x9E, "SHX*", ABSOLUTE_Y);
        unstable(0x9C, "SHY*", ABSOLUTE_X);
        unstable(0x9B, "TAS*", ABSOLUTE_Y);

        jam(0x02);
        jam(0x12);
        jam(0x22);
        jam(0x32);
        jam(0x42);
        jam(0x52);
        jam(0x62);
        jam(0x72);
        jam(0x92);
        jam(0xB2);
        jam(0xD2);
        jam(0xF2);

        // No STA IMMEDIATE -- storing to an immediate value is not a real operation.
        sta(0x85, ZERO_PAGE);
        sta(0x95, ZERO_PAGE_X);
        sta(0x8D, ABSOLUTE);
        sta(0x9D, ABSOLUTE_X);
        sta(0x99, ABSOLUTE_Y);
        sta(0x81, INDEXED_INDIRECT_X);
        sta(0x91, INDIRECT_INDEXED_Y);

        // LDX/STX index with Y, not X -- X can't index itself.
        ldx(0xA2, IMMEDIATE);
        ldx(0xA6, ZERO_PAGE);
        ldx(0xB6, ZERO_PAGE_Y);
        ldx(0xAE, ABSOLUTE);
        ldx(0xBE, ABSOLUTE_Y);

        stx(0x86, ZERO_PAGE);
        stx(0x96, ZERO_PAGE_Y);
        stx(0x8E, ABSOLUTE);

        // LDY/STY index with X, not Y, for the same reason in reverse.
        // Neither has an indexed-absolute store -- only zp, zp-indexed, and absolute.
        ldy(0xA0, IMMEDIATE);
        ldy(0xA4, ZERO_PAGE);
        ldy(0xB4, ZERO_PAGE_X);
        ldy(0xAC, ABSOLUTE);
        ldy(0xBC, ABSOLUTE_X);

        sty(0x84, ZERO_PAGE);
        sty(0x94, ZERO_PAGE_X);
        sty(0x8C, ABSOLUTE);

        cmp(0xC9, IMMEDIATE);
        cmp(0xC5, ZERO_PAGE);
        cmp(0xD5, ZERO_PAGE_X);
        cmp(0xCD, ABSOLUTE);
        cmp(0xDD, ABSOLUTE_X);
        cmp(0xD9, ABSOLUTE_Y);
        cmp(0xC1, INDEXED_INDIRECT_X);
        cmp(0xD1, INDIRECT_INDEXED_Y);

        // CPX/CPY have no indexed addressing modes at all -- immediate, zero page, absolute only.
        cpx(0xE0, IMMEDIATE);
        cpx(0xE4, ZERO_PAGE);
        cpx(0xEC, ABSOLUTE);

        cpy(0xC0, IMMEDIATE);
        cpy(0xC4, ZERO_PAGE);
        cpy(0xCC, ABSOLUTE);

        // NMOS BIT has only these two modes -- the immediate and indexed
        // variants are 65C02 additions, excluded per this project's NMOS-only scope.
        bit(0x24, ZERO_PAGE);
        bit(0x2C, ABSOLUTE);

        // INC/DEC: no immediate (can't increment a literal), no Y-indexed,
        // no indirect modes on NMOS. First read-modify-write instructions --
        // exercises that branch of CycleCosts for the first time.
        inc(0xE6, ZERO_PAGE);
        inc(0xF6, ZERO_PAGE_X);
        inc(0xEE, ABSOLUTE);
        inc(0xFE, ABSOLUTE_X);

        dec(0xC6, ZERO_PAGE);
        dec(0xD6, ZERO_PAGE_X);
        dec(0xCE, ABSOLUTE);
        dec(0xDE, ABSOLUTE_X);

        aslAccumulator(0x0A);
        asl(0x06, ZERO_PAGE);
        asl(0x16, ZERO_PAGE_X);
        asl(0x0E, ABSOLUTE);
        asl(0x1E, ABSOLUTE_X);

        lsrAccumulator(0x4A);
        lsr(0x46, ZERO_PAGE);
        lsr(0x56, ZERO_PAGE_X);
        lsr(0x4E, ABSOLUTE);
        lsr(0x5E, ABSOLUTE_X);

        rolAccumulator(0x2A);
        rol(0x26, ZERO_PAGE);
        rol(0x36, ZERO_PAGE_X);
        rol(0x2E, ABSOLUTE);
        rol(0x3E, ABSOLUTE_X);

        rorAccumulator(0x6A);
        ror(0x66, ZERO_PAGE);
        ror(0x76, ZERO_PAGE_X);
        ror(0x6E, ABSOLUTE);
        ror(0x7E, ABSOLUTE_X);

        // INX/INY/DEX/DEY: pure register arithmetic, no memory access at all --
        // IMPLIED mode, single, instruction-specific cycle count (2), same
        // reasoning as documented in CycleCosts for why IMPLIED isn't in that table.
        TABLE[0xE8] = new OpcodeDef("INX", IMPLIED, (cpu, operand) -> {
            cpu.x = (cpu.x + 1) & 0xFF;
            cpu.status.updateNZ(cpu.x);
            return 2;
        });
        TABLE[0xC8] = new OpcodeDef("INY", IMPLIED, (cpu, operand) -> {
            cpu.y = (cpu.y + 1) & 0xFF;
            cpu.status.updateNZ(cpu.y);
            return 2;
        });
        TABLE[0xCA] = new OpcodeDef("DEX", IMPLIED, (cpu, operand) -> {
            cpu.x = (cpu.x - 1) & 0xFF;
            cpu.status.updateNZ(cpu.x);
            return 2;
        });
        TABLE[0x88] = new OpcodeDef("DEY", IMPLIED, (cpu, operand) -> {
            cpu.y = (cpu.y - 1) & 0xFF;
            cpu.status.updateNZ(cpu.y);
            return 2;
        });
    }

    private Opcodes() {}
}
