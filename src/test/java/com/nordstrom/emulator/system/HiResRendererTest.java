package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks in HiResRenderer's monochrome output against its color output for the same bytes. */
class HiResRendererTest {

    private static final class FlatBus implements MemoryBus {
        final int[] mem = new int[0x10000];
        public int read(int a) { return mem[a & 0xFFFF]; }
        public void write(int a, int v) { mem[a & 0xFFFF] = v & 0xFF; }
    }

    @Test
    void monochromeLightsAnIsolatedBitThatColorShowsAsBlue() {
        FlatBus bus = new FlatBus();
        bus.write(0x2000, 0x81); // pixel 0 on, palette bit 1
        VideoSoftSwitches video = new VideoSoftSwitches();
        assertEquals(GraphicsRenderer.PALETTE[6], HiResRenderer.render(bus, video)[0][0]);
        boolean[] line = HiResRenderer.monochrome(bus, video)[0];
        assertTrue(line[0]);
        for (int x = 1; x < line.length; x++) {
            assertFalse(line[x], "pixel " + x);
        }
    }

    @Test
    void monochromeIgnoresThePaletteBit() {
        FlatBus bus = new FlatBus();
        bus.write(0x2000, 0x2A);
        bus.write(0x2400, 0xAA); // line 1: the same pixels, palette bit 1
        boolean[][] pixels = HiResRenderer.monochrome(bus, new VideoSoftSwitches());
        for (int x = 0; x < 7; x++) {
            assertEquals(pixels[0][x], pixels[1][x], "pixel " + x);
        }
    }

    @Test
    void monochromeUsesTheSameAddressingAsColor() {
        FlatBus bus = new FlatBus();
        bus.write(0x2000 + 0x28 + 0x80 + 0x400, 0x01); // line 73, column 0
        boolean[][] pixels = HiResRenderer.monochrome(bus, new VideoSoftSwitches());
        assertTrue(pixels[73][0]);
        assertEquals(GraphicsRenderer.PALETTE[3], HiResRenderer.render(bus, new VideoSoftSwitches())[73][0]);
    }
}
