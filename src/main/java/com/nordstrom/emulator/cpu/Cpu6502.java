package com.nordstrom.emulator.cpu;

import com.nordstrom.emulator.MemoryBus;

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
    private final int resetVectorAddress;
    private final AddressingModeResolver resolver = new AddressingModeResolver();

    /** Running total of cycles executed since construction. */
    long cycleCount;

    /**
     * Set by a JAM/KIL opcode; once true, real hardware ignores the clock
     * entirely until a hardware reset -- {@link #step} becomes a no-op
     * rather than throwing, since this is a genuine, correctly-emulated
     * CPU state, not a gap in the emulator. There is deliberately no
     * public setter: only {@link #jam} (called from the JAM/KIL opcode
     * entries) can set it.
     */
    private boolean halted;

    /**
     * Lagged snapshot of the Interrupt-disable flag, used ONLY to decide
     * IRQ eligibility -- real hardware polls IRQ using I's value as it
     * stood BEFORE the previous instruction ran, not the live value.
     * That lag is why SEI doesn't mask the very next instruction's
     * interrupt check, and why CLI doesn't unmask anything until the
     * instruction after next -- the guarantee real 6502 assembly relies
     * on to make a CLI-then-critical-instruction sequence safe. Updated
     * once per {@link #step}, immediately before the real instruction
     * executes, so by the time it's used for the NEXT check it reflects
     * "I as of one instruction ago." Hardware interrupt entry and BRK are
     * both exempt from this lag -- see {@link #maskInterruptsImmediately}.
     */
    private boolean irqDisabledForPolling = true; // matches the forced I=1 at power-on

    /**
     * Whether a JAM/KIL opcode has permanently halted this CPU -- see {@link #jam} and {@link #reset}.
     *
     * @return true if this CPU is halted
     */
    public boolean isHalted() {
        return halted;
    }

    // ---- IRQ/NMI lines. Modeled as request counts, not booleans, because
    // both lines are physically wire-ORed on real hardware: any number of
    // devices can assert simultaneously, and the line stays asserted until
    // ALL of them release it, not just whichever one released last. Each
    // caller must raise/lower in a matched pair -- one raise per assertion,
    // one lower per release.

    private int irqRequestCount;
    private int nmiRequestCount;
    private boolean nmiLineIdle = true; // true = line currently high (not asserted)
    private boolean nmiPending;         // set on a falling edge; consumed once serviced

    /** A device asserts IRQ. Level-sensitive: stays asserted as long as ANY caller has raised it and not yet lowered it. */
    public void raiseIrq() {
        irqRequestCount++;
    }

    /** The same device releases its IRQ assertion. */
    public void lowerIrq() {
        if (irqRequestCount > 0) {
            irqRequestCount--;
        }
    }

    /**
     * A device asserts NMI. Edge-sensitive, unlike IRQ: only the
     * transition from idle to asserted matters. If the (possibly
     * wire-ORed) line is already asserted by some other caller, this is
     * NOT a new edge and does not queue a second NMI -- matching real
     * hardware, where holding the line low never refires it.
     */
    public void raiseNmi() {
        nmiRequestCount++;
        if (nmiLineIdle) {
            nmiLineIdle = false;
            nmiPending = true;
        }
    }

    /** The same device releases its NMI assertion. Only once EVERY caller has released does the line return to idle, ready to detect the next edge. */
    public void lowerNmi() {
        if (nmiRequestCount > 0) {
            nmiRequestCount--;
        }
        if (nmiRequestCount == 0) {
            nmiLineIdle = true;
        }
    }

    // ---- RESET line. Opposite polarity from NMI: the meaningful edge is
    // the RELEASE, not the assertion. Real hardware holds the CPU idle for
    // as long as the line stays low and only runs the actual reset
    // sequence once it goes high again -- so raiseReset() just holds
    // step() at a no-op, and the real work happens in lowerReset(), once
    // every asserting source has released (same wire-OR reasoning as
    // IRQ/NMI: multiple sources -- e.g. a power-good circuit and a
    // physical reset key -- could assert simultaneously).

    private int resetRequestCount;

    /** A source (power-on circuit, reset button, etc.) asserts RESET, holding the CPU idle. */
    public void raiseReset() {
        resetRequestCount++;
    }

    /** The same source releases RESET. Once every source has released, this is the release edge that actually triggers {@link #reset}. */
    public void lowerReset() {
        if (resetRequestCount > 0) {
            resetRequestCount--;
        }
        if (resetRequestCount == 0) {
            reset();
        }
    }

    /**
     * Constructs a CPU wired to {@code bus}, with PC initialized from the
     * reset vector at {@code resetVectorAddress} (i.e. $FFFC for the real
     * hardware reset vector) -- mirroring how a real 6502 powers on.
     * <p>
     * A, X, Y, and the status flags are simply left at their JVM default
     * (0, and {@link Status6502}'s own power-on default respectively)
     * rather than modeling genuinely indeterminate power-on values --
     * a defensible convention, not an architectural guarantee. SP is set
     * to $FD, a conventional starting value; see {@link #reset} for the
     * mechanism that actually has documented (if partial) guarantees.
     *
     * @param bus the memory bus this CPU reads instructions and data from
     * @param resetVectorAddress where to read the initial PC from (i.e. $FFFC)
     */
    public Cpu6502(MemoryBus bus, int resetVectorAddress) {
        this.bus = bus;
        this.resetVectorAddress = resetVectorAddress;
        this.sp = 0xFD; // conventional starting value; see reset() for the real mechanism
        this.pc = readVector(resetVectorAddress);
    }

    /**
     * Models a hardware RESET -- distinct from the power-on state the
     * constructor sets up. Per documented real 6502 behavior:
     * <ul>
     *   <li>A, X, Y are left completely unmodified.</li>
     *   <li>The Decimal flag is left unmodified too -- real NMOS hardware
     *       leaves it genuinely indeterminate after reset, so software is
     *       expected to explicitly CLD in its own reset handler. This
     *       emulator does not do that on the software's behalf; whatever
     *       D happened to be before reset is what it still is after.</li>
     *   <li>The Interrupt-disable flag IS reliably set by reset, so this
     *       does set it unconditionally.</li>
     *   <li>SP is decremented by 3, not set to a fixed value. Real RESET
     *       runs the same three "push" bus cycles an interrupt does, but
     *       with writes suppressed -- nothing is actually written to the
     *       stack and whatever was there survives untouched, only the
     *       pointer itself moves.</li>
     *   <li>PC is reloaded from the reset vector.</li>
     * </ul>
     * Also clears {@link #halted} -- this is the actual mechanism by
     * which a JAM/KIL-halted CPU resumes, per {@link #jam}.
     * <p>
     * Callable directly (e.g. for tests or simple setups with no reset
     * line to model), but {@link #lowerReset} is the hardware-faithful
     * entry point when something actually holds and releases the line.
     */
    public void reset() {
        sp = (sp - 3) & 0xFF;
        status.setFlag(Status6502.IRQ_DISABLE);
        irqDisabledForPolling = true; // no lag here -- reset forces this the same way hardware interrupt entry does
        pc = readVector(resetVectorAddress);
        halted = false;
    }

    /**
     * Executes exactly one instruction. A no-op returning 0 while halted
     * (see {@link #isHalted}) or while RESET is held asserted.
     *
     * @return the number of cycles this step took
     */
    public int step() {
        if (halted || resetRequestCount > 0) {
            return 0;
        }

        if (nmiPending) {
            nmiPending = false;
            return serviceInterrupt(0xFFFA);
        }
        if (irqRequestCount > 0 && !irqDisabledForPolling) {
            return serviceInterrupt(0xFFFE);
        }

        // Snapshot I now, before this instruction runs, for the check at
        // the START of the NEXT step() call -- this is what creates the
        // one-instruction lag described on the field itself.
        irqDisabledForPolling = status.isSet(Status6502.IRQ_DISABLE);

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

    /**
     * Shared IRQ/NMI servicing: pushes PC and status (with B=0, since
     * this is a hardware interrupt, not BRK -- see
     * {@link Status6502#toPushedByteHardware}, which exists specifically
     * for this distinction), sets the interrupt-disable flag, and vectors
     * through {@code vectorAddress}. Both take 7 cycles, matching real
     * hardware and matching BRK's own cost.
     */
    private int serviceInterrupt(int vectorAddress) {
        push((pc >> 8) & 0xFF);
        push(pc & 0xFF);
        push(status.toPushedByteHardware());
        status.setFlag(Status6502.IRQ_DISABLE);
        maskInterruptsImmediately();
        pc = readVector(vectorAddress);
        return 7;
    }

    /**
     * Forces the interrupt-disable flag to take effect IMMEDIATELY for
     * polling purposes, bypassing the usual one-instruction lag described
     * on {@link #irqDisabledForPolling}. Hardware interrupt entry (IRQ,
     * NMI) and BRK both use this -- they share the same interrupt-entry
     * microcode on real silicon, unlike a software SEI instruction, and
     * without this a still-asserted IRQ line could immediately
     * re-interrupt the handler's own first instruction.
     */
    void maskInterruptsImmediately() {
        irqDisabledForPolling = true;
    }

    /**
     * JAM/KIL: reads (and discards) the byte following the opcode -- a
     * real bus access, matching real hardware, so a soft switch at that
     * address still fires -- then halts permanently. Only {@link #reset}
     * can resume execution; see {@link #isHalted}.
     */
    void jam() {
        bus.read(pc);
        halted = true;
    }
}
