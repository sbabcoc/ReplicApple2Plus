package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Locks in LoResRenderer's dot patterns: the video signal a monochrome monitor shows. */
class LoResRendererTest {

    private static final class FlatBus implements MemoryBus {
        final int[] mem = new int[0x10000];
        public int read(int a) { return mem[a & 0xFFFF]; }
        public void write(int a, int v) { mem[a & 0xFFFF] = v & 0xFF; }
    }

    private static String dotsOf(boolean[] line, int from, int to) {
        StringBuilder s = new StringBuilder();
        for (int d = from; d < to; d++) {
            s.append(line[d] ? '1' : '0');
        }
        return s.toString();
    }

    @Test
    void gridIsFiveHundredSixtyDotsWide() {
        boolean[][] dots = LoResRenderer.dots(new FlatBus(), new VideoSoftSwitches());
        assertEquals(192, dots.length);
        assertEquals(560, dots[0].length);
    }

    @Test
    void eachBitOfTheColorLightsItsOwnPhase() {
        FlatBus bus = new FlatBus();
        bus.write(0x400, 0x01); // column 0, color 1: bit 0 only
        bus.write(0x402, 0x02); // column 2, color 2: bit 1 only
        bus.write(0x404, 0x04); // column 4, color 4: bit 2 only
        bus.write(0x406, 0x08); // column 6, color 8: bit 3 only
        boolean[] line = LoResRenderer.dots(bus, new VideoSoftSwitches())[0];
        assertEquals("10001000100010", dotsOf(line, 0, 14));
        assertEquals("01000100010001", dotsOf(line, 28, 42));
        assertEquals("00100010001000", dotsOf(line, 56, 70));
        assertEquals("00010001000100", dotsOf(line, 84, 98));
    }

    @Test
    void oddColumnsStartTwoBitsIntoThePattern() {
        FlatBus bus = new FlatBus();
        bus.write(0x400, 0x01); // columns 0 and 1, color 1
        bus.write(0x401, 0x01);
        boolean[] line = LoResRenderer.dots(bus, new VideoSoftSwitches())[0];
        assertEquals("10001000100010", dotsOf(line, 0, 14));
        assertEquals("00100010001000", dotsOf(line, 14, 28));
    }

    @Test
    void highNibbleIsTheBottomBlock() {
        FlatBus bus = new FlatBus();
        bus.write(0x400, 0xF0); // top block black, bottom block white
        boolean[][] dots = LoResRenderer.dots(bus, new VideoSoftSwitches());
        for (int y = 0; y < 4; y++) {
            assertEquals("00000000000000", dotsOf(dots[y], 0, 14), "top block, line " + y);
        }
        for (int y = 4; y < 8; y++) {
            assertEquals("11111111111111", dotsOf(dots[y], 0, 14), "bottom block, line " + y);
        }
    }

    @Test
    void violetLightsTheSameDotsInLoResAndHiRes() {
        FlatBus bus = new FlatBus();
        bus.write(0x400, 0x03); // lo-res color 3, columns 0 and 1
        bus.write(0x401, 0x03);
        bus.write(0x2000, 0x55); // hi-res violet: even pixels, palette bit 0
        bus.write(0x2001, 0x2A);
        VideoSoftSwitches video = new VideoSoftSwitches();
        boolean[] loRes = LoResRenderer.dots(bus, video)[0];
        boolean[] hiRes = HiResRenderer.monochrome(bus, video)[0];
        for (int x = 0; x < 14; x++) {
            assertEquals(hiRes[x], loRes[2 * x], "first dot of pixel " + x);
            assertEquals(hiRes[x], loRes[2 * x + 1], "second dot of pixel " + x);
        }
    }

    @Test
    void page2SwitchesWhichMemoryIsRead() {
        FlatBus bus = new FlatBus();
        VideoSoftSwitches video = new VideoSoftSwitches();
        bus.write(0x800, 0x0F);
        video.write(0x5, 0); // PAGE2 on
        assertEquals("11111111111111", dotsOf(LoResRenderer.dots(bus, video)[0], 0, 14));
    }
}
