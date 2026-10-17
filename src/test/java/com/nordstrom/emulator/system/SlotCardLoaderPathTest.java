package com.nordstrom.emulator.system;

import com.nordstrom.emulator.transfer.HostTransferCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Relative paths in a slot configuration are relative to the folder holding
 * the file, not to the working directory -- which an installed app doesn't
 * control. The test's configuration lives in a folder of its own, away from
 * the working directory, so a path resolved against the wrong place isn't
 * found.
 */
class SlotCardLoaderPathTest {

    private static final int DSK_SIZE = 35 * 16 * 256;

    @Test
    void aRelativeDiskPathIsRelativeToTheConfigurationFile(@TempDir Path dir) throws IOException {
        Path config = Files.createDirectories(dir.resolve("machines"));
        Path disk = Files.createDirectories(config.resolve("disks")).resolve("blank.dsk");
        Files.write(disk, new byte[DSK_SIZE]);
        Path ini = config.resolve("slots.ini");
        Files.writeString(ini, "[6]\ntype=disk2\ndrive1=disks/blank.dsk\n");

        SlotCard controller = SlotCardLoader.load(ini, getClass().getClassLoader())[6];
        assertEquals(disk.toRealPath(), controller.removableDrives().get(0).currentImagePath().orElseThrow().toRealPath());
    }

    @Test
    void anAbsoluteDiskPathIsUsedAsItIs(@TempDir Path dir) throws IOException {
        Path disk = dir.resolve("elsewhere.dsk");
        Files.write(disk, new byte[DSK_SIZE]);
        Path config = Files.createDirectories(dir.resolve("machines"));
        Path ini = config.resolve("slots.ini");
        Files.writeString(ini, "[6]\ntype=disk2\ndrive1=" + disk.toAbsolutePath().toString().replace('\\', '/') + "\n");

        SlotCard controller = SlotCardLoader.load(ini, getClass().getClassLoader())[6];
        assertEquals(disk.toRealPath(), controller.removableDrives().get(0).currentImagePath().orElseThrow().toRealPath());
    }

    @Test
    void theTransferCardsFolderIsRelativeToTheConfigurationFileToo(@TempDir Path dir) throws IOException {
        Path config = Files.createDirectories(dir.resolve("machines"));
        Path files = Files.createDirectories(config.resolve("files"));
        Path ini = config.resolve("slots.ini");
        Files.writeString(ini, "[2]\ntype=hostfiles\ndir=files\n");

        HostTransferCard card = (HostTransferCard) SlotCardLoader.load(ini, getClass().getClassLoader())[2];
        assertEquals(files.toRealPath(), card.initialDirectory().toRealPath());
    }

    @Test
    void withoutAConfigurationFileARelativePathStaysRelative() {
        Properties props = new Properties(); // a card configured directly, as in a test
        assertEquals(Path.of("disks/a.dsk"), SlotCardLoader.resolvePath(props, "disks/a.dsk"));
    }
}
