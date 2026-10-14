package com.nordstrom.emulator.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where ROM images are read from: the {@code --roms} directory first, by
 * file name, then the classpath. Uses names no real ROM has, so the
 * ROM classes' own images are never involved.
 */
class RomFilesTest {

    @AfterEach
    void forgetDirectory() {
        RomFiles.setDirectory(null);
    }

    @Test
    void readsAFileFromTheRomsDirectory(@TempDir Path roms) throws IOException {
        Files.write(roms.resolve("test-image.rom"), new byte[] {1, 2, 3});
        RomFiles.setDirectory(roms);
        try (InputStream in = RomFiles.open(RomFilesTest.class, "test-image.rom")) {
            assertArrayEquals(new byte[] {1, 2, 3}, in.readAllBytes());
        }
    }

    @Test
    void fallsBackToTheClasspath(@TempDir Path roms) throws IOException {
        RomFiles.setDirectory(roms); // empty: the classpath still answers
        try (InputStream in = RomFiles.open(RomFilesTest.class, "RomFilesTest.class")) {
            assertTrue(in != null && in.readAllBytes().length > 0);
        }
    }

    @Test
    void answersNullWhenTheImageIsNowhere(@TempDir Path roms) throws IOException {
        RomFiles.setDirectory(roms);
        assertNull(RomFiles.open(RomFilesTest.class, "no-such-image.rom"));
        String message = RomFiles.missing("no-such-image.rom");
        assertTrue(message.contains(roms.toString()) && message.contains("--roms"), message);
    }
}
