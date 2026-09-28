package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The same behaviors as {@link GameIoReadHandlerTest}, but reached the
 * way a running program reaches them: through the full
 * {@link MotherboardBus} address map, by absolute address. The whole
 * point of the change these cover is that reading these addresses no
 * longer stops the machine, so the check that matters is that a real
 * bus read at each one completes.
 */
class GameIoBusIntegrationTest {

    @Test
    void everyGameIoAndGeneralSwitchAddressIsReadableAndWritable() {
        try (MotherboardBus bus = new MotherboardBus(new SlotCard[8])) {
            int[][] ranges = {
                {0xC020, 0xC02F},  // cassette output toggle
                {0xC040, 0xC04F},  // utility strobe
                {0xC060, 0xC06F},  // cassette input, pushbuttons, paddles, mirrors
            };
            for (int[] range : ranges) {
                for (int address = range[0]; address <= range[1]; address++) {
                    int a = address;
                    assertDoesNotThrow(() -> bus.read(a), "read $" + Integer.toHexString(a));
                    assertDoesNotThrow(() -> bus.write(a, 0), "write $" + Integer.toHexString(a));
                }
            }
        }
    }

    @Test
    void aPushbuttonPressReachesTheCpuAtItsAbsoluteAddress() {
        try (MotherboardBus bus = new MotherboardBus(new SlotCard[8])) {
            assertEquals(0x00, bus.read(0xC061));
            bus.gameButtons().setPressed(0, true);
            assertEquals(0x80, bus.read(0xC061), "button 0 -> $C061");
            assertEquals(0x00, bus.read(0xC062), "button 1 is unaffected");
            assertEquals(0x00, bus.read(0xC063), "button 2 is unaffected");
            assertEquals(0x80, bus.read(0xC069), "the mirror at $C069 agrees");
            bus.gameButtons().setPressed(0, false);
            assertEquals(0x00, bus.read(0xC061));
        }
    }
}
