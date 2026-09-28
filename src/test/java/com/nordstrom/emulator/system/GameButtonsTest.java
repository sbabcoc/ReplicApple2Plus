package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameButtonsTest {

    @Test
    void buttonsStartReleased() {
        GameButtons buttons = new GameButtons();
        for (int b = 0; b < GameButtons.COUNT; b++) {
            assertEquals(0x00, buttons.read(b));
        }
    }

    @Test
    void aHeldButtonReportsBitSevenOnly() {
        GameButtons buttons = new GameButtons();
        buttons.setPressed(2, true);
        assertEquals(0x80, buttons.read(2));
    }

    @Test
    void buttonsAreIndependent() {
        GameButtons buttons = new GameButtons();
        buttons.setPressed(0, true);
        buttons.setPressed(2, true);
        buttons.setPressed(0, false);
        assertEquals(0x00, buttons.read(0));
        assertEquals(0x00, buttons.read(1));
        assertEquals(0x80, buttons.read(2));
    }

    @Test
    void anOutOfRangeButtonIsRejectedNotSilentlyIgnored() {
        GameButtons buttons = new GameButtons();
        assertThrows(IllegalArgumentException.class, () -> buttons.setPressed(3, true));
        assertThrows(IllegalArgumentException.class, () -> buttons.setPressed(-1, true));
        assertThrows(IllegalArgumentException.class, () -> buttons.read(3));
    }
}
