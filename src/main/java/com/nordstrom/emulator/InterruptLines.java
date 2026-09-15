package com.nordstrom.emulator;

/**
 * A peripheral's view of the CPU's interrupt lines -- deliberately
 * narrow: a card can assert or release IRQ/NMI, and nothing else.
 * Handing a card an {@code InterruptLines} reference rather than the
 * whole {@link com.nordstrom.emulator.cpu.Cpu6502} is a real
 * least-privilege boundary, not just tidiness -- a real Apple II+ slot
 * card is physically capable of pulling the {@code /IRQ} and
 * {@code /NMI} lines low and nothing more; it has no way to read CPU
 * registers, single-step execution, or otherwise reach into the CPU
 * beyond that. A peripheral holding this interface can't either.
 * <p>
 * {@code Cpu6502} already exposes exactly these four methods, with
 * matching signatures, for its own reasons (see that class's Javadoc on
 * why IRQ is modeled as a level-sensitive, wire-ORed request count and
 * NMI as edge-sensitive) -- it implements this interface directly, with
 * no adapter code needed. This interface adds no behavior of its own; it
 * only narrows what a peripheral is handed.
 */
public interface InterruptLines {

    /** Asserts IRQ. Level-sensitive and wire-ORed: stays asserted as long as ANY caller has raised it and not yet lowered it. */
    void raiseIrq();

    /** Releases this caller's IRQ assertion. */
    void lowerIrq();

    /**
     * Asserts NMI. Edge-sensitive, unlike IRQ: only the transition from
     * idle to asserted matters -- holding the line already asserted
     * (whether by this caller or another) does not refire it.
     */
    void raiseNmi();

    /** Releases this caller's NMI assertion. */
    void lowerNmi();
}
