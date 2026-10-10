package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Capturing the program listing with PR#2, under the real systems: output
 * stays on the card, the card types LIST itself, and the capture holds
 * exactly the listing -- every line intact, nothing else: not the typed
 * command's echo, not BASIC's prompt after it. Imported back as a text file
 * and EXECed, it rebuilds an identical program.
 * <p>
 * Needs the same user-supplied images as the other integration tests;
 * each test is skipped without its image.
 */
class PrintIntegrationTest {

    @TempDir
    Path dir;

    private static final String LONG_10 = "10 PRINT \"THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 0123456789\"";
    private static final String LONG_20 = "20 FOR I=1 TO 3: PRINT I;\" AND SOME MORE TEXT TO MAKE IT LONG\": NEXT I";

    /** What Applesoft's LIST makes of the program: its own spacing, every line whole. */
    private static final String APPLESOFT_LISTING = ""
        + "10  PRINT \"THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 0123456789\"\n"
        + "20  FOR I = 1 TO 3: PRINT I;\" AND SOME MORE TEXT TO MAKE IT LONG\": NEXT I\n"
        + "30  END \n";

    /** What Integer BASIC's LIST makes of it: line numbers right-aligned. */
    private static final String INTEGER_LISTING = ""
        + "   10 PRINT \"THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 0123456789\"\n"
        + "   20 FOR I=1 TO 3: PRINT I;\" AND SOME MORE TEXT TO MAKE IT LONG\": NEXT I\n"
        + "   30 END \n";

    private static void enterProgram(MachineHarness m) {
        m.enter("NEW", 1);
        m.enter(LONG_10, 1);
        m.enter(LONG_20, 1);
        m.enter("30 END", 2);
    }

    /**
     * PR#2: the card types LIST, and the listing arrives in the spool.
     * Afterwards the keyboard is the keyboard again, and output is back on
     * the screen.
     */
    private static String capture(MachineHarness m) {
        m.takePrinted();
        m.enter("PR#2", 10); // the typing, then the listing
        assertEquals(0, m.card.readIoSwitch(Protocol.REG_TYPING), "done typing");
        String listing = m.takePrinted();
        m.enter("PRINT 6*7", 2); // shown by behavior: the vector itself differs by OS
        assertTrue(m.screen().lines().anyMatch(row -> row.strip().equals("42")), "the keyboard works:\n" + m.screen());
        return listing;
    }

    /** Imports host text as a text file through an IN#2 session, then NEW and EXEC it. */
    private void execBack(MachineHarness m, String listing, String container) throws Exception {
        Path source = Files.writeString(dir.resolve("PROG.TXT"), listing);
        TransferSession session = m.startSession();
        assertNotNull(session, "IN#2 began a session");
        TransferOperations ops = new TransferOperations(session);
        AtomicReference<TransferOperations.Result> imported = new AtomicReference<>();
        m.whileRunning(() -> {
            imported.set(ops.importFile(source, List.of(container), "PROG", null));
            ops.end();
        }, 60);
        assertEquals(TransferOperations.Outcome.DONE, imported.get().outcome(), imported.get().detail());
        m.enter("NEW", 1);
        m.enter("EXEC PROG", 8);
    }

    @Test
    void applesoftUnderProDos() throws Exception {
        Path disk = MachineHarness.resource(dir, "ProDOS_2_4_3.po");
        assumeTrue(disk != null, "ProDOS_2_4_3.po not supplied in test resources");
        try (MachineHarness m = new MachineHarness(disk)) {
            assertTrue(m.bootProDosBasic());
            enterProgram(m);
            String listing = capture(m);
            assertEquals(APPLESOFT_LISTING, listing, "exactly the listing");

            execBack(m, listing, "PRODOS.2.4.3");
            assertEquals(listing, capture(m), "EXEC rebuilt the same program");
        }
    }

    @Test
    void applesoftUnderDos33() throws Exception {
        Path disk = MachineHarness.resource(dir,
            "DOS_3_3_System_Master.woz", "DOS_3_3_System_Master.dsk", "DOS_3_3_System_Master.po");
        assumeTrue(disk != null, "DOS_3_3_System_Master.woz/.dsk/.po not supplied in test resources");
        try (MachineHarness m = new MachineHarness(disk)) {
            assertTrue(m.bootDos());
            enterProgram(m);
            assertEquals(APPLESOFT_LISTING, capture(m), "exactly the listing");
        }
    }

    @Test
    void integerBasicUnderDos33() throws Exception {
        Path disk = MachineHarness.resource(dir,
            "DOS_3_3_System_Master.woz", "DOS_3_3_System_Master.dsk", "DOS_3_3_System_Master.po");
        assumeTrue(disk != null, "DOS_3_3_System_Master.woz/.dsk/.po not supplied in test resources");
        try (MachineHarness m = new MachineHarness(disk)) {
            assertTrue(m.bootDos());
            m.enter("INT", 3);
            enterProgram(m);
            String listing = capture(m);
            assertEquals(INTEGER_LISTING, listing, "exactly the listing");

            execBack(m, listing, "S6,D1");
            assertEquals(listing, capture(m), "EXEC rebuilt the same program");
        }
    }
}
