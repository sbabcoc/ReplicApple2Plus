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
 * Slot 0 is real but electrically special (see {@link SlotCard}'s own
 * Javadoc): whatever occupies it gets its own $C080-$C08F I/O switches
 * like any other slot, but never a $Cn00-$CnFF ROM window, and uniquely
 * gets the option to bank-switch $D000-$FFFF instead. Both of those
 * routes are wired dynamically, against whatever (if anything) is
 * actually in {@code slots[0]} -- there is deliberately no hardcoded
 * reference to {@link com.nordstrom.emulator.expansion.LanguageCard} or any other specific card here.
 * <p>
 * Handles:
 * <ul>
 *   <li>$C000-$C00F: keyboard data ({@link KeyboardDataHandler})</li>
 *   <li>$C010-$C01F: keyboard strobe clear ({@link KeyboardStrobeHandler})</li>
 *   <li>$C030-$C03F: speaker toggle ({@link SpeakerToggle})</li>
 *   <li>$0000-$BFFF: main RAM ({@link RamHandler})</li>
 *   <li>$C050-$C05F: video mode switches ({@link VideoSoftSwitches})</li>
 *   <li>$C060-$C06F: game I/O -- cassette/buttons (gaps), paddle reads at offsets 4-7 ({@link GameIoReadHandler})</li>
 *   <li>$C070-$C07F: paddle trigger strobe ({@link PaddleStrobeHandler})</li>
 *   <li>$C080-$C08F: slot 0's I/O switches ({@link SlotZeroIoHandler})</li>
 *   <li>$C090-$C0FF: slots 1-7's I/O switches ({@link SlotIoHandler})</li>
 *   <li>$C100-$C7FF: slots 1-7's ROM ({@link SlotRomHandler})</li>
 *   <li>$C800-$CFFF: the shared expansion ROM window ({@link ExpansionRomHandler}, via the shared {@link ExpansionRomArbiter}) -- slots 1-7 only</li>
 *   <li>$D000-$FFFF: slot 0's bank-switched RAM if it wants one ({@link SlotZeroBankingHandler}), else the system ROM directly ({@link SystemRomHandler})</li>
 * </ul>
 * Everything else -- $C020-$C02F and $C040-$C04F
 * (general/keyboard switches) -- is registered as a
 * {@link NotYetImplementedHandler}, naming the specific missing
 * subsystem. Building the real one later means changing exactly one
 * registration line here; nothing about {@link AddressSpace}, the CPU,
 * or any other region's handler needs to change at all.
 */
public final class MotherboardBus implements MemoryBus {

    private static final String GENERAL_SWITCHES_GAP =
        "General/keyboard soft switches ($C020-$C02F, $C040-$C04F) are not yet implemented";

    private final AddressSpace addressSpace = new AddressSpace();
    private final KeyboardRegister keyboardRegister = new KeyboardRegister();
    private final PaddleTimers paddleTimers = new PaddleTimers();
    private final SpeakerToggle speakerToggle = new SpeakerToggle();
    private final VideoSoftSwitches videoSoftSwitches = new VideoSoftSwitches();
    private final VideoScanner videoScanner = new VideoScanner(videoSoftSwitches);

    /**
     * Wires up the full Apple II+ memory map against {@code slots} (length 8, slot 0 included) -- typically the array {@link SlotCardLoader#load} just populated.
     *
     * @param slots the machine's populated slots, length 8, indices 0-7
     */
    public MotherboardBus(SlotCard[] slots) {
        if (slots.length != 8) {
            throw new IllegalArgumentException("slots must have length 8 (slots 0-7)");
        }

        addressSpace.register(0x0000, 0xBFFF, new RamHandler(0xC000));

        addressSpace.register(0xC000, 0xC00F, new KeyboardDataHandler(keyboardRegister));
        addressSpace.register(0xC010, 0xC01F, new KeyboardStrobeHandler(keyboardRegister));
        addressSpace.register(0xC020, 0xC02F, new NotYetImplementedHandler(GENERAL_SWITCHES_GAP));
        addressSpace.register(0xC030, 0xC03F, speakerToggle);
        addressSpace.register(0xC040, 0xC04F, new NotYetImplementedHandler(GENERAL_SWITCHES_GAP));
        addressSpace.register(0xC050, 0xC05F, videoSoftSwitches);
        addressSpace.register(0xC060, 0xC06F, new GameIoReadHandler(paddleTimers));
        addressSpace.register(0xC070, 0xC07F, new PaddleStrobeHandler(paddleTimers));

        FloatingBus floatingBus = new FloatingBus(videoScanner, addressSpace);

        addressSpace.register(0xC080, 0xC08F, new SlotZeroIoHandler(slots[0], floatingBus));

        ExpansionRomArbiter arbiter = new ExpansionRomArbiter(slots, floatingBus);
        addressSpace.register(0xC090, 0xC0FF, new SlotIoHandler(slots, floatingBus));
        addressSpace.register(0xC100, 0xC7FF, new SlotRomHandler(slots, arbiter, floatingBus));
        addressSpace.register(0xC800, 0xCFFF, new ExpansionRomHandler(arbiter));

        AddressRangeHandler upperMemory = (slots[0] != null && slots[0].wantsSlotZeroBanking())
            ? new SlotZeroBankingHandler(slots[0])
            : new SystemRomHandler();
        addressSpace.register(0xD000, 0xFFFF, upperMemory);
    }

    /**
     * The keyboard register wired into $C000-$C01F -- the only way anything
     * outside this class can feed a real keypress in via
     * {@link KeyboardRegister#keyPressed}, since this class has no
     * dependency on any specific UI toolkit itself.
     *
     * @return this machine's keyboard register
     */
    public KeyboardRegister keyboardRegister() {
        return keyboardRegister;
    }

    /**
     * The paddle/joystick analog timers wired into $C060-$C07F -- how a
     * real input source would set dial positions via
     * {@link PaddleTimers#setPosition}, and how the running countdowns
     * get ticked via {@link SystemClock#addCycleListener} once assembled
     * with a real clock.
     *
     * @return this machine's paddle timers
     */
    public PaddleTimers paddleTimers() {
        return paddleTimers;
    }

    /**
     * The speaker toggle wired into $C030-$C03F -- how anything outside
     * this class would observe toggle events for real audio synthesis,
     * once something exists to do that.
     *
     * @return this machine's speaker toggle
     */
    public SpeakerToggle speakerToggle() {
        return speakerToggle;
    }

    /**
     * The video mode switches wired into $C050-$C05F -- how anything
     * outside this class would read current video mode state for real
     * rendering.
     *
     * @return this machine's video mode switches
     */
    public VideoSoftSwitches videoSoftSwitches() {
        return videoSoftSwitches;
    }

    /**
     * Computes which memory address the video circuitry is fetching at
     * any point in the frame -- what floating-bus emulation needs, and
     * also a real building block for an eventual actual renderer. Ticked
     * via {@link SystemClock#addCycleListener} once assembled with a
     * real clock, the same mechanism {@link #paddleTimers} uses.
     *
     * @return this machine's video scanner
     */
    public VideoScanner videoScanner() {
        return videoScanner;
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
