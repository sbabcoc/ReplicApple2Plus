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
 * Empty (unmapped, 0xFF in TMAP) quarter-tracks match the spec exactly:
 * a 51,200-bit fake track, with every read returning fresh, genuine
 * randomness (roughly 30% ones, the spec's own documented figure) to
 * plausibly emulate the Disk II hardware's own "weak bits" behavior on
 * truly blank media -- confirmed directly against the spec's own
 * reference ("the emulator should be outputting random bits in this
 * case"), not a fixed or one-time-randomized substitute (see
 * {@link TrackBitStream#weakBits} for why genuine, per-read randomness
 * specifically matters here). {@link #createBlank} uses this same
 * mechanism for every track of a freshly created disk, until real
 * software actually formats each one.
 */
public final class WozDiskImage implements DiskImage {

    private static final int HEADER_SIZE = 12;
    private static final int TMAP_ENTRIES = 160;
    private static final int TRK_ENTRY_COUNT = 160;
    private static final int TRK_ENTRY_SIZE = 8;
    private static final int BLOCK_SIZE = 512;
    private static final int EMPTY_TRACK_BIT_COUNT = 51_200;

    private boolean writeProtected;
    private boolean dirty;
    private final Path path;
    private final int[] trackMap = new int[TMAP_ENTRIES]; // TRKS index per quarter-track, or 0xFF
    private final byte[][] trackData = new byte[TRK_ENTRY_COUNT][];
    private final int[] trackBitCounts = new int[TRK_ENTRY_COUNT];
    /** Each track's byte offset within the host file -- where {@link #persist} writes its current trackData back to. */
    private final int[] trackByteOffsets = new int[TRK_ENTRY_COUNT];
    /**
     * True for a track that has a real, pre-allocated slot (unlike a
     * genuinely-0xFF-unmapped one) but has never actually been written
     * to. Detected, not just remembered: {@link #parseTrks} sets this
     * for any track whose stored bytes are all zero (see
     * {@link #isAllZero}'s own Javadoc for why that's a fully reliable
     * signal, not a heuristic), so this is correctly set on every load
     * -- including a completely fresh reload, in a later session, of a
     * file {@link #createBlank} wrote earlier and nothing has formatted
     * yet, not only on the one in-memory instance {@code createBlank}
     * itself returns. See {@link TrackBitStream#weakBits}'s own Javadoc
     * for what this actually changes about how the track reads and
     * writes.
     */
    private final boolean[] trackIsVirgin = new boolean[TRK_ENTRY_COUNT];

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

    /** The historical, standard 5.25" disk track count DOS 3.3 itself expects -- what a freshly created blank disk gets. */
    private static final int STANDARD_TRACK_COUNT = 35;

    /**
     * Creates a new, blank, writable WOZ2 disk image file at {@code path}
     * and returns it loaded, ready to insert into a drive. Every one of
     * its {@value #STANDARD_TRACK_COUNT} standard tracks reads as fresh,
     * genuine randomized noise (see {@link TrackBitStream#weakBits}) until
     * real software -- a real {@code INIT}, included -- actually formats
     * it, at which point each track permanently becomes real, persisted
     * data the same as any other written track.
     * <p>
     * Deliberately pre-allocates a real TRK slot (and real file space) for
     * every standard track up front, rather than leaving them genuinely
     * unmapped (0xFF in TMAP, the WOZ spec's own representation for blank
     * media) and allocating one dynamically on first write: a real,
     * already-formatted-looking file structure means the existing write/
     * persist mechanism (built around every writable track already having
     * a known file offset) needs no changes at all for this to work,
     * rather than requiring the file to grow and gain new TRK entries
     * mid-session. Each track's placeholder content is all zero bytes,
     * which {@link #isAllZero} then recognizes as "still virgin" on any
     * load, not just this one -- so a disk created this way but never
     * formatted, then reloaded in a completely separate session, still
     * correctly resumes as fresh random noise rather than silently
     * becoming stable, meaningless zero data.
     *
     * @param path where to write the new file -- must not already exist
     * @return the new image, loaded and ready to use
     * @throws IOException if the file can't be written, or already exists
     */
    public static WozDiskImage createBlank(Path path) throws IOException {
        if (Files.exists(path)) {
            throw new IOException(path + " already exists");
        }
        Files.write(path, buildBlankFileBytes());
        // No separate step needed to mark the new tracks virgin: load()
        // -> parseTrks() detects that on its own, from the all-zero
        // content this method just wrote -- the same mechanism that also
        // means a later, completely fresh reload (a different session,
        // even) still correctly recognizes an unformatted track as
        // virgin, not just the instance this call happens to return.
        return load(path);
    }

    /**
     * Builds the raw bytes of a new, valid, minimal WOZ2 file: a header,
     * a real 60-byte INFO chunk (not write-protected; disk type 5.25";
     * every other field either accurately describing this file or left
     * at its spec-defined "unknown/not specified" default), a 160-byte
     * TMAP mapping only the {@value #STANDARD_TRACK_COUNT} standard
     * whole-track positions (quarter-tracks 0, 4, 8, ... -- real 5.25"
     * media has no meaningful content at the in-between quarter-track
     * positions for a disk imaged at standard, not finer, granularity),
     * and a TRKS chunk with a real, block-aligned slot for each of those
     * tracks, every byte zero (irrelevant until formatting actually
     * writes real data -- see {@link #createBlank}'s own Javadoc for why
     * reads ignore these zeros entirely until then).
     *
     * @return the complete file's bytes, CRC32 already computed and in place
     */
    private static byte[] buildBlankFileBytes() {
        int trkEntryTableSize = TRK_ENTRY_COUNT * TRK_ENTRY_SIZE;
        int blocksPerTrack = (EMPTY_TRACK_BIT_COUNT / 8 + BLOCK_SIZE - 1) / BLOCK_SIZE;

        byte[] info = new byte[60];
        info[0] = 3; // INFO chunk version
        info[1] = 1; // disk type: 5.25"
        info[2] = 0; // not write-protected
        info[3] = 0; // synchronized: no cross-track sync was used (not a real capture)
        info[4] = 0; // cleaned: not applicable -- this was never a physical MC3470 capture to begin with
        byte[] creator = "ReplicApple2Plus".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(creator, 0, info, 5, creator.length);
        for (int i = 5 + creator.length; i < 37; i++) {
            info[i] = ' '; // spec: creator field is space-padded to its full 32 bytes
        }
        info[37] = 1; // disk sides
        writeU16(info, 44, blocksPerTrack); // "largest track", in blocks -- every track here is the same size

        byte[] tmap = new byte[TMAP_ENTRIES];
        java.util.Arrays.fill(tmap, (byte) 0xFF);
        for (int track = 0; track < STANDARD_TRACK_COUNT; track++) {
            tmap[track * 4] = (byte) track; // whole-track positions only; TRK entry index == track number here
        }

        byte[] trkEntries = new byte[trkEntryTableSize];
        for (int track = 0; track < STANDARD_TRACK_COUNT; track++) {
            int entryOffset = track * TRK_ENTRY_SIZE;
            int startingBlock = 3 + track * blocksPerTrack; // block 3: right after this 3-block TRK entry table itself
            writeU16(trkEntries, entryOffset, startingBlock);
            writeU16(trkEntries, entryOffset + 2, blocksPerTrack);
            writeU32(trkEntries, entryOffset + 4, EMPTY_TRACK_BIT_COUNT);
        }

        byte[] trackData = new byte[STANDARD_TRACK_COUNT * blocksPerTrack * BLOCK_SIZE]; // all zeros -- never read until formatted
        byte[] trksData = concat(trkEntries, trackData);

        byte[] infoChunk = chunk("INFO", info);
        byte[] tmapChunk = chunk("TMAP", tmap);
        byte[] trksChunk = chunk("TRKS", trksData);
        byte[] body = concat(infoChunk, tmapChunk, trksChunk);

        CRC32 crc = new CRC32();
        crc.update(body);
        byte[] header = new byte[HEADER_SIZE];
        header[0] = 'W'; header[1] = 'O'; header[2] = 'Z'; header[3] = '2';
        header[4] = (byte) 0xFF;
        header[5] = 0x0A; header[6] = 0x0D; header[7] = 0x0A;
        writeU32(header, 8, (int) crc.getValue());

        return concat(header, body);
    }

    private static byte[] chunk(String id, byte[] data) {
        byte[] result = new byte[8 + data.length];
        for (int i = 0; i < 4; i++) {
            result[i] = (byte) id.charAt(i);
        }
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

    private WozDiskImage(byte[] file, Path path) {
        this.path = path;
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

    /**
     * Scans a WOZ file's chunks for the INFO chunk's data offset --
     * shared by the constructor (reading the write-protect byte
     * initially) and {@link #setWriteProtected} (rewriting it later),
     * rather than duplicating this same scan in both places.
     *
     * @param file the full file bytes
     * @return the byte offset where the INFO chunk's own data begins
     * @throws IllegalStateException if no INFO chunk is found -- should
     *         never happen for a file that already loaded successfully,
     *         since the constructor itself requires one
     */
    private static int findInfoChunkDataStart(byte[] file) {
        int offset = HEADER_SIZE;
        while (offset + 8 <= file.length) {
            String chunkId = new String(file, offset, 4, StandardCharsets.US_ASCII);
            int chunkSize = readU32(file, offset + 4);
            int dataStart = offset + 8;
            if (chunkId.equals("INFO")) {
                return dataStart;
            }
            offset = dataStart + chunkSize;
        }
        throw new IllegalStateException("No INFO chunk found -- this should be unreachable for a file "
            + "that already passed this same scan once, in the constructor");
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
            trackByteOffsets[i] = byteStart;
            trackIsVirgin[i] = isAllZero(data);
        }
    }

    /**
     * True if every byte is exactly zero -- used to detect a still-virgin
     * (never formatted) track from its own stored content, at load time,
     * for every track this class parses, not only ones {@link #createBlank}
     * happens to remember creating in this same session.
     * <p>
     * Confirmed, not assumed, to have zero false positives on real,
     * correctly-formatted Apple II disk data: DOS 3.3's own 6-and-2
     * encoding physically cannot produce a byte-aligned run of 8
     * consecutive zero bits at any bit alignment, let alone an entire
     * track's worth -- confirmed across multiple independent sources,
     * consistently, that real disk hardware enforces "no more than two
     * consecutive zero bits anywhere" in valid encoded data (three or
     * more, and "the disk hardware can't reliably read back the data" at
     * all -- this isn't a software convention, it's what makes the
     * self-clocking read mechanism work in the first place). An entire
     * track of zero bytes would need 51,200 consecutive zero bits, so far
     * beyond that limit that no real, formatted track -- copy-protected
     * or otherwise unusual ones included -- could ever produce it by
     * accident.
     *
     * @param data the bytes to check
     * @return true if every byte is 0
     */
    private static boolean isAllZero(byte[] data) {
        for (byte b : data) {
            if (b != 0) {
                return false;
            }
        }
        return true;
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

    private static void writeU16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >> 8);
    }

    private static void writeU32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >> 8);
        data[offset + 2] = (byte) (value >> 16);
        data[offset + 3] = (byte) (value >> 24);
    }

    /**
     * @return true if either the WOZ file's own INFO chunk flag says
     *         write-protected, or the host disk image file itself is
     *         not writable -- either one is sufficient on real
     *         hardware too: a real WOZ-captured disk's own write
     *         protection and a host-level restriction are independent
     *         ways to end up unable to write, not alternatives to pick
     *         between
     */
    @Override
    public boolean isWriteProtected() {
        return writeProtected || !Files.isWritable(path);
    }

    /**
     * Sets this disk's write-protect state in both places at once,
     * deliberately kept in tandem rather than independently toggleable:
     * the WOZ file's own INFO chunk flag (rewritten in place, with the
     * file's CRC32 recomputed to match -- a stale CRC would make this
     * same file fail verification the next time it's loaded) and the
     * host disk image file's writable attribute. Letting the two drift
     * apart would mean the UI's own toggle could appear to silently
     * fail: clearing only the internal flag while the host file stayed
     * read-only would still report write-protected per
     * {@link #isWriteProtected()}, with nothing of the real cause of the
     * discrepancy visible to the UI.
     * <p>
     * Temporarily grants host-level write access before rewriting the
     * file's contents if needed, regardless of which direction
     * {@code protect} goes -- the file's own bytes have to be modified
     * either way, and that requires write access even when the end
     * state being set is "protected."
     *
     * @param protect true to write-protect this disk, false to allow writes
     * @throws IOException if the file can't be read, rewritten, or have its host permission changed
     */
    @Override
    public void setWriteProtected(boolean protect) throws IOException {
        rewriteFile(protect, file -> {
            int infoDataStart = findInfoChunkDataStart(file);
            file[infoDataStart + 2] = (byte) (protect ? 1 : 0);
        });
        // Only reached once rewriteFile has fully succeeded, including
        // restoring the host file's own permission to match -- if it
        // threw partway through, this field must NOT have already
        // changed to reflect a persist that didn't actually complete.
        this.writeProtected = protect;
    }

    /**
     * Writes every captured track's current in-memory bit data back into
     * the host file, at each track's own original byte offset --
     * persisting whatever {@link Disk2Controller} has written into this
     * image's {@code trackData} since it was loaded (or since the last
     * {@code persist()}), the same way a real floppy's writes are
     * already physically on the medium the instant they happen, with no
     * separate "save" step on real hardware to mirror.
     * <p>
     * Deliberately rewrites every captured track every time, not just
     * ones a dirty-tracking scheme would mark as changed: a standard
     * 35-track disk's total captured data is on the order of 220 KB
     * (confirmed directly: 35 x {@link #EMPTY_TRACK_BIT_COUNT}/8 bytes),
     * trivial to rewrite wholesale on any modern storage. Tracking which
     * specific tracks changed since the last persist would be real,
     * ongoing bookkeeping bought for a performance problem that doesn't
     * exist at this size -- not worth the added complexity.
     * <p>
     * Does not touch the write-protect flag or the host file's
     * permission at all -- those are {@link #setWriteProtected}'s own,
     * separate concern. {@link Disk2Controller} is expected to call this
     * only when the disk is not write-protected in the first place
     * (matching how {@code writeBit} is never even reached for a
     * protected disk), but calling it on one does no harm: it would
     * simply rewrite every track's current, unchanged bytes.
     *
     * @throws IOException if the file can't be read, rewritten, or have its host permission temporarily changed
     */
    public void persist() throws IOException {
        if (!dirty) {
            return; // nothing written since the last persist (or ever) -- avoid rewriting an unchanged file
        }
        rewriteFile(this.writeProtected, file -> {
            for (int i = 0; i < TRK_ENTRY_COUNT; i++) {
                if (trackData[i] != null) {
                    System.arraycopy(trackData[i], 0, file, trackByteOffsets[i], trackData[i].length);
                }
            }
        });
        dirty = false;
    }

    @Override
    public void markDirty() {
        dirty = true;
    }

    /**
     * Shared by {@link #setWriteProtected} and {@link #persist}: grants
     * temporary host-level write access if needed, re-reads the file
     * fresh from disk (so anything this class doesn't itself track --
     * META, WRIT, FLUX, chunk ordering, INFO fields beyond write-protect
     * -- survives completely untouched, the same way {@code
     * setWriteProtected} already worked before this method existed to
     * share its own pattern), lets {@code patcher} modify only the
     * specific bytes it's responsible for, recomputes the CRC32 over the
     * result, writes it back, and finally restores the host file's own
     * permission to match {@code finalWriteProtectedState}.
     * <p>
     * That restoration is a parameter, not a read of {@code this.writeProtected}
     * directly, so that a caller changing the write-protect state itself
     * (only {@link #setWriteProtected} does) can pass the new, not-yet-committed
     * value without mutating the field until this method has fully
     * succeeded -- if anything here throws, the field must still reflect
     * the last state that was actually, successfully persisted, not one
     * a failed attempt only meant to reach.
     *
     * @param finalWriteProtectedState the write-protect state to restore the host file's permission to once rewritten
     * @param patcher mutates the freshly-read file bytes in place before the CRC is recomputed
     * @throws IOException if the file can't be read, temporarily made writable, rewritten, or have its final permission set
     */
    private void rewriteFile(boolean finalWriteProtectedState, java.util.function.Consumer<byte[]> patcher)
            throws IOException {
        if (!path.toFile().setWritable(true)) {
            throw new IOException("Could not temporarily grant write access to " + path
                + " in order to update it");
        }

        byte[] file = Files.readAllBytes(path);
        patcher.accept(file);

        CRC32 crc = new CRC32();
        crc.update(file, HEADER_SIZE, file.length - HEADER_SIZE);
        writeU32(file, 8, (int) crc.getValue());

        Files.write(path, file);

        if (!path.toFile().setWritable(!finalWriteProtectedState)) {
            throw new IOException("Wrote " + path + "'s new contents, but could not set its host file "
                + "permission to match its write-protect state -- the two are now out of sync");
        }
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
        int trksIndex = resolveTrksIndex(quarterTrack);
        if (trksIndex == 0xFF) {
            // Genuinely unmapped in this file's own TMAP -- no backing
            // storage exists for this track at all (a real copy-protected
            // disk deliberately left this blank, say), so a write to it
            // (not the expected case, but not prevented either) is lost
            // the instant the head moves on. Only {@link #createBlank}'s
            // pre-allocated, trackIsVirgin-tracked tracks can actually
            // persist a write starting from weak bits -- seeing this
            // path means there was never a real slot to write into in
            // the first place.
            return TrackBitStream.weakBits(new byte[EMPTY_TRACK_BIT_COUNT / 8], EMPTY_TRACK_BIT_COUNT, null);
        }
        if (trackIsVirgin[trksIndex]) {
            int virginIndex = trksIndex; // effectively final, for the lambda below
            return TrackBitStream.weakBits(trackData[trksIndex], trackBitCounts[trksIndex],
                () -> trackIsVirgin[virginIndex] = false);
        }
        return new TrackBitStream(trackData[trksIndex], trackBitCounts[trksIndex]);
    }

    /**
     * Resolves a quarter-track to its actual data, falling back to the
     * nearest <em>mapped</em> neighbor when the requested quarter-track
     * itself isn't captured in the TMAP. Real Disk II hardware's read
     * head is physically wide enough to still pick up an adjacent
     * track's flux pattern when positioned at an uncaptured
     * quarter-track sitting between two captured ones -- many real WOZ
     * captures only capture one quarter-track per whole track, leaving
     * the other three formally unmapped, and a reader that returns
     * silence there instead of the neighbor's data diverges from what
     * real hardware (and other real emulators) actually produce at
     * that head position. This was confirmed to matter, not just a
     * theoretical concern: it caused a real Disk II seek-and-verify
     * step to read genuine silence at an unmapped quarter-track
     * between two perfectly good mapped tracks, making the seek appear
     * to fail and retry forever.
     * <p>
     * The empty-track sentinel is reserved for quarter-tracks with no
     * mapped neighbor at all within the search radius below (e.g. past
     * the outermost or innermost real track, or a genuinely blank
     * disk) -- not for every gap in a sparse TMAP.
     *
     * @param quarterTrack the requested quarter-track, 0-159
     * @return the TRKS index to use, or {@code 0xFF} if nothing mapped is nearby
     */
    private int resolveTrksIndex(int quarterTrack) {
        if (isMapped(quarterTrack)) {
            return trackMap[quarterTrack];
        }
        // Search outward by increasing distance -- a real head only picks up an
        // immediately adjacent track, not one several quarter-tracks away, so this
        // is bounded to a small, physically-plausible radius rather than scanning
        // the whole disk.
        int maxSearchDistance = 3;
        for (int distance = 1; distance <= maxSearchDistance; distance++) {
            int lower = quarterTrack - distance;
            if (lower >= 0 && isMapped(lower)) {
                return trackMap[lower];
            }
            int upper = quarterTrack + distance;
            if (upper < TMAP_ENTRIES && isMapped(upper)) {
                return trackMap[upper];
            }
        }
        return 0xFF;
    }

    /** True if quarter-track {@code qt} has a real, populated TRKS entry -- not just a non-0xFF map value. */
    private boolean isMapped(int qt) {
        return trackMap[qt] != 0xFF && trackData[trackMap[qt]] != null;
    }

    /**
     * A single track's bit-level data, with a wrapping read position --
     * real disk tracks are circular, so reading past the last bit
     * continues from the first.
     */
}
