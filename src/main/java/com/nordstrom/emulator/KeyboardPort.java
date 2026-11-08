package com.nordstrom.emulator;

/**
 * The keyboard connector, as a device on the keyboard cable drives it:
 * a key goes onto the data lines with the strobe, and the device knows
 * whether that key has been taken yet -- the strobe clears when the CPU
 * touches {@code $C010}-{@code $C01F}, which a device that also sees the
 * CPU's pins observes. Nothing comes back on the connector itself.
 * {@link CommandRecall} is the device; {@link TypingFeeder} types through it.
 */
interface KeyboardPort {

    /**
     * Presses a key: puts it on the connector's data lines and fires the strobe.
     *
     * @param key the key's Apple II ASCII value; only bits 0-6 are meaningful
     */
    void send(int key);

    /**
     * Presses a key with the shift-key mod's Shift held for it, as a person
     * types a capital. A port with no shift-key mod to hold just presses it.
     *
     * @param key the key's Apple II ASCII value
     */
    default void sendShifted(int key) {
        send(key);
    }

    /**
     * Whether a key sent is still waiting: the CPU hasn't cleared the
     * strobe since. Sending another now would replace a key software never
     * saw -- the II+ keyboard is a single-character latch with no buffer.
     *
     * @return true while the last key sent hasn't been taken
     */
    boolean keyWaiting();
}
