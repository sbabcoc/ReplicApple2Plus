package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PowerOnRamTest {

    @Test
    void powerOnRamIsRandomNotZeroed() {
        byte[] ram = PowerOnRam.allocate(0xC000);
        assertEquals(0xC000, ram.length);
        int ones = 0;
        for (byte b : ram) {
            ones += Integer.bitCount(b & 0xFF);
        }
        // 393,216 random bits average half ones; this window is many standard deviations wide.
        double fraction = ones / (ram.length * 8.0);
        assertFalse(fraction < 0.45 || fraction > 0.55, "expected about half the bits set, got " + fraction);
    }

    @Test
    void eachPowerOnComesUpDifferently() {
        assertFalse(Arrays.equals(PowerOnRam.allocate(4096), PowerOnRam.allocate(4096)),
            "two power-ons should not leave identical RAM");
    }

    @Test
    void ramHandlerStartsInThePowerOnState() {
        RamHandler ram = new RamHandler(0x100);
        boolean anyNonZero = false;
        for (int i = 0; i < 0x100; i++) {
            anyNonZero |= ram.read(i) != 0;
        }
        assertFalse(!anyNonZero, "a page of power-on RAM should not read as all zeros");
    }
}
