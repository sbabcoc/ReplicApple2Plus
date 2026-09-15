package com.nordstrom.emulator.system;

/**
 * A handler for one contiguous slice of the address space, addressed
 * relative to that slice's own start (offset 0 = the slice's first
 * address) -- never in absolute CPU address terms. This is what keeps
 * {@link AddressSpace} itself, and by extension the CPU's whole view of
 * memory (see {@link com.nordstrom.emulator.MemoryBus}), completely free
 * of any specific hardware's memory-map layout: a handler only needs to
 * know its own local geometry, never where it sits in the full 64KB
 * space, and {@code AddressSpace} only needs to know how to route to a
 * handler, never what that handler actually does.
 */
public interface AddressRangeHandler {
    /**
     * Reads one byte at {@code offset} within this handler's own range.
     *
     * @param offset 0-based offset within this handler's range
     * @return the byte at that offset
     */
    int read(int offset);

    /**
     * Writes one byte at {@code offset} within this handler's own range.
     *
     * @param offset 0-based offset within this handler's range
     * @param value the byte to write
     */
    void write(int offset, int value);
}
