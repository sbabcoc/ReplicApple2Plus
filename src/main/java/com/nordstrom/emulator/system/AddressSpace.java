package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;

/**
 * A generic, hardware-agnostic address-range dispatcher: given a set of
 * registered (start, end, handler) ranges, routes each read/write to
 * whichever handler owns that address, translated to a local offset.
 * This class has no idea what a "slot," a "video switch," or a
 * "language card" is -- and that's the entire point of it existing as
 * its own class, separate from {@link MotherboardBus}, which is where
 * all of that Apple II+-specific knowledge actually lives, expressed as
 * a list of registrations rather than branching logic mixed into the
 * dispatch mechanics themselves.
 * <p>
 * This is the closest a portable JVM can get to the effect the original
 * x86 6502-Emulator project got from real page-level memory protection
 * (mapping most of the address space as ordinary memory and trapping
 * accesses to specific protected pages via the OS's own page-fault
 * mechanism): the CPU's {@code read}/{@code write} calls stay completely
 * oblivious to what's actually behind any given address, and swapping
 * one region's behavior -- a placeholder for a real implementation, say
 * -- never requires touching this class, the CPU, or any other region's
 * handler.
 * <p>
 * Internally, the full 64KB space is divided into 4096 16-byte blocks;
 * {@code read}/{@code write} resolve to a handler by direct array
 * indexing rather than scanning a list of registered ranges -- O(1)
 * instead of O(number of registered ranges), paid for entirely at
 * {@link #register} time rather than on every CPU memory access. This is
 * exactly the granularity real Apple II+ hardware already uses at its
 * finest-grained regions (one slot's I/O switches ARE 16 bytes), so
 * every range this project has ever registered already happens to fall
 * on a block boundary -- not a coincidence forced onto the hardware, a
 * property the hardware already had. {@link #register} enforces this
 * alignment as a real correctness requirement, not a style preference:
 * a misaligned range genuinely cannot be represented in a per-block
 * table at all, since one block can only ever point to one handler.
 * <p>
 * Since {@link #BLOCK_SIZE} is a power of two, block-index and alignment
 * arithmetic uses shifts and masks ({@code >> BLOCK_SHIFT}, {@code &
 * BLOCK_MASK}) rather than division and modulo -- both because that's
 * literally how real address-decode logic works (matching bit patterns,
 * not doing arithmetic), and to keep every block-size-dependent
 * computation tied to the same two derived constants rather than
 * scattering an assumption about the block size across multiple
 * differently-expressed calculations.
 * <p>
 * Each block's handler AND the absolute start of the full range it
 * belongs to are tracked together as one {@link Registration} record per
 * block, not as two separately-indexed parallel arrays. Every block
 * belonging to the same {@link #register} call shares the exact same
 * {@code Registration} instance, so the two facts can never drift apart
 * from each other -- there is nothing to keep in sync by hand, since
 * they were never two things to begin with.
 */
public final class AddressSpace implements MemoryBus {

    private static final int BLOCK_SIZE = 0x10;         // must stay a power of two -- see BLOCK_SHIFT
    private static final int BLOCK_SHIFT = 4;            // log2(BLOCK_SIZE): address >> BLOCK_SHIFT gives the block index
    private static final int BLOCK_MASK = BLOCK_SIZE - 1; // isolates the low bits, for alignment checks
    private static final int BLOCK_COUNT = 0x10000 >> BLOCK_SHIFT;

    private record Registration(int start, AddressRangeHandler handler) {}

    private final Registration[] blockRegistration = new Registration[BLOCK_COUNT];

    /**
     * Registers {@code handler} to own every address from {@code start}
     * to {@code end} inclusive. {@code end} must not be before
     * {@code start}; both {@code start} and {@code end + 1} must be
     * aligned to a 16-byte block boundary; and the range must not
     * overlap anything already registered.
     *
     * @param start first address this handler owns (inclusive), must be 16-byte aligned
     * @param end last address this handler owns (inclusive), must not be before {@code start}; end + 1 must be 16-byte aligned
     * @param handler the handler to route this range to
     */
    public void register(int start, int end, AddressRangeHandler handler) {
        if (end < start) {
            throw new IllegalArgumentException("end $" + Integer.toHexString(end)
                + " is before start $" + Integer.toHexString(start));
        }
        if ((start & BLOCK_MASK) != 0) {
            throw new IllegalArgumentException("start $" + Integer.toHexString(start)
                + " is not aligned to a " + BLOCK_SIZE + "-byte block boundary");
        }
        if (((end + 1) & BLOCK_MASK) != 0) {
            throw new IllegalArgumentException("end $" + Integer.toHexString(end)
                + " does not end exactly at a " + BLOCK_SIZE + "-byte block boundary "
                + "(the range's length must be a whole number of blocks)");
        }

        int startBlock = start >> BLOCK_SHIFT;
        int endBlock = end >> BLOCK_SHIFT;
        for (int b = startBlock; b <= endBlock; b++) {
            if (blockRegistration[b] != null) {
                throw new IllegalStateException("Block containing $" + Integer.toHexString(b << BLOCK_SHIFT)
                    + " is already registered -- range $" + Integer.toHexString(start) + "-$"
                    + Integer.toHexString(end) + " overlaps an existing registration");
            }
        }

        Registration registration = new Registration(start, handler);
        for (int b = startBlock; b <= endBlock; b++) {
            blockRegistration[b] = registration;
        }
    }

    /**
     * Reads one byte, routed to whichever registered handler owns
     * {@code address}. The return value is unconditionally masked to
     * 0-255, for the exact same reason {@link #write} masks its incoming
     * value: the real data bus is physically 8 bits wide, full stop --
     * there is no such thing as a 9th bit "existing" anywhere for
     * hardware to detect or reject, so masking isn't leniency here any
     * more than it is for writes, it's the literal physical truth in
     * both directions.
     * <p>
     * A handler returning something outside 0-255 is not a hardware
     * condition at all -- no real Apple II+ scenario could ever produce
     * it, since a real chip's output pins simply can't represent it.
     * It's a bug in this software's own handler implementation, an
     * artifact of Java's {@code int} being wider than the 8-bit contract
     * {@link AddressRangeHandler#read} promises. That's real and worth
     * catching, but it is NOT part of what's being emulated, so it's
     * caught with a Java {@code assert} -- active during testing, zero
     * cost in a normal run, and unmistakably a software-correctness
     * check rather than a simulated hardware fault (unlike the
     * multi-latch bus conflict elsewhere in this project, which IS a
     * genuinely undefined real hardware condition and is thrown as one).
     *
     * @param address the address to read from
     * @return the byte at that address
     */
    @Override
    public int read(int address) {
        address &= 0xFFFF;
        Registration registration = requireRegistration(address);
        int value = registration.handler().read(address - registration.start());
        assert value >= 0 && value <= 0xFF : registration.handler().getClass().getName()
            + " returned an illegal value " + value + " (must be 0-255) for address $"
            + Integer.toHexString(address) + " -- this is a bug in that handler, not an emulated condition";
        return value & 0xFF;
    }

    /**
     * Writes one byte, routed to whichever registered handler owns {@code address}.
     *
     * @param address the address to write to
     * @param value the byte to write
     */
    @Override
    public void write(int address, int value) {
        address &= 0xFFFF;
        Registration registration = requireRegistration(address);
        registration.handler().write(address - registration.start(), value & 0xFF);
    }

    private Registration requireRegistration(int address) {
        Registration registration = blockRegistration[address >> BLOCK_SHIFT];
        if (registration == null) {
            throw new IllegalStateException("No handler registered for $" + Integer.toHexString(address));
        }
        return registration;
    }
}
