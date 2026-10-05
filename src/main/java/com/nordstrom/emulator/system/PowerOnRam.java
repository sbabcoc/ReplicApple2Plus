package com.nordstrom.emulator.system;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Allocates RAM in the state real DRAM powers up in: random 0s and 1s.
 * <p>
 * The Apple II+'s RAM -- the motherboard's 48K and a Language Card's 16K
 * alike -- is dynamic RAM, whose cells come up in no particular state when
 * power is applied. The video circuit's continuous scanning refreshes every
 * row from then on, so the contents stay exactly as power-up left them until
 * software writes over them; random-at-allocation, stable afterwards, is the
 * faithful model.
 * <p>
 * This matters for real software, not just for accuracy's sake: programs
 * that read memory they never initialized -- a random-number seed, for
 * instance, which some games never set -- behave differently on all-zero
 * RAM than on real hardware.
 */
public final class PowerOnRam {

    private PowerOnRam() {}

    /**
     * @param size how many bytes of RAM
     * @return a new array of that size, filled with random bits
     */
    public static byte[] allocate(int size) {
        byte[] ram = new byte[size];
        ThreadLocalRandom.current().nextBytes(ram);
        return ram;
    }
}
