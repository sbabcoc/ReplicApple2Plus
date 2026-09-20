package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks in GameIoReadHandler's offset dispatch: paddle reads at 4-7, named gaps elsewhere. */
class GameIoReadHandlerTest {

    @Test
    void paddleOffsetsDispatchToTheCorrectChannel() {
        PaddleTimers paddles = new PaddleTimers();
        GameIoReadHandler handler = new GameIoReadHandler(paddles);
        paddles.setPosition(0, 128);
        paddles.trigger();

        assertTrue((handler.read(4) & 0x80) != 0, "channel 0 (offset 4) should reflect the triggered position");
    }

    @Test
    void writeToPaddleOffsetIsSilentlyIgnored() {
        PaddleTimers paddles = new PaddleTimers();
        GameIoReadHandler handler = new GameIoReadHandler(paddles);
        assertDoesNotThrow(() -> handler.write(4, 0));
    }

    @Test
    void cassetteOffsetThrowsItsOwnNamedGap() {
        GameIoReadHandler handler = new GameIoReadHandler(new PaddleTimers());
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> handler.read(0));
        assertTrue(e.getMessage().contains("cassette"));
    }

    @Test
    void pushbuttonOffsetsThrowTheirOwnNamedGap() {
        GameIoReadHandler handler = new GameIoReadHandler(new PaddleTimers());
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> handler.read(2));
        assertTrue(e.getMessage().contains("pushbutton"));
    }

    @Test
    void unusedOffsetsThrowTheirOwnNamedGap() {
        GameIoReadHandler handler = new GameIoReadHandler(new PaddleTimers());
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> handler.read(10));
        assertTrue(e.getMessage().contains("unused"));
    }

    @Test
    void writesToNonPaddleOffsetsAlsoThrow() {
        GameIoReadHandler handler = new GameIoReadHandler(new PaddleTimers());
        assertThrows(UnsupportedOperationException.class, () -> handler.write(0, 0));
    }
}
