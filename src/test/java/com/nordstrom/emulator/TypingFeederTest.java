package com.nordstrom.emulator;

import com.nordstrom.emulator.system.KeyboardRegister;
import com.nordstrom.emulator.system.KeyboardTestAccess;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * The typing progress window opens and closes on these notifications, so
     * each change must be reported exactly once: typing starts on enqueue, stops
     * when the last key loads or on cancel, and nothing in between repeats.
     */
    @Test
    void reportsEachStartAndStopOfTypingOnce() {
        KeyboardRegister keyboard = new KeyboardRegister();
        TypingFeeder feeder = new TypingFeeder(keyboard);
        List<Boolean> changes = new ArrayList<>();
        feeder.setTypingListener(changes::add);

        feeder.enqueue("AB");
        feeder.enqueue("C"); // already typing: no new notification
        for (int i = 0; i < 3; i++) {
            feeder.tick(1);
            KeyboardTestAccess.clearStrobe(keyboard);
        }
        assertEquals(List.of(true, false), changes, "start on enqueue, stop when the last key loads");

        feeder.enqueue("DE");
        feeder.cancel();
        feeder.cancel(); // already stopped: no new notification
        assertEquals(List.of(true, false, true, false), changes, "start on enqueue, stop on cancel");
    }

    /** Progress counts cover one run of typing, including text queued while it runs. */
    @Test
    void countsProgressForEachRunOfTyping() {
        KeyboardRegister keyboard = new KeyboardRegister();
        TypingFeeder feeder = new TypingFeeder(keyboard);
        feeder.enqueue("ABC");
        feeder.tick(1);
        KeyboardTestAccess.clearStrobe(keyboard);
        feeder.enqueue("DE"); // joins the run in progress
        assertEquals(1, feeder.typedCount());
        assertEquals(5, feeder.queuedCount());

        for (int i = 0; i < 4; i++) {
            feeder.tick(1);
            KeyboardTestAccess.clearStrobe(keyboard);
        }
        assertEquals(5, feeder.typedCount());

        feeder.enqueue("XY"); // a new run starts the counts over
        assertEquals(0, feeder.typedCount());
        assertEquals(2, feeder.queuedCount());
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

    /** Raw keystrokes reach the keyboard as given -- ^C included, which typed text can't express. */
    @Test
    void queuesRawKeystrokes() {
        KeyboardRegister keyboard = new KeyboardRegister();
        TypingFeeder feeder = new TypingFeeder(keyboard);
        feeder.enqueue("\u0003"); // skipped as text
        assertEquals(0, feeder.queuedCount());
        feeder.enqueueKeys(0x03);
        assertTrue(feeder.isTyping());
        feeder.tick(1);
        assertEquals(0x83, KeyboardTestAccess.readKbd(keyboard) & 0xFF, "^C with the strobe set");
    }

    /** Reads keys as fast as they come, like CP/M's server filling its type-ahead ring. */
    private static String drain(TypingFeeder feeder, KeyboardRegister keyboard, int cycles, int step) {
        StringBuilder received = new StringBuilder();
        for (int elapsed = 0; elapsed < cycles; elapsed += step) {
            feeder.tick(step);
            if (keyboard.isStrobeSet()) {
                received.append((char) (KeyboardTestAccess.readKbd(keyboard) & 0x7F));
                KeyboardTestAccess.clearStrobe(keyboard);
            }
        }
        return received.toString();
    }

    /**
     * After each Return, the next line waits until the screen has stayed
     * unchanged for SETTLE_CYCLES, so a reader that buffers keys never
     * holds more than one line.
     */
    @Test
    void waitsForTheScreenToSettleBetweenLines() {
        KeyboardRegister keyboard = new KeyboardRegister();
        TypingFeeder feeder = new TypingFeeder(keyboard);
        int[] screen = {0};
        feeder.setScreenSignature(() -> screen[0]);
        feeder.enqueue("AB\nCD\n");

        assertEquals("AB\r", drain(feeder, keyboard, 5_000, 100), "the first line, then a wait");
        screen[0] = 1; // the program echoes the line
        assertEquals("", drain(feeder, keyboard, TypingFeeder.SETTLE_CYCLES / 2, 100), "still changing recently");
        assertEquals("CD\r", drain(feeder, keyboard, TypingFeeder.SETTLE_CYCLES * 2, 100), "settled: the next line");
    }

    @Test
    void aScreenThatNeverSettlesDelaysTheNextLineOnlySoLong() {
        KeyboardRegister keyboard = new KeyboardRegister();
        TypingFeeder feeder = new TypingFeeder(keyboard);
        int[] screen = {0};
        feeder.setScreenSignature(() -> screen[0]++); // different at every sample
        feeder.enqueue("A\nB");
        assertEquals("A\r", drain(feeder, keyboard, TypingFeeder.MAX_LINE_WAIT_CYCLES - 20_000, 1_000));
        assertEquals("B", drain(feeder, keyboard, 40_000, 1_000));
    }

    @Test
    void withoutAScreenLinesFollowAtOnce() {
        KeyboardRegister keyboard = new KeyboardRegister();
        TypingFeeder feeder = new TypingFeeder(keyboard);
        feeder.enqueue("A\nB\n");
        assertEquals("A\rB\r", drain(feeder, keyboard, 1_000, 100));
    }
}
