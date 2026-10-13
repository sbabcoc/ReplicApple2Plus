package com.nordstrom.emulator;

import com.nordstrom.emulator.system.KeyboardRegister;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Types text into the machine one keystroke at a time -- the engine behind
 * the Edit menu's Paste and the File menu's Type File....
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
 * {@link #isTyping}, {@link #typedCount} and {@link #queuedCount} are safe
 * from any thread, and the listener given to {@link #setTypingListener} is
 * told of each change on the emulation thread.
 */
final class TypingFeeder {

    private final KeyboardRegister keyboard;
    private final ArrayDeque<Integer> pending = new ArrayDeque<>(); // emulation thread only
    private volatile boolean typing;
    private volatile Consumer<Boolean> typingListener = nowTyping -> { };
    private volatile int queued; // keys queued since typing last started
    private volatile int typed;  // of those, keys already typed

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
        enqueueKeys(toKeystrokes(text));
    }

    /**
     * Queues keystrokes as the Apple keyboard produces them, after anything
     * already queued -- for keys text can't express, such as ^C, which
     * {@link #enqueue} skips as it does any control character in pasted text.
     *
     * @param keys Apple II key codes
     */
    void enqueueKeys(int... keys) {
        if (!typing) {
            queued = 0;
            typed = 0;
        }
        for (int key : keys) {
            pending.add(key);
        }
        queued += keys.length;
        setTyping(!pending.isEmpty());
    }

    /** Discards everything not yet typed. A key already in the latch stays there. */
    void cancel() {
        pending.clear();
        setTyping(false);
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
            typed++;
            setTyping(!pending.isEmpty());
        }
    }

    /** @return true while queued text remains to be typed */
    boolean isTyping() {
        return typing;
    }

    /** @return keys typed since typing last started, for showing progress */
    int typedCount() {
        return typed;
    }

    /** @return keys queued since typing last started, typed or not, for showing progress */
    int queuedCount() {
        return queued;
    }

    /**
     * Sets who is told when typing starts and stops -- the typing progress
     * window, which opens and closes with it.
     *
     * @param listener called on the emulation thread with the new state, each
     *                 time it changes
     */
    void setTypingListener(Consumer<Boolean> listener) {
        typingListener = listener;
    }

    private void setTyping(boolean nowTyping) {
        if (nowTyping != typing) {
            typing = nowTyping;
            typingListener.accept(nowTyping);
        }
    }
}
