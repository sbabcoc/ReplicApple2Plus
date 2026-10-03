package com.nordstrom.emulator.expansion;

import java.util.Random;

/**
 * A single track's bit-level data, with a wrapping read position --
 * real disk tracks are circular, so reading past the last bit
 * continues from the first. Shared by {@link WozDiskImage} (real
 * captured bitstreams) and {@link DskDiskImage} (bitstreams
 * synthesized on the fly from sector data via real 6-and-2 GCR
 * encoding) -- both produce the same kind of object because
 * {@link Disk2LogicSequencer} genuinely doesn't care where the bits
 * came from, only that they're real, correctly-ordered disk bits.
 */
public final class TrackBitStream {

    /**
     * The WOZ spec's own documented figure for how often a genuinely
     * blank track's randomized bits should come up 1 -- "a randomized,
     * roughly-30%-ones pattern" (see this project's own, pre-existing
     * notes on this, now actually implemented rather than simplified
     * to zeros; see {@link #weakBits}).
     */
    private static final int WEAK_BIT_ONES_PERCENT = 30;

    private static final Random WEAK_BIT_RANDOM = new Random();

    private final byte[] data;
    private final int bitCount;
    private int position;
    private boolean weakBitMode;
    private Runnable onExitWeakBitMode;

    /**
     * @param data the track's raw bytes, high bit first within each byte
     * @param bitCount the number of meaningful bits (not necessarily a multiple of 8)
     */
    TrackBitStream(byte[] data, int bitCount) {
        this.data = data;
        this.bitCount = bitCount;
    }

    /**
     * Builds a stream in "weak bit" mode: every {@link #nextBit} call
     * returns fresh, genuine randomness (roughly 30% ones, per the WOZ
     * spec's own documented figure for blank media), completely
     * ignoring {@code data} -- not a one-time-randomized copy of it,
     * which would let software that rereads the same position detect a
     * suspiciously static pattern the real MC3470 noise this models
     * never produces. {@code position} still advances and wraps
     * normally, so bit-cell timing stays consistent with every other
     * track; only the bit *values* are random.
     * <p>
     * The moment {@link #writeBit} is called -- real software actually
     * formatting this track, the one case this mode exists for --
     * this permanently exits weak-bit mode (calling {@code
     * onExitWeakBitMode} once, if given) and behaves exactly like a
     * normal stream from then on, writing real data into {@code data}
     * as usual. Deliberately an all-or-nothing transition for the
     * whole track on the very first write anywhere in it, not
     * per-bit-position tracking of exactly which bits have been
     * written: real formatting (DOS 3.3's INIT included) writes an
     * entire track's worth of sync bytes, address fields, and data
     * fields in one pass, so by the time formatting of a given track
     * finishes every position has genuinely been written anyway. A
     * software path that deliberately writes only part of a track and
     * leaves the rest meaningfully "still blank" would see that
     * remainder read back as real (zeroed, not random) data instead --
     * a real simplification for a case real DOS 3.3 formatting never
     * actually produces, not a hidden one.
     *
     * @param data the track's backing bytes -- written into for real once weak-bit mode ends, never read from before then
     * @param bitCount the number of meaningful bits
     * @param onExitWeakBitMode runs once, the first time anything is written to this track; may be null
     * @return the built stream, starting in weak-bit mode
     */
    static TrackBitStream weakBits(byte[] data, int bitCount, Runnable onExitWeakBitMode) {
        TrackBitStream stream = new TrackBitStream(data, bitCount);
        stream.weakBitMode = true;
        stream.onExitWeakBitMode = onExitWeakBitMode;
        return stream;
    }

    /**
     * Reads the bit at the current position and advances, wrapping
     * to 0 after the last bit. Bit order within each stored byte is
     * high to low, per the WOZ specification (and applied identically
     * here for DSK-synthesized streams, for consistency).
     * <p>
     * In weak-bit mode (see {@link #weakBits}), returns fresh
     * randomness instead of reading {@code data} at all; the position
     * still advances normally.
     *
     * @return 0 or 1
     */
    public int nextBit() {
        if (weakBitMode) {
            position = (position + 1) % bitCount;
            return WEAK_BIT_RANDOM.nextInt(100) < WEAK_BIT_ONES_PERCENT ? 1 : 0;
        }
        int byteIndex = position / 8;
        int bitIndexInByte = 7 - (position % 8);
        int bit = (data[byteIndex] >> bitIndexInByte) & 1;
        position = (position + 1) % bitCount;
        return bit;
    }

    /**
     * Overwrites the bit at the current position and advances, wrapping
     * to 0 after the last bit -- the write-side mirror of {@link #nextBit},
     * mutating the same backing array in place rather than returning a
     * new one. For a {@link WozDiskImage} track, that backing array is
     * the same one held persistently inside the image for the life of
     * the session (confirmed directly: {@code trackAt} wraps a fresh
     * {@link TrackBitStream} around it on every call, but the array
     * itself is never copied), so a write here is visible to every
     * later read of this track, including after the head moves away
     * and back -- not just within this one {@code TrackBitStream}
     * instance's own lifetime.
     *
     * In weak-bit mode (see {@link #weakBits}), the first call to this
     * method permanently exits that mode -- see {@link #weakBits}'s own
     * Javadoc for why this is an all-or-nothing, whole-track transition
     * rather than tracking individual bit positions.
     *
     * @param bit 0 or 1 -- the bit to write at the current position
     */
    public void writeBit(int bit) {
        if (weakBitMode) {
            weakBitMode = false;
            if (onExitWeakBitMode != null) {
                onExitWeakBitMode.run();
                onExitWeakBitMode = null; // fires once, not on every subsequent write
            }
        }
        int byteIndex = position / 8;
        int bitIndexInByte = 7 - (position % 8);
        int mask = 1 << bitIndexInByte;
        if (bit != 0) {
            data[byteIndex] = (byte) (data[byteIndex] | mask);
        } else {
            data[byteIndex] = (byte) (data[byteIndex] & ~mask);
        }
        position = (position + 1) % bitCount;
    }

    /**
     * @return the total number of bits in this track
     */
    public int bitCount() {
        return bitCount;
    }

    /**
     * @return the current read position, 0 to {@link #bitCount()} - 1
     */
    public int position() {
        return position;
    }

    /**
     * Moves the read position directly, for track-changing (the WOZ
     * spec's own guidance: preserve relative bitstream position
     * across a track change, scaled by the new track's length,
     * rather than resetting to 0).
     *
     * @param newPosition the new bit position, taken modulo this track's bit count
     */
    public void seekTo(int newPosition) {
        position = ((newPosition % bitCount) + bitCount) % bitCount;
    }
}
