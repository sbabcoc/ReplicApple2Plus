package com.nordstrom.emulator.expansion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes a sector image in DOS order (.dsk/.do) or ProDOS order (.po), 35 tracks x 16
 * sectors x 256 bytes = 143,360 bytes). Reading synthesizes the real, historical
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
 * holds logical sector {@link #DOS_ORDER}[P]. A .po file holds the
 * same 560 sectors in ProDOS order instead -- each 512-byte ProDOS
 * block as two consecutive 256-byte sectors -- so physical position P
 * holds file sector {@link #PRODOS_ORDER}[P]. The extension picks the
 * order; everything else is identical. The address
 * field written at each physical position still names that physical
 * position's own number (0-15) -- the skew is purely about which
 * logical data ends up where, not a renumbering of the address fields
 * themselves.
 * <p>
 * Writing works on the synthesized bitstream exactly as it does on a
 * WOZ track: each track is encoded once, on first access, and that one
 * {@link TrackBitStream} is kept, so writes land in it and survive head
 * movement. {@link #persist} then reverses the encoding for every track
 * whose bits actually changed -- recognizing address fields, reversing
 * the 6-and-2 data field back to 256 bytes, and storing each sector at
 * its logical position -- and rewrites the file. A .dsk file can only
 * hold sector contents, so anything else a write puts on the track (an
 * {@code INIT}'s volume number, gap lengths) is not kept: on reload the
 * track is re-synthesized with this class's standard layout and volume
 * {@value #DEFAULT_VOLUME}, as with any DSK image.
 * <p>
 * Blank media: a .dsk file has no way to say a track was never
 * formatted, so an <em>empty</em> (0-byte) file stands for an entirely
 * unformatted disk -- the same convention Virtual ][ uses for the blank
 * DSK images it creates. Every track of such a disk reads as genuine
 * per-read random noise ({@link TrackBitStream#weakBits}, as
 * {@link WozDiskImage} does for its virgin tracks) until something writes
 * to it. The first {@link #persist} after a write turns the file into a
 * normal 143,360-byte image: written tracks hold whatever sectors decode
 * from them, and any track still never written is stored as zeroed
 * sectors -- the format simply can't record "unformatted" for one track
 * among formatted ones. An {@code INIT} writes every track, so in normal
 * use nothing is lost to that.
 * <p>
 * Deliberately scoped to standard 16-sector DOS 3.3 media only -- no
 * 13-sector DOS 3.2/3.1 support, no ProDOS-order (.po) files (which
 * use a different, +2 skew), and no copy-protection-specific
 * variations (nonstandard sync gaps, altered checksums, nibble
 * counts other than 256). A .dsk file has no write-protect flag at
 * all, unlike WOZ -- confirmed directly against the format's own
 * documentation (fileformats.archiveteam.org's DSK entry: "the bytes
 * are stored in these files in their raw form, with no headers or
 * separators added"), not assumed. So write-protect here is the host
 * disk image file's own read-only state -- the same approach Virtual
 * ][ uses for exactly this format, per its own documentation ("you
 * can select the disk image file in the Finder and set its 'Locked'
 * property"). This is a genuine analogy to a real 5.25" floppy's
 * write-protect notch, not just a convenient workaround: a real
 * drive can't write through a covered notch regardless of what any
 * software believes about the disk, and a real floppy's notch state
 * persists with the physical medium itself, exactly as a host file's
 * permission bit does here.
 */
public final class DskDiskImage implements DiskImage {

    private static final int TRACKS = 35;
    private static final int SECTORS_PER_TRACK = 16;
    private static final int SECTOR_SIZE = 256;
    private static final int EXPECTED_FILE_SIZE = TRACKS * SECTORS_PER_TRACK * SECTOR_SIZE;

    /** Real, documented DOS 3.3 sector skew: physical position -> logical sector number. */
    private static final int[] DOS_ORDER = {0, 7, 14, 6, 13, 5, 12, 4, 11, 3, 10, 2, 9, 1, 8, 15};

    /** ProDOS-order file sector at each physical position -- MAME's prodos_skewing (ap2_dsk.cpp). */
    private static final int[] PRODOS_ORDER = {0, 8, 1, 9, 2, 10, 3, 11, 4, 12, 5, 13, 6, 14, 7, 15};

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
    /**
     * Self-sync bytes per gap. Each is a real 10-bit sync byte -- {@code FF}
     * followed by two zero bits -- not a plain 8-bit {@code FF}: the trailing
     * zeros are what bring a reader that has lost byte framing back into
     * step, which is what happens at the end of every newly written field,
     * since it ends at an arbitrary bit offset relative to the bits after it.
     * With plain 8-bit gaps a reader stays misframed past the end of a
     * rewritten data field and can't find the next sector's address field.
     */
    private static final int SYNC_BYTES_PER_GAP = 10;

    /**
     * Length of an unformatted track on a blank (0-byte) image -- the same
     * 51,200 bits {@link WozDiskImage} gives its blank tracks, which DOS's
     * {@code INIT} is already proven to format correctly. Once formatted and
     * persisted, a track is re-synthesized at this class's standard length
     * on reload, like any other DSK track.
     */
    private static final int UNFORMATTED_TRACK_BIT_COUNT = 51_200;

    /** How many nibbles past an address field's epilog to look for its data field's prolog. */
    private static final int DATA_PROLOG_SEARCH_LIMIT = 64;

    /** {@link #TRANSLATE_62} inverted: disk nibble -> 6-bit value, or -1 for a byte that isn't a valid data nibble. */
    private static final int[] UNTRANSLATE_62 = new int[256];
    static {
        java.util.Arrays.fill(UNTRANSLATE_62, -1);
        for (int i = 0; i < TRANSLATE_62.length; i++) {
            UNTRANSLATE_62[TRANSLATE_62[i]] = i;
        }
    }

    private final byte[] diskData;
    private final Path path;

    /** Each track's stream, encoded on first access and then kept so writes persist in it. */
    private final TrackBitStream[] trackStreams = new TrackBitStream[TRACKS];
    /** The byte array behind each kept stream -- the bits {@link #persist} decodes. */
    private final byte[][] trackBits = new byte[TRACKS][];
    /** Each kept stream's bits as originally encoded -- a track whose bits still match was never written. */
    private final byte[][] encodedBits = new byte[TRACKS][];
    private boolean dirty;
    /** Tracks of a blank (0-byte) image that nothing has written to yet -- they read as random noise. */
    private final boolean[] trackIsUnformatted = new boolean[TRACKS];

    /** File sector at each physical position: {@link #DOS_ORDER} or {@link #PRODOS_ORDER}. */
    private final int[] sectorOrder;

    private DskDiskImage(byte[] diskData, Path path, boolean unformatted) {
        this.diskData = diskData;
        this.path = path;
        this.sectorOrder = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".po")
            ? PRODOS_ORDER : DOS_ORDER;
        java.util.Arrays.fill(trackIsUnformatted, unformatted);
    }

    /**
     * Loads a DOS-order sector image, or a blank (0-byte) one -- see this
     * class's Javadoc on blank media.
     *
     * @param path the .dsk/.do/.po file to load
     * @return the parsed disk image
     * @throws IOException if the file can't be read
     * @throws IllegalArgumentException if the file is neither empty nor exactly the expected 143,360-byte size
     */
    public static DskDiskImage load(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length == 0) {
            return new DskDiskImage(new byte[EXPECTED_FILE_SIZE], path, true);
        }
        if (data.length != EXPECTED_FILE_SIZE) {
            throw new IllegalArgumentException(path + " is " + data.length
                + " bytes, expected exactly " + EXPECTED_FILE_SIZE
                + " (35 tracks x 16 sectors x 256 bytes) for a standard DOS-order disk image, or 0 for a blank one");
        }
        return new DskDiskImage(data, path, false);
    }

    /**
     * Creates a blank, unformatted DSK image: an empty file, which
     * {@link #load} reads as a disk whose every track is unformatted (see
     * this class's Javadoc on blank media).
     *
     * @param path where to create the file; must not already exist
     * @return the new image, every track unformatted
     * @throws IOException if {@code path} already exists or can't be created
     */
    public static DskDiskImage createBlank(Path path) throws IOException {
        if (Files.exists(path)) {
            throw new IOException(path + " already exists");
        }
        Files.write(path, new byte[0]);
        return load(path);
    }

    /**
     * @return true if the host disk image file is not writable -- see this class's own Javadoc for why
     */
    @Override
    public boolean isWriteProtected() {
        return !Files.isWritable(path);
    }

    /**
     * Sets or clears the host disk image file's own writable
     * attribute -- see this class's own Javadoc for why this is the
     * right analog to a real floppy's write-protect notch for a
     * format with no internal metadata capacity at all.
     *
     * @param protect true to make the host file read-only, false to make it writable
     * @throws IOException if the file's permission can't be changed (e.g. this process doesn't own it)
     */
    @Override
    public void setWriteProtected(boolean protect) throws IOException {
        boolean succeeded = path.toFile().setWritable(!protect);
        if (!succeeded) {
            throw new IOException("Could not change the write permission on " + path
                + " -- this process may not have permission to change it");
        }
    }

    /**
     * Writes every track that was actually written to back into the
     * host .dsk file, by decoding its sectors from the track's bits.
     * A no-op if nothing has been written since the last call.
     * <p>
     * Only tracks whose bits differ from how they were originally
     * encoded are decoded, so an untouched track is never re-derived.
     * Within a written track, a sector that can't be found or fails its
     * checksums keeps its previous contents rather than being guessed
     * at; every sector that does decode is still saved, and then an
     * {@link IOException} names the ones that didn't.
     *
     * @throws IOException if the file can't be written, or if any written track had a sector that couldn't be decoded
     */
    @Override
    public void persist() throws IOException {
        if (!dirty) {
            return;
        }
        java.util.List<String> undecoded = new java.util.ArrayList<>();
        for (int track = 0; track < TRACKS; track++) {
            if (trackBits[track] == null || java.util.Arrays.equals(trackBits[track], encodedBits[track])) {
                continue; // never accessed, or accessed but never written
            }
            byte[][] sectors = decodeTrack(trackBits[track], trackStreams[track].bitCount());
            for (int physical = 0; physical < SECTORS_PER_TRACK; physical++) {
                if (sectors[physical] == null) {
                    undecoded.add("track " + track + " sector " + sectorOrder[physical]);
                    continue;
                }
                int offset = (track * SECTORS_PER_TRACK + sectorOrder[physical]) * SECTOR_SIZE;
                System.arraycopy(sectors[physical], 0, diskData, offset, SECTOR_SIZE);
            }
            // What the file now holds for this track is the new baseline: a later persist
            // re-decodes it only if it's written again.
            encodedBits[track] = trackBits[track].clone();
        }
        Files.write(path, diskData);
        dirty = false;
        if (!undecoded.isEmpty()) {
            throw new IOException("Saved " + path + ", but these sectors couldn't be decoded from what was "
                + "written and kept their previous contents: " + String.join(", ", undecoded));
        }
    }

    /** Called by {@link Disk2Controller} when it writes a bit -- see {@link DiskImage#markDirty}. */
    @Override
    public void markDirty() {
        dirty = true;
    }

    /**
     * Returns the bitstream for the track visible at the given
     * quarter-track head position. DSK images have no quarter-track
     * resolution -- all four quarter-track positions within one whole
     * track share the same stream. The stream is synthesized from the
     * sector data on first access and kept from then on, so bits written
     * to it are still there the next time the head arrives.
     *
     * @param quarterTrack 0-159 (track number x4, plus 0-3 for the quarter-track offset within it)
     * @return a bit stream over that track's data
     */
    @Override
    public TrackBitStream trackAt(int quarterTrack) {
        int track = quarterTrack / 4;
        if (track >= TRACKS) {
            // Past the real 35 tracks this format has -- same 51,200-bit zero-filled
            // convention WozDiskImage uses for genuinely empty media. Nothing written
            // here could be stored in a .dsk file anyway.
            return new TrackBitStream(new byte[51_200 / 8], 51_200);
        }
        if (trackStreams[track] == null && trackIsUnformatted[track]) {
            // Unformatted: random noise on every read until the first write lands,
            // which drops the stream out of weak-bit mode for good. The bits start
            // all zero, so persist() sees a written track as differing from them.
            trackBits[track] = new byte[UNFORMATTED_TRACK_BIT_COUNT / 8];
            encodedBits[track] = trackBits[track].clone();
            int written = track; // effectively final, for the lambda
            trackStreams[track] = TrackBitStream.weakBits(trackBits[track], UNFORMATTED_TRACK_BIT_COUNT,
                () -> trackIsUnformatted[written] = false);
        }
        if (trackStreams[track] == null) {
            BitOutput encoded = encodeTrack(track);
            trackBits[track] = encoded.toByteArray();
            encodedBits[track] = trackBits[track].clone();
            trackStreams[track] = new TrackBitStream(trackBits[track], encoded.bitCount());
        }
        return trackStreams[track];
    }

    /**
     * Recovers a track's sectors from its bits -- the reverse of
     * {@link #encodeTrack}, but tolerant of anything a real write can
     * leave behind: fields that don't start on any particular bit
     * boundary, self-sync gaps of any length, and leftover fragments
     * where a rewritten data field ends partway into the old one.
     * <p>
     * Bits become disk bytes the way the controller's latch forms them:
     * shifted in one at a time, a byte complete once its high bit is
     * set, leading zero bits skipped. The track is read around twice,
     * continuously, so a field that wraps past the track's end is read
     * whole.
     *
     * @param bits     the track's bits, MSB-first
     * @param bitCount how many bits of {@code bits} are the track
     * @return 16 entries indexed by physical sector; each the 256 decoded bytes, or null if that sector wasn't found intact
     */
    static byte[][] decodeTrack(byte[] bits, int bitCount) {
        int[] nibbles = new int[2 * bitCount / 8 + 1];
        int count = 0;
        int latch = 0;
        for (int i = 0; i < 2 * bitCount; i++) {
            int position = i % bitCount;
            latch = ((latch << 1) | ((bits[position / 8] >> (7 - position % 8)) & 1)) & 0xFF;
            if ((latch & 0x80) != 0) {
                nibbles[count++] = latch;
                latch = 0;
            }
        }

        byte[][] sectors = new byte[SECTORS_PER_TRACK][];
        for (int i = 0; i + 3 + 8 + 2 <= count; i++) {
            if (!matches(nibbles, i, count, ADDRESS_PROLOG)) {
                continue;
            }
            int volume = decode44(nibbles, i + 3);
            int trackField = decode44(nibbles, i + 5);
            int sector = decode44(nibbles, i + 7);
            int checksum = decode44(nibbles, i + 9);
            if ((volume ^ trackField ^ sector) != checksum || sector < 0 || sector >= SECTORS_PER_TRACK
                    || sectors[sector] != null) {
                continue;
            }
            int searchFrom = i + 11;
            int searchTo = Math.min(count - DATA_PROLOG.length, searchFrom + DATA_PROLOG_SEARCH_LIMIT);
            for (int j = searchFrom; j <= searchTo; j++) {
                if (matches(nibbles, j, count, ADDRESS_PROLOG)) {
                    break; // reached the next address field without finding this one's data
                }
                if (matches(nibbles, j, count, DATA_PROLOG)) {
                    sectors[sector] = decodeDataField(nibbles, j + DATA_PROLOG.length, count);
                    break;
                }
            }
        }
        return sectors;
    }

    private static boolean matches(int[] nibbles, int at, int count, int[] expected) {
        if (at + expected.length > count) {
            return false;
        }
        for (int k = 0; k < expected.length; k++) {
            if (nibbles[at + k] != expected[k]) {
                return false;
            }
        }
        return true;
    }

    /** Reverses {@link #writeEncoded44}: odd bits from the first byte, even bits from the second. */
    private static int decode44(int[] nibbles, int at) {
        return ((nibbles[at] << 1) | 1) & nibbles[at + 1];
    }

    /**
     * Reverses {@link #writeDataField}: 343 nibbles back to 256 bytes,
     * accepted only if every nibble is a valid data nibble, the running
     * checksum matches, and the field ends with the {@code DE AA} DOS
     * itself checks for on every read.
     *
     * @return the 256 decoded bytes, or null if the field isn't intact
     */
    private static byte[] decodeDataField(int[] nibbles, int at, int count) {
        if (at + 343 + 2 > count) {
            return null;
        }
        int[] values = new int[342];
        int running = 0;
        for (int k = 0; k < 342; k++) {
            int six = UNTRANSLATE_62[nibbles[at + k]];
            if (six < 0) {
                return null;
            }
            running ^= six;
            values[k] = running;
        }
        int finalChecksum = UNTRANSLATE_62[nibbles[at + 342]];
        if (finalChecksum != running
                || nibbles[at + 343] != DATA_EPILOG[0] || nibbles[at + 344] != DATA_EPILOG[1]) {
            return null;
        }
        // The encoder writes twos[85] down to twos[0], then top[0..255].
        int[] twos = new int[86];
        for (int k = 0; k < 86; k++) {
            twos[85 - k] = values[k];
        }
        byte[] data = new byte[SECTOR_SIZE];
        int twoShift = 0;
        int twoPos = 85;
        for (int i = 0; i < SECTOR_SIZE; i++) {
            int pair = (twos[twoPos] >> twoShift) & 3;
            int low2 = ((pair & 1) << 1) | ((pair & 2) >> 1); // the encoder stores the two low bits swapped
            data[i] = (byte) ((values[86 + i] << 2) | low2);
            if (twoPos == 0) {
                twoPos = 86;
                twoShift += 2;
            }
            twoPos -= 1;
        }
        return data;
    }

    private BitOutput encodeTrack(int track) {
        BitOutput out = new BitOutput();
        writeSyncGap(out);
        for (int physicalSector = 0; physicalSector < SECTORS_PER_TRACK; physicalSector++) {
            int logicalSector = sectorOrder[physicalSector];
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
        return out;
    }

    private byte[] readSector(int track, int logicalSector) {
        int offset = (track * SECTORS_PER_TRACK + logicalSector) * SECTOR_SIZE;
        byte[] sector = new byte[SECTOR_SIZE];
        System.arraycopy(diskData, offset, sector, 0, SECTOR_SIZE);
        return sector;
    }

    private void writeAddressField(BitOutput out, int track, int physicalSector) {
        int checksum = DEFAULT_VOLUME ^ track ^ physicalSector;
        writeEncoded44(out, DEFAULT_VOLUME);
        writeEncoded44(out, track);
        writeEncoded44(out, physicalSector);
        writeEncoded44(out, checksum);
    }

    /** Real DOS 3.3 4-and-4 encoding: a byte becomes two bytes, {@code (val>>1)|0xAA} then {@code val|0xAA}. */
    private static void writeEncoded44(BitOutput out, int value) {
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
    private void writeDataField(BitOutput out, byte[] data) {
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

    private static void writeSyncGap(BitOutput out) {
        for (int i = 0; i < SYNC_BYTES_PER_GAP; i++) {
            out.write(0xFF);
            out.writeBit(0);
            out.writeBit(0);
        }
    }

    private static void writeBytes(BitOutput out, int[] bytes) {
        for (int b : bytes) {
            out.write(b);
        }
    }

    /** An append-only, MSB-first bit buffer -- a synthesized track is not a whole number of bytes. */
    private static final class BitOutput {
        private byte[] bytes = new byte[6400];
        private int bitCount;

        void writeBit(int bit) {
            if (bitCount / 8 == bytes.length) {
                bytes = java.util.Arrays.copyOf(bytes, bytes.length * 2);
            }
            if (bit != 0) {
                bytes[bitCount / 8] |= (byte) (0x80 >> (bitCount % 8));
            }
            bitCount++;
        }

        /** Appends one disk byte, MSB first -- same name as {@code ByteArrayOutputStream.write}, which this replaced. */
        void write(int value) {
            for (int bit = 7; bit >= 0; bit--) {
                writeBit((value >> bit) & 1);
            }
        }

        int bitCount() {
            return bitCount;
        }

        /** The bits so far, padded with zeros to a whole byte. */
        byte[] toByteArray() {
            return java.util.Arrays.copyOf(bytes, (bitCount + 7) / 8);
        }
    }
}
