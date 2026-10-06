package com.nordstrom.emulator.system;

/**
 * The Apple II+'s video mode soft switches, $C050-$C05F. Motherboard-level,
 * not a {@link SlotCard} -- video isn't something plugged into a slot on
 * real hardware, it's built into the machine itself.
 * <p>
 * Every one of these 16 addresses is a pure toggle: ANY access, read or
 * write, sets the corresponding flag, exactly like {@link com.nordstrom.emulator.expansion.Disk2Controller}'s
 * phase-stepper and motor switches. Unlike that class's Q6/Q7 data latch,
 * nothing here needs to expose data a subsystem computes -- but reads DO
 * need to return the real floating-bus value, not a fixed one (see
 * {@link #read}'s own Javadoc for why that assumption changed).
 * <pre>
 *   $C050/$C051  TEXT off/on       (graphics/text)
 *   $C052/$C053  MIXED off/on      (full screen/mixed)
 *   $C054/$C055  PAGE2 off/on      (page 1/page 2)
 *   $C056/$C057  HIRES off/on      (lo-res/hi-res)
 *   $C058/$C059  AN0 off/on
 *   $C05A/$C05B  AN1 off/on
 *   $C05C/$C05D  AN2 off/on
 *   $C05E/$C05F  AN3 off/on
 * </pre>
 * $C05E/$C05F are the fourth annunciator (AN3) specifically on the
 * Apple II+ -- the IIe/IIc repurpose these same two addresses for
 * double-hi-res, which does not apply to the machine this project
 * emulates.
 * <p>
 * These flags are consumed by {@link TextScreenRenderer}, {@link LoResRenderer},
 * {@link HiResRenderer}, and {@link ScanlineModes}. This class itself only tracks the switches'
 * own state and the real floating-bus read on access -- it has no
 * opinion about rendering.
 */
public final class VideoSoftSwitches implements AddressRangeHandler {

    private boolean text;
    private boolean mixed;
    private boolean page2;
    private boolean hires;
    private final boolean[] annunciator = new boolean[4];
    private java.util.function.IntSupplier floatingBusSupplier;

    /**
     * Wires in the real floating-bus read these switches need on a read
     * access (see {@link #read}'s own Javadoc for why this exists, and
     * {@code Disk2Controller#setFloatingBusSupplier} for the identical
     * pattern already established there for exactly the same real-hardware
     * reason). Left unwired (e.g. in isolated unit tests), reads simply
     * come back as 0.
     *
     * @param floatingBusSupplier supplies the current floating-bus byte
     */
    public void setFloatingBusSupplier(java.util.function.IntSupplier floatingBusSupplier) {
        this.floatingBusSupplier = floatingBusSupplier;
    }

    @Override
    public int read(int offset) {
        applySwitch(offset);
        // Real hardware shows the floating bus here (whatever the video
        // circuitry last fetched), not a fixed value -- these addresses
        // don't drive the data bus themselves any more than the Disk II
        // controller's own non-latched offsets do (see that class's
        // identical reasoning). This was originally modeled as always
        // returning 0, on the assumption that real software only ever
        // touches these addresses for the switch side effect and never
        // inspects the returned byte. That assumption is false for at
        // least one real, historically significant, documented program:
        // Bob Bishop's "floating bus" screen-split demo (Softalk, October
        // 1982, included as a Virtual ][ example script) explicitly reads
        // $C050/$C051 in a polling loop and compares the returned value
        // against specific expected bytes to synchronize a screen-mode
        // switch to an exact scan line -- confirmed directly by running
        // that exact program's real, disassembled machine code against
        // this emulator: with a fixed 0 return, its polling loop spins
        // forever (0 never matches its target bytes), and the CPU can be
        // observed stuck cycling through that loop's three instructions
        // indefinitely. Wiring in the real floating-bus value here is the
        // same fix already applied to Disk2Controller's own non-latched
        // switch offsets, for the same underlying reason.
        return floatingBusSupplier != null ? floatingBusSupplier.getAsInt() : 0;
    }

    @Override
    public void write(int offset, int value) {
        applySwitch(offset);
    }

    private void applySwitch(int offset) {
        switch (offset) {
            case 0x0 -> text = false;
            case 0x1 -> text = true;
            case 0x2 -> mixed = false;
            case 0x3 -> mixed = true;
            case 0x4 -> page2 = false;
            case 0x5 -> page2 = true;
            case 0x6 -> hires = false;
            case 0x7 -> hires = true;
            case 0x8 -> annunciator[0] = false;
            case 0x9 -> annunciator[0] = true;
            case 0xA -> annunciator[1] = false;
            case 0xB -> annunciator[1] = true;
            case 0xC -> annunciator[2] = false;
            case 0xD -> annunciator[2] = true;
            case 0xE -> annunciator[3] = false;
            case 0xF -> annunciator[3] = true;
            default -> throw new IllegalArgumentException("offset " + offset + " is outside 0-15");
        }
    }

    /** Package-visible for tests and the renderers. */
    boolean isText() {
        return text;
    }

    /** Package-visible for tests and the renderers. */
    boolean isMixed() {
        return mixed;
    }

    /** Package-visible for tests and the renderers. */
    boolean isPage2() {
        return page2;
    }

    /** Package-visible for tests and the renderers. */
    boolean isHires() {
        return hires;
    }

    /** Package-visible for tests and the renderers. {@code n} is 0-3. */
    boolean isAnnunciatorOn(int n) {
        return annunciator[n];
    }

    /**
     * Whether Videx's Soft Video Switch would be passing an 80-column card's
     * video to the monitor: annunciator 0 high and graphics off (the color
     * killer high), both required -- either one low selects the Apple's own
     * video. See HARDWARE-REFERENCE.md section 3.
     *
     * @return true when the 80-column display is selected
     */
    public boolean softVideoSwitchSelects80Columns() {
        return annunciator[0] && text;
    }
}
