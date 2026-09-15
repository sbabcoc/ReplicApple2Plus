package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;

/**
 * The Apple II+'s real memory map, expressed as a set of registrations
 * into a generic {@link AddressSpace} rather than as branching logic
 * mixed into the dispatch mechanics themselves. This is where every
 * piece of this project's Apple II+-specific memory-map knowledge
 * actually lives -- {@link AddressSpace} itself has none, and neither
 * does the CPU (see {@link MemoryBus}), which only ever calls
 * {@code read}/{@code write} without any idea what's behind either.
 * <p>
 * Real Apple II+ slots are standardized edge connectors with no
 * motherboard-level knowledge of what's plugged into them beyond fixed
 * address ranges and signal lines -- no single chip on the board carries
 * intimate knowledge of the whole memory map. This class mirrors that:
 * the underlying goal is the same one page-level memory protection was
 * meant to achieve in this project's original x86 design (map most of
 * the address space as ordinary memory, trap access to specific
 * protected pages via the OS's own page-fault mechanism, and let the
 * CPU's own load/store code stay completely oblivious to what's behind
 * any given address). A portable JVM has no equivalent to real
 * page-level protection, but the underlying goal -- decoupling address
 * decoding from both the CPU and from every other region's behavior --
 * is fully achievable anyway: {@link AddressSpace} is the generic
 * dispatch mechanism, each region below is its own small, independently
 * swappable handler, and this class is nothing more than the specific
 * list of registrations that assembles them into an Apple II+.
 * <p>
 * Handles:
 * <ul>
 *   <li>$0000-$BFFF: main RAM ({@link RamHandler})</li>
 *   <li>$C050-$C05F: video mode switches ({@link VideoSoftSwitches})</li>
 *   <li>$C090-$C0FF: per-slot I/O switches ({@link SlotIoHandler})</li>
 *   <li>$C100-$C7FF: per-slot ROM ({@link SlotRomHandler})</li>
 *   <li>$C800-$CFFF: the shared expansion ROM window ({@link ExpansionRomHandler}, via the shared {@link ExpansionRomArbiter})</li>
 * </ul>
 * Everything else -- $C000-$C04F and $C060-$C07F (general/keyboard
 * switches) and $C080-$C08F and $D000-$FFFF (language card control
 * switches and banking) -- is registered as a {@link NotYetImplementedHandler},
 * naming the specific missing subsystem. Building the real one later
 * means changing exactly one registration line here; nothing about
 * {@link AddressSpace}, the CPU, or any other region's handler needs to
 * change at all.
 */
public final class MotherboardBus implements MemoryBus {

    private static final String GENERAL_SWITCHES_GAP =
        "General/keyboard soft switches ($C000-$C04F, $C060-$C07F) are not yet implemented";
    private static final String LANGUAGE_CARD_GAP =
        "LanguageCard ($C080-$C08F control switches, $D000-$FFFF banking) is not yet implemented";

    private final AddressSpace addressSpace = new AddressSpace();

    /**
     * Wires up the full Apple II+ memory map against {@code slots} (length 8, index 0 unused) -- typically the array {@link SlotCardLoader#load} just populated.
     *
     * @param slots the machine's populated slots, length 8, index 0 unused
     */
    public MotherboardBus(SlotCard[] slots) {
        if (slots.length != 8) {
            throw new IllegalArgumentException("slots must have length 8 (index 0 unused, slots are 1-7)");
        }

        addressSpace.register(0x0000, 0xBFFF, new RamHandler(0xC000));

        addressSpace.register(0xC000, 0xC04F, new NotYetImplementedHandler(GENERAL_SWITCHES_GAP));
        addressSpace.register(0xC050, 0xC05F, new VideoSoftSwitches());
        addressSpace.register(0xC060, 0xC07F, new NotYetImplementedHandler(GENERAL_SWITCHES_GAP));
        addressSpace.register(0xC080, 0xC08F, new NotYetImplementedHandler(LANGUAGE_CARD_GAP));

        ExpansionRomArbiter arbiter = new ExpansionRomArbiter(slots);
        addressSpace.register(0xC090, 0xC0FF, new SlotIoHandler(slots));
        addressSpace.register(0xC100, 0xC7FF, new SlotRomHandler(slots, arbiter));
        addressSpace.register(0xC800, 0xCFFF, new ExpansionRomHandler(arbiter));

        addressSpace.register(0xD000, 0xFFFF, new NotYetImplementedHandler(LANGUAGE_CARD_GAP));
    }

    /**
     * Reads one byte, routed through {@link AddressSpace} to whichever handler owns that address.
     *
     * @param address the address to read from
     * @return the byte at that address
     */
    @Override
    public int read(int address) {
        return addressSpace.read(address);
    }

    /**
     * Writes one byte, routed through {@link AddressSpace} to whichever handler owns that address.
     *
     * @param address the address to write to
     * @param value the byte to write
     */
    @Override
    public void write(int address, int value) {
        addressSpace.write(address, value);
    }
}
