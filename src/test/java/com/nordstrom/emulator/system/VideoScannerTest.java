package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks in {@link VideoScanner}'s address computation against the
 * specific reference points confirmed when it was built (row 0, 1, 8,
 * 64 for both text/lores and hi-res addressing, independently checked
 * against a real, detailed row-address listing) and its genuinely
 * gap-free behavior across a complete frame, including blanking and
 * mixed mode -- the property that made it possible to wire real
 * floating-bus emulation in at all.
 */
class VideoScannerTest {

    private static final int CYCLES_PER_SCANLINE = 65;

    private static int addressAt(VideoSoftSwitches video, int line, int column) {
        VideoScanner scanner = new VideoScanner(video);
        scanner.tick(line * CYCLES_PER_SCANLINE + column);
        return scanner.currentAddress();
    }

    @Test
    void textModeRowAddressesMatchConfirmedReferencePoints() {
        VideoSoftSwitches video = new VideoSoftSwitches();
        video.write(0x1, 0); // TEXT on

        // Each text row spans 8 scanlines, so row N's first scanline is v_clock = N*8 --
        // unlike hi-res, where row and scanline are the same thing.
        assertEquals(0x400, addressAt(video, 0, 0));
        assertEquals(0x480, addressAt(video, 8, 0));
        assertEquals(0x428, addressAt(video, 64, 0));
        assertEquals(0x450, addressAt(video, 128, 0));
    }

    @Test
    void textModeColumnAddressesAreSequentialWithinARow() {
        VideoSoftSwitches video = new VideoSoftSwitches();
        video.write(0x1, 0); // TEXT on

        assertEquals(0x405, addressAt(video, 0, 5));
        assertEquals(0x427, addressAt(video, 0, 39));
    }

    @Test
    void textModePage2Offsets() {
        VideoSoftSwitches video = new VideoSoftSwitches();
        video.write(0x1, 0); // TEXT on
        video.write(0x5, 0); // PAGE2 on

        assertEquals(0x800, addressAt(video, 0, 0));
    }

    @Test
    void hiresModeRowAddressesMatchConfirmedReferencePoints() {
        VideoSoftSwitches video = new VideoSoftSwitches();
        video.write(0x0, 0); // TEXT off (graphics)
        video.write(0x7, 0); // HIRES on

        assertEquals(0x2000, addressAt(video, 0, 0));
        assertEquals(0x2400, addressAt(video, 1, 0));
        assertEquals(0x2080, addressAt(video, 8, 0));
        assertEquals(0x2028, addressAt(video, 64, 0));
    }

    @Test
    void hiresModePage2Offset() {
        VideoSoftSwitches video = new VideoSoftSwitches();
        video.write(0x0, 0);
        video.write(0x7, 0);
        video.write(0x5, 0); // PAGE2 on

        assertEquals(0x4000, addressAt(video, 0, 0));
    }

    @Test
    void loresModeUsesIdenticalAddressingToText() {
        VideoSoftSwitches video = new VideoSoftSwitches();
        video.write(0x0, 0); // TEXT off (graphics)
        video.write(0x6, 0); // HIRES off (lores)

        assertEquals(0x400, addressAt(video, 0, 0));
    }

    @Test
    void neverThrowsAcrossACompleteFrameInTextMode() {
        VideoSoftSwitches video = new VideoSoftSwitches();
        video.write(0x1, 0); // TEXT on
        VideoScanner scanner = new VideoScanner(video);

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 17_030; i++) {
                scanner.currentAddress();
                scanner.tick(1);
            }
        });
    }

    @Test
    void neverThrowsAcrossACompleteFrameInMixedMode() {
        VideoSoftSwitches video = new VideoSoftSwitches();
        video.write(0x0, 0); // graphics
        video.write(0x3, 0); // MIXED on
        video.write(0x7, 0); // HIRES on
        VideoScanner scanner = new VideoScanner(video);

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 17_030; i++) {
                scanner.currentAddress();
                scanner.tick(1);
            }
        });
    }

    @Test
    void wrapsToFrameStartAfterExactlyOneFullFrame() {
        VideoSoftSwitches video = new VideoSoftSwitches();
        video.write(0x1, 0); // TEXT on
        VideoScanner scanner = new VideoScanner(video);

        scanner.tick(17_030);

        assertEquals(0x400, scanner.currentAddress());
    }
}
