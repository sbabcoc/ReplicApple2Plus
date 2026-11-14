package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Locks in HiResRenderer's dots, the signal a monochrome monitor shows, against its color output. */
class HiResRendererTest {

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

    private static boolean[] line0(FlatBus bus) {
        return HiResRenderer.dots(bus, new VideoSoftSwitches())[0];
    }

    @Test
    void gridIsFiveHundredSixtyDotsWide() {
        boolean[][] dots = HiResRenderer.dots(new FlatBus(), new VideoSoftSwitches());
        assertEquals(192, dots.length);
        assertEquals(560, dots[0].length);
    }

    @Test
    void eachBitIsTwoDotsBitZeroFirst() {
        FlatBus bus = new FlatBus();
        bus.write(0x2000, 0x05); // bits 0 and 2
        assertEquals("11001100000000", dotsOf(line0(bus), 0, 14));
    }

    @Test
    void thePaletteBitDelaysTheByteByOneDot() {
        FlatBus bus = new FlatBus();
        bus.write(0x2000, 0x85);
        assertEquals("01100110000000", dotsOf(line0(bus), 0, 14));
    }

    @Test
    void aDelayedByteExtendsThePreviousBitSix() {
        FlatBus bus = new FlatBus();
        bus.write(0x2000, 0x40); // bit 6: dots 12-13
        bus.write(0x2001, 0x80); // delayed and empty: its first dot holds bit 6
        assertEquals("00000000000011" + "10000000000000", dotsOf(line0(bus), 0, 28));
    }

    @Test
    void anUndelayedByteCutsADelayedBitSixToOneDot() {
        FlatBus bus = new FlatBus();
        bus.write(0x2000, 0xC0); // delayed bit 6: dot 13, and dot 14 if the next byte holds it
        bus.write(0x2001, 0x00);
        assertEquals("00000000000001" + "00000000000000", dotsOf(line0(bus), 0, 28));
        bus.write(0x2001, 0x80);
        assertEquals("00000000000001" + "10000000000000", dotsOf(line0(bus), 0, 28));
    }

    @Test
    void aDelayedFirstByteHoldsBitSixOfTheByteFetchedBeforeTheLine() {
        FlatBus bus = new FlatBus();
        bus.write(0x2000, 0x80);
        assertEquals("0", dotsOf(line0(bus), 0, 1));
        bus.write(0x207F, 0x40); // the screen hole the scanner reads just before line 0
        assertEquals("1", dotsOf(line0(bus), 0, 1));
        bus.write(0x2000, 0x00); // undelayed: no hold
        assertEquals("0", dotsOf(line0(bus), 0, 1));
    }

    @Test
    void satherTopThirdExampleShowsAHalfDotAtTheLeftOfEachLine() {
        // Sather p. 8-22: 20 FOR A = $207F TO $3FFF STEP $80 : POKE A, $40
        FlatBus bus = new FlatBus();
        for (int a = 0x207F; a <= 0x3FFF; a += 0x80) {
            bus.write(a, 0x40);
        }
        for (int y = 0; y < 192; y++) {
            int rowBase = 0x2000 + (y & 7) * 0x400 + ((y >> 3) & 7) * 0x80 + (y >> 6) * 0x28;
            bus.write(rowBase, 0x80); // a delayed, empty first byte
        }
        boolean[][] dots = HiResRenderer.dots(bus, new VideoSoftSwitches());
        for (int y = 0; y < 192; y++) {
            assertEquals(y < 64, dots[y][0], "line " + y);
        }
    }

    @Test
    void satherDotTwoSeventyNineExtendsToTheLeftOfLineSixtyFour() {
        // Sather p. 8-22: "Dot (279,0) is extended to dot (0,64); dot (279,1) is extended to dot (0,65)"
        FlatBus bus = new FlatBus();
        bus.write(0x2027, 0x40); // pixel 279 of line 0
        bus.write(0x2028, 0x80); // line 64 starts with a delayed, empty byte
        bus.write(0x2428, 0x80); // line 65 likewise, with pixel 279 of line 1 off
        boolean[][] dots = HiResRenderer.dots(bus, new VideoSoftSwitches());
        assertEquals(true, dots[64][0]);
        assertEquals(false, dots[65][0]);
        bus.write(0x2427, 0x40);
        assertEquals(true, HiResRenderer.dots(bus, new VideoSoftSwitches())[65][0]);
    }

    @Test
    void theLeadInAddressIsWhatTheScannerFetchesJustBeforeEachLine() {
        for (boolean page2 : new boolean[] {false, true}) {
            VideoSoftSwitches video = new VideoSoftSwitches();
            video.write(0x0, 0); // graphics
            video.write(0x7, 0); // hi-res
            if (page2) {
                video.write(0x5, 0);
            }
            int pageBase = page2 ? 0x4000 : 0x2000;
            for (int y = 0; y < HiResRenderer.LINES; y++) {
                int rowBase = pageBase + (y & 7) * 0x400 + ((y >> 3) & 7) * 0x80 + (y >> 6) * 0x28;
                VideoScanner scanner = new VideoScanner(video);
                scanner.tick((y * VideoScanner.H_CLOCKS - 1 + VideoScanner.CYCLES_PER_FRAME)
                    % VideoScanner.CYCLES_PER_FRAME);
                assertEquals(scanner.currentAddress(), HiResRenderer.leadInAddress(rowBase),
                    "line " + y + (page2 ? ", page 2" : ""));
            }
        }
    }

    @Test
    void anIsolatedBitWithThePaletteBitIsBlueInColor() {
        FlatBus bus = new FlatBus();
        bus.write(0x2000, 0x81);
        assertEquals(GraphicsRenderer.PALETTE[6], HiResRenderer.render(bus, new VideoSoftSwitches())[0][0]);
    }

    @Test
    void dotsUseTheSameAddressingAsColor() {
        FlatBus bus = new FlatBus();
        bus.write(0x2000 + 0x28 + 0x80 + 0x400, 0x01); // line 73, column 0
        assertEquals("11", dotsOf(HiResRenderer.dots(bus, new VideoSoftSwitches())[73], 0, 2));
        assertEquals(GraphicsRenderer.PALETTE[3], HiResRenderer.render(bus, new VideoSoftSwitches())[73][0]);
    }
}
