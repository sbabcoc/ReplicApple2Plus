package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TrackBitStream} never had its own dedicated test file --
 * {@link #nextBit} was only ever exercised indirectly through
 * {@code WozDiskImageTest}/{@code DskDiskImageTest}'s round-trip tests.
 * Adding {@link #writeBit}, a genuinely new capability rather than a
 * variation on an existing one, is the point at which this class earns
 * tests of its own.
 */
class TrackBitStreamTest {

    private static TrackBitStream zeroFilledStream(int byteCount) {
        return new TrackBitStream(new byte[byteCount], byteCount * 8);
    }

    private static String allBitsAsString(TrackBitStream stream, int bitCount) {
        stream.seekTo(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bitCount; i++) {
            sb.append(stream.nextBit());
        }
        return sb.toString();
    }

    @Test
    void writingAOneSetsExactlyThatBitLeavingNeighborsUntouched() {
        TrackBitStream stream = zeroFilledStream(2); // 16 bits, all starting at 0
        stream.seekTo(5);
        stream.writeBit(1);

        assertEquals("0000010000000000", allBitsAsString(stream, 16),
            "only bit position 5 should be set; every other bit stays 0");
    }

    @Test
    void writingAZeroOverAOneClearsExactlyThatBit() {
        byte[] allOnes = {(byte) 0xFF, (byte) 0xFF};
        TrackBitStream stream = new TrackBitStream(allOnes, 16);
        stream.seekTo(5);
        stream.writeBit(0);

        assertEquals("1111101111111111", allBitsAsString(stream, 16),
            "only bit position 5 should be cleared; every other bit stays 1");
    }

    @Test
    void writeBitAdvancesThePositionExactlyLikeNextBitDoes() {
        TrackBitStream stream = zeroFilledStream(1);
        assertEquals(0, stream.position());
        stream.writeBit(1);
        assertEquals(1, stream.position(), "writeBit should advance position by one, same as nextBit");
    }

    @Test
    void writeBitWrapsToZeroAfterTheLastBitSameAsNextBit() {
        TrackBitStream stream = zeroFilledStream(1); // 8 bits
        for (int i = 0; i < 8; i++) {
            stream.writeBit(1);
        }
        assertEquals(0, stream.position(), "position should wrap back to 0 after writing the last bit");
        assertEquals("11111111", allBitsAsString(stream, 8));
    }

    @Test
    void aFullByteWrittenOneBitAtATimeReadsBackExactly() {
        // The actual real-world usage pattern: Disk2Controller writes one
        // bit per bit-cell, not a whole byte in one call -- confirm that
        // composing writeBit() calls this way reconstructs the intended
        // byte exactly, MSB first, matching the bit order nextBit() and
        // the WOZ spec itself both already use.
        TrackBitStream stream = zeroFilledStream(1);
        int byteToWrite = 0xD5; // 11010101
        for (int bitIndex = 7; bitIndex >= 0; bitIndex--) {
            stream.writeBit((byteToWrite >> bitIndex) & 1);
        }
        assertEquals("11010101", allBitsAsString(stream, 8));
    }

    @Test
    void writingPastTheEndOfOneTrackDoesNotCorruptTheNextTracksBits() {
        // Two independent tracks' worth of data, confirming writeBit()
        // respects bitCount's own boundary (the constructor parameter,
        // not just the backing array's own length) the same way nextBit()
        // already does, rather than writing past where this track
        // actually ends into what looks like unused padding.
        byte[] data = new byte[2]; // 16 bits available, but only 4 are "this track"
        TrackBitStream stream = new TrackBitStream(data, 4);
        for (int i = 0; i < 4; i++) {
            stream.writeBit(1);
        }
        assertEquals(0, stream.position(), "should have wrapped back to 0 after exactly 4 bits, not continued into byte 2");
        assertEquals((byte) 0xF0, data[0], "the first 4 bits (this track's own length) should be set");
        assertEquals((byte) 0x00, data[1], "bits beyond this track's declared length must be untouched");
    }

    @Test
    void readingAfterWritingSeesTheNewValueImmediately() {
        // Confirms writeBit() and nextBit() share the same position
        // counter and the same backing data -- not two independent views
        // that could drift apart.
        TrackBitStream stream = zeroFilledStream(1);
        stream.seekTo(3);
        stream.writeBit(1);
        stream.seekTo(3);
        assertEquals(1, stream.nextBit(), "a read at the same position just written to should see the new value");
    }
}
