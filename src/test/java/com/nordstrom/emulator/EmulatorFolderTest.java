package com.nordstrom.emulator;

import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SlotCardLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Setting up an emulator folder: a folder without slots.ini is set up with
 * the subfolders and a starter configuration; a folder with one is taken as
 * already set up, and left alone.
 */
class EmulatorFolderTest {

    @Test
    void aNewFolderIsSetUpWithAStarterConfiguration(@TempDir Path dir) throws IOException {
        Path folder = dir.resolve("ReplicApple2Plus"); // doesn't exist yet
        assertFalse(EmulatorFolder.isSetUp(folder));
        assertTrue(EmulatorFolder.setUp(folder));
        for (String part : new String[] {"roms", "plugins", "disks"}) {
            assertTrue(Files.isDirectory(folder.resolve(part)), part);
        }
        assertTrue(EmulatorFolder.isSetUp(folder));
    }

    @Test
    void theStarterConfigurationLoads(@TempDir Path dir) throws IOException {
        EmulatorFolder.setUp(dir);
        SlotCard[] slots = SlotCardLoader.load(dir.resolve("slots.ini"), getClass().getClassLoader());
        assertNotNull(slots[0], "Language Card");
        assertNotNull(slots[6], "Disk II");
    }

    @Test
    void aFolderWithASlotConfigurationIsTakenAsSetUp(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("slots.ini"), "[0]\ntype=languageCard\n");
        assertTrue(EmulatorFolder.isSetUp(dir));
        assertFalse(EmulatorFolder.setUp(dir), "a set-up folder is used as it is");
        assertEquals("[0]\ntype=languageCard\n", Files.readString(dir.resolve("slots.ini")));
        assertFalse(Files.exists(dir.resolve("roms")), "nothing is added to it");
    }

    @Test
    void itsPartsFollowTheFolderInUse(@TempDir Path dir) {
        try {
            EmulatorFolder.use(dir);
            assertEquals(dir.resolve("roms"), EmulatorFolder.roms());
            assertEquals(dir.resolve("plugins"), EmulatorFolder.plugins());
            assertEquals(dir.resolve("slots.ini"), EmulatorFolder.slotConfiguration());
        } finally {
            EmulatorFolder.use(null);
        }
    }
}
