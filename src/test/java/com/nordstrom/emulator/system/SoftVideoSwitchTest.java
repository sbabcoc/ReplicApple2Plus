package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Soft Video Switch rule from Videx's schematic: 80 columns only when AN0 is high and graphics is off. */
class SoftVideoSwitchTest {

    private static final int GRAPHICS = 0x0, TEXT = 0x1, AN0_OFF = 0x8, AN0_ON = 0x9; // offsets from $C050

    private static boolean selects80(int textOrGraphics, int annunciator) {
        VideoSoftSwitches switches = new VideoSoftSwitches();
        switches.write(textOrGraphics, 0);
        switches.write(annunciator, 0);
        return switches.softVideoSwitchSelects80Columns();
    }

    @Test
    void eightyColumnsNeedBothAn0HighAndGraphicsOff() {
        assertEquals(true, selects80(TEXT, AN0_ON), "text mode, AN0 high ($C059): 80 columns");
        assertEquals(false, selects80(TEXT, AN0_OFF), "AN0 low ($C058): the Apple's video");
        assertEquals(false, selects80(GRAPHICS, AN0_ON), "graphics on: the Apple's video");
        assertEquals(false, selects80(GRAPHICS, AN0_OFF));
    }

    @Test
    void mixedModeCountsAsGraphicsOn() {
        VideoSoftSwitches switches = new VideoSoftSwitches();
        switches.write(GRAPHICS, 0);
        switches.write(0x3, 0); // $C053: mixed
        switches.write(AN0_ON, 0);
        assertEquals(false, switches.softVideoSwitchSelects80Columns());
    }
}
