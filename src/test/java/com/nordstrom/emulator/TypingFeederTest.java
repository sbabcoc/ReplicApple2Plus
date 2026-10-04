package com.nordstrom.emulator;

import com.nordstrom.emulator.system.KeyboardRegister;
import com.nordstrom.emulator.system.KeyboardTestAccess;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypingFeederTest {

    @Test
    void everyLineEndingStyleBecomesExactlyOneReturn() {
        assertArrayEquals(new int[] {'A', 0x0D, 'B', 0x0D, 'C', 0x0D, 'D'},
            TypingFeeder.toKeystrokes("A\r\nB\nC\rD"));
        assertArrayEquals(new int[] {0x0D, 0x0D}, TypingFeeder.toKeystrokes("\n\n"),
            "consecutive line endings are separate Returns -- only \\r\\n pairs collapse");
    }

    @Test
    void textIsMappedExactlyLikeTypedText() {
        // Lowercase folds to uppercase; tab and non-ASCII are skipped, the same as KeyboardMapper does for live typing.
        assertArrayEquals(new int[] {'P', 'R', 'I', 'N', 'T', ' ', '"', 'H', 'I', '"'},
            TypingFeeder.toKeystrokes("print\t \"hi\u00e9\""));
    }

    /**
     * The core property: with software reading the keyboard at irregular,
     * sometimes long intervals, every character still arrives exactly once
     * and in order -- the feeder never loads a key while the previous one
     * is unacknowledged, so nothing is overwritten.
     */
    @Test
    void strobeGatingDeliversEveryCharacterInOrderAtAnyReadPace() {
        String text = "10 PRINT \"HELLO\"\n20 GOTO 10\nRUN\n";
        int[] expected = TypingFeeder.toKeystrokes(text);

        KeyboardRegister keyboard = new KeyboardRegister();
        TypingFeeder feeder = new TypingFeeder(keyboard);
        feeder.enqueue(text);
        assertTrue(feeder.isTyping());

        Random random = new Random(7);
        int[] received = new int[expected.length];
        int count = 0;
        for (int step = 0; step < 100_000 && count < expected.length; step++) {
            feeder.tick(1);
            if (random.nextInt(50) == 0) { // "software" polls only occasionally
                int kbd = KeyboardTestAccess.readKbd(keyboard);
                if ((kbd & 0x80) != 0) {
                    received[count++] = kbd & 0x7F;
                    KeyboardTestAccess.clearStrobe(keyboard);
                }
            }
        }
        assertArrayEquals(expected, received);
        assertFalse(feeder.isTyping(), "nothing should remain queued once everything is typed");
    }

    @Test
    void nothingIsLoadedWhileAKeyIsStillUnacknowledged() {
        KeyboardRegister keyboard = new KeyboardRegister();
        keyboard.keyPressed('X'); // a live keypress nobody has read yet
        TypingFeeder feeder = new TypingFeeder(keyboard);
        feeder.enqueue("AB");
        for (int i = 0; i < 1000; i++) {
            feeder.tick(1);
        }
        assertEquals('X' | 0x80, KeyboardTestAccess.readKbd(keyboard), "the unread key must not be overwritten");
        KeyboardTestAccess.clearStrobe(keyboard);
        feeder.tick(1);
        assertEquals('A' | 0x80, KeyboardTestAccess.readKbd(keyboard), "the next key loads as soon as the strobe clears");
    }

    @Test
    void cancelDiscardsWhatHasNotBeenTyped() {
        KeyboardRegister keyboard = new KeyboardRegister();
        TypingFeeder feeder = new TypingFeeder(keyboard);
        feeder.enqueue("ABC");
        feeder.tick(1); // 'A' loads
        feeder.cancel();
        assertFalse(feeder.isTyping());
        KeyboardTestAccess.clearStrobe(keyboard);
        for (int i = 0; i < 100; i++) {
            feeder.tick(1);
        }
        assertFalse(keyboard.isStrobeSet(), "B and C must never be typed after a cancel");
        assertEquals('A', KeyboardTestAccess.readKbd(keyboard), "the key already in the latch stays there");
    }

    @Test
    void laterTextQueuesBehindEarlierText() {
        KeyboardRegister keyboard = new KeyboardRegister();
        TypingFeeder feeder = new TypingFeeder(keyboard);
        feeder.enqueue("A");
        feeder.enqueue("B");
        StringBuilder received = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            feeder.tick(1);
            if (keyboard.isStrobeSet()) {
                received.append((char) (KeyboardTestAccess.readKbd(keyboard) & 0x7F));
                KeyboardTestAccess.clearStrobe(keyboard);
            }
        }
        assertEquals("AB", received.toString());
    }
}
