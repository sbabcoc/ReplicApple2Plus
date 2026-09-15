package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;

import java.util.ArrayList;
import java.util.List;

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
 */
public final class AddressSpace implements MemoryBus {

    private record Range(int start, int end, AddressRangeHandler handler) {
        boolean contains(int address) {
            return address >= start && address <= end;
        }
    }

    private final List<Range> ranges = new ArrayList<>();

    /**
     * Registers {@code handler} to own every address from {@code start}
     * to {@code end} inclusive. Ranges must not overlap with anything
     * already registered.
     *
     * @param start first address this handler owns (inclusive)
     * @param end last address this handler owns (inclusive)
     * @param handler the handler to route this range to
     */
    public void register(int start, int end, AddressRangeHandler handler) {
        for (Range existing : ranges) {
            if (existing.start() <= end && start <= existing.end()) {
                throw new IllegalStateException("Range $" + Integer.toHexString(start) + "-$"
                    + Integer.toHexString(end) + " overlaps already-registered range $"
                    + Integer.toHexString(existing.start()) + "-$" + Integer.toHexString(existing.end()));
            }
        }
        ranges.add(new Range(start, end, handler));
    }

    /**
     * Reads one byte, routed to whichever registered handler owns {@code address}.
     *
     * @param address the address to read from
     * @return the byte at that address
     */
    @Override
    public int read(int address) {
        Range range = find(address);
        return range.handler().read(address - range.start());
    }

    /**
     * Writes one byte, routed to whichever registered handler owns {@code address}.
     *
     * @param address the address to write to
     * @param value the byte to write
     */
    @Override
    public void write(int address, int value) {
        Range range = find(address);
        range.handler().write(address - range.start(), value & 0xFF);
    }

    private Range find(int address) {
        address &= 0xFFFF;
        for (Range range : ranges) {
            if (range.contains(address)) {
                return range;
            }
        }
        throw new IllegalStateException("No handler registered for $" + Integer.toHexString(address));
    }
}
