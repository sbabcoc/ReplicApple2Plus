package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void quarterTrackFarFromAnyMappedNeighborReturnsTheSpecsEmptyTrackLengthAndGenuineRandomness(@TempDir Path tempDir) throws IOException {
        Path path = WozTestFixtures.buildSyntheticWozFile(tempDir, true);
        // Distance 10 from quarter-track 0 and distance 6 from quarter-track 4 -- both beyond the
        // fallback search radius, so no real hardware would pick up either track from here either.
        WozDiskImage firstLoad = WozDiskImage.load(path);
        TrackBitStream emptyTrack = firstLoad.trackAt(10);
        assertEquals(51_200, emptyTrack.bitCount());

        // Genuinely random per the WOZ spec's own requirement for blank
        // media (confirmed directly against applesaucefdc.com's own
        // reference: "the emulator should be outputting random bits in
        // this case") -- not the fixed zero this project's own code
        // previously, and deliberately, documented as a simplification.
        // Two completely separate loads of the same quarter-track must
        // differ, the same way two separate reads of a blank-disk track
        // already must (see the createBlank-specific test for that).
        WozDiskImage secondLoad = WozDiskImage.load(path);
        TrackBitStream emptyTrackAgain = secondLoad.trackAt(10);
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            first.append(emptyTrack.nextBit());
            second.append(emptyTrackAgain.nextBit());
        }
        assertFalse(first.toString().equals(second.toString()),
            "a genuinely unmapped track must read as fresh randomness each time, not a fixed pattern");
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

    @Test
    void persistWritesModifiedTrackDataToTheHostFile(@TempDir Path tempDir) throws IOException {
        Path path = WozTestFixtures.buildSyntheticWozFile(tempDir, false);
        WozDiskImage image = WozDiskImage.load(path);

        TrackBitStream track0 = image.trackAt(0);
        track0.writeBit(1);
        track0.writeBit(0);
        track0.writeBit(1);
        image.markDirty(); // Disk2Controller's own job normally -- done directly here to isolate persist() itself

        image.persist();

        // A completely fresh instance, from a fresh read of the file,
        // must see the change -- not just the live image's own
        // trackData array, which would trivially already reflect it.
        WozDiskImage reloaded = WozDiskImage.load(path);
        TrackBitStream reloadedTrack0 = reloaded.trackAt(0);
        assertEquals(1, reloadedTrack0.nextBit());
        assertEquals(0, reloadedTrack0.nextBit());
        assertEquals(1, reloadedTrack0.nextBit());
    }

    @Test
    void persistDoesNothingWhenNothingHasBeenWritten(@TempDir Path tempDir) throws IOException, InterruptedException {
        // The real assertion is that persist() does not even attempt a
        // rewrite -- checked via the file's own last-modified time,
        // which changes on any actual write regardless of whether the
        // bytes written happen to be identical to what was already
        // there. Root-independent, unlike an earlier version of this
        // test that relied on a read-only file throwing on write:
        // confirmed directly, by mutation-testing that version, that it
        // was a false positive under this sandbox's root privileges,
        // which bypass the read-only attribute entirely -- a rewrite
        // would have silently succeeded either way.
        Path path = WozTestFixtures.buildSyntheticWozFile(tempDir, false);
        WozDiskImage image = WozDiskImage.load(path);
        Thread.sleep(10); // ensure a real rewrite, if one happened, would produce a detectably later timestamp
        java.nio.file.attribute.FileTime before = Files.getLastModifiedTime(path);

        image.persist();

        java.nio.file.attribute.FileTime after = Files.getLastModifiedTime(path);
        assertEquals(before, after, "persist() with nothing dirty must not rewrite the file at all");
    }

    @Test
    void metaChunkSurvivesAWriteAndPersistRoundTripVerbatim(@TempDir Path tempDir) throws IOException {
        String metaContent = "title\tTest Disk\nauthor\tReplicApple2Plus Tests\n";
        Path path = WozTestFixtures.buildSyntheticWozFile(tempDir, false, metaContent);
        WozDiskImage image = WozDiskImage.load(path);

        TrackBitStream track0 = image.trackAt(0);
        track0.writeBit(1);
        image.markDirty();
        image.persist();

        byte[] file = Files.readAllBytes(path);
        String fileText = new String(file, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(fileText.contains(metaContent),
            "the META chunk's exact content must survive a write+persist cycle untouched");
    }

    @Test
    void fileStaysLoadableAfterAWriteAndPersistCycle(@TempDir Path tempDir) throws IOException {
        // The same CRC-staleness regression class covered for
        // setWriteProtected earlier, now for track-data persistence
        // specifically.
        Path path = WozTestFixtures.buildSyntheticWozFile(tempDir, false);
        WozDiskImage image = WozDiskImage.load(path);
        image.trackAt(0).writeBit(1);
        image.markDirty();
        image.persist();

        assertDoesNotThrow(() -> WozDiskImage.load(path), "the file must still pass CRC verification after a persist");
    }

    @Test
    void createBlankProducesALoadableNotWriteProtectedFile(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("blank.woz");
        WozDiskImage image = WozDiskImage.createBlank(path);

        assertTrue(Files.exists(path));
        assertFalse(image.isWriteProtected(), "a freshly created disk should be writable, not protected");
        assertDoesNotThrow(() -> WozDiskImage.load(path), "the created file must itself be a valid, loadable WOZ2 file");
    }

    @Test
    void createBlankRefusesToOverwriteAnExistingFile(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("existing.woz");
        WozTestFixtures.buildSyntheticWozFile(tempDir, false); // just to confirm the fixture itself still works independent of this
        Files.write(path, new byte[]{1, 2, 3}); // anything -- just needs to already exist

        assertThrows(IOException.class, () -> WozDiskImage.createBlank(path));
    }

    @Test
    void unwrittenTrackOnABlankDiskReadsAsGenuinelyRandomEachTime(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("blank.woz");
        WozDiskImage image = WozDiskImage.createBlank(path);

        TrackBitStream firstRead = image.trackAt(0);
        TrackBitStream secondRead = image.trackAt(0); // a fresh wrapper, same underlying track, still virgin
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            first.append(firstRead.nextBit());
            second.append(secondRead.nextBit());
        }

        assertFalse(first.toString().equals(second.toString()),
            "two reads of the same unformatted track must differ -- a fixed, repeating pattern is exactly "
            + "what the WOZ spec's own weak-bit requirement exists to avoid, and what real software "
            + "checking for genuine blank media specifically tests for");
    }

    @Test
    void writingToAVirginTrackPermanentlyExitsWeakBitMode(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("blank.woz");
        WozDiskImage image = WozDiskImage.createBlank(path);

        TrackBitStream writer = image.trackAt(0);
        writer.seekTo(0);
        int byteToWrite = 0xD5;
        for (int i = 7; i >= 0; i--) {
            writer.writeBit((byteToWrite >> i) & 1);
        }

        // Two SEPARATE reads, from two fresh TrackBitStream wrappers --
        // confirms the track itself (not just the one stream instance
        // that happened to write it) has permanently left weak-bit mode.
        TrackBitStream readerA = image.trackAt(0);
        TrackBitStream readerB = image.trackAt(0);
        readerA.seekTo(0);
        readerB.seekTo(0);
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            a.append(readerA.nextBit());
            b.append(readerB.nextBit());
        }
        assertEquals("11010101", a.toString());
        assertEquals(a.toString(), b.toString(), "once written, repeated reads must be stable, real data -- not random anymore");
    }

    @Test
    void writingToAVirginTrackPersistsCorrectlyAcrossAFreshReload(@TempDir Path tempDir) throws IOException {
        Path path = tempDir.resolve("blank.woz");
        WozDiskImage image = WozDiskImage.createBlank(path);

        TrackBitStream track0 = image.trackAt(0);
        track0.seekTo(0);
        int byteToWrite = 0xAA;
        for (int i = 7; i >= 0; i--) {
            track0.writeBit((byteToWrite >> i) & 1);
        }
        image.markDirty();
        image.persist();

        WozDiskImage reloaded = WozDiskImage.load(path);
        TrackBitStream reloadedTrack0 = reloaded.trackAt(0);
        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            bits.append(reloadedTrack0.nextBit());
        }
        assertEquals("10101010", bits.toString(), "a persisted write to a formerly-virgin track must survive a completely fresh reload");
    }

    @Test
    void anUnwrittenTrackOnACompletelyFreshReloadStillReadsAsGenuineRandomness(@TempDir Path tempDir) throws IOException {
        // Confirms the fix for what was originally a disclosed, accepted
        // limitation: virgin status is now detected from the file's own
        // content (every byte zero -- confirmed physically impossible for
        // real, formatted Apple II disk data, see isAllZero's own
        // Javadoc), not remembered only in the one in-memory instance
        // createBlank() happened to return. A track that was never
        // written, reloaded via a completely independent load() call
        // (standing in for a separate session), must still read as fresh
        // randomness on each access, not the stable placeholder bytes
        // that were actually saved.
        Path path = tempDir.resolve("blank.woz");
        WozDiskImage.createBlank(path); // this specific instance is discarded -- the point is the file alone

        WozDiskImage reloaded = WozDiskImage.load(path);
        TrackBitStream track34 = reloaded.trackAt(34 * 4); // never written
        TrackBitStream track34Again = reloaded.trackAt(34 * 4);
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            first.append(track34.nextBit());
            second.append(track34Again.nextBit());
        }

        assertFalse(first.toString().equals(second.toString()),
            "an unwritten track must still read as genuine randomness after a completely independent reload");
    }

    @Test
    void aFormattedTrackOnAReloadIsNeverMistakenForVirgin(@TempDir Path tempDir) throws IOException {
        // The other direction: confirms formatting a track, persisting,
        // and reloading correctly leaves it OUT of weak-bit mode -- the
        // all-zero detection must not misfire on real data. Uses a
        // non-zero byte specifically (0xD5, never 0x00) so this test
        // cannot pass by accident the way writing all-zero data would.
        Path path = tempDir.resolve("blank.woz");
        WozDiskImage image = WozDiskImage.createBlank(path);
        TrackBitStream track0 = image.trackAt(0);
        track0.seekTo(0);
        for (int i = 7; i >= 0; i--) {
            track0.writeBit((0xD5 >> i) & 1);
        }
        image.markDirty();
        image.persist();

        WozDiskImage reloaded = WozDiskImage.load(path);
        TrackBitStream reloadedTrack0 = reloaded.trackAt(0);
        TrackBitStream reloadedTrack0Again = reloaded.trackAt(0);
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            first.append(reloadedTrack0.nextBit());
            second.append(reloadedTrack0Again.nextBit());
        }
        assertEquals("11010101", first.toString());
        assertEquals(first.toString(), second.toString(),
            "a formatted track must read as stable, real data after a reload -- never randomized");
    }
}
