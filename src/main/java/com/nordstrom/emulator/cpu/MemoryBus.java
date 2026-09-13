package com.nordstrom.emulator.cpu;

/**
 * The CPU's view of addressable memory. This duplicates the contract
 * sketched earlier as com.nordstrom.emulator.MemoryBus -- still needs
 * reconciling into one shared interface once bus wiring resumes; kept
 * separate for now so the cpu package is self-contained and compilable
 * on its own.
 */
public interface MemoryBus {
    /** Reads one byte (0..255) from {@code address}. */
    int read(int address);

    /** Writes one byte to {@code address}; only the low 8 bits of {@code value} are meaningful. */
    void write(int address, int value);
}
