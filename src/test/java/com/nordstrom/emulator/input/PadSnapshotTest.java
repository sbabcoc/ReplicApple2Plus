package com.nordstrom.emulator.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadSnapshotTest {

    @Test
    void noPadAndAPadAtRestAreDifferentStates() {
        assertFalse(PadSnapshot.ABSENT.isPresent());
        assertTrue(PadSnapshot.NEUTRAL.isPresent());
        assertFalse(PadSnapshot.ABSENT.equals(PadSnapshot.NEUTRAL),
            "the Apple II can tell them apart: an unplugged paddle reads 255, a centered one 128");
        assertFalse(PadSnapshot.NEUTRAL.equals(PadSnapshot.ABSENT));
    }

    @Test
    void anAbsentPadHasNothingPressedAndEverythingAtRest() {
        for (PadAxis axis : PadAxis.values()) {
            assertEquals(0f, PadSnapshot.ABSENT.axis(axis));
        }
        for (PadButton button : PadButton.values()) {
            assertFalse(PadSnapshot.ABSENT.pressed(button));
        }
    }

    @Test
    void anythingBuiltIsAConnectedPad() {
        assertTrue(PadSnapshot.builder().build().isPresent());
        assertTrue(PadSnapshot.builder().axis(PadAxis.LEFT_X, 1f).press(PadButton.A).build().isPresent());
        assertEquals(PadSnapshot.NEUTRAL, PadSnapshot.builder().build());
    }

    @Test
    void axisValuesAreClampedAndNanIsTreatedAsRest() {
        PadSnapshot s = PadSnapshot.builder()
            .axis(PadAxis.LEFT_X, 5f).axis(PadAxis.LEFT_Y, -5f).axis(PadAxis.RIGHT_X, Float.NaN).build();
        assertEquals(1f, s.axis(PadAxis.LEFT_X));
        assertEquals(-1f, s.axis(PadAxis.LEFT_Y));
        assertEquals(0f, s.axis(PadAxis.RIGHT_X));
    }

    @Test
    void aSnapshotIsUnaffectedByLaterUseOfItsBuilder() {
        PadSnapshot.Builder builder = PadSnapshot.builder().press(PadButton.A);
        PadSnapshot first = builder.build();
        builder.press(PadButton.B);
        assertFalse(first.pressed(PadButton.B), "snapshots are immutable");
        assertTrue(builder.build().pressed(PadButton.B));
    }
}
