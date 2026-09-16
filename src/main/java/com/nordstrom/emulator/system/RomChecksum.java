package com.nordstrom.emulator.system;

import java.util.zip.CRC32;

/**
 * Verifies a byte range's CRC32 against an expected value, throwing
 * loudly on mismatch. Checksums were verified once, at development
 * time, against real hardware dumps and MAME's own source -- this is
 * what makes that verification durable rather than a one-time claim: if
 * a resource file is ever truncated, corrupted, or accidentally
 * substituted for the wrong one, this fails at class-load time with a
 * specific, actionable error instead of silently serving wrong data
 * that could take a long time to trace back to its actual cause.
 */
public final class RomChecksum {

    /**
     * Verifies {@code data[offset..offset+length)}'s CRC32 matches
     * {@code expectedCrc32} (an 8-hex-digit string, case-insensitive).
     *
     * @param data the loaded ROM bytes
     * @param offset start of the slice to verify
     * @param length length of the slice to verify
     * @param expectedCrc32 the expected CRC32, as 8 hex digits
     * @param label what to call this slice in the error message if verification fails
     * @throws IllegalStateException if the computed CRC32 doesn't match
     */
    public static void verify(byte[] data, int offset, int length, String expectedCrc32, String label) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        String actual = String.format("%08x", crc.getValue());
        if (!actual.equalsIgnoreCase(expectedCrc32)) {
            throw new IllegalStateException(label + " failed checksum verification: expected CRC32 "
                + expectedCrc32 + ", got " + actual + ". The loaded ROM data does not match what was "
                + "verified against its real hardware source -- this resource is corrupted, truncated, "
                + "or was substituted for the wrong file.");
        }
    }

    private RomChecksum() {}
}
