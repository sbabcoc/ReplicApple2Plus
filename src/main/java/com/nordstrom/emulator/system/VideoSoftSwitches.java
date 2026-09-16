package com.nordstrom.emulator.system;

/**
 * The Apple II+'s video mode soft switches, $C050-$C05F. Motherboard-level,
 * not a {@link SlotCard} -- video isn't something plugged into a slot on
 * real hardware, it's built into the machine itself.
 * <p>
 * Every one of these 16 addresses is a pure toggle: ANY access, read or
 * write, sets the corresponding flag, exactly like {@link com.nordstrom.emulator.expansion.Disk2Controller}'s
 * phase-stepper and motor switches. Unlike that class's Q6/Q7 data latch,
 * though, nothing here ever needs to expose data a not-yet-built
 * subsystem would have to compute -- there's no deferred half to this
 * one. Reads return a harmless 0; real software accessing these
 * addresses does so purely for the side effect and never inspects the
 * returned byte, the same reasoning already applied to the equivalent
 * control switches in {@link com.nordstrom.emulator.expansion.Disk2Controller}.
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
 * These flags aren't consumed by anything yet -- there's no video
 * renderer or {@code SystemClock} in this project to read them for
 * actual pixel output. This class still gets built now because the
 * switches themselves are static, well-documented hardware facts with
 * no timing dependency, the same reasoning that let
 * {@link com.nordstrom.emulator.expansion.Disk2Controller}'s control switches be built ahead of the
 * clock loop that will eventually drive the rest of that card.
 */
public final class VideoSoftSwitches implements AddressRangeHandler {

    private boolean text;
    private boolean mixed;
    private boolean page2;
    private boolean hires;
    private final boolean[] annunciator = new boolean[4];

    @Override
    public int read(int offset) {
        applySwitch(offset);
        return 0; // harmless -- real software never inspects this, see class Javadoc
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

    /** Package-visible for tests and the not-yet-built video renderer. */
    boolean isText() {
        return text;
    }

    /** Package-visible for tests and the not-yet-built video renderer. */
    boolean isMixed() {
        return mixed;
    }

    /** Package-visible for tests and the not-yet-built video renderer. */
    boolean isPage2() {
        return page2;
    }

    /** Package-visible for tests and the not-yet-built video renderer. */
    boolean isHires() {
        return hires;
    }

    /** Package-visible for tests and the not-yet-built video renderer. {@code n} is 0-3. */
    boolean isAnnunciatorOn(int n) {
        return annunciator[n];
    }
}
