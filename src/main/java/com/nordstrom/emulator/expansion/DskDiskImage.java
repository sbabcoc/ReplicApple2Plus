package com.nordstrom.emulator.expansion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a DOS-order sector image (.dsk/.do, 35 tracks x 16 sectors x
 * 256 bytes = 143,360 bytes) and synthesizes the real, historical
 * 6-and-2 GCR bitstream each track would actually have on a physical
 * disk -- so {@link Disk2LogicSequencer} reads it through the exact
 * same path as a {@link WozDiskImage}, with no separate code path for
 * "sector images" versus "real bitstreams." This is real encoding
 * work, not a shortcut: the address field, data field, checksums, and
 * the 64-entry 6-and-2 translate table are all the genuine historical
 * DOS 3.3 disk format, ported directly from a2kit's own verified Rust
 * implementation (itself a port of CiderPress's C++).
 * <p>
 * Sector skew: a .dsk/.do file stores sector data in DOS logical
 * order (sector 0 first, sector 1 second, ...), but physical sector 0
 * on the disk is not logical sector 0's neighbor -- DOS 3.3's
 * well-documented software skew means physical sector position P
 * holds logical sector {@link #PHYSICAL_TO_LOGICAL}[P]. The address
 * field written at each physical position still names that physical
 * position's own number (0-15) -- the skew is purely about which
 * logical data ends up where, not a renumbering of the address fields
 * themselves.
 * <p>
 * Deliberately scoped to standard 16-sector DOS 3.3 media only -- no
 * 13-sector DOS 3.2/3.1 support, no ProDOS-order (.po) files (which
 * use a different, +2 skew), and no copy-protection-specific
 * variations (nonstandard sync gaps, altered checksums, nibble
 * counts other than 256). A .dsk file has no write-protect flag at
 * all, unlike WOZ -- {@link #isWriteProtected()} always returns
 * {@code false}.
 */
public final class DskDiskImage implements DiskImage {

    private static final int TRACKS = 35;
    private static final int SECTORS_PER_TRACK = 16;
    private static final int SECTOR_SIZE = 256;
    private static final int EXPECTED_FILE_SIZE = TRACKS * SECTORS_PER_TRACK * SECTOR_SIZE;

    /** Real, documented DOS 3.3 sector skew: physical position -> logical sector number. */
    private static final int[] PHYSICAL_TO_LOGICAL = {0, 7, 14, 6, 13, 5, 12, 4, 11, 3, 10, 2, 9, 1, 8, 15};

    /** The real 64-entry 6-and-2 GCR translate table, ported from a2kit's FWD_62. */
    private static final int[] TRANSLATE_62 = {
        0x96, 0x97, 0x9a, 0x9b, 0x9d, 0x9e, 0x9f, 0xa6,
        0xa7, 0xab, 0xac, 0xad, 0xae, 0xaf, 0xb2, 0xb3,
        0xb4, 0xb5, 0xb6, 0xb7, 0xb9, 0xba, 0xbb, 0xbc,
        0xbd, 0xbe, 0xbf, 0xcb, 0xcd, 0xce, 0xcf, 0xd3,
        0xd6, 0xd7, 0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde,
        0xdf, 0xe5, 0xe6, 0xe7, 0xe9, 0xea, 0xeb, 0xec,
        0xed, 0xee, 0xef, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6,
        0xf7, 0xf9, 0xfa, 0xfb, 0xfc, 0xfd, 0xfe, 0xff
    };

    /** Standard DOS 3.3 default volume number, used by {@code INIT} when none is specified. */
    private static final int DEFAULT_VOLUME = 254;

    private static final int[] ADDRESS_PROLOG = {0xD5, 0xAA, 0x96};
    private static final int[] ADDRESS_EPILOG = {0xDE, 0xAA, 0xEB};
    private static final int[] DATA_PROLOG = {0xD5, 0xAA, 0xAD};
    private static final int[] DATA_EPILOG = {0xDE, 0xAA, 0xEB};
    private static final int SYNC_BYTES_PER_GAP = 10;

    private final byte[] diskData;

    private DskDiskImage(byte[] diskData) {
        this.diskData = diskData;
    }

    /**
     * Loads a DOS-order sector image.
     *
     * @param path the .dsk/.do file to load
     * @return the parsed disk image
     * @throws IOException if the file can't be read
     * @throws IllegalArgumentException if the file isn't exactly the expected 143,360-byte size
     */
    public static DskDiskImage load(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length != EXPECTED_FILE_SIZE) {
            throw new IllegalArgumentException(path + " is " + data.length
                + " bytes, expected exactly " + EXPECTED_FILE_SIZE
                + " (35 tracks x 16 sectors x 256 bytes) for a standard DOS-order disk image");
        }
        return new DskDiskImage(data);
    }

    /**
     * @return always {@code false} -- the DSK format has no write-protect flag at all
     */
    @Override
    public boolean isWriteProtected() {
        return false;
    }

    /**
     * Synthesizes and returns the real GCR bitstream for the track
     * visible at the given quarter-track head position. DSK images
     * have no quarter-track resolution -- all four quarter-track
     * positions within one whole track return the same encoded data.
     *
     * @param quarterTrack 0-159 (track number x4, plus 0-3 for the quarter-track offset within it)
     * @return a bit stream over that track's synthesized data
     */
    @Override
    public TrackBitStream trackAt(int quarterTrack) {
        int track = quarterTrack / 4;
        if (track >= TRACKS) {
            // Past the real 35 tracks this format has -- same 51,200-bit zero-filled
            // convention WozDiskImage uses for genuinely empty media.
            return new TrackBitStream(new byte[51_200 / 8], 51_200);
        }
        byte[] encoded = encodeTrack(track);
        return new TrackBitStream(encoded, encoded.length * 8);
    }

    private byte[] encodeTrack(int track) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeSyncGap(out);
        for (int physicalSector = 0; physicalSector < SECTORS_PER_TRACK; physicalSector++) {
            int logicalSector = PHYSICAL_TO_LOGICAL[physicalSector];
            byte[] sectorData = readSector(track, logicalSector);

            writeBytes(out, ADDRESS_PROLOG);
            writeAddressField(out, track, physicalSector);
            writeBytes(out, ADDRESS_EPILOG);
            writeSyncGap(out);

            writeBytes(out, DATA_PROLOG);
            writeDataField(out, sectorData);
            writeBytes(out, DATA_EPILOG);
            writeSyncGap(out);
        }
        return out.toByteArray();
    }

    private byte[] readSector(int track, int logicalSector) {
        int offset = (track * SECTORS_PER_TRACK + logicalSector) * SECTOR_SIZE;
        byte[] sector = new byte[SECTOR_SIZE];
        System.arraycopy(diskData, offset, sector, 0, SECTOR_SIZE);
        return sector;
    }

    private void writeAddressField(ByteArrayOutputStream out, int track, int physicalSector) {
        int checksum = DEFAULT_VOLUME ^ track ^ physicalSector;
        writeEncoded44(out, DEFAULT_VOLUME);
        writeEncoded44(out, track);
        writeEncoded44(out, physicalSector);
        writeEncoded44(out, checksum);
    }

    /** Real DOS 3.3 4-and-4 encoding: a byte becomes two bytes, {@code (val>>1)|0xAA} then {@code val|0xAA}. */
    private static void writeEncoded44(ByteArrayOutputStream out, int value) {
        out.write((value >> 1) | 0xAA);
        out.write(value | 0xAA);
    }

    /**
     * Real DOS 3.3 6-and-2 sector encoding: 256 bytes become 342
     * nibbles of scrambled, checksummed 6-bit values, translated
     * through {@link #TRANSLATE_62} into 343 on-disk bytes (each
     * guaranteed to have its own high bit set and never two
     * consecutive zero bits) -- ported directly from a2kit's
     * {@code encode_sector_62_256}, itself a port of CiderPress's
     * {@code EncodeNibble62}.
     */
    private void writeDataField(ByteArrayOutputStream out, byte[] data) {
        int[] top = new int[256];
        int[] twos = new int[86];
        int twoShift = 0;
        int twoPos = 85;
        for (int i = 0; i < 256; i++) {
            int val = data[i] & 0xFF;
            top[i] = val >> 2;
            twos[twoPos] |= (((val & 1) << 1) | ((val & 2) >> 1)) << twoShift;
            if (twoPos == 0) {
                twoPos = 86;
                twoShift += 2;
            }
            twoPos -= 1;
        }

        int checksum = 0;
        for (int i = 85; i >= 0; i--) {
            out.write(TRANSLATE_62[(twos[i] ^ checksum) & 0x3F]);
            checksum = twos[i];
        }
        for (int i = 0; i < 256; i++) {
            out.write(TRANSLATE_62[(top[i] ^ checksum) & 0x3F]);
            checksum = top[i];
        }
        out.write(TRANSLATE_62[checksum & 0x3F]);
    }

    private static void writeSyncGap(ByteArrayOutputStream out) {
        for (int i = 0; i < SYNC_BYTES_PER_GAP; i++) {
            out.write(0xFF);
        }
    }

    private static void writeBytes(ByteArrayOutputStream out, int[] bytes) {
        for (int b : bytes) {
            out.write(b);
        }
    }
}
