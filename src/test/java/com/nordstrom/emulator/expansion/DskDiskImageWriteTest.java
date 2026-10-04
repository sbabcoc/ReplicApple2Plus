package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSK write support: writes land in a kept bitstream, and
 * {@link DskDiskImage#persist} decodes written tracks back into the
 * flat sector file.
 */
class DskDiskImageWriteTest {

    private static final int TRACKS = 35;
    private static final int SECTORS = 16;
    private static final int SECTOR_SIZE = 256;
    /** Duplicated deliberately as the test's own expectation, as in {@link DskDiskImageTest}. */
    private static final int[] PHYSICAL_TO_LOGICAL = {0, 7, 14, 6, 13, 5, 12, 4, 11, 3, 10, 2, 9, 1, 8, 15};

    private static byte[] randomDisk(long seed) {
        byte[] disk = new byte[TRACKS * SECTORS * SECTOR_SIZE];
        new Random(seed).nextBytes(disk);
        return disk;
    }

    private static Path writeDisk(Path dir, String name, byte[] disk) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, disk);
        return path;
    }

    private static byte[] sector(byte[] disk, int track, int logicalSector) {
        int offset = (track * SECTORS + logicalSector) * SECTOR_SIZE;
        return Arrays.copyOfRange(disk, offset, offset + SECTOR_SIZE);
    }

    /** A track's bits as the stream holds them, read without moving its head. */
    private static byte[] bitsOf(TrackBitStream stream) {
        int saved = stream.position();
        stream.seekTo(0);
        byte[] bits = new byte[(stream.bitCount() + 7) / 8];
        for (int i = 0; i < stream.bitCount(); i++) {
            bits[i / 8] |= (byte) (stream.nextBit() << (7 - i % 8));
        }
        stream.seekTo(saved);
        return bits;
    }

    @Test
    void decodeTrackRecoversEverySectorOfEveryTrackItsOwnEncoderProduced(@TempDir Path dir) throws IOException {
        byte[] disk = randomDisk(1);
        DskDiskImage image = DskDiskImage.load(writeDisk(dir, "random.dsk", disk));
        for (int track = 0; track < TRACKS; track++) {
            TrackBitStream stream = image.trackAt(track * 4);
            byte[][] decoded = DskDiskImage.decodeTrack(bitsOf(stream), stream.bitCount());
            for (int physical = 0; physical < SECTORS; physical++) {
                assertNotNull(decoded[physical], "track " + track + " physical sector " + physical + " not found");
                assertArrayEquals(sector(disk, track, PHYSICAL_TO_LOGICAL[physical]), decoded[physical],
                    "track " + track + " physical sector " + physical);
            }
        }
    }

    @Test
    void decodeTrackFindsAFieldThatWrapsPastTheEndOfTheTrack(@TempDir Path dir) throws IOException {
        byte[] disk = randomDisk(2);
        DskDiskImage image = DskDiskImage.load(writeDisk(dir, "random.dsk", disk));
        TrackBitStream stream = image.trackAt(5 * 4);
        byte[] bits = bitsOf(stream);
        int bitCount = stream.bitCount();
        // Rotate the track so it starts partway through a data field: every field still exists, but one now straddles the end.
        int shift = bitCount / 2 + 3;
        byte[] rotated = new byte[bits.length];
        for (int i = 0; i < bitCount; i++) {
            int from = (i + shift) % bitCount;
            int bit = (bits[from / 8] >> (7 - from % 8)) & 1;
            rotated[i / 8] |= (byte) (bit << (7 - i % 8));
        }
        byte[][] decoded = DskDiskImage.decodeTrack(rotated, bitCount);
        for (int physical = 0; physical < SECTORS; physical++) {
            assertArrayEquals(sector(disk, 5, PHYSICAL_TO_LOGICAL[physical]), decoded[physical],
                "physical sector " + physical);
        }
    }

    @Test
    void theSameStreamIsReturnedForEveryQuarterTrackAndOnEveryVisit(@TempDir Path dir) throws IOException {
        DskDiskImage image = DskDiskImage.load(writeDisk(dir, "random.dsk", randomDisk(3)));
        TrackBitStream first = image.trackAt(8);
        for (int quarter = 8; quarter < 12; quarter++) {
            assertSame(first, image.trackAt(quarter), "quarter-track " + quarter);
        }
        image.trackAt(40); // move far away...
        assertSame(first, image.trackAt(9), "...and back: a write made earlier must still be there");
    }

    @Test
    void persistWithNoWritesLeavesTheFileUntouched(@TempDir Path dir) throws IOException {
        Path path = writeDisk(dir, "random.dsk", randomDisk(4));
        Files.setLastModifiedTime(path, FileTime.fromMillis(0));
        DskDiskImage image = DskDiskImage.load(path);
        image.trackAt(0);
        image.trackAt(4);
        image.persist();
        assertEquals(0, Files.getLastModifiedTime(path).toMillis(), "nothing was written, so the file must not be rewritten");
    }

    @Test
    void aWrittenSectorThatNoLongerDecodesKeepsItsOldContentsAndIsReported(@TempDir Path dir) throws IOException {
        byte[] disk = randomDisk(5);
        Path path = writeDisk(dir, "random.dsk", disk);
        DskDiskImage image = DskDiskImage.load(path);
        TrackBitStream stream = image.trackAt(0);
        // Damage physical sector 0's data field: it begins after the 10-byte initial gap, the 14-byte
        // address field, a 10-byte gap and the 3-byte data prolog. Overwriting 64 bits inside it with
        // zeros produces invalid nibbles, so its checksum can't pass.
        stream.seekTo((10 + 14 + 10 + 3 + 20) * 8);
        for (int i = 0; i < 64; i++) {
            stream.writeBit(0);
        }
        image.markDirty();

        IOException e = assertThrows(IOException.class, image::persist);
        assertTrue(e.getMessage().contains("track 0 sector 0"), e.getMessage());
        byte[] saved = Files.readAllBytes(path);
        assertArrayEquals(disk, saved, "the damaged sector keeps its old contents; every other sector re-decoded unchanged");
    }

    // --- End to end, through the real controller --------------------------------------------------

    /** Polls the data latch like DOS's read loop (LDA $C08C,X / BPL), returning the next complete nibble. */
    private static int readNibble(Disk2Controller controller) {
        int value;
        do {
            controller.tick(4);
            value = controller.readIoSwitch(0xC);
        } while ((value & 0x80) == 0);
        do { // wait for the latch to move on, so the same byte isn't read twice
            controller.tick(4);
        } while ((controller.readIoSwitch(0xC) & 0x80) != 0);
        return value;
    }

    /** Reads until the address field for {@code physicalSector} has been passed, as RWTS does before writing. */
    private static void seekToAfterAddressField(Disk2Controller controller, int physicalSector) {
        int[] window = new int[3];
        for (int n = 0; n < 100_000; n++) {
            window[0] = window[1];
            window[1] = window[2];
            window[2] = readNibble(controller);
            if (window[0] == 0xD5 && window[1] == 0xAA && window[2] == 0x96) {
                int[] field = new int[8];
                for (int i = 0; i < 8; i++) {
                    field[i] = readNibble(controller);
                }
                int sector = ((field[4] << 1) | 1) & field[5];
                readNibble(controller); // DE
                readNibble(controller); // AA -- RWTS checks these two, then starts writing
                if (sector == physicalSector) {
                    return;
                }
            }
        }
        throw new AssertionError("address field for physical sector " + physicalSector + " never found");
    }

    /** Writes bytes the way DOS's write loop does: 40 cycles per self-sync byte, 32 per data byte. */
    private static void writeBytes(Disk2Controller controller, int[] bytes, int cyclesPerByte) {
        for (int value : bytes) {
            controller.writeIoSwitch(0xD, value); // STA $C08D,X
            controller.tick(4);                   // ORA $C08C,X
            controller.writeIoSwitch(0xC, 0);
            controller.tick(cyclesPerByte - 4);
        }
    }

    /** The 343-nibble data field the production encoder makes for {@code data}, taken from a second image. */
    private static int[] encodedDataField(Path dir, byte[] data) throws IOException {
        byte[] disk = new byte[TRACKS * SECTORS * SECTOR_SIZE];
        System.arraycopy(data, 0, disk, 0, SECTOR_SIZE); // track 0, logical sector 0 = physical sector 0
        TrackBitStream stream = DskDiskImage.load(writeDisk(dir, "source.dsk", disk)).trackAt(0);
        int[] window = new int[3];
        while (!(window[0] == 0xD5 && window[1] == 0xAA && window[2] == 0xAD)) {
            window[0] = window[1];
            window[1] = window[2];
            window[2] = readStreamByte(stream);
        }
        int[] field = new int[343];
        for (int i = 0; i < field.length; i++) {
            field[i] = readStreamByte(stream);
        }
        return field;
    }

    private static int readStreamByte(TrackBitStream stream) {
        int b = 0;
        for (int i = 0; i < 8; i++) {
            b = (b << 1) | stream.nextBit();
        }
        return b;
    }

    /**
     * The whole path DOS uses to write one sector, against a real .dsk
     * file: insert it, find the target sector's address field by reading
     * the latch, switch to write mode and write a new data field with
     * DOS's own loop timing, then eject (which persists). The reloaded
     * file must hold the new sector at the right logical position, with
     * every other sector byte-for-byte unchanged.
     */
    @Test
    void aSectorWrittenThroughTheControllerLandsInTheDskFile(@TempDir Path dir) throws IOException {
        byte[] disk = randomDisk(6);
        Path path = writeDisk(dir, "target.dsk", disk);
        byte[] replacement = new byte[SECTOR_SIZE];
        new Random(99).nextBytes(replacement);
        int physicalSector = 5; // logical sector 5 under DOS 3.3's skew

        Disk2Controller controller = new Disk2Controller();
        controller.drive(0).insert(path);
        controller.writeIoSwitch(0x9, 0); // motor on
        controller.writeIoSwitch(0xA, 0); // drive 1
        controller.writeIoSwitch(0xE, 0); // Q7 low: read mode

        seekToAfterAddressField(controller, physicalSector);
        controller.writeIoSwitch(0xF, 0); // Q7 high: write mode
        writeBytes(controller, new int[] {0xFF, 0xFF, 0xFF, 0xFF, 0xFF}, 40);
        writeBytes(controller, new int[] {0xD5, 0xAA, 0xAD}, 32);
        writeBytes(controller, encodedDataField(dir, replacement), 32);
        writeBytes(controller, new int[] {0xDE, 0xAA, 0xEB, 0xFF}, 32);
        controller.writeIoSwitch(0xE, 0); // Q7 low

        controller.drive(0).eject(); // persists

        byte[] expected = disk.clone();
        int logicalSector = PHYSICAL_TO_LOGICAL[physicalSector];
        System.arraycopy(replacement, 0, expected, logicalSector * SECTOR_SIZE, SECTOR_SIZE);
        byte[] saved = Files.readAllBytes(path);
        assertArrayEquals(sector(expected, 0, logicalSector), sector(saved, 0, logicalSector),
            "the written sector must be stored at its logical position");
        assertArrayEquals(expected, saved, "and every other sector must be unchanged");
    }

    // --- Blank (0-byte) images ---------------------------------------------------------------------

    @Test
    void anEmptyFileLoadsAsAnUnformattedDiskThatReadsAsFreshNoise(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("blank.dsk");
        Files.write(path, new byte[0]); // what Virtual ][ creates for a blank DSK
        DskDiskImage image = DskDiskImage.load(path);
        for (int track : new int[] {0, 17, 34}) {
            TrackBitStream stream = image.trackAt(track * 4);
            byte[] first = bitsOf(stream);
            byte[] second = bitsOf(stream);
            assertTrue(!Arrays.equals(first, second),
                "an unformatted track must read differently every time, not as fixed data (track " + track + ")");
        }
    }

    @Test
    void createBlankMakesAnEmptyFileAndRefusesToOverwrite(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("new.dsk");
        DskDiskImage.createBlank(path);
        assertEquals(0, Files.size(path), "a blank DSK is an empty file");
        assertThrows(IOException.class, () -> DskDiskImage.createBlank(path));
    }

    @Test
    void persistingAnUnwrittenBlankDiskLeavesItEmpty(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("blank.dsk");
        DskDiskImage image = DskDiskImage.createBlank(path);
        image.trackAt(0).nextBit(); // reading alone isn't writing
        image.persist();
        assertEquals(0, Files.size(path));
    }

    /** One 4-and-4 encoded byte, as an address field stores volume/track/sector/checksum. */
    private static int[] fourAndFour(int value) {
        return new int[] {(value >> 1) | 0xAA, value | 0xAA};
    }

    /**
     * Formats track 0 of a brand-new blank DSK through the real controller,
     * the way INIT does -- one continuous write of 16 address fields and data
     * fields with DOS's loop timing -- then ejects. The file must become a
     * full 143,360-byte image holding exactly those sectors at their logical
     * positions, with every track never written stored as zeroed sectors.
     */
    @Test
    void formattingATrackOfABlankDskThroughTheControllerProducesAFullImage(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("formatted.dsk");
        Disk2Controller controller = new Disk2Controller();
        controller.drive(0).insertNewBlankDisk(path);
        controller.writeIoSwitch(0x9, 0); // motor on
        controller.writeIoSwitch(0xA, 0); // drive 1

        byte[][] sectorData = new byte[SECTORS][SECTOR_SIZE];
        Random random = new Random(42);
        controller.writeIoSwitch(0xF, 0); // Q7 high: write mode, from wherever the head happens to be
        writeBytes(controller, filled(40, 0xFF), 40); // leading gap
        for (int physical = 0; physical < SECTORS; physical++) {
            random.nextBytes(sectorData[physical]);
            int volume = 254, track = 0;
            writeBytes(controller, new int[] {0xD5, 0xAA, 0x96}, 32);
            writeBytes(controller, fourAndFour(volume), 32);
            writeBytes(controller, fourAndFour(track), 32);
            writeBytes(controller, fourAndFour(physical), 32);
            writeBytes(controller, fourAndFour(volume ^ track ^ physical), 32);
            writeBytes(controller, new int[] {0xDE, 0xAA, 0xEB}, 32);
            writeBytes(controller, filled(5, 0xFF), 40);
            writeBytes(controller, new int[] {0xD5, 0xAA, 0xAD}, 32);
            writeBytes(controller, encodedDataField(dir, sectorData[physical]), 32);
            writeBytes(controller, new int[] {0xDE, 0xAA, 0xEB}, 32);
            writeBytes(controller, filled(14, 0xFF), 40);
        }
        controller.writeIoSwitch(0xE, 0); // Q7 low
        controller.drive(0).eject(); // persists

        byte[] saved = Files.readAllBytes(path);
        assertEquals(TRACKS * SECTORS * SECTOR_SIZE, saved.length, "the first persist turns the empty file into a full image");
        for (int physical = 0; physical < SECTORS; physical++) {
            assertArrayEquals(sectorData[physical], sector(saved, 0, PHYSICAL_TO_LOGICAL[physical]),
                "physical sector " + physical + " must land at its logical position");
        }
        for (int offset = SECTORS * SECTOR_SIZE; offset < saved.length; offset++) {
            assertEquals(0, saved[offset], "tracks never written are stored as zeroed sectors (offset " + offset + ")");
        }
    }

    private static int[] filled(int count, int value) {
        int[] bytes = new int[count];
        Arrays.fill(bytes, value);
        return bytes;
    }

    @Test
    void anEmptyDskFileCanBeInsertedLikeAnyOther(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("from-virtual-ii.dsk");
        Files.write(path, new byte[0]);
        Disk2Controller controller = new Disk2Controller();
        controller.drive(0).insert(path); // previously rejected as the wrong size
        assertTrue(controller.drive(0).isPresent());
    }
}
