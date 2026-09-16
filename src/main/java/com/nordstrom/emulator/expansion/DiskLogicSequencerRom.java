package com.nordstrom.emulator.expansion;

/**
 * The Disk II controller's P6/P6A logic state sequencer (LSS) table
 * (Apple part numbers 341-0028 and 342-0028-A -- confirmed byte-for-byte
 * identical to each other, the same silicon under two part numbers).
 * 256 bytes; NOT CPU-addressable -- this drives the LSS state machine
 * directly, ticking once every 4 CPU cycles, indexed by
 * {@code {state:4}{Q7,Q6:2}{latchMSB:1}{sense:1}} rather than sitting in
 * the CPU's own address space the way {@link DiskBootRom} does.
 * <p>
 * Verified against Joao Ricardo Pagotto's Apple-Disk-II-PROM-Verify
 * project (a hardware PROM dumper, MIT licensed -- see NOTICE):
 * <pre>
 *   CRC32: B72A2C70
 *   SHA1:  bc39fbd5b9a8d2287ac5d0a42e639fc4d3c2f9d4
 * </pre>
 * Both 341-0028.BIN and 342-0028-A.BIN in that project's dumps carry
 * these exact checksums, confirming their identity independently rather
 * than merely asserting it. Unlike {@link DiskBootRom}, this checksum
 * has not been independently cross-checked against MAME's own source --
 * worth doing if that source location is found, though nothing here
 * currently depends on that additional confirmation.
 * <p>
 * Backed by a real {@code byte[]}, not {@code int[]}, for the same
 * reason as {@link DiskBootRom}: pure, fixed, read-only storage, never
 * itself computed on.
 */
public final class DiskLogicSequencerRom {

    private static final byte[] DATA = {
        (byte) 0x18, (byte) 0xD8, (byte) 0x18, (byte) 0x08, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x18, (byte) 0x39, (byte) 0x18, (byte) 0x39, (byte) 0x18, (byte) 0x3B, (byte) 0x18, (byte) 0x3B,
        (byte) 0x18, (byte) 0x38, (byte) 0x18, (byte) 0x28, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x18, (byte) 0x39, (byte) 0x18, (byte) 0x39, (byte) 0x18, (byte) 0x3B, (byte) 0x18, (byte) 0x3B,
        (byte) 0x2D, (byte) 0xD8, (byte) 0x38, (byte) 0x48, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x28, (byte) 0x48, (byte) 0x28, (byte) 0x48, (byte) 0x28, (byte) 0x48, (byte) 0x28, (byte) 0x48,
        (byte) 0x2D, (byte) 0x48, (byte) 0x38, (byte) 0x48, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x28, (byte) 0x48, (byte) 0x28, (byte) 0x48, (byte) 0x28, (byte) 0x48, (byte) 0x28, (byte) 0x48,
        (byte) 0xD8, (byte) 0xD8, (byte) 0xD8, (byte) 0xD8, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x58, (byte) 0x78, (byte) 0x58, (byte) 0x78, (byte) 0x58, (byte) 0x78, (byte) 0x58, (byte) 0x78,
        (byte) 0x58, (byte) 0x78, (byte) 0x58, (byte) 0x78, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x58, (byte) 0x78, (byte) 0x58, (byte) 0x78, (byte) 0x58, (byte) 0x78, (byte) 0x58, (byte) 0x78,
        (byte) 0xD8, (byte) 0xD8, (byte) 0xD8, (byte) 0xD8, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x68, (byte) 0x08, (byte) 0x68, (byte) 0x88, (byte) 0x68, (byte) 0x08, (byte) 0x68, (byte) 0x88,
        (byte) 0x68, (byte) 0x88, (byte) 0x68, (byte) 0x88, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x68, (byte) 0x08, (byte) 0x68, (byte) 0x88, (byte) 0x68, (byte) 0x08, (byte) 0x68, (byte) 0x88,
        (byte) 0xD8, (byte) 0xCD, (byte) 0xD8, (byte) 0xD8, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x98, (byte) 0xB9, (byte) 0x98, (byte) 0xB9, (byte) 0x98, (byte) 0xBB, (byte) 0x98, (byte) 0xBB,
        (byte) 0x98, (byte) 0xBD, (byte) 0x98, (byte) 0xB8, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x98, (byte) 0xB9, (byte) 0x98, (byte) 0xB9, (byte) 0x98, (byte) 0xBB, (byte) 0x98, (byte) 0xBB,
        (byte) 0xD8, (byte) 0xD9, (byte) 0xD8, (byte) 0xD8, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0xA8, (byte) 0xC8, (byte) 0xA8, (byte) 0xC8, (byte) 0xA8, (byte) 0xC8, (byte) 0xA8, (byte) 0xC8,
        (byte) 0x29, (byte) 0x59, (byte) 0xA8, (byte) 0xC8, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0xA8, (byte) 0xC8, (byte) 0xA8, (byte) 0xC8, (byte) 0xA8, (byte) 0xC8, (byte) 0xA8, (byte) 0xC8,
        (byte) 0xD9, (byte) 0xFD, (byte) 0xD8, (byte) 0xF8, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0xD8, (byte) 0xF8, (byte) 0xD8, (byte) 0xF8, (byte) 0xD8, (byte) 0xF8, (byte) 0xD8, (byte) 0xF8,
        (byte) 0xD9, (byte) 0xFD, (byte) 0xA0, (byte) 0xF8, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0xD8, (byte) 0xF8, (byte) 0xD8, (byte) 0xF8, (byte) 0xD8, (byte) 0xF8, (byte) 0xD8, (byte) 0xF8,
        (byte) 0xD8, (byte) 0xDD, (byte) 0xE8, (byte) 0xE0, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0xE8, (byte) 0x88, (byte) 0xE8, (byte) 0x08, (byte) 0xE8, (byte) 0x88, (byte) 0xE8, (byte) 0x08,
        (byte) 0x08, (byte) 0x4D, (byte) 0xE8, (byte) 0xE0, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0x0A, (byte) 0xE8, (byte) 0x88, (byte) 0xE8, (byte) 0x08, (byte) 0xE8, (byte) 0x88, (byte) 0xE8, (byte) 0x08,
    };

    /**
     * Reads one byte (0-255) at {@code offset} (0-255) within this table.
     *
     * @param offset 0-255 within this table
     * @return the byte at that offset
     */
    public static int read(int offset) {
        return DATA[offset] & 0xFF;
    }
}
