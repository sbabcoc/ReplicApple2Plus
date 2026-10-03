package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Locks in {@link WozDiskImage}'s parsing correctness, using
 * {@link WozTestFixtures}'s synthetic WOZ2 file -- see that class's own
 * Javadoc for why the file is generated at test-run time rather than
 * committed as an external fixture.
 */
class WozDiskImageTest {

    /**
     * True if this process can write to a file even after its own
     * write permission bit is cleared -- true for root (confirmed
     * directly: {@code chmod 444} followed by a write still succeeds
     * as root), and possibly true under other privileged setups too.
     * {@code isWriteProtected()}'s host-file-permission check is
     * correct behavior for the real, non-root usage this project
     * actually runs under -- a normal user genuinely cannot bypass
     * their own file's read-only bit the way root can -- but that
     * same correctness makes it unverifiable end-to-end in a
     * privileged test environment. Used to skip only the specific
     * assertions that can't mean anything here, not to skip
     * everything -- the permission bit itself still being set
     * correctly is checked separately, via
     * {@link Files#getPosixFilePermissions}, which reports the raw
     * bit regardless of who is asking.
     */
    /**
     * True if {@code path}'s filesystem supports POSIX permission bits
     * at all -- false on Windows, where {@link Files#getPosixFilePermissions}
     * would throw {@link UnsupportedOperationException} rather than
     * return a meaningful (or even empty) result. Write-protect itself
     * (via {@link WozDiskImage#isWriteProtected}/{@code setWriteProtected},
     * built on {@link Files#isWritable}/{@code File.setWritable}) works
     * on every platform those methods support, Windows included -- only
     * these specific, more granular tests that inspect the raw
     * permission bit directly need this guard, not the feature itself.
     */
    private static boolean isPosixFilesystem(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class);
    }

    private static boolean currentProcessBypassesFilePermissions(Path tempDir) throws IOException {
        Path probe = tempDir.resolve("permission-probe.tmp");
        Files.writeString(probe, "x");
        probe.toFile().setWritable(false);
        boolean bypassed;
        try {
            Files.writeString(probe, "y");
            bypassed = true;
        } catch (IOException e) {
            bypassed = false;
        }
        probe.toFile().setWritable(true); // restore, so @TempDir cleanup can delete it
        Files.deleteIfExists(probe);
        return bypassed;
    }

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

    @Test
    void setWriteProtectedTruePersistsToTheFileAndTheHostPermission(@TempDir Path tempDir) throws IOException {
        Path path = WozTestFixtures.buildSyntheticWozFile(tempDir, false);
        WozDiskImage image = WozDiskImage.load(path);
        assertFalse(image.isWriteProtected());

        image.setWriteProtected(true);

        // The INFO chunk flag is verified end-to-end below via a fresh
        // reload, which is unaffected by process privilege or platform.
        // The host permission bit itself is checked directly via POSIX
        // permissions (root-independent) rather than Files.isWritable,
        // which reports *effective* access and is true for root
        // regardless of the bit -- confirmed directly in this sandbox.
        // Only meaningful on a POSIX filesystem at all (Windows has no
        // such permission bit to inspect this way) -- skipped, not
        // failed, where it doesn't apply.
        if (isPosixFilesystem(path)) {
            assertFalse(
                Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_WRITE),
                "the host file's own owner-write permission bit should be cleared, in tandem");
        }
        if (!currentProcessBypassesFilePermissions(tempDir)) {
            assertTrue(image.isWriteProtected(), "the in-memory image should report protected immediately");
            assertFalse(Files.isWritable(path), "a non-privileged process should see the file as not writable");
        }

        // Independently confirms persistence: a FRESH load, from a
        // completely new WozDiskImage instance, must also see it --
        // not just the same in-memory object remembering what was set.
        // No need to restore write access first -- reading a read-only
        // file needs no write permission at all.
        WozDiskImage reloaded = WozDiskImage.load(path);
        assertTrue(reloaded.isWriteProtected(), "a fresh load must see the persisted INFO chunk flag, not just the live instance");
    }

    @Test
    void setWriteProtectedFalseClearsBothTheFlagAndTheHostPermission(@TempDir Path tempDir) throws IOException {
        Path path = WozTestFixtures.buildSyntheticWozFile(tempDir, true);
        WozDiskImage image = WozDiskImage.load(path);
        assertTrue(image.isWriteProtected());

        image.setWriteProtected(false);

        assertFalse(image.isWriteProtected());
        assertTrue(Files.isWritable(path), "the host file's own permission should be cleared too, in tandem");

        WozDiskImage reloaded = WozDiskImage.load(path);
        assertFalse(reloaded.isWriteProtected());
    }

    @Test
    void theFileStaysLoadableAfterTogglingWriteProtectTwice(@TempDir Path tempDir) throws IOException {
        // The regression this guards against: a stale CRC32 after
        // rewriting the INFO chunk would make the file fail its own
        // load-time verification the next time it's opened -- toggling
        // twice (true then false) exercises the CRC recompute path on
        // two different byte values, not just one.
        Path path = WozTestFixtures.buildSyntheticWozFile(tempDir, false);
        WozDiskImage image = WozDiskImage.load(path);
        image.setWriteProtected(true);
        image.setWriteProtected(false);

        WozDiskImage reloaded = WozDiskImage.load(path); // throws IllegalStateException on a bad CRC
        assertFalse(reloaded.isWriteProtected());
        // Confirms the actual track data wasn't corrupted by the two
        // rewrites either, not just that the CRC happens to match.
        TrackBitStream track0 = reloaded.trackAt(0);
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < WozTestFixtures.TRACK_0_BITS.length(); i++) {
            actual.append(track0.nextBit());
        }
        assertEquals(WozTestFixtures.TRACK_0_BITS, actual.toString());
    }

    @Test
    void isWriteProtectedIsTrueWhenOnlyTheHostFileIsReadOnly(@TempDir Path tempDir) throws IOException {
        // The WOZ-specific half of the OR: a disk whose own INFO flag says
        // writable can still be protected purely by the host file's
        // permission -- confirmed independently of setWriteProtected,
        // which always moves both together; this sets only the host
        // permission directly, bypassing this class entirely. Meaningless
        // under a process that bypasses file permissions (root,
        // confirmed directly in this sandbox) -- skipped there rather
        // than asserting something that can't actually hold.
        assumeFalse(currentProcessBypassesFilePermissions(tempDir),
            "this process bypasses file permissions (likely running as root) -- host-file-based write-protect can't be meaningfully tested here");

        Path path = WozTestFixtures.buildSyntheticWozFile(tempDir, false);
        WozDiskImage image = WozDiskImage.load(path);
        assertFalse(image.isWriteProtected());

        path.toFile().setWritable(false);

        assertTrue(image.isWriteProtected(), "host-level read-only should be sufficient on its own, regardless of the INFO flag");
    }
}
