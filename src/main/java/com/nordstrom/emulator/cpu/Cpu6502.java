package com.nordstrom.emulator.cpu;

/**
 * NMOS 6502 registers and the fetch/decode/execute step. Addressing-mode
 * resolution and per-instruction cycle accounting are delegated to
 * {@link AddressingModeResolver} and {@link Opcodes} respectively, per
 * this project's table-driven-dispatch convention.
 */
public final class Cpu6502 {

    /** Accumulator, and the X/Y index registers -- each an 8-bit value stored as plain int 0..255. */
    int a, x, y;
    /** Stack pointer; the stack itself lives at $0100-$01FF, addressed as $0100 + sp. */
    int sp;
    /** Program counter -- a 16-bit address. */
    int pc;
    /** The processor status register ("P"). */
    final Status6502 status = new Status6502();

    private final MemoryBus bus;
    private final AddressingModeResolver resolver = new AddressingModeResolver();

    /** Running total of cycles executed since construction. */
    long cycleCount;

    /**
     * Constructs a CPU wired to {@code bus}, with PC initialized from the
     * reset vector at {@code resetVectorAddress} (i.e. $FFFC for the real
     * hardware reset vector) -- mirroring how a real 6502 powers on.
     */
    public Cpu6502(MemoryBus bus, int resetVectorAddress) {
        this.bus = bus;
        this.sp = 0xFD; // real power-on/reset value
        this.pc = readVector(resetVectorAddress);
    }

    /** Executes exactly one instruction; returns the number of cycles it took. */
    public int step() {
        int opcode = bus.read(pc);
        pc = (pc + 1) & 0xFFFF;

        OpcodeDef def = Opcodes.TABLE[opcode];
        if (def == null) {
            throw new UnsupportedOperationException(
                "Opcode $" + Integer.toHexString(opcode) + " not yet implemented");
        }

        ResolvedOperand operand = resolver.resolve(def.mode(), bus, pc, x, y);
        pc = (pc + operand.bytesConsumed()) & 0xFFFF;

        int cycles = def.executor().execute(this, operand);
        cycleCount += cycles;
        return cycles;
    }

    // ---- helpers instruction executors use ----

    /** Reads a resolved operand's value -- from memory if it has an address, or the immediate byte otherwise. */
    int readOperand(ResolvedOperand operand) {
        return operand.address() >= 0 ? bus.read(operand.address()) : operand.immediateValue();
    }

    /** Writes a value back through a resolved operand. Only valid when the addressing mode targets memory (i.e. has a real address, not IMPLIED/ACCUMULATOR/IMMEDIATE). */
    void writeResult(ResolvedOperand operand, int value) {
        bus.write(operand.address(), value & 0xFF);
    }

    /** Pushes one byte onto the stack ($0100-$01FF) and decrements SP, wrapping within that page as real hardware does (no overflow protection). */
    void push(int value) {
        bus.write(0x0100 | sp, value & 0xFF);
        sp = (sp - 1) & 0xFF;
    }

    /** Increments SP and pulls one byte from the stack, wrapping within $0100-$01FF. */
    int pull() {
        sp = (sp + 1) & 0xFF;
        return bus.read(0x0100 | sp);
    }

    /** Reads a 16-bit little-endian vector (reset/IRQ/NMI) starting at {@code address}. */
    int readVector(int address) {
        return bus.read(address) | (bus.read(address + 1) << 8);
    }
}
