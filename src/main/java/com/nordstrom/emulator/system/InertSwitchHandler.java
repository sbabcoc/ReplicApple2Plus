package com.nordstrom.emulator.system;

/**
 * A soft switch whose only real-hardware effect is on something this
 * machine has no model of, so accessing it changes nothing here.
 * Registered for two ranges:
 * <ul>
 *   <li>$C020-$C02F -- the cassette output toggle. Real hardware flips
 *       a flip-flop that drives the cassette jack. With no cassette
 *       interface modeled, nothing is listening.</li>
 *   <li>$C040-$C04F -- the utility strobe, a one-cycle pulse on the game
 *       connector. With nothing plugged into that pin, nothing is
 *       listening.</li>
 * </ul>
 * Software touches these purely for the side effect, never for a
 * returned value, so a read returns a harmless 0 -- the same convention
 * {@link SpeakerToggle} and {@link PaddleStrobeHandler} use -- and a
 * write is ignored.
 * <p>
 * This exists because both ranges used to be
 * {@link NotYetImplementedHandler}s: any program that so much as poked
 * one stopped the whole machine, even though the poke had no visible
 * effect on real hardware either. If a cassette or utility-strobe
 * device is ever modeled, the registration for that one range changes
 * and this class stays as-is for the other.
 */
final class InertSwitchHandler implements AddressRangeHandler {

    @Override
    public int read(int offset) {
        return 0;
    }

    @Override
    public void write(int offset, int value) {
        // no observable effect -- see class Javadoc
    }
}
