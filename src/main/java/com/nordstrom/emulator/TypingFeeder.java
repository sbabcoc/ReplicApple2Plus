package com.nordstrom.emulator;

import com.nordstrom.emulator.system.KeyboardRegister;

import java.util.ArrayDeque;

/**
 * Types text into the machine one keystroke at a time -- the engine behind
 * the Edit menu's Paste and Type File... items.
 * <p>
 * Pacing is gated on the keyboard strobe rather than a fixed delay. The
 * real II+ keyboard is a single-character latch with no buffer, and
 * {@link KeyboardRegister#keyPressed} overwrites it unconditionally, so a
 * key loaded before software has acknowledged the previous one (by
 * touching $C010) silently replaces it. Waiting for the strobe to clear
 * makes injection lossless and self-paced: exactly as fast as whatever is
 * reading the keyboard, whether that's GETLN at a BASIC prompt or a
 * program's own input loop, and never faster -- including while BASIC
 * pauses to tokenize a line, where any fixed delay is a guess.
 * <p>
 * Threading: {@link #enqueue}, {@link #cancel} and {@link #tick} run on the
 * emulation thread only (post the first two via the emulation loop);
 * {@link #isTyping} is safe from any thread, for menu state.
 */
final class TypingFeeder {

    private final KeyboardRegister keyboard;
    private final ArrayDeque<Integer> pending = new ArrayDeque<>(); // emulation thread only
    private volatile boolean typing;

    TypingFeeder(KeyboardRegister keyboard) {
        this.keyboard = keyboard;
    }

    /**
     * Converts text to the keystrokes a person would type for it. Each line
     * ending -- {@code \r\n}, {@code \n} or a lone {@code \r} -- becomes one
     * Return ($0D). Everything else goes through the same
     * {@link KeyboardMapper#mapTypedCharacter} the live keyboard uses, so
     * injected text behaves exactly like typed text: letters fold to
     * uppercase, and characters the mapper rejects (tabs, other control
     * characters, anything outside ASCII) are skipped.
     *
     * @param text the text to convert
     * @return the Apple II ASCII values to press, in order
     */
    static int[] toKeystrokes(CharSequence text) {
        int[] keys = new int[text.length()];
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                keys[count++] = 0x0D;
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++; // \r\n is one line ending, not two
                }
                continue;
            }
            int mapped = KeyboardMapper.mapTypedCharacter(c);
            if (mapped >= 0) {
                keys[count++] = mapped;
            }
        }
        return java.util.Arrays.copyOf(keys, count);
    }

    /**
     * Queues text to be typed after anything already queued.
     *
     * @param text the text to type
     */
    void enqueue(CharSequence text) {
        for (int key : toKeystrokes(text)) {
            pending.add(key);
        }
        typing = !pending.isEmpty();
    }

    /** Discards everything not yet typed. A key already in the latch stays there. */
    void cancel() {
        pending.clear();
        typing = false;
    }

    /**
     * Cycle listener: loads the next queued key once software has
     * acknowledged the previous one.
     *
     * @param cycles CPU cycles elapsed (unused -- the strobe, not time, paces typing)
     */
    void tick(int cycles) {
        if (!pending.isEmpty() && !keyboard.isStrobeSet()) {
            keyboard.keyPressed(pending.poll());
            typing = !pending.isEmpty();
        }
    }

    /** @return true while queued text remains to be typed */
    boolean isTyping() {
        return typing;
    }
}
