package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Locks in {@link DskDiskImage}'s real 6-and-2 GCR encoding, its
 * address fields, and its sector skew, by round-tripping: encode
 * known sector data, then decode the resulting bitstream with a
 * reference decoder ported from a2kit's verified
 * {@code decode_sector_62_256} and {@code decode_44} (themselves ports
 * of CiderPress's C++), and confirm an exact match. This is the
 * strongest verification available without a real, legally-sourced
 * DOS 3.3 disk image to test against -- the encoder and this test's
 * decoder are independently-implemented halves of the same real
 * historical algorithm, not the same code checking itself.
 */
class DskDiskImageTest {

    private static final int[] FWD_62 = {
        0x96, 0x97, 0x9a, 0x9b, 0x9d, 0x9e, 0x9f, 0xa6,
        0xa7, 0xab, 0xac, 0xad, 0xae, 0xaf, 0xb2, 0xb3,
        0xb4, 0xb5, 0xb6, 0xb7, 0xb9, 0xba, 0xbb, 0xbc,
        0xbd, 0xbe, 0xbf, 0xcb, 0xcd, 0xce, 0xcf, 0xd3,
        0xd6, 0xd7, 0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde,
        0xdf, 0xe5, 0xe6, 0xe7, 0xe9, 0xea, 0xeb, 0xec,
        0xed, 0xee, 0xef, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6,
        0xf7, 0xf9, 0xfa, 0xfb, 0xfc, 0xfd, 0xfe, 0xff
    };
    private static final int[] REV_62 = buildReverseTable();
    /** Same skew this class's own subject (DskDiskImage) uses -- duplicated here deliberately, as the test's own independent expectation, not read from the class under test. */
    private static final int[] PHYSICAL_TO_LOGICAL = {0, 7, 14, 6, 13, 5, 12, 4, 11, 3, 10, 2, 9, 1, 8, 15};

    private static int[] buildReverseTable() {
        int[] rev = new int[256];
        java.util.Arrays.fill(rev, -1);
        for (int i = 0; i < FWD_62.length; i++) {
            rev[FWD_62[i]] = i;
        }
        return rev;
    }

    @Test
    void encodedDataFieldRoundTripsExactlyForRandomSectorData(@TempDir Path tempDir) throws IOException {
        Random random = new Random(55);
        byte[] sectorData = new byte[256];
        random.nextBytes(sectorData);

        Path dsk = buildSyntheticDsk(tempDir, 0, 0, sectorData); // track 0, logical sector 0 -> physical 0
        DskDiskImage image = DskDiskImage.load(dsk);
        TrackBitStream track = image.trackAt(0);

        readUntilPattern(track, 0xD5, 0xAA, 0xAD);
        byte[] decoded = decodeDataField(readNBytes(track, 343));

        assertArrayEquals(sectorData, decoded, "decoded sector data should exactly match the original");
    }

    @Test
    void encodedDataFieldRoundTripsForAllZeroSector(@TempDir Path tempDir) throws IOException {
        byte[] sectorData = new byte[256];
        Path dsk = buildSyntheticDsk(tempDir, 0, 0, sectorData);
        DskDiskImage image = DskDiskImage.load(dsk);
        TrackBitStream track = image.trackAt(0);

        readUntilPattern(track, 0xD5, 0xAA, 0xAD);
        assertArrayEquals(sectorData, decodeDataField(readNBytes(track, 343)));
    }

    @Test
    void encodedDataFieldRoundTripsForAllOnesSector(@TempDir Path tempDir) throws IOException {
        byte[] sectorData = new byte[256];
        java.util.Arrays.fill(sectorData, (byte) 0xFF);
        Path dsk = buildSyntheticDsk(tempDir, 0, 0, sectorData);
        DskDiskImage image = DskDiskImage.load(dsk);
        TrackBitStream track = image.trackAt(0);

        readUntilPattern(track, 0xD5, 0xAA, 0xAD);
        assertArrayEquals(sectorData, decodeDataField(readNBytes(track, 343)));
    }

    @Test
    void fullSectorSkewIsAppliedCorrectlyAcrossAllSixteenSectors(@TempDir Path tempDir) throws IOException {
        // Fill every logical sector of one track with a distinct, recognizable byte value, then
        // confirm each physical position holds exactly the logical sector the real DOS 3.3 skew
        // table says it should -- not just that "some" sector round-trips, but that the mapping
        // from logical sector number to physical position is the real, documented one.
        int track = 5;
        byte[] disk = new byte[35 * 16 * 256];
        for (int logicalSector = 0; logicalSector < 16; logicalSector++) {
            int offset = (track * 16 + logicalSector) * 256;
            java.util.Arrays.fill(disk, offset, offset + 256, (byte) (0x10 + logicalSector));
        }
        Path dsk = tempDir.resolve("skew_" + System.nanoTime() + ".dsk");
        Files.write(dsk, disk);

        DskDiskImage image = DskDiskImage.load(dsk);
        TrackBitStream stream = image.trackAt(track * 4);

        Map<Integer, byte[]> physicalPositionToData = new HashMap<>();
        for (int i = 0; i < 16; i++) {
            int[] address = readAddressField(stream);
            byte[] data = readAndDecodeDataField(stream);
            physicalPositionToData.put(address[2], data); // address[2] is the physical sector number
        }

        for (int physicalPosition = 0; physicalPosition < 16; physicalPosition++) {
            int expectedLogicalSector = PHYSICAL_TO_LOGICAL[physicalPosition];
            byte expectedByte = (byte) (0x10 + expectedLogicalSector);
            byte[] actual = physicalPositionToData.get(physicalPosition);
            assertEquals(expectedByte, actual[0],
                "physical position " + physicalPosition + " should hold logical sector "
                + expectedLogicalSector + "'s data");
        }
    }

    @Test
    void addressFieldTrackNumberMatchesTheRequestedTrack(@TempDir Path tempDir) throws IOException {
        int track = 12;
        byte[] disk = new byte[35 * 16 * 256];
        Path dsk = tempDir.resolve("track_" + System.nanoTime() + ".dsk");
        Files.write(dsk, disk);
        DskDiskImage image = DskDiskImage.load(dsk);
        TrackBitStream stream = image.trackAt(track * 4);

        for (int i = 0; i < 16; i++) {
            int[] address = readAddressField(stream);
            assertEquals(track, address[1], "address field's track number should match the requested track");
            readAndDecodeDataField(stream);
        }
    }

    @Test
    void wrongFileSizeIsRejected(@TempDir Path tempDir) throws IOException {
        Path badFile = tempDir.resolve("wrong-size.dsk");
        Files.write(badFile, new byte[1000]);
        assertThrows(IllegalArgumentException.class, () -> DskDiskImage.load(badFile));
    }

    @Test
    void dskImagesAreNeverWriteProtected(@TempDir Path tempDir) throws IOException {
        Path dsk = buildSyntheticDsk(tempDir, 0, 0, new byte[256]);
        DskDiskImage image = DskDiskImage.load(dsk);
        assertFalse(image.isWriteProtected());
    }

    @Test
    void everyOnDiskByteIsAValidSixAndTwoNibble(@TempDir Path tempDir) throws IOException {
        Random random = new Random(1);
        byte[] sectorData = new byte[256];
        random.nextBytes(sectorData);
        Path dsk = buildSyntheticDsk(tempDir, 5, 3, sectorData);
        DskDiskImage image = DskDiskImage.load(dsk);
        TrackBitStream track = image.trackAt(5 * 4);

        readUntilPattern(track, 0xD5, 0xAA, 0xAD);
        for (int nibble : readNBytes(track, 343)) {
            assertTrue(REV_62[nibble] != -1, "every on-disk byte must be a valid 6-and-2 nibble: "
                + Integer.toHexString(nibble));
        }
    }

    // === Test infrastructure ===

    private static Path buildSyntheticDsk(Path dir, int track, int logicalSector, byte[] sectorData) throws IOException {
        byte[] disk = new byte[35 * 16 * 256];
        int offset = (track * 16 + logicalSector) * 256;
        System.arraycopy(sectorData, 0, disk, offset, 256);
        Path path = dir.resolve("synthetic_" + System.nanoTime() + ".dsk");
        Files.write(path, disk);
        return path;
    }

    private static int[] readAddressField(TrackBitStream stream) {
        readUntilPattern(stream, 0xD5, 0xAA, 0x96);
        int volume = decode44(readByte(stream), readByte(stream));
        int track = decode44(readByte(stream), readByte(stream));
        int sector = decode44(readByte(stream), readByte(stream));
        int checksum = decode44(readByte(stream), readByte(stream));
        return new int[] {volume, track, sector, checksum};
    }

    private static byte[] readAndDecodeDataField(TrackBitStream stream) {
        readUntilPattern(stream, 0xD5, 0xAA, 0xAD);
        return decodeDataField(readNBytes(stream, 343));
    }

    /** Real DOS 3.3 4-and-4 decode: ((n1<<1)|1) & n2. */
    private static int decode44(int n1, int n2) {
        return ((n1 << 1) | 0x01) & n2;
    }

    private static void readUntilPattern(TrackBitStream track, int a, int b, int c) {
        int[] window = {-1, -1, -1};
        while (true) {
            int by = readByte(track);
            window[0] = window[1];
            window[1] = window[2];
            window[2] = by;
            if (window[0] == a && window[1] == b && window[2] == c) {
                return;
            }
        }
    }

    private static int[] readNBytes(TrackBitStream track, int n) {
        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            result[i] = readByte(track);
        }
        return result;
    }

    /**
     * Reads the next disk byte the way the controller's latch forms one:
     * leading zero bits are skipped, and the byte is the first 1 bit plus
     * the seven after it. Every valid disk byte has its high bit set, so
     * this is exact -- and it's what lets a reader cross DskDiskImage's
     * 10-bit self-sync gaps (FF plus two zero bits), which a reader taking
     * raw 8-bit chunks would misframe.
     */
    private static int readByte(TrackBitStream track) {
        int b;
        do {
            b = track.nextBit();
        } while (b == 0);
        for (int i = 0; i < 7; i++) {
            b = (b << 1) | track.nextBit();
        }
        return b;
    }

    /** Reference decoder for the 343-nibble 6-and-2 data field, ported from a2kit's decode_sector_62_256. */
    private static byte[] decodeDataField(int[] nibbles) {
        int[] twos = new int[86 * 3];
        int checksum = 0;
        int idx = 0;
        for (int i = 0; i < 86; i++) {
            int val = REV_62[nibbles[idx++]];
            checksum ^= val;
            twos[i] = ((checksum & 0x01) << 1) | ((checksum & 0x02) >> 1);
            twos[i + 86] = ((checksum & 0x04) >> 1) | ((checksum & 0x08) >> 3);
            twos[i + 172] = ((checksum & 0x10) >> 3) | ((checksum & 0x20) >> 5);
        }
        byte[] result = new byte[256];
        for (int i = 0; i < 256; i++) {
            int val = REV_62[nibbles[idx++]];
            checksum ^= val;
            result[i] = (byte) ((checksum << 2) | twos[i]);
        }
        int finalChecksumNibble = REV_62[nibbles[idx]];
        checksum ^= finalChecksumNibble;
        if (checksum != 0) {
            throw new AssertionError("checksum verification failed: " + checksum);
        }
        return result;
    }

    /**
     * True if this process can write to a file even after its own
     * write permission bit is cleared -- see the identical helper in
     * {@code WozDiskImageTest} for the full explanation (confirmed
     * directly: true for root, which this sandbox runs as).
     */
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
        probe.toFile().setWritable(true);
        Files.deleteIfExists(probe);
        return bypassed;
    }

    @Test
    void isWriteProtectedReflectsTheHostFilesOwnPermission(@TempDir Path tempDir) throws IOException {
        assumeFalse(currentProcessBypassesFilePermissions(tempDir),
            "this process bypasses file permissions (likely running as root) -- host-file-based write-protect can't be meaningfully tested here");

        Path dsk = buildSyntheticDsk(tempDir, 0, 0, new byte[256]);
        DskDiskImage image = DskDiskImage.load(dsk);
        assertFalse(image.isWriteProtected(), "a freshly-created temp file should be writable by default");

        dsk.toFile().setWritable(false);
        assertTrue(image.isWriteProtected(), "read-only on the host file should be reported as write-protected");

        dsk.toFile().setWritable(true);
        assertFalse(image.isWriteProtected(), "restoring host write access should clear write-protect again");
    }

    /**
     * True if {@code path}'s filesystem supports POSIX permission bits
     * at all -- false on Windows. See the identical helper in
     * {@code WozDiskImageTest} for the full explanation: write-protect
     * itself works cross-platform via {@link Files#isWritable}/
     * {@code File.setWritable}; only this specific, more granular check
     * of the raw permission bit needs this guard.
     */
    private static boolean isPosixFilesystem(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class);
    }

    @Test
    void setWriteProtectedChangesTheHostFilesPermissionBit(@TempDir Path tempDir) throws IOException {
        // Checks the raw POSIX permission bit directly, which is
        // root-independent, rather than Files.isWritable/isWriteProtected,
        // which reflect *effective* access and are true for root
        // regardless of the bit (confirmed directly in this sandbox).
        Path dsk = buildSyntheticDsk(tempDir, 0, 0, new byte[256]);
        DskDiskImage image = DskDiskImage.load(dsk);

        image.setWriteProtected(true);
        if (isPosixFilesystem(dsk)) {
            assertFalse(Files.getPosixFilePermissions(dsk).contains(PosixFilePermission.OWNER_WRITE),
                "the owner-write permission bit should be cleared");
        }

        image.setWriteProtected(false);
        if (isPosixFilesystem(dsk)) {
            assertTrue(Files.getPosixFilePermissions(dsk).contains(PosixFilePermission.OWNER_WRITE),
                "the owner-write permission bit should be restored");
        }
        assertFalse(image.isWriteProtected(), "restoring write access should clear write-protect even under a privileged process");
    }
}
