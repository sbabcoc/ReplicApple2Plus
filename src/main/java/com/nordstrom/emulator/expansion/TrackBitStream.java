package com.nordstrom.emulator.expansion;

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

    private final byte[] data;
    private final int bitCount;
    private int position;

    /**
     * @param data the track's raw bytes, high bit first within each byte
     * @param bitCount the number of meaningful bits (not necessarily a multiple of 8)
     */
    TrackBitStream(byte[] data, int bitCount) {
        this.data = data;
        this.bitCount = bitCount;
    }

    /**
     * Reads the bit at the current position and advances, wrapping
     * to 0 after the last bit. Bit order within each stored byte is
     * high to low, per the WOZ specification (and applied identically
     * here for DSK-synthesized streams, for consistency).
     *
     * @return 0 or 1
     */
    public int nextBit() {
        int byteIndex = position / 8;
        int bitIndexInByte = 7 - (position % 8);
        int bit = (data[byteIndex] >> bitIndexInByte) & 1;
        position = (position + 1) % bitCount;
        return bit;
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
