package com.nordstrom.emulator.cpu;

import java.util.EnumMap;
import java.util.Map;

import static com.nordstrom.emulator.cpu.AccessType.*;
import static com.nordstrom.emulator.cpu.AddressingMode.*;

/**
 * Base cycle count and page-cross penalty for every (addressing mode,
 * access type) combination that actually occurs on the NMOS 6502,
 * official and illegal alike. Shared across every instruction with that
 * combination -- ADC, SBC, LDA/STA, LDX/STX, LDY/STY, CMP/CPX/CPY, BIT,
 * INC/DEC, and the illegal SLO/RLA/SRE/RRA/DCP/ISC/SAX/LAX family all use it.
 * <p>
 * Deliberately does NOT cover IMPLIED, ACCUMULATOR, or RELATIVE. Those
 * aren't governed by this regularity at all: IMPLIED's cost is different
 * for essentially every instruction that uses it (NOP, PHA, PLA, RTS, RTI,
 * BRK are all different), and RELATIVE's cost depends on whether the
 * branch is taken -- something only known at execution time, not
 * something any static table can hold. Both stay as per-opcode or
 * per-execution values rather than being forced into this table.
 */
final class CycleCosts {

    private static final Map<AddressingMode, Map<AccessType, CycleCost>> TABLE = build();

    /** One table entry: the fixed cycle count, plus the extra cycle owed only on a page-crossing access (0 if this mode/access combination never gets that penalty). */
    record CycleCost(int baseCycles, int pageCrossPenalty) {
        /** baseCycles, plus the penalty if this mode/access combination has one and this particular access crossed a page. */
        int totalCycles(ResolvedOperand operand) {
            return baseCycles + pageCrossPenalty * operand.pageCrossedBit();
        }
    }

    /** Looks up the cycle cost for a given addressing mode and access pattern. */
    static CycleCost of(AddressingMode mode, AccessType access) {
        return TABLE.get(mode).get(access);
    }

    private static Map<AddressingMode, Map<AccessType, CycleCost>> build() {
        Map<AddressingMode, Map<AccessType, CycleCost>> t = new EnumMap<>(AddressingMode.class);

        put(t, IMMEDIATE, READ, 2, 0);

        put(t, ZERO_PAGE, READ, 3, 0);
        put(t, ZERO_PAGE, WRITE, 3, 0);
        put(t, ZERO_PAGE, READ_MODIFY_WRITE, 5, 0);

        put(t, ZERO_PAGE_X, READ, 4, 0);
        put(t, ZERO_PAGE_X, WRITE, 4, 0);
        put(t, ZERO_PAGE_X, READ_MODIFY_WRITE, 6, 0);

        put(t, ZERO_PAGE_Y, READ, 4, 0);
        put(t, ZERO_PAGE_Y, WRITE, 4, 0);

        put(t, ABSOLUTE, READ, 4, 0);
        put(t, ABSOLUTE, WRITE, 4, 0);
        put(t, ABSOLUTE, READ_MODIFY_WRITE, 6, 0);

        // Indexed reads get the conditional discount; writes and RMW never do --
        // the CPU can't undo a memory access it already committed to.
        put(t, ABSOLUTE_X, READ, 4, 1);
        put(t, ABSOLUTE_X, WRITE, 5, 0);
        put(t, ABSOLUTE_X, READ_MODIFY_WRITE, 7, 0);

        put(t, ABSOLUTE_Y, READ, 4, 1);
        put(t, ABSOLUTE_Y, WRITE, 5, 0);
        put(t, ABSOLUTE_Y, READ_MODIFY_WRITE, 7, 0); // same -- illegal-opcode-only

        put(t, INDEXED_INDIRECT_X, READ, 6, 0);
        put(t, INDEXED_INDIRECT_X, WRITE, 6, 0);
        put(t, INDEXED_INDIRECT_X, READ_MODIFY_WRITE, 8, 0); // no official opcode uses this combination -- only the illegal SLO/RLA/SRE/RRA/DCP/ISC family does

        put(t, INDIRECT_INDEXED_Y, READ, 5, 1);
        put(t, INDIRECT_INDEXED_Y, WRITE, 6, 0);
        put(t, INDIRECT_INDEXED_Y, READ_MODIFY_WRITE, 8, 0); // same -- illegal-opcode-only

        return t;
    }

    private static void put(Map<AddressingMode, Map<AccessType, CycleCost>> t,
                             AddressingMode mode, AccessType access, int baseCycles, int pageCrossPenalty) {
        t.computeIfAbsent(mode, m -> new EnumMap<>(AccessType.class))
         .put(access, new CycleCost(baseCycles, pageCrossPenalty));
    }

    private CycleCosts() {}
}
