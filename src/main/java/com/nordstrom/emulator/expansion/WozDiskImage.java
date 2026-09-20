package com.nordstrom.emulator.expansion;

import com.nordstrom.emulator.system.RomChecksum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * Parses a WOZ2 disk image file (the format's own reference:
 * applesaucefdc.com/woz/reference2/) into disk metadata and bit-level
 * per-quarter-track access. Deliberately scoped to 5.25-inch disks --
 * the only kind a real Apple II+ Disk II drive reads -- so 3.5-inch
 * disks' different TMAP layout, and the FLUX/WRIT chunks (relevant only
 * to writing WOZ files back to real media or to flux-level capture,
 * neither a concern for this project) are not implemented at all.
 * <p>
 * Real hardware detail this class takes seriously: the bitstream is
 * genuinely bit-level, not byte-aligned -- a track's declared bit count
 * is essentially never a multiple of 8, and the order of bits within
 * each stored byte is high to low (the spec's own words), not low to
 * high. Both are handled exactly, not approximated, by
 * {@link TrackBitStream}.
 * <p>
 * The header's CRC32 uses the exact same standard algorithm (credited
 * in the spec to Gary S. Brown -- the well-known IEEE 802.3/zlib
 * CRC-32) already used by {@link RomChecksum} elsewhere in this
 * project, via {@link java.util.zip.CRC32} directly rather than
 * hand-porting the spec's own C lookup table. A CRC of exactly 0 means
 * the file's creator didn't compute one at all -- the spec says to
 * skip verification in that case, not treat it as a mismatch.
 * <p>
 * Empty (unmapped, 0xFF in TMAP) quarter-tracks are a real, deliberately
 * simplified case: the spec recommends a 51,200-bit fake track filled
 * with a randomized, roughly-30%-ones pattern to plausibly emulate the
 * Disk II hardware's own "weak bits" behavior on truly blank media.
 * This class returns that same 51,200-bit length (so software checking
 * for a plausible track size behaves the same way) but filled with
 * zeros, not randomized weak bits -- a real simplification, not a
 * hidden one.
 */
public final class WozDiskImage implements DiskImage {

    private static final int HEADER_SIZE = 12;
    private static final int TMAP_ENTRIES = 160;
    private static final int TRK_ENTRY_COUNT = 160;
    private static final int TRK_ENTRY_SIZE = 8;
    private static final int BLOCK_SIZE = 512;
    private static final int EMPTY_TRACK_BIT_COUNT = 51_200;

    private final boolean writeProtected;
    private final int[] trackMap = new int[TMAP_ENTRIES]; // TRKS index per quarter-track, or 0xFF
    private final byte[][] trackData = new byte[TRK_ENTRY_COUNT][];
    private final int[] trackBitCounts = new int[TRK_ENTRY_COUNT];

    /**
     * Loads and parses a WOZ2 disk image file.
     *
     * @param path the .woz file to load
     * @return the parsed disk image
     * @throws IOException if the file can't be read
     * @throws IllegalArgumentException if the file isn't a valid 5.25-inch WOZ2 image
     * @throws IllegalStateException if the file declares a non-zero CRC32 that doesn't match its actual content
     */
    public static WozDiskImage load(Path path) throws IOException {
        byte[] file = Files.readAllBytes(path);
        if (file.length < HEADER_SIZE) {
            throw new IllegalArgumentException(path + " is too short to be a WOZ file");
        }
        requireMagic(file, path);
        verifyCrc(file, path);
        return new WozDiskImage(file, path);
    }

    private WozDiskImage(byte[] file, Path path) {
        int infoDiskType = -1;
        boolean parsedWriteProtected = false;
        boolean sawInfo = false;
        boolean sawTmap = false;
        boolean sawTrks = false;

        int offset = HEADER_SIZE;
        while (offset + 8 <= file.length) {
            String chunkId = new String(file, offset, 4, StandardCharsets.US_ASCII);
            int chunkSize = readU32(file, offset + 4);
            int dataStart = offset + 8;

            switch (chunkId) {
                case "INFO" -> {
                    infoDiskType = file[dataStart + 1] & 0xFF;
                    parsedWriteProtected = (file[dataStart + 2] & 0xFF) == 1;
                    sawInfo = true;
                }
                case "TMAP" -> {
                    for (int i = 0; i < TMAP_ENTRIES; i++) {
                        trackMap[i] = file[dataStart + i] & 0xFF;
                    }
                    sawTmap = true;
                }
                case "TRKS" -> {
                    parseTrks(file, dataStart);
                    sawTrks = true;
                }
                default -> { /* META, WRIT, FLUX, or anything else: not needed, skip */ }
            }

            offset = dataStart + chunkSize;
        }

        if (!sawInfo || !sawTmap || !sawTrks) {
            throw new IllegalArgumentException(path + " is missing a required chunk (INFO, TMAP, or TRKS)");
        }
        if (infoDiskType != 1) {
            throw new IllegalArgumentException(path + " is not a 5.25-inch disk image (INFO disk type = "
                + infoDiskType + ") -- this project only supports 5.25-inch Disk II media");
        }

        this.writeProtected = parsedWriteProtected;
    }

    private void parseTrks(byte[] file, int trksDataStart) {
        for (int i = 0; i < TRK_ENTRY_COUNT; i++) {
            int entryOffset = trksDataStart + i * TRK_ENTRY_SIZE;
            int startingBlock = readU16(file, entryOffset);
            int blockCount = readU16(file, entryOffset + 2);
            int bitCount = readU32(file, entryOffset + 4);
            if (blockCount == 0) {
                continue; // unused TRK entry
            }
            int byteStart = startingBlock * BLOCK_SIZE;
            int byteLength = (bitCount + 7) / 8;
            byte[] data = new byte[byteLength];
            System.arraycopy(file, byteStart, data, 0, byteLength);
            trackData[i] = data;
            trackBitCounts[i] = bitCount;
        }
    }

    private static void requireMagic(byte[] file, Path path) {
        boolean magicOk = file[0] == 'W' && file[1] == 'O' && file[2] == 'Z' && file[3] == '2'
            && (file[4] & 0xFF) == 0xFF && file[5] == 0x0A && file[6] == 0x0D && file[7] == 0x0A;
        if (!magicOk) {
            throw new IllegalArgumentException(path + " does not have a valid WOZ2 header");
        }
    }

    private static void verifyCrc(byte[] file, Path path) {
        int declaredCrc = readU32(file, 8);
        if (declaredCrc == 0) {
            return; // spec: a 0 CRC means none was computed -- skip verification
        }
        CRC32 crc = new CRC32();
        crc.update(file, HEADER_SIZE, file.length - HEADER_SIZE);
        int actual = (int) crc.getValue();
        if (actual != declaredCrc) {
            throw new IllegalStateException(path + " failed CRC32 verification: file declares "
                + String.format("%08x", declaredCrc) + ", computed " + String.format("%08x", actual)
                + " -- this file is corrupted or truncated");
        }
    }

    private static int readU16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readU32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    /**
     * @return true if this disk image is marked write-protected
     */
    @Override
    public boolean isWriteProtected() {
        return writeProtected;
    }

    /**
     * Returns a bit-level reader for the track visible at the given
     * quarter-track head position.
     *
     * @param quarterTrack 0-159 (track number x4, plus 0-3 for the
     *                     quarter-track offset within it)
     * @return a bit stream over that quarter-track's data
     */
    @Override
    public TrackBitStream trackAt(int quarterTrack) {
        int trksIndex = trackMap[quarterTrack];
        if (trksIndex == 0xFF || trackData[trksIndex] == null) {
            return new TrackBitStream(new byte[EMPTY_TRACK_BIT_COUNT / 8], EMPTY_TRACK_BIT_COUNT);
        }
        return new TrackBitStream(trackData[trksIndex], trackBitCounts[trksIndex]);
    }

    /**
     * A single track's bit-level data, with a wrapping read position --
     * real disk tracks are circular, so reading past the last bit
     * continues from the first.
     */
}
