package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mixed mode shows the bottom four text rows (scan lines 160-191) as text,
 * whichever graphics mode fills the top 160 lines -- lo-res as well as
 * hi-res. Applesoft's GR relies on it: it selects lo-res mixed mode so
 * commands and the prompt stay readable at the bottom of the screen.
 */
class ScanlineModesMixedModeTest {

    // offsets from $C050
    private static final int GRAPHICS = 0x0, FULL_SCREEN = 0x2, MIXED = 0x3, LORES = 0x6, HIRES = 0x7;

    private static ScanlineModes.LineMode[] frameWith(int... switchOffsets) {
        VideoSoftSwitches switches = new VideoSoftSwitches();
        for (int offset : switchOffsets) {
            switches.write(offset, 0);
        }
        ScanlineModes modes = new ScanlineModes(switches);
        for (int i = 0; i < 3 * VideoScanner.CYCLES_PER_FRAME; i += 65) {
            modes.tick(65); // a few whole frames, one scan line's worth of cycles at a time
        }
        return modes.completedFrame();
    }

    private static void assertLines(ScanlineModes.LineMode[] frame, int from, int to, ScanlineModes.Source expected) {
        for (int line = from; line <= to; line++) {
            assertEquals(expected, frame[line].source(), "scan line " + line);
        }
    }

    @Test
    void loResMixedModeShowsTextOnTheBottomFourRows() {
        ScanlineModes.LineMode[] frame = frameWith(GRAPHICS, LORES, MIXED); // what GR selects
        assertLines(frame, 0, 159, ScanlineModes.Source.LORES);
        assertLines(frame, 160, 191, ScanlineModes.Source.TEXT);
    }

    @Test
    void hiResMixedModeShowsTextOnTheBottomFourRows() {
        ScanlineModes.LineMode[] frame = frameWith(GRAPHICS, HIRES, MIXED); // what HGR selects
        assertLines(frame, 0, 159, ScanlineModes.Source.HIRES);
        assertLines(frame, 160, 191, ScanlineModes.Source.TEXT);
    }

    @Test
    void fullScreenGraphicsHasNoTextRows() {
        assertLines(frameWith(GRAPHICS, LORES, FULL_SCREEN), 0, 191, ScanlineModes.Source.LORES);
        assertLines(frameWith(GRAPHICS, HIRES, FULL_SCREEN), 0, 191, ScanlineModes.Source.HIRES);
    }
}
