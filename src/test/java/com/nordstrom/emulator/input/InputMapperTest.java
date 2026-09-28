package com.nordstrom.emulator.input;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The paddle math and change reporting. This is the one piece of input
 * code every platform shares, so it is where "feels the same everywhere"
 * is actually pinned down.
 */
class InputMapperTest {

    /** Records everything the mapper reports. */
    private static final class RecordingSink implements InputSink {
        final List<String> events = new ArrayList<>();
        @Override public void setPaddle(int channel, int position) { events.add("P" + channel + "=" + position); }
        @Override public void setButton(int button, boolean pressed) { events.add("B" + button + "=" + pressed); }
    }

    private static final float DEAD = 0.15f;
    private static final InputMapping.PaddleBinding STICK =
        new InputMapping.PaddleBinding(PadAxis.LEFT_X, false, null, null);

    private static int pos(InputMapping.PaddleBinding binding, PadSnapshot snapshot) {
        return InputMapper.position(binding, DEAD, snapshot);
    }

    private static PadSnapshot leftX(float value) {
        return PadSnapshot.builder().axis(PadAxis.LEFT_X, value).build();
    }

    // ---- paddle position math ----

    @Test
    void aStickAtRestIsCentered() {
        assertEquals(128, pos(STICK, PadSnapshot.NEUTRAL));
    }

    @Test
    void fullDeflectionReachesBothExtremes() {
        assertEquals(255, pos(STICK, leftX(1f)));
        assertEquals(0, pos(STICK, leftX(-1f)));
    }

    @Test
    void nothingRegistersInsideTheDeadZoneIncludingItsEdge() {
        assertEquals(128, pos(STICK, leftX(0.10f)));
        assertEquals(128, pos(STICK, leftX(-0.10f)));
        assertEquals(128, pos(STICK, leftX(DEAD)), "the edge itself is still inside");
    }

    @Test
    void travelOutsideTheDeadZoneIsRescaledSoThereIsNoJumpAtTheEdge() {
        // Halfway between the dead zone (0.15) and full (1.0) is 0.575, which
        // must land halfway between center and the extreme.
        assertEquals(191, pos(STICK, leftX(0.575f)), "halfway out on the high side");
        assertEquals(64, pos(STICK, leftX(-0.575f)), "halfway out on the low side");
        // Just past the edge must be barely off center, not a leap.
        int justOutside = pos(STICK, leftX(0.16f));
        assertTrue(justOutside >= 128 && justOutside <= 130, "just past the dead zone should be near center, was " + justOutside);
    }

    @Test
    void positionRisesMonotonicallyAcrossTheWholeStrokeAndStaysInRange() {
        int previous = -1;
        for (int i = -100; i <= 100; i++) {
            int p = pos(STICK, leftX(i / 100f));
            assertTrue(p >= 0 && p <= 255, "out of range at " + i + ": " + p);
            assertTrue(p >= previous, "went backwards at " + i + ": " + p + " after " + previous);
            previous = p;
        }
    }

    @Test
    void invertFlipsTheStick() {
        InputMapping.PaddleBinding inverted = new InputMapping.PaddleBinding(PadAxis.LEFT_X, true, null, null);
        assertEquals(0, pos(inverted, leftX(1f)));
        assertEquals(255, pos(inverted, leftX(-1f)));
        assertEquals(128, pos(inverted, PadSnapshot.NEUTRAL));
    }

    @Test
    void aHeldDpadButtonOverridesTheStickWithFullDeflection() {
        InputMapping.PaddleBinding withDpad =
            new InputMapping.PaddleBinding(PadAxis.LEFT_X, false, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT);
        assertEquals(0, pos(withDpad, PadSnapshot.builder().press(PadButton.DPAD_LEFT).build()));
        assertEquals(255, pos(withDpad, PadSnapshot.builder().press(PadButton.DPAD_RIGHT).build()));
        assertEquals(0, pos(withDpad, PadSnapshot.builder().axis(PadAxis.LEFT_X, 1f).press(PadButton.DPAD_LEFT).build()),
            "the D-pad wins over a stick pushed the other way");
    }

    @Test
    void bothDpadButtonsTogetherCancelToCenterEvenWithTheStickPushed() {
        InputMapping.PaddleBinding withDpad =
            new InputMapping.PaddleBinding(PadAxis.LEFT_X, false, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT);
        assertEquals(128, pos(withDpad, PadSnapshot.builder().axis(PadAxis.LEFT_X, 1f)
            .press(PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT).build()));
    }

    @Test
    void aPaddleWithNeitherStickNorDpadStaysCentered() {
        InputMapping.PaddleBinding none = new InputMapping.PaddleBinding(null, false, null, null);
        assertEquals(128, pos(none, PadSnapshot.builder().axis(PadAxis.LEFT_X, 1f).press(PadButton.A).build()));
    }

    @Test
    void aZeroDeadZoneRegistersEvenTinyMovement() {
        assertEquals(129, InputMapper.position(STICK, 0f, leftX(0.01f)));
    }

    // ---- change reporting ----

    private static InputMapper mapperWithDefaults(RecordingSink sink) {
        return new InputMapper(InputMapping.defaults(), sink);
    }

    @Test
    void theFirstSnapshotReportsEverythingEvenAtRest() {
        RecordingSink sink = new RecordingSink();
        mapperWithDefaults(sink).apply(PadSnapshot.NEUTRAL);
        assertEquals(List.of("P0=128", "P1=128", "P2=128", "P3=128", "B0=false", "B1=false", "B2=false"), sink.events);
    }

    @Test
    void anUnchangedSnapshotReportsNothing() {
        RecordingSink sink = new RecordingSink();
        InputMapper mapper = mapperWithDefaults(sink);
        mapper.apply(PadSnapshot.NEUTRAL);
        sink.events.clear();
        mapper.apply(PadSnapshot.NEUTRAL);
        assertEquals(List.of(), sink.events);
    }

    @Test
    void onlyTheChangedInputIsReported() {
        RecordingSink sink = new RecordingSink();
        InputMapper mapper = mapperWithDefaults(sink);
        mapper.apply(PadSnapshot.NEUTRAL);
        sink.events.clear();

        mapper.apply(PadSnapshot.builder().axis(PadAxis.RIGHT_Y, 1f).build());
        assertEquals(List.of("P3=255"), sink.events);
    }

    @Test
    void tinyStickNoiseInsideTheDeadZoneProducesNoTraffic() {
        RecordingSink sink = new RecordingSink();
        InputMapper mapper = mapperWithDefaults(sink);
        mapper.apply(PadSnapshot.NEUTRAL);
        sink.events.clear();
        for (float noise : new float[]{0.02f, -0.03f, 0.05f, -0.01f, 0.10f}) {
            mapper.apply(leftX(noise));
        }
        assertEquals(List.of(), sink.events);
    }

    // ---- default mapping, end to end through the mapper ----

    private static List<String> reportFor(PadSnapshot snapshot) {
        RecordingSink sink = new RecordingSink();
        InputMapper mapper = mapperWithDefaults(sink);
        mapper.apply(PadSnapshot.NEUTRAL);
        sink.events.clear();
        mapper.apply(snapshot);
        return sink.events;
    }

    @Test
    void theDefaultFireButtonsAreTheRightTriggerAndA() {
        assertEquals(List.of("B0=true"), reportFor(PadSnapshot.builder().press(PadButton.RIGHT_TRIGGER).build()));
        assertEquals(List.of("B0=true"), reportFor(PadSnapshot.builder().press(PadButton.A).build()));
    }

    @Test
    void theOtherDefaultButtons() {
        assertEquals(List.of("B1=true"), reportFor(PadSnapshot.builder().press(PadButton.LEFT_TRIGGER).build()));
        assertEquals(List.of("B1=true"), reportFor(PadSnapshot.builder().press(PadButton.B).build()));
        assertEquals(List.of("B2=true"), reportFor(PadSnapshot.builder().press(PadButton.X).build()));
    }

    @Test
    void unassignedButtonsDoNothing() {
        assertEquals(List.of(), reportFor(PadSnapshot.builder()
            .press(PadButton.Y, PadButton.LEFT_BUMPER, PadButton.RIGHT_BUMPER, PadButton.START, PadButton.BACK).build()));
    }

    @Test
    void aButtonWithSeveralSourcesStaysDownUntilTheLastIsReleased() {
        RecordingSink sink = new RecordingSink();
        InputMapper mapper = mapperWithDefaults(sink);
        mapper.apply(PadSnapshot.NEUTRAL);
        sink.events.clear();

        mapper.apply(PadSnapshot.builder().press(PadButton.A).build());
        mapper.apply(PadSnapshot.builder().press(PadButton.A, PadButton.RIGHT_TRIGGER).build());
        mapper.apply(PadSnapshot.builder().press(PadButton.RIGHT_TRIGGER).build());
        assertEquals(List.of("B0=true"), sink.events, "one press event, no flicker while a second source joins and the first leaves");
        mapper.apply(PadSnapshot.NEUTRAL);
        assertEquals(List.of("B0=true", "B0=false"), sink.events);
    }

    @Test
    void theDefaultSticksAndDpadDriveTheDefaultPaddles() {
        assertEquals(List.of("P0=0"), reportFor(PadSnapshot.builder().axis(PadAxis.LEFT_X, -1f).build()));
        assertEquals(List.of("P0=0"), reportFor(PadSnapshot.builder().press(PadButton.DPAD_LEFT).build()));
        assertEquals(List.of("P1=255"), reportFor(PadSnapshot.builder().press(PadButton.DPAD_DOWN).build()));
        assertEquals(List.of("P2=255"), reportFor(PadSnapshot.builder().axis(PadAxis.RIGHT_X, 1f).build()));
        assertEquals(List.of("P3=0"), reportFor(PadSnapshot.builder().axis(PadAxis.RIGHT_Y, -1f).build()));
    }

    @Test
    void theDefaultRightStickHasNoDpadOverride() {
        assertEquals(List.of(), reportFor(PadSnapshot.builder().press(PadButton.DPAD_LEFT, PadButton.DPAD_UP).build())
            .stream().filter(e -> e.startsWith("P2") || e.startsWith("P3")).toList());
    }
}
