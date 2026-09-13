package com.nordstrom.emulator.cpu;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves each NMOS 6502 addressing mode against the current PC and index
 * registers. Table-driven dispatch via EnumMap, as elsewhere in this
 * project.
 * <p>
 * Page-boundary crossings are computed as literal carry-out bits from the
 * low-byte addition, not as a boolean comparison of high bytes -- this is
 * not just a branchless trick, it is how the real 6502 actually detects
 * the condition: it adds the index to the low byte first, speculatively
 * reads using the unmodified high byte, and only spends an extra cycle
 * correcting the high byte if that low-byte addition carried. The carry
 * bit IS the page-cross flag on real hardware, not a derived comparison.
 */
final class AddressingModeResolver {

    /** One addressing mode's resolution logic against the current PC and index registers. */
    @FunctionalInterface
    interface Resolver {
        ResolvedOperand resolve(MemoryBus bus, int pc, int x, int y);
    }

    private static final Map<AddressingMode, Resolver> TABLE = buildTable();

    /** Resolves {@code mode} against the current PC/registers by table lookup. */
    ResolvedOperand resolve(AddressingMode mode, MemoryBus bus, int pc, int x, int y) {
        return TABLE.get(mode).resolve(bus, pc, x, y);
    }

    /** Branchless "did the high byte of a and b differ" as 0/1 -- used only where there is no natural carry bit to read directly (RELATIVE's signed offset). */
    private static int highByteChanged(int a, int b) {
        int diff = ((a ^ b) >> 8) & 0xFF;
        return (diff | -diff) >>> 31;
    }

    private static Map<AddressingMode, Resolver> buildTable() {
        Map<AddressingMode, Resolver> t = new EnumMap<>(AddressingMode.class);

        t.put(AddressingMode.IMPLIED, (bus, pc, x, y) -> ResolvedOperand.none());
        t.put(AddressingMode.ACCUMULATOR, (bus, pc, x, y) -> ResolvedOperand.none());

        t.put(AddressingMode.IMMEDIATE, (bus, pc, x, y) ->
            ResolvedOperand.ofImmediate(bus.read(pc)));

        t.put(AddressingMode.ZERO_PAGE, (bus, pc, x, y) ->
            ResolvedOperand.ofAddress(bus.read(pc) & 0xFF, 0, 1));

        t.put(AddressingMode.ZERO_PAGE_X, (bus, pc, x, y) ->
            ResolvedOperand.ofAddress((bus.read(pc) + x) & 0xFF, 0, 1)); // wraps within page zero -- never crosses

        t.put(AddressingMode.ZERO_PAGE_Y, (bus, pc, x, y) ->
            ResolvedOperand.ofAddress((bus.read(pc) + y) & 0xFF, 0, 1));

        t.put(AddressingMode.ABSOLUTE, (bus, pc, x, y) ->
            ResolvedOperand.ofAddress(bus.read(pc) | (bus.read(pc + 1) << 8), 0, 2));

        t.put(AddressingMode.ABSOLUTE_X, (bus, pc, x, y) -> {
            int baseHi = bus.read(pc + 1);
            int lowSum = bus.read(pc) + x;
            int carry = (lowSum >> 8) & 1; // the real carry-out bit -- see class javadoc
            return ResolvedOperand.ofAddress((((baseHi + carry) & 0xFF) << 8) | (lowSum & 0xFF), carry, 2);
        });

        t.put(AddressingMode.ABSOLUTE_Y, (bus, pc, x, y) -> {
            int baseHi = bus.read(pc + 1);
            int lowSum = bus.read(pc) + y;
            int carry = (lowSum >> 8) & 1;
            return ResolvedOperand.ofAddress((((baseHi + carry) & 0xFF) << 8) | (lowSum & 0xFF), carry, 2);
        });

        t.put(AddressingMode.INDIRECT, (bus, pc, x, y) -> {
            int pointer = bus.read(pc) | (bus.read(pc + 1) << 8);
            // NMOS bug: if the pointer's low byte is $FF, the high byte is
            // fetched from the START of the same page -- the increment
            // never carries into the pointer's high byte.
            int highAddr = (pointer & 0xFF00) | ((pointer + 1) & 0xFF);
            return ResolvedOperand.ofAddress(bus.read(pointer) | (bus.read(highAddr) << 8), 0, 2);
        });

        t.put(AddressingMode.INDEXED_INDIRECT_X, (bus, pc, x, y) -> {
            int zp = (bus.read(pc) + x) & 0xFF; // index applied first, wraps in page zero -- never crosses
            return ResolvedOperand.ofAddress(bus.read(zp) | (bus.read((zp + 1) & 0xFF) << 8), 0, 1);
        });

        t.put(AddressingMode.INDIRECT_INDEXED_Y, (bus, pc, x, y) -> {
            int zp = bus.read(pc) & 0xFF;
            int baseHi = bus.read((zp + 1) & 0xFF);
            int lowSum = bus.read(zp) + y; // index applied AFTER the indirection, unlike (zp,X)
            int carry = (lowSum >> 8) & 1;
            return ResolvedOperand.ofAddress((((baseHi + carry) & 0xFF) << 8) | (lowSum & 0xFF), carry, 1);
        });

        t.put(AddressingMode.RELATIVE, (bus, pc, x, y) -> {
            byte offset = (byte) bus.read(pc); // signed
            int nextPc = (pc + 1) & 0xFFFF;
            int target = (nextPc + offset) & 0xFFFF;
            // Signed offset means there's no single clean carry-out bit to
            // read the way indexed modes have one -- fall back to the
            // generic branchless "did the high byte change" test.
            return ResolvedOperand.ofAddress(target, highByteChanged(nextPc, target), 1);
        });

        return t;
    }
}
