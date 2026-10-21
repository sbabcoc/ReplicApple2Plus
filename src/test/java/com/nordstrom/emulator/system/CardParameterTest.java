package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardParameterTest {

    @Test
    void anIntegerMustBeAWholeNumberWithinRange() {
        CardParameter clock = CardParameter.integer("z80ClockHz", "Z80 clock (Hz)", 6_000_000, 1, Integer.MAX_VALUE);
        assertEquals("6000000", clock.defaultValue());
        assertTrue(clock.problem(" 4000000 ").isEmpty(), "surrounding spaces are ignored, as the loader trims them");
        assertTrue(clock.problem("0").isPresent());
        assertTrue(clock.problem("2147483648").isPresent());
        assertTrue(clock.problem("6 MHz").orElseThrow().contains("whole number from 1 to 2147483647"));
    }

    @Test
    void anIntegersDefaultMustBeWithinItsRange() {
        assertThrows(IllegalArgumentException.class, () -> CardParameter.integer("n", "N", 0, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> CardParameter.integer("n", "N", 5, 10, 1));
    }

    @Test
    void pathsAreLeftForTheCardToCheck() {
        CardParameter drive = CardParameter.file("drive1", "Drive 1 at startup", "woz", "dsk");
        assertEquals(List.of("woz", "dsk"), drive.extensions());
        assertTrue(drive.problem("no/such/disk.woz").isEmpty());
        assertTrue(CardParameter.folder("dir", "Start folder").problem("anything").isEmpty());
    }
}
