package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A card declaring {@link SlotCard#supportedSlots()} is refused anywhere else, at startup. */
class SlotCardLoaderSlotTest {

    private static SlotCard[] load(Path dir, String ini) throws IOException {
        Path config = dir.resolve("slots.ini");
        Files.writeString(config, ini);
        return SlotCardLoader.load(config, SlotCardLoaderSlotTest.class.getClassLoader());
    }

    @Test
    void aCardOutsideItsSupportedSlotsIsRefusedWithAClearMessage(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> load(dir, "[4]\ntype=videoterm\n"));
        assertEquals("Slot 4: a \"videoterm\" card only works in slot 3", e.getMessage());
    }

    @Test
    void aCardInItsSupportedSlotLoads(@TempDir Path dir) throws IOException {
        assertNotNull(load(dir, "[3]\ntype=videoterm\n")[3]);
    }

    @Test
    void aCardWithTheDefaultWorksInAnySlot(@TempDir Path dir) throws IOException {
        for (int slot = 1; slot <= 7; slot++) {
            assertNotNull(load(dir, "[" + slot + "]\ntype=disk2\n")[slot], "slot " + slot);
        }
    }
}
