package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RESET in the middle of a session, under the real systems: the card's
 * firmware has borrowed $0800-$1BFF -- where a BASIC program lives -- and
 * hooked the soft-reset vector. RESET restores the borrowed memory and the
 * OS's own vector, then the OS warm-starts as it would have: the program
 * runs, and the keyboard is the keyboard.
 * <p>
 * Needs the same user-supplied images as the other integration tests;
 * each test is skipped without its image.
 */
class ResetDuringSessionIntegrationTest {

    @TempDir
    Path dir;

    /** Enters a program, starts a session, presses RESET, and checks what's left. */
    private static void resetMidSession(MachineHarness m, boolean prodos) {
        m.enter("NEW", 1);
        m.enter("10 PRINT \"KEEP ME\"", 2); // at $0801, inside the borrowed RAM
        int[] vector = {m.bus.read(0x3F2), m.bus.read(0x3F3), m.bus.read(0x3F4)};

        assertNotNull(m.startSession(), "IN#2 began a session");
        m.run(1); // the agent waits on the host
        assertEquals(0xC2, m.bus.read(0x3F3), "RESET is hooked to the card in slot 2 while RAM is borrowed");

        m.pressReset();
        assertTrue(m.runUntil("]", 10), "back at BASIC's prompt:\n" + m.screen());
        m.run(1);
        assertEquals(TransferHost.EndReason.RESET, m.ended());
        assertEquals(vector[0], m.bus.read(0x3F2), "the OS's soft-reset vector, restored");
        assertEquals(vector[1], m.bus.read(0x3F3));
        assertEquals(vector[2], m.bus.read(0x3F4));
        assertEquals(0xFD1B, m.word(0x38), "KSW on the keyboard");
        if (prodos) {
            assertEquals(0xFD1B, m.word(0xBE32), "VECTIN on the keyboard");
        }
        m.enter("RUN", 2);
        // RUN's output is a row reading exactly KEEP ME (the program line itself has the quotes)
        assertTrue(m.screen().lines().anyMatch(row -> row.strip().equals("KEEP ME")),
            "the BASIC program survived:\n" + m.screen());
    }

    @Test
    void underProDos() throws Exception {
        Path disk = MachineHarness.resource(dir, "ProDOS_2_4_3.po");
        assumeTrue(disk != null, "ProDOS_2_4_3.po not supplied in test resources");
        try (MachineHarness m = new MachineHarness(disk)) {
            assertTrue(m.bootProDosBasic());
            resetMidSession(m, true);
        }
    }

    @Test
    void underDos33() throws Exception {
        Path disk = MachineHarness.resource(dir,
            "DOS_3_3_System_Master.woz", "DOS_3_3_System_Master.dsk", "DOS_3_3_System_Master.po");
        assumeTrue(disk != null, "DOS_3_3_System_Master.woz/.dsk/.po not supplied in test resources");
        try (MachineHarness m = new MachineHarness(disk)) {
            assertTrue(m.bootDos());
            resetMidSession(m, false);
        }
    }
}
