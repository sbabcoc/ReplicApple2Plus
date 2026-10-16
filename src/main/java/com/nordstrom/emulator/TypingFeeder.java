package com.nordstrom.emulator;

import com.nordstrom.emulator.system.KeyboardRegister;

import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

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
 * The strobe alone isn't enough for software that empties the keyboard
 * into a buffer of its own. CP/M on the SoftCard II does: its 6502 server
 * reads every key as it arrives into a 256-byte type-ahead ring, and drops
 * keys once the ring is full -- far sooner than a program like BASIC-80
 * takes them out, a line at a time. So the feeder also types a line at a
 * time: after each Return it waits for the screen to settle -- unchanged
 * for {@link #SETTLE_CYCLES} -- before starting the next line, which keeps
 * any such buffer to one line's worth. Most software echoes what it's
 * typed, so a quiet screen means the line has been taken; software that
 * doesn't echo just costs the settle time per line. A screen that never
 * settles, such as a program printing steadily, holds the next line for
 * at most {@link #MAX_LINE_WAIT_CYCLES}.
 * <p>
 * Threading: {@link #enqueue}, {@link #cancel} and {@link #tick} run on the
 * emulation thread only (post the first two via the emulation loop);
 * {@link #isTyping}, {@link #typedCount} and {@link #queuedCount} are safe
 * from any thread, and the listener given to {@link #setTypingListener} is
 * told of each change on the emulation thread.
 */
final class TypingFeeder {

    /** How long the screen must stay unchanged after a Return before the next line: 0.1 second. */
    static final int SETTLE_CYCLES = 100_000;
    /** The longest the next line waits for the screen to settle: 2 seconds. */
    static final int MAX_LINE_WAIT_CYCLES = 2_000_000;
    /** How often the screen is sampled while waiting. */
    private static final int SAMPLE_CYCLES = 10_000;
    private static final int RETURN = 0x0D;

    private final KeyboardRegister keyboard;
    private volatile IntSupplier screenSignature; // null: no screen to watch, so no wait
    private boolean awaitingSettle;   // after a Return: emulation thread only
    private int waited;               // cycles since that Return
    private int unchanged;            // cycles the screen has stayed the same
    private int sinceSample;
    private int lastSignature;
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
        awaitingSettle = false;
        setTyping(false);
    }

    /**
     * Cycle listener: loads the next queued key once software has
     * acknowledged the previous one.
     *
     * @param cycles CPU cycles elapsed (unused -- the strobe, not time, paces typing)
     */
    void tick(int cycles) {
        if (pending.isEmpty() || keyboard.isStrobeSet()) {
            return;
        }
        if (awaitingSettle && !settled(cycles)) {
            return;
        }
        int key = pending.poll();
        keyboard.keyPressed(key);
        typed++;
        if (key == RETURN && screenSignature != null) {
            awaitingSettle = true;
            waited = 0;
            unchanged = 0;
            sinceSample = 0;
            lastSignature = screenSignature.getAsInt();
        }
        setTyping(!pending.isEmpty());
    }

    /**
     * Sets what tells whether the screen is still changing: any value that
     * differs whenever what's displayed does, such as a hash of the text.
     * Without one, lines follow each other as fast as the strobe allows.
     *
     * @param signature read on the emulation thread
     */
    void setScreenSignature(IntSupplier signature) {
        screenSignature = signature;
    }

    /** Whether the screen has been quiet long enough since the last Return to start the next line. */
    private boolean settled(int cycles) {
        waited += cycles;
        sinceSample += cycles;
        if (sinceSample >= SAMPLE_CYCLES) {
            int signature = screenSignature.getAsInt();
            if (signature == lastSignature) {
                unchanged += sinceSample;
            } else {
                lastSignature = signature;
                unchanged = 0;
            }
            sinceSample = 0;
        }
        if (unchanged >= SETTLE_CYCLES || waited >= MAX_LINE_WAIT_CYCLES) {
            awaitingSettle = false;
            return true;
        }
        return false;
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
