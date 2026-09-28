package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in GameIoReadHandler's offset dispatch: cassette at 0,
 * pushbuttons at 1-3, paddles at 4-7, and the upper half of the block
 * mirroring the lower. The last three used to throw a "not yet
 * implemented" gap, which stopped the whole machine the first time any
 * program read them.
 */
class GameIoReadHandlerTest {

    private static GameIoReadHandler handler(PaddleTimers paddles, GameButtons buttons) {
        return new GameIoReadHandler(paddles, buttons);
    }

    @Test
    void paddleOffsetsDispatchToTheCorrectChannel() {
        PaddleTimers paddles = new PaddleTimers();
        GameIoReadHandler handler = handler(paddles, new GameButtons());
        paddles.setPosition(0, 128);
        paddles.trigger();

        assertTrue((handler.read(4) & 0x80) != 0, "channel 0 (offset 4) should reflect the triggered position");
    }

    @Test
    void cassetteInputReadsAsNoSignalInsteadOfStoppingTheMachine() {
        GameIoReadHandler handler = handler(new PaddleTimers(), new GameButtons());
        assertEquals(0x00, handler.read(0));
    }

    @Test
    void pushbuttonsReadClearUntilPressed() {
        GameIoReadHandler handler = handler(new PaddleTimers(), new GameButtons());
        for (int offset = 1; offset <= 3; offset++) {
            assertEquals(0x00, handler.read(offset), "button at offset " + offset + " starts released");
        }
    }

    @Test
    void eachPushbuttonMapsToItsOwnOffset() {
        GameButtons buttons = new GameButtons();
        GameIoReadHandler handler = handler(new PaddleTimers(), buttons);

        for (int button = 0; button < 3; button++) {
            buttons.setPressed(button, true);
            for (int other = 0; other < 3; other++) {
                int expected = (other == button) ? 0x80 : 0x00;
                assertEquals(expected, handler.read(1 + other),
                    "with only button " + button + " held, offset " + (1 + other) + " (button " + other + ")");
            }
            buttons.setPressed(button, false);
        }
    }

    @Test
    void releasingAButtonClearsIt() {
        GameButtons buttons = new GameButtons();
        GameIoReadHandler handler = handler(new PaddleTimers(), buttons);
        buttons.setPressed(1, true);
        assertEquals(0x80, handler.read(2));
        buttons.setPressed(1, false);
        assertEquals(0x00, handler.read(2));
    }

    @Test
    void upperHalfOfTheBlockMirrorsTheLowerHalf() {
        PaddleTimers paddles = new PaddleTimers();
        GameButtons buttons = new GameButtons();
        GameIoReadHandler handler = handler(paddles, buttons);
        buttons.setPressed(0, true);
        paddles.setPosition(2, 200);
        paddles.trigger();

        for (int offset = 0; offset < 8; offset++) {
            assertEquals(handler.read(offset), handler.read(offset + 8),
                "offset " + (offset + 8) + " must read the same as offset " + offset);
        }
    }

    @Test
    void writesToAnyOffsetAreSilentlyIgnored() {
        GameIoReadHandler handler = handler(new PaddleTimers(), new GameButtons());
        for (int offset = 0; offset < 16; offset++) {
            int o = offset;
            assertDoesNotThrow(() -> handler.write(o, 0xFF), "write to offset " + o);
        }
    }
}
