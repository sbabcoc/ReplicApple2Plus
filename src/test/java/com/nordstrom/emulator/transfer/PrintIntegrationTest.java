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
 * Printing BASIC source to the transfer card (PR#2) with the recipes of
 * TRANSFER-CARD.md 7, under the real systems: the job holds exactly the
 * listing -- every line intact, nothing else -- and imported back as a text
 * file and EXECed, it rebuilds an identical program.
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
    private static final String APPLESOFT_LISTING = "\n"
        + "10  PRINT \"THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 0123456789\"\n"
        + "20  FOR I = 1 TO 3: PRINT I;\" AND SOME MORE TEXT TO MAKE IT LONG\": NEXT I\n"
        + "30  END \n";

    /** What Integer BASIC's LIST makes of it: line numbers right-aligned. */
    private static final String INTEGER_LISTING = ""
        + "   10 PRINT \"THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 0123456789\"\n"
        + "   20 FOR I=1 TO 3: PRINT I;\" AND SOME MORE TEXT TO MAKE IT LONG\": NEXT I\n"
        + "   30 END \n";

    private static final String APPLESOFT_RECIPE = ":PR#2: LIST: PR#0";
    private static final String INTEGER_RECIPE = "0 PRINT \"\u0004PR#2\": LIST 1,32767: PRINT \"\u0004PR#0\": END";

    private static void enterProgram(MachineHarness m) {
        m.enter("NEW", 1);
        m.enter(LONG_10, 1);
        m.enter(LONG_20, 1);
        m.enter("30 END", 2);
    }

    private static String printApplesoft(MachineHarness m) {
        m.takePrinted();
        m.enter(APPLESOFT_RECIPE, 4);
        return m.takePrinted();
    }

    private static String printInteger(MachineHarness m) {
        m.takePrinted();
        m.enter(INTEGER_RECIPE, 1);
        m.enter("RUN", 4);
        String printed = m.takePrinted();
        m.enter("DEL 0,0", 1);
        return printed;
    }

    /**
     * Print Program Listing: an IN#2 session asked to end by printing the
     * listing -- the card types the running BASIC's recipe itself.
     */
    private static String printByCard(MachineHarness m) {
        m.takePrinted();
        TransferSession session = m.startSession();
        assertNotNull(session, "IN#2 began a session");
        assertTrue(session.capabilities().printsListing());
        java.util.concurrent.CompletableFuture<Void> done = session.printListing();
        for (int i = 0; i < 400 && !done.isDone(); i++) {
            m.run(0.05);
        }
        m.run(8); // the typing, then the listing
        assertEquals(0, m.card.readIoSwitch(Protocol.REG_TYPING), "done typing");
        String printed = m.takePrinted();
        // Input is back on the keyboard -- shown by behavior, since the vector
        // itself differs by OS (under DOS 3.3 it's DOS's own hook again).
        m.enter("PRINT 6*7", 2);
        assertTrue(m.screen().lines().anyMatch(row -> row.strip().equals("42")), "the keyboard works:\n" + m.screen());
        return printed;
    }

    /** Imports host text as a text file through an IN#2 session, then NEW and EXEC it. */
    private void execBack(MachineHarness m, String printed, String container) throws Exception {
        Path source = Files.writeString(dir.resolve("PROG.TXT"), printed);
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
            String printed = printApplesoft(m);
            assertEquals(APPLESOFT_LISTING, printed, "exactly the listing");

            execBack(m, printed, "PRODOS.2.4.3");
            assertEquals(printed, printApplesoft(m), "EXEC rebuilt the same program");
            assertEquals(printed, printByCard(m), "the card types the recipe itself");
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
            assertEquals(APPLESOFT_LISTING, printApplesoft(m), "exactly the listing");
            assertEquals(APPLESOFT_LISTING, printByCard(m), "the card types the recipe itself");
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
            String printed = printInteger(m);
            assertEquals(INTEGER_LISTING, printed, "exactly the listing: the helper at line 0 isn't in it");

            execBack(m, printed, "S6,D1");
            assertEquals(printed, printInteger(m), "EXEC rebuilt the same program");
            assertEquals(printed, printByCard(m), "the card types the recipe itself -- line 0 and all");
            m.takePrinted();
            m.enter("LIST", 3);
            assertTrue(!m.screen().contains("    0 PRINT"), "and its DEL 0,0 removed the helper line");
        }
    }
}
