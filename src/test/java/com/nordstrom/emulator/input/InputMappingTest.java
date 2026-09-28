package com.nordstrom.emulator.input;

import com.nordstrom.emulator.system.IniFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputMappingTest {

    private static InputMapping custom(String... lines) {
        return InputMapping.fromSections(IniFile.parse(List.of(lines)));
    }

    private static String errorFor(String... lines) {
        return assertThrows(IllegalArgumentException.class, () -> custom(lines)).getMessage();
    }

    // ---- defaults: pins down what "the agreed layout" actually is ----

    @Test
    void theDefaultsAreTheAgreedLayout() {
        InputMapping m = InputMapping.defaults();
        assertEquals(0.15f, m.deadZone());

        assertEquals(new InputMapping.PaddleBinding(PadAxis.LEFT_X, false, PadButton.DPAD_LEFT, PadButton.DPAD_RIGHT), m.paddle(0));
        assertEquals(new InputMapping.PaddleBinding(PadAxis.LEFT_Y, false, PadButton.DPAD_UP, PadButton.DPAD_DOWN), m.paddle(1));
        assertEquals(new InputMapping.PaddleBinding(PadAxis.RIGHT_X, false, null, null), m.paddle(2));
        assertEquals(new InputMapping.PaddleBinding(PadAxis.RIGHT_Y, false, null, null), m.paddle(3));

        assertEquals(Set.of(PadButton.RIGHT_TRIGGER, PadButton.A), m.button(0));
        assertEquals(Set.of(PadButton.LEFT_TRIGGER, PadButton.B), m.button(1));
        assertEquals(Set.of(PadButton.X), m.button(2));
    }

    // ---- layering over the defaults ----

    @Test
    void aCustomFileOnlyChangesWhatItMentions() {
        InputMapping m = custom("[input]", "deadzone = 0.3");
        assertEquals(0.3f, m.deadZone());
        assertEquals(PadAxis.LEFT_X, m.paddle(0).axis(), "unmentioned settings keep their defaults");
        assertEquals(Set.of(PadButton.X), m.button(2));
    }

    @Test
    void mentioningOneKeyLeavesTheSiblingKeysAtTheirDefaults() {
        InputMapping.PaddleBinding p = custom("[paddle1]", "invert = true").paddle(1);
        assertTrue(p.invert());
        assertEquals(PadAxis.LEFT_Y, p.axis());
        assertEquals(PadButton.DPAD_UP, p.dpadLow());
    }

    @Test
    void anEmptyValueClearsABindingInsteadOfKeepingTheDefault() {
        assertEquals(Set.of(), custom("[button0]", "pad =").button(0));
        assertNull(custom("[paddle0]", "dpad =").paddle(0).dpadLow());
        assertNull(custom("[paddle2]", "axis =").paddle(2).axis());
    }

    @Test
    void padNamesAreCaseInsensitiveAndListsMayBeLong() {
        InputMapping m = custom("[button2]", "pad = y, Start , left_bumper", "[paddle3]", "axis = left_y");
        assertEquals(Set.of(PadButton.Y, PadButton.START, PadButton.LEFT_BUMPER), m.button(2));
        assertEquals(PadAxis.LEFT_Y, m.paddle(3).axis());
    }

    @Test
    void aFileCanRewireAPaddleCompletely() {
        InputMapping.PaddleBinding p = custom("[paddle0]", "axis = RIGHT_X", "invert = true", "dpad = DPAD_UP, DPAD_DOWN").paddle(0);
        assertEquals(new InputMapping.PaddleBinding(PadAxis.RIGHT_X, true, PadButton.DPAD_UP, PadButton.DPAD_DOWN), p);
    }

    // ---- strictness: a typo must never be a silent no-op ----

    @Test
    void anUnknownSectionIsRejectedAndValidOnesAreListed() {
        String message = errorFor("[padle0]", "axis = LEFT_X");
        assertTrue(message.contains("Unknown section [padle0]"), message);
        assertTrue(message.contains("paddle0") && message.contains("button2") && message.contains("input"), message);
    }

    @Test
    void anUnknownKeyIsRejectedNamingTheSectionAndTheValidKeys() {
        String message = errorFor("[paddle0]", "axiz = LEFT_X");
        assertTrue(message.contains("Unknown key \"axiz\" in [paddle0]"), message);
        assertTrue(message.contains("axis") && message.contains("invert") && message.contains("dpad"), message);
    }

    @Test
    void aKeyIsCaseSensitiveSoAMisCasedOneIsCaughtNotIgnored() {
        assertTrue(errorFor("[input]", "DeadZone = 0.2").contains("Unknown key \"DeadZone\""));
    }

    @Test
    void aBadAxisNameListsTheValidOnes() {
        String message = errorFor("[paddle0]", "axis = LEFT_Z");
        assertTrue(message.contains("\"LEFT_Z\" is not a valid axis name"), message);
        assertTrue(message.contains("LEFT_X") && message.contains("RIGHT_Y"), message);
    }

    @Test
    void aBadButtonNameListsTheValidOnes() {
        String message = errorFor("[button0]", "pad = A, TURBO");
        assertTrue(message.contains("\"TURBO\" is not a valid button name"), message);
        assertTrue(message.contains("DPAD_UP") && message.contains("RIGHT_TRIGGER"), message);
    }

    @Test
    void aBadDeadZoneIsRejected() {
        assertTrue(errorFor("[input]", "deadzone = wide").contains("must be a number"));
        assertTrue(errorFor("[input]", "deadzone = 1.0").contains("less than 1.0"));
        assertTrue(errorFor("[input]", "deadzone = -0.1").contains("at least 0.0"));
    }

    @Test
    void anInvertValueThatIsNotTrueOrFalseIsRejected() {
        assertTrue(errorFor("[paddle1]", "invert = yes").contains("true or false"));
    }

    @Test
    void aDpadNeedsExactlyTwoButtons() {
        assertTrue(errorFor("[paddle0]", "dpad = DPAD_LEFT").contains("exactly two"));
        assertTrue(errorFor("[paddle0]", "dpad = DPAD_LEFT, DPAD_RIGHT, DPAD_UP").contains("exactly two"));
    }

    @Test
    void aZeroDeadZoneIsAllowed() {
        assertEquals(0f, custom("[input]", "deadzone = 0").deadZone());
    }

    // ---- loading a real file ----

    @Test
    void loadReadsARealFileAndLayersItOverTheDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("mine.ini");
        Files.write(file, List.of("; my layout", "[input]", "deadzone = 0.25", "[button0]", "pad = START"));
        InputMapping m = InputMapping.load(file);
        assertEquals(0.25f, m.deadZone());
        assertEquals(Set.of(PadButton.START), m.button(0));
        assertEquals(Set.of(PadButton.X), m.button(2), "and the rest is still the defaults");
    }

    @Test
    void loadingAMissingFileIsAnIoErrorNotSilentDefaults(@TempDir Path dir) {
        assertThrows(NoSuchFileException.class, () -> InputMapping.load(dir.resolve("nope.ini")));
    }
}
