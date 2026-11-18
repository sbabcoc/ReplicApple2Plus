package com.nordstrom.emulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Locks in how ScreenImage spreads 560 dots over 840 screen columns. */
class ScreenImageColumnsTest {

    private static final int LIT = 0xFFB000;
    private static final int HALF = 0x7F5800;

    @Test
    void equalDotPairsStayCrispCells() {
        int[] columns = ScreenImage.screenColumns(new int[] {LIT, LIT, 0, 0}, 3);
        assertArrayEquals(new int[] {LIT, LIT, LIT, 0, 0, 0}, columns);
    }

    @Test
    void aLoneDotLightsItsOwnColumnAndHalfTheOneItShares() {
        assertArrayEquals(new int[] {LIT, HALF, 0}, ScreenImage.screenColumns(new int[] {LIT, 0}, 3));
        assertArrayEquals(new int[] {0, HALF, LIT}, ScreenImage.screenColumns(new int[] {0, LIT}, 3));
    }

    @Test
    void anEvenScaleSplitsEachPixelBetweenItsDots() {
        assertArrayEquals(new int[] {LIT, 0}, ScreenImage.screenColumns(new int[] {LIT, 0}, 2));
    }
}
