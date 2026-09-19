package com.nordstrom.emulator.expansion;

/**
 * The Disk II controller's Logic State Sequencer (LSS) -- the actual
 * state machine that converts a raw disk bitstream pulse, one per tick,
 * into the shift register (data latch) contents software reads via
 * Q6/Q7. Ticks once every 4 CPU cycles on real hardware; this class
 * itself has no opinion about timing at all -- it only knows how to
 * advance one step given the current pulse bit, matching
 * {@link com.nordstrom.emulator.system.PaddleTimers} and
 * {@link com.nordstrom.emulator.system.VideoScanner}'s own separation
 * between "what happens per tick" and "how often ticks happen."
 * <p>
 * Address-bit wiring and the ROM table itself are
 * {@link DiskLogicSequencerRom}'s concern, not this class's -- see that
 * class's Javadoc for the real, verified (not assumed) bit mapping.
 * This class's own job is just: assemble the address from current
 * state, applying the exact same mapping.
 * <p>
 * Output byte decoding -- high nibble is the next state, low nibble is
 * the action to take on the latch -- confirmed directly against
 * a2kit's independent, real-world Rust implementation (the same source
 * used to verify the address mapping):
 * <pre>
 *   0x0 - CLR: latch = 0
 *   0x8 - NOP: latch unchanged
 *   0x9 - SL0: latch = latch &lt;&lt; 1 (shift left, insert a 0 bit)
 *   0xA - SR:  write-protect sense (if write-protected, latch = 0xFF;
 *              otherwise shift right -- used when Q6=1,Q7=0, the
 *              controller's "sense" mode, not real disk reading)
 *   0xB - LD:  latch = the write-data register (set via a $C08D write)
 *   0xD - SL1: latch = (latch &lt;&lt; 1) | 1 (shift left, insert a 1 bit)
 * </pre>
 * Any other low-nibble value would mean either the ROM data or the
 * address mapping is wrong -- this class throws rather than silently
 * ignoring an action it doesn't recognize.
 */
final class Disk2LogicSequencer {

    private int state;
    private int latch;
    private boolean q6;
    private boolean q7;
    private boolean writeProtected;
    private int writeDataRegister;

    /**
     * Sets Q6 (the read-data/write-protect-sense vs write-load control line).
     *
     * @param q6 the new Q6 value
     */
    void setQ6(boolean q6) {
        this.q6 = q6;
    }

    /**
     * Sets Q7 (the read vs write mode control line).
     *
     * @param q7 the new Q7 value
     */
    void setQ7(boolean q7) {
        this.q7 = q7;
    }

    /**
     * Sets whether the currently-selected disk is write-protected --
     * consulted only in Q6=1,Q7=0 (sense) mode.
     *
     * @param writeProtected true if the disk is write-protected
     */
    void setWriteProtected(boolean writeProtected) {
        this.writeProtected = writeProtected;
    }

    /**
     * Sets the byte software wrote to $C08D (Q6 high with Q7 high),
     * consulted only in Q6=1,Q7=1 (load) mode.
     *
     * @param value the byte to load into the latch on the next LD action
     */
    void setWriteDataRegister(int value) {
        this.writeDataRegister = value & 0xFF;
    }

    /**
     * @return the current data latch contents -- what a $C08C/$C08E read exposes
     */
    int latch() {
        return latch;
    }

    /**
     * Advances the sequencer by one LSS cycle.
     *
     * @param pulseBit the current bit from the disk bitstream (0 or 1) --
     *                 the caller's responsibility to supply, including
     *                 any weak-bit/fake-bit handling
     */
    void tick(int pulseBit) {
        int s0 = state & 1;
        int s1 = (state >> 1) & 1;
        int s2 = (state >> 2) & 1;
        int s3 = (state >> 3) & 1;
        int highBit = (latch >> 7) & 1;
        int q6Bit = q6 ? 1 : 0;
        int q7Bit = q7 ? 1 : 0;
        int pulseInverted = pulseBit ^ 1;

        int address = (s3 << 7) | (s2 << 6) | (s0 << 5) | (pulseInverted << 4)
            | (q7Bit << 3) | (q6Bit << 2) | (highBit << 1) | s1;

        int output = DiskLogicSequencerRom.read(address);
        int nextState = (output >> 4) & 0xF;
        int action = output & 0xF;

        switch (action) {
            case 0x0 -> latch = 0;
            case 0x8 -> { /* NOP: latch unchanged */ }
            case 0x9 -> latch = (latch << 1) & 0xFF;
            case 0xA -> latch = writeProtected ? 0xFF : (latch >> 1);
            case 0xB -> latch = writeDataRegister;
            case 0xD -> latch = ((latch << 1) | 1) & 0xFF;
            default -> throw new IllegalStateException(
                "Illegal LSS ROM action code 0x" + Integer.toHexString(action) + " at address 0x"
                + Integer.toHexString(address) + " -- this indicates a bug in the ROM data or address "
                + "mapping, not a real hardware state");
        }

        state = nextState;
    }
}
