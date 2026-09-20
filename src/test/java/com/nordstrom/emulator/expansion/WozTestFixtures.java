package com.nordstrom.emulator.expansion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * Builds spec-compliant synthetic WOZ2 files at test-run time, shared
 * by {@link WozDiskImageTest} and {@link Disk2ControllerTickTest}
 * rather than duplicated in each. Deliberately generates its own file
 * bytes rather than depending on a committed {@code .woz} fixture --
 * this project's {@code .gitignore} excludes {@code *.woz} files for
 * copyright reasons regardless of whether a specific file needs that
 * protection, so a committed test fixture would silently vanish on a
 * fresh checkout.
 */
final class WozTestFixtures {

    /** Track 0.00's known bit pattern, deliberately not byte-aligned (24 bits). */
    static final String TRACK_0_BITS = "101100111000010101101001";
    /** Track 1.00's known bit pattern, deliberately not byte-aligned (11 bits). */
    static final String TRACK_1_BITS = "11110000101";

    private WozTestFixtures() {}

    /**
     * Builds a minimal, spec-compliant WOZ2 file byte-for-byte: a real
     * header (magic, CRC32 of everything after it), a real 60-byte
     * INFO chunk, a real 160-byte TMAP mapping only quarter-tracks 0
     * and 4, and a real TRKS chunk with two tracks of known,
     * deliberately non-byte-aligned bit patterns ({@link #TRACK_0_BITS},
     * {@link #TRACK_1_BITS}) at their real block-aligned file offsets.
     *
     * @param dir where to write the file (typically a JUnit {@code @TempDir})
     * @param writeProtected the INFO chunk's write-protected flag
     * @return the path of the file just written
     */
    static Path buildSyntheticWozFile(Path dir, boolean writeProtected) throws IOException {
        int blockSize = 512;

        byte[] track0Bytes = packBits(TRACK_0_BITS);
        byte[] track1Bytes = packBits(TRACK_1_BITS);
        int track0Blocks = (track0Bytes.length + blockSize - 1) / blockSize;
        int track1Blocks = (track1Bytes.length + blockSize - 1) / blockSize;
        int track0StartBlock = 3; // TRKS's 160*8-byte TRK array occupies blocks 0-2 of the chunk's own data
        int track1StartBlock = track0StartBlock + track0Blocks;

        byte[] info = new byte[60];
        info[0] = 3; // INFO version
        info[1] = 1; // disk type: 5.25"
        info[2] = (byte) (writeProtected ? 1 : 0);
        info[37] = 1; // disk sides

        byte[] tmap = new byte[160];
        java.util.Arrays.fill(tmap, (byte) 0xFF);
        tmap[0] = 0; // track 0.00 -> TRKS entry 0
        tmap[4] = 1; // track 1.00 -> TRKS entry 1

        byte[] trkEntries = new byte[8 * 160];
        writeU16(trkEntries, 0, track0StartBlock);
        writeU16(trkEntries, 2, track0Blocks);
        writeU32(trkEntries, 4, TRACK_0_BITS.length());
        writeU16(trkEntries, 8, track1StartBlock);
        writeU16(trkEntries, 10, track1Blocks);
        writeU32(trkEntries, 12, TRACK_1_BITS.length());

        byte[] track0Padded = new byte[track0Blocks * blockSize];
        System.arraycopy(track0Bytes, 0, track0Padded, 0, track0Bytes.length);
        byte[] track1Padded = new byte[track1Blocks * blockSize];
        System.arraycopy(track1Bytes, 0, track1Padded, 0, track1Bytes.length);

        byte[] trksData = concat(trkEntries, track0Padded, track1Padded);

        byte[] infoChunk = chunk("INFO", info);
        byte[] tmapChunk = chunk("TMAP", tmap);
        byte[] trksChunk = chunk("TRKS", trksData);
        byte[] body = concat(infoChunk, tmapChunk, trksChunk);

        CRC32 crc = new CRC32();
        crc.update(body);
        byte[] header = new byte[12];
        header[0] = 'W'; header[1] = 'O'; header[2] = 'Z'; header[3] = '2';
        header[4] = (byte) 0xFF;
        header[5] = 0x0A; header[6] = 0x0D; header[7] = 0x0A;
        writeU32(header, 8, (int) crc.getValue());

        byte[] fullFile = concat(header, body);
        Path path = dir.resolve("synthetic_test_" + System.nanoTime() + ".woz");
        Files.write(path, fullFile);
        return path;
    }

    private static byte[] packBits(String bits) {
        String padded = bits + "0".repeat((8 - bits.length() % 8) % 8);
        byte[] result = new byte[padded.length() / 8];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(padded.substring(i * 8, i * 8 + 8), 2);
        }
        return result;
    }

    private static byte[] chunk(String id, byte[] data) {
        byte[] result = new byte[8 + data.length];
        result[0] = (byte) id.charAt(0);
        result[1] = (byte) id.charAt(1);
        result[2] = (byte) id.charAt(2);
        result[3] = (byte) id.charAt(3);
        writeU32(result, 4, data.length);
        System.arraycopy(data, 0, result, 8, data.length);
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            total += a.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, offset, a.length);
            offset += a.length;
        }
        return result;
    }

    private static void writeU16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeU32(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
