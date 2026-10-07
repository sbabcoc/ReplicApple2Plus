package com.nordstrom.emulator.expansion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A .po image holds the same sectors as a .dsk, in ProDOS order. The two
 * orders, as MAME's ap2_dsk.cpp defines them: the file sector stored at
 * each physical sector position.
 */
class DskDiskImageProDosOrderTest {

    private static final int[] DOS_ORDER = {0, 7, 14, 6, 13, 5, 12, 4, 11, 3, 10, 2, 9, 1, 8, 15};
    private static final int[] PRODOS_ORDER = {0, 8, 1, 9, 2, 10, 3, 11, 4, 12, 5, 13, 6, 14, 7, 15};
    private static final int TRACKS = 35;
    private static final int SECTORS = 16;
    private static final int SECTOR_SIZE = 256;

    /** The same disk, re-ordered from DOS order into ProDOS order sector by sector. */
    private static byte[] toProDosOrder(byte[] dosOrder) {
        byte[] proDos = new byte[dosOrder.length];
        for (int track = 0; track < TRACKS; track++) {
            for (int physical = 0; physical < SECTORS; physical++) {
                System.arraycopy(dosOrder, (track * SECTORS + DOS_ORDER[physical]) * SECTOR_SIZE,
                    proDos, (track * SECTORS + PRODOS_ORDER[physical]) * SECTOR_SIZE, SECTOR_SIZE);
            }
        }
        return proDos;
    }

    private static byte[] trackBits(DskDiskImage image, int track) {
        TrackBitStream stream = image.trackAt(track * 4);
        byte[] bits = new byte[stream.bitCount()];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = (byte) stream.nextBit();
        }
        return bits;
    }

    @Test
    void aDiskStoredInProDosOrderProducesTheSameTracksAsInDosOrder(@TempDir Path dir) throws IOException {
        byte[] dosOrder = new byte[TRACKS * SECTORS * SECTOR_SIZE];
        new Random(1983).nextBytes(dosOrder);
        Path dsk = dir.resolve("disk.dsk");
        Path po = dir.resolve("disk.po");
        Files.write(dsk, dosOrder);
        Files.write(po, toProDosOrder(dosOrder));

        DskDiskImage fromDsk = DskDiskImage.load(dsk);
        DskDiskImage fromPo = DskDiskImage.load(po);
        for (int track = 0; track < TRACKS; track++) {
            assertArrayEquals(trackBits(fromDsk, track), trackBits(fromPo, track), "track " + track);
        }
    }

    @Test
    void theExtensionDecidesTheOrder(@TempDir Path dir) throws IOException {
        byte[] data = new byte[TRACKS * SECTORS * SECTOR_SIZE];
        new Random(1983).nextBytes(data);
        Path dsk = dir.resolve("same.dsk");
        Path po = dir.resolve("same.po");
        Files.write(dsk, data);
        Files.write(po, data);
        // Identical bytes, read in different orders: track 0's sector 0 is the same in both, but not the rest.
        assertFalse(java.util.Arrays.equals(trackBits(DskDiskImage.load(dsk), 0), trackBits(DskDiskImage.load(po), 0)));
    }
}
