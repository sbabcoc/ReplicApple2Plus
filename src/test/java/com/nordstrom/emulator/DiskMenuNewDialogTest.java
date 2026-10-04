package com.nordstrom.emulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiskMenuNewDialogTest {

    @Test
    void switchingTypeSwapsAKnownDiskExtension() {
        assertEquals("untitled.dsk", DiskMenu.withDiskExtension("untitled.woz", ".dsk"));
        assertEquals("My Disk.woz", DiskMenu.withDiskExtension("My Disk.dsk", ".woz"));
        assertEquals("GAME.woz", DiskMenu.withDiskExtension("GAME.DO", ".woz"), "any case, and .do counts as DSK");
    }

    @Test
    void aNameWithoutADiskExtensionGetsOneAppended() {
        assertEquals("notes.dsk", DiskMenu.withDiskExtension("notes", ".dsk"));
        assertEquals("v1.2.woz", DiskMenu.withDiskExtension("v1.2", ".woz"), "only disk extensions are replaced");
    }

    @Test
    void aBlankNameFallsBackToUntitled() {
        assertEquals("untitled.woz", DiskMenu.withDiskExtension("", ".woz"));
        assertEquals("untitled.dsk", DiskMenu.withDiskExtension("   ", ".dsk"));
        assertEquals("untitled.dsk", DiskMenu.withDiskExtension(null, ".dsk"));
    }
}
