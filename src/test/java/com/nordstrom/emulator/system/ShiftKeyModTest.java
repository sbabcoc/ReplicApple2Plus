package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shift-key mod's wirings, read the way a running program reads them:
 * through the full {@link MotherboardBus} address map. The input
 * assignments and the active-low polarity (bit 7 clear while the key is
 * held) follow MAME's {@code apple2} driver.
 */
class ShiftKeyModTest {

    @Test
    void notConnectedLeavesEveryInputToTheGameConnector() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            bus.shiftKeyMod().setShiftHeld(true);
            bus.shiftKeyMod().setControlHeld(true);
            assertEquals(0x80, bus.read(0xC063),
                "nothing wired and nothing plugged in: button 2 floats high, which shift-mod software reads as Shift up");
            bus.gameButtons().setPressed(2, false);
            assertEquals(0x00, bus.read(0xC063), "a plugged-in, unpressed button 2 still reads as the button");
            bus.gameButtons().setPressed(2, true);
            assertEquals(0x80, bus.read(0xC063));
        }
    }

    @Test
    void sw2ReadsShiftAtC063ActiveLow() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            bus.shiftKeyMod().setWiring(ShiftKeyMod.Wiring.SW2);
            assertEquals(0x80, bus.read(0xC063), "Shift up");
            bus.shiftKeyMod().setShiftHeld(true);
            assertEquals(0x00, bus.read(0xC063), "Shift held");
            assertEquals(0x00, bus.read(0xC06B), "the mirror at $C06B agrees");
            bus.shiftKeyMod().setShiftHeld(false);
            assertEquals(0x80, bus.read(0xC063), "Shift released");
        }
    }

    @Test
    void aWiredInputNoLongerReadsTheGameConnector() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            bus.shiftKeyMod().setWiring(ShiftKeyMod.Wiring.SW2);
            bus.gameButtons().setPressed(2, true);
            assertEquals(0x80, bus.read(0xC063), "button 2 pressed, Shift up: the Shift line wins");
            bus.gameButtons().setPressed(0, true);
            assertEquals(0x80, bus.read(0xC061), "button 0 is unaffected");
        }
    }

    @Test
    void sw2LeavesControlUnconnected() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            bus.shiftKeyMod().setWiring(ShiftKeyMod.Wiring.SW2);
            bus.shiftKeyMod().setControlHeld(true);
            assertEquals(0x80, bus.read(0xC063), "Control doesn't touch the Shift line");
            bus.gameButtons().setPressed(0, false);
            bus.gameButtons().setPressed(1, false);
            for (int address = 0xC061; address <= 0xC062; address++) {
                assertEquals(0x00, bus.read(address), "$" + Integer.toHexString(address) + " is still a button");
            }
        }
    }

    @Test
    void paddl2Paddl3ReadShiftAndControlDirectly() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            bus.shiftKeyMod().setWiring(ShiftKeyMod.Wiring.PADDL2_PADDL3);
            bus.read(0xC070); // trigger the paddle timers: an unplugged paddle would now read 0x80 forever
            assertEquals(0x80, bus.read(0xC066), "Shift up");
            assertEquals(0x80, bus.read(0xC067), "Control up");
            bus.shiftKeyMod().setShiftHeld(true);
            assertEquals(0x00, bus.read(0xC066), "Shift held");
            assertEquals(0x80, bus.read(0xC067), "Control still up");
            bus.shiftKeyMod().setControlHeld(true);
            assertEquals(0x00, bus.read(0xC067), "Control held");
            assertEquals(0x80, bus.read(0xC064), "paddle 0 is still a timer (unplugged, triggered)");
        }
    }

    @Test
    void sw0Sw1ReadShiftAndControl() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            bus.shiftKeyMod().setWiring(ShiftKeyMod.Wiring.SW0_SW1);
            assertEquals(0x80, bus.read(0xC061), "Shift up");
            assertEquals(0x80, bus.read(0xC062), "Control up");
            bus.shiftKeyMod().setShiftHeld(true);
            bus.shiftKeyMod().setControlHeld(true);
            assertEquals(0x00, bus.read(0xC061), "Shift held");
            assertEquals(0x00, bus.read(0xC062), "Control held");
            bus.gameButtons().setPressed(2, true);
            assertEquals(0x80, bus.read(0xC063), "button 2 is unaffected");
        }
    }

    @Test
    void rewiringReturnsTheOldInputToTheGameConnector() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            bus.gameButtons().setPressed(2, false);
            bus.shiftKeyMod().setWiring(ShiftKeyMod.Wiring.SW2);
            bus.shiftKeyMod().setShiftHeld(true);
            assertEquals(0x00, bus.read(0xC063));
            bus.shiftKeyMod().setWiring(ShiftKeyMod.Wiring.NOT_CONNECTED);
            assertEquals(0x00, bus.read(0xC063), "button 2 again, plugged in and unpressed");
            bus.gameButtons().setPressed(2, true);
            assertEquals(0x80, bus.read(0xC063), "button 2 again, pressed");
        }
    }
}
