package com.nordstrom.emulator;

/**
 * The addressable memory contract shared across this emulator -- what a
 * {@link com.nordstrom.emulator.cpu.Cpu6502} reads from and writes to,
 * and what {@link com.nordstrom.emulator.system.MotherboardBus} actually
 * implements. Deliberately NOT in the {@code cpu} package: this isn't
 * CPU-specific behavior, it's a motherboard-level concept the CPU merely
 * consumes, the same way the real chip's address/data pins are a shared
 * bus resource rather than something internal to the CPU's own design.
 */
public interface MemoryBus {
    /**
     * Reads one byte (0..255) from {@code address}.
     *
     * @param address the address to read from
     * @return the byte at that address, 0..255
     */
    int read(int address);

    /**
     * Writes one byte to {@code address}; only the low 8 bits of {@code value} are meaningful.
     *
     * @param address the address to write to
     * @param value the byte to write; only its low 8 bits matter
     */
    void write(int address, int value);

    /**
     * Reads an instruction's opcode: a {@link #read} during which the 6502
     * drives its SYNC pin high, the one signal that tells a device watching
     * the CPU's pins that an instruction is starting at {@code address}.
     * Nothing on the motherboard uses SYNC, so by default this is a plain
     * read; a device between the CPU and its socket overrides it to see
     * where execution goes (see {@code CommandRecall}).
     *
     * @param address the address of the opcode -- the program counter
     * @return the opcode, 0..255
     */
    default int fetchOpcode(int address) {
        return read(address);
    }
}
