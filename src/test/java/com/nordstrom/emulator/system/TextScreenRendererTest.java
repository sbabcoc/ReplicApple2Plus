package com.nordstrom.emulator.system;

import com.nordstrom.emulator.MemoryBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks in TextScreenRenderer's addressing, PAGE2 switching, and flash/inverse rendering rules. */
class TextScreenRendererTest {

    private static final class FlatBus implements MemoryBus {
        final int[] mem = new int[0x10000];
        public int read(int a) { return mem[a & 0xFFFF]; }
        public void write(int a, int v) { mem[a & 0xFFFF] = v & 0xFF; }
    }

    @Test
    void gridDimensionsAreCorrect() {
        FlatBus bus = new FlatBus();
        VideoSoftSwitches video = new VideoSoftSwitches();
        boolean[][] pixels = TextScreenRenderer.render(bus, video, true);
        assertEquals(192, pixels.length);
        assertEquals(280, pixels[0].length);
    }

    @Test
    void page2SwitchesWhichMemoryIsRead() {
        FlatBus bus = new FlatBus();
        VideoSoftSwitches video = new VideoSoftSwitches();
        bus.write(0x400, 'X' | 0x80);
        boolean[][] page1 = TextScreenRenderer.render(bus, video, true);

        video.write(0x5, 0); // PAGE2 on
        bus.write(0x800, 'Y' | 0x80);
        bus.write(0x400, ' ' | 0x80); // clear page 1's cell so any difference is unambiguous
        boolean[][] page2 = TextScreenRenderer.render(bus, video, true);

        boolean differs = false;
        for (int row = 0; row < 8 && !differs; row++) {
            for (int col = 0; col < 7 && !differs; col++) {
                if (page1[row][col] != page2[row][col]) differs = true;
            }
        }
        assertTrue(differs, "PAGE2 should change what's rendered");
    }

    @Test
    void flashRangeCharacterTogglesWithFlashVisible() {
        FlatBus bus = new FlatBus();
        VideoSoftSwitches video = new VideoSoftSwitches();
        bus.write(0x400, 0x41); // 'A' in flash range ($40-$7F)

        boolean[][] on = TextScreenRenderer.render(bus, video, true);
        boolean[][] off = TextScreenRenderer.render(bus, video, false);

        boolean differs = false;
        for (int row = 0; row < 8 && !differs; row++) {
            for (int col = 0; col < 7 && !differs; col++) {
                if (on[row][col] != off[row][col]) differs = true;
            }
        }
        assertTrue(differs, "flash-range character should differ between flashVisible true/false");
    }

    @Test
    void normalRangeCharacterIsUnaffectedByFlashToggle() {
        FlatBus bus = new FlatBus();
        VideoSoftSwitches video = new VideoSoftSwitches();
        bus.write(0x400, 0x41 | 0x80); // 'A' in normal range

        boolean[][] on = TextScreenRenderer.render(bus, video, true);
        boolean[][] off = TextScreenRenderer.render(bus, video, false);

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 7; col++) {
                assertEquals(on[row][col], off[row][col],
                    "normal-range character must not change with flash state");
            }
        }
    }

    @Test
    void inverseRangeCharacterProducesInvertedPixels() {
        FlatBus bus = new FlatBus();
        VideoSoftSwitches video = new VideoSoftSwitches();
        bus.write(0x400, 0x41 & 0x3F); // 'A' in inverse range ($00-$3F)
        bus.write(0x401, 0x41 | 0x80); // 'A' in normal range, for comparison

        boolean[][] pixels = TextScreenRenderer.render(bus, video, true);

        boolean anyDiffer = false;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 7; col++) {
                if (pixels[row][col] != pixels[row][col + 7]) {
                    anyDiffer = true;
                }
            }
        }
        assertTrue(anyDiffer, "inverse and normal renderings of the same letter should differ");
    }
}
