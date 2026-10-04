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
 * that read memory they never initialized behave differently on all-zero
 * RAM. Diagnosed case: Joust's random-number generator seeds itself from
 * $4D-$4F without ever setting them -- relying on whatever power-up left
 * there, or on the Monitor's KEYIN having churned $4E/$4F while waiting at
 * a prompt. With all-zero RAM and nothing run before it (booting the game
 * directly, or after Reboot), the seed stayed 00 00 00, the generator
 * returned the same value forever, and the game hung in a loop at $A32D
 * picking a random number that had to differ from the last one.
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
