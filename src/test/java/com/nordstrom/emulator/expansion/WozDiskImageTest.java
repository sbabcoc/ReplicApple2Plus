package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in {@link WozDiskImage}'s parsing correctness, using
 * {@link WozTestFixtures}'s synthetic WOZ2 file -- see that class's own
 * Javadoc for why the file is generated at test-run time rather than
 * committed as an external fixture.
 */
class WozDiskImageTest {

    @Test
    void parsesWriteProtectedFlag(@TempDir Path tempDir) throws IOException {
        WozDiskImage image = WozDiskImage.load(WozTestFixtures.buildSyntheticWozFile(tempDir, true));
        assertTrue(image.isWriteProtected());
    }

    @Test
    void track0BitsRoundTripExactly(@TempDir Path tempDir) throws IOException {
        WozDiskImage image = WozDiskImage.load(WozTestFixtures.buildSyntheticWozFile(tempDir, true));
        TrackBitStream track0 = image.trackAt(0);

        assertEquals(WozTestFixtures.TRACK_0_BITS.length(), track0.bitCount());
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < WozTestFixtures.TRACK_0_BITS.length(); i++) {
            actual.append(track0.nextBit());
        }
        assertEquals(WozTestFixtures.TRACK_0_BITS, actual.toString());
    }

    @Test
    void track1BitsRoundTripExactly(@TempDir Path tempDir) throws IOException {
        WozDiskImage image = WozDiskImage.load(WozTestFixtures.buildSyntheticWozFile(tempDir, true));
        TrackBitStream track1 = image.trackAt(4); // quarter-track 4 = track 1.00

        assertEquals(WozTestFixtures.TRACK_1_BITS.length(), track1.bitCount());
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < WozTestFixtures.TRACK_1_BITS.length(); i++) {
            actual.append(track1.nextBit());
        }
        assertEquals(WozTestFixtures.TRACK_1_BITS, actual.toString());
    }

    @Test
    void trackWrapsToBitZeroAfterTheLastBit(@TempDir Path tempDir) throws IOException {
        WozDiskImage image = WozDiskImage.load(WozTestFixtures.buildSyntheticWozFile(tempDir, true));
        TrackBitStream track0 = image.trackAt(0);

        for (int i = 0; i < WozTestFixtures.TRACK_0_BITS.length(); i++) {
            track0.nextBit(); // consume the whole track once
        }
        int wrappedBit = track0.nextBit();
        assertEquals(Character.getNumericValue(WozTestFixtures.TRACK_0_BITS.charAt(0)), wrappedBit);
        assertEquals(1, track0.position());
    }

    @Test
    void seekToPositionsCorrectly(@TempDir Path tempDir) throws IOException {
        WozDiskImage image = WozDiskImage.load(WozTestFixtures.buildSyntheticWozFile(tempDir, true));
        TrackBitStream track0 = image.trackAt(0);

        track0.seekTo(5);
        assertEquals(5, track0.position());
        assertEquals(Character.getNumericValue(WozTestFixtures.TRACK_0_BITS.charAt(5)), track0.nextBit());
    }

    @Test
    void unmappedQuarterTrackFallsBackToNearestMappedNeighbor(@TempDir Path tempDir) throws IOException {
        WozDiskImage image = WozDiskImage.load(WozTestFixtures.buildSyntheticWozFile(tempDir, true));

        // Real Disk II hardware's read head is wide enough to still pick up an adjacent mapped
        // track's data at an uncaptured quarter-track between two captured ones -- a reader that
        // returns silence there instead diverges from what real hardware actually produces.
        TrackBitStream nearTrack0 = image.trackAt(1); // distance 1 from mapped quarter-track 0
        assertEquals(WozTestFixtures.TRACK_0_BITS.length(), nearTrack0.bitCount());

        TrackBitStream nearTrack1 = image.trackAt(3); // distance 1 from mapped quarter-track 4
        assertEquals(WozTestFixtures.TRACK_1_BITS.length(), nearTrack1.bitCount());
    }

    @Test
    void quarterTrackFarFromAnyMappedNeighborReturnsTheSpecsEmptyTrackLength(@TempDir Path tempDir) throws IOException {
        WozDiskImage image = WozDiskImage.load(WozTestFixtures.buildSyntheticWozFile(tempDir, true));
        // Distance 10 from quarter-track 0 and distance 6 from quarter-track 4 -- both beyond the
        // fallback search radius, so no real hardware would pick up either track from here either.
        TrackBitStream emptyTrack = image.trackAt(10);

        assertEquals(51_200, emptyTrack.bitCount());
        assertEquals(0, emptyTrack.nextBit());
    }

    @Test
    void corruptedFileFailsCrc32Verification(@TempDir Path tempDir) throws IOException {
        Path original = WozTestFixtures.buildSyntheticWozFile(tempDir, true);
        byte[] bytes = Files.readAllBytes(original);
        bytes[100] ^= 0xFF; // flip a byte within the chunk data
        Path corrupted = tempDir.resolve("corrupted.woz");
        Files.write(corrupted, bytes);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> WozDiskImage.load(corrupted));
        assertTrue(e.getMessage().contains("CRC32"));
    }

    @Test
    void badMagicBytesAreRejected(@TempDir Path tempDir) throws IOException {
        Path original = WozTestFixtures.buildSyntheticWozFile(tempDir, true);
        byte[] bytes = Files.readAllBytes(original);
        bytes[0] = 'X';
        Path badMagic = tempDir.resolve("badmagic.woz");
        Files.write(badMagic, bytes);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> WozDiskImage.load(badMagic));
        assertTrue(e.getMessage().contains("WOZ2 header"));
    }

    @Test
    void notWriteProtectedFlagParsesCorrectlyToo(@TempDir Path tempDir) throws IOException {
        WozDiskImage image = WozDiskImage.load(WozTestFixtures.buildSyntheticWozFile(tempDir, false));
        assertFalse(image.isWriteProtected());
    }
}
