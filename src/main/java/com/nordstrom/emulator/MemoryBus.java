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
}
