package com.nordstrom.emulator;

/**
 * What tests that boot the real ROM need to run reliably on RAM that powers
 * up random ({@code PowerOnRam}).
 */
public final class RomBoot {

    private RomBoot() {}

    /**
     * Makes power-on take the cold-start path. About one power-on in 256,
     * the Autostart ROM's check finds page 3 looking already powered up
     * ({@code $03F4} equal to {@code $03F3} XOR {@code $A5}) and warm-starts
     * through the random address at {@code $03F2} -- into random memory,
     * where the CPU can halt on a JAM opcode or hit an unsupported unstable
     * one. Failing the check makes every boot do what the other 255 in 256
     * do. Call it before the first instruction runs.
     *
     * @param bus the machine's memory, freshly powered up
     */
    public static void coldStart(MemoryBus bus) {
        bus.write(0x03F4, bus.read(0x03F3) ^ 0xA5 ^ 0xFF);
    }

    /**
     * Passes on the cycles a step took, failing the test if the CPU has
     * halted: a halted CPU's steps take no time, so a loop that runs for a
     * stretch of machine time would never end.
     *
     * @param cycles what {@code SystemClock.step()} returned
     * @return {@code cycles}
     */
    public static int notHalted(int cycles) {
        if (cycles == 0) {
            throw new AssertionError("the CPU halted");
        }
        return cycles;
    }
}
