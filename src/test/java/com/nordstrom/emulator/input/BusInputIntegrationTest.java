package com.nordstrom.emulator.input;

import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pad snapshot in, machine behavior out: a real {@link MotherboardBus}
 * driven through the real mapper and sink, read back the way a program
 * reads a game connector -- by absolute address. This is the check that
 * the whole chain, not just each link, does what the mapping says.
 */
class BusInputIntegrationTest {

    /** Triggers the paddle strobe and counts cycles until the channel's bit 7 clears. */
    private static int cyclesUntilExpired(MotherboardBus bus, int channel) {
        bus.write(0xC070, 0);
        int cycles = 0;
        while ((bus.read(0xC064 + channel) & 0x80) != 0) {
            bus.paddleTimers().tick(1);
            if (++cycles > 10_000) {
                throw new AssertionError("paddle " + channel + " never expired");
            }
        }
        return cycles;
    }

    /** Triggers the strobe and reports whether the channel's timer ever trips within {@code limit} cycles. */
    private static boolean tripsWithin(MotherboardBus bus, int channel, int limit) {
        bus.write(0xC070, 0);
        for (int cycles = 0; cycles < limit; cycles++) {
            if ((bus.read(0xC064 + channel) & 0x80) == 0) {
                return true;
            }
            bus.paddleTimers().tick(1);
        }
        return false;
    }

    private static InputMapper mapperFor(MotherboardBus bus) {
        return new InputMapper(InputMapping.defaults(), new BusInputSink(bus, Runnable::run));
    }

    @Test
    void buttonsReachTheirAbsoluteAddresses() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            InputMapper mapper = mapperFor(bus);
            mapper.apply(PadSnapshot.NEUTRAL);
            assertEquals(0x00, bus.read(0xC061));

            mapper.apply(PadSnapshot.builder().press(PadButton.RIGHT_TRIGGER).build());
            assertEquals(0x80, bus.read(0xC061), "right trigger is fire, button 0");
            assertEquals(0x00, bus.read(0xC062));
            assertEquals(0x00, bus.read(0xC063));

            mapper.apply(PadSnapshot.builder().press(PadButton.B, PadButton.X).build());
            assertEquals(0x00, bus.read(0xC061));
            assertEquals(0x80, bus.read(0xC062), "B is button 1");
            assertEquals(0x80, bus.read(0xC063), "X is button 2");
        }
    }

    @Test
    void thePaddleTimeScalesWithTheStickAcrossItsWholeRange() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            InputMapper mapper = mapperFor(bus);

            mapper.apply(PadSnapshot.builder().axis(PadAxis.LEFT_X, -1f).build());
            int left = cyclesUntilExpired(bus, 0);
            mapper.apply(PadSnapshot.NEUTRAL);
            int center = cyclesUntilExpired(bus, 0);
            mapper.apply(PadSnapshot.builder().axis(PadAxis.LEFT_X, 1f).build());
            int right = cyclesUntilExpired(bus, 0);

            assertEquals(0, left, "full left is position 0, expired the instant it is read");
            assertTrue(right > 2000, "full right takes the full timer, was " + right);
            assertTrue(Math.abs(center - right / 2) <= 10, "center is halfway: center=" + center + " right=" + right);
        }
    }

    @Test
    void aMachineWithNoPadTheTimersNeverTripLikeAnEmptyGamePort() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            for (int channel = 0; channel < 4; channel++) {
                assertFalse(tripsWithin(bus, channel, 20_000), "channel " + channel + " untouched, nothing plugged in");
            }
            mapperFor(bus).apply(PadSnapshot.ABSENT);
            for (int channel = 0; channel < 4; channel++) {
                assertFalse(tripsWithin(bus, channel, 20_000), "channel " + channel + " after an explicit 'no pad'");
            }
        }
    }

    @Test
    void connectingAPadAtRestPlugsInACenteredJoystickAndDisconnectingItUnplugsItAgain() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            InputMapper mapper = mapperFor(bus);
            mapper.apply(PadSnapshot.ABSENT);
            assertFalse(tripsWithin(bus, 0, 20_000));

            mapper.apply(PadSnapshot.NEUTRAL);
            int center = cyclesUntilExpired(bus, 0);
            assertTrue(center > 1000 && center < 1800, "a pad at rest should read mid-scale, was " + center);

            mapper.apply(PadSnapshot.ABSENT);
            assertFalse(tripsWithin(bus, 0, 20_000), "unplugged again once the pad is gone");
        }
    }

    @Test
    void aParticularPaddleLeftUnboundStaysUnpluggedWhileTheOthersWork() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            InputMapping oneJoystick = InputMapping.fromSections(com.nordstrom.emulator.system.IniFile.parse(java.util.List.of(
                "[paddle2]", "axis =", "[paddle3]", "axis =")));
            new InputMapper(oneJoystick, new BusInputSink(bus, Runnable::run)).apply(PadSnapshot.NEUTRAL);

            assertTrue(tripsWithin(bus, 0, 20_000), "paddle 0 is the joystick's X");
            assertTrue(tripsWithin(bus, 1, 20_000), "paddle 1 is the joystick's Y");
            assertFalse(tripsWithin(bus, 2, 20_000), "paddle 2 has nothing attached");
            assertFalse(tripsWithin(bus, 3, 20_000), "paddle 3 has nothing attached");
        }
    }

    @Test
    void theFourPaddlesAreIndependent() {
        try (MotherboardBus bus = MotherboardBus.withoutAudio(new SlotCard[8])) {
            InputMapper mapper = mapperFor(bus);
            mapper.apply(PadSnapshot.builder().axis(PadAxis.RIGHT_Y, 1f).build());
            int rightY = cyclesUntilExpired(bus, 3);
            int leftX = cyclesUntilExpired(bus, 0);
            assertTrue(rightY > 2000, "paddle 3 follows the right stick's Y, was " + rightY);
            assertTrue(Math.abs(leftX - rightY / 2) <= 10, "paddle 0 stayed centered: " + leftX);
        }
    }
}
