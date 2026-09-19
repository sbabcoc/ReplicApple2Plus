package com.nordstrom.emulator.system;

/**
 * Produces the actual floating-bus byte value real hardware would show
 * at an address nothing is driving: whatever the video circuitry last
 * fetched. Combines {@link VideoScanner}'s address computation with a
 * genuine read from RAM at that address -- {@link VideoScanner} only
 * answers "which address," this class answers "what byte is actually
 * there right now."
 * <p>
 * Shared by every "empty slot" or "nothing latched" fallback
 * ({@link SlotIoHandler}, {@link SlotZeroIoHandler}, {@link SlotRomHandler},
 * {@link ExpansionRomArbiter}) so each of those classes stays focused on
 * its own dispatch logic rather than repeating this lookup.
 */
final class FloatingBus {

    private final VideoScanner videoScanner;
    private final AddressSpace addressSpace;

    FloatingBus(VideoScanner videoScanner, AddressSpace addressSpace) {
        this.videoScanner = videoScanner;
        this.addressSpace = addressSpace;
    }

    /**
     * @return the byte currently sitting on the bus, driven by the video circuitry's own last fetch
     */
    int read() {
        return addressSpace.read(videoScanner.currentAddress());
    }
}
