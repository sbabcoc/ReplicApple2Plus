package com.nordstrom.emulator.transfer;

import com.nordstrom.emulator.cpu.Cpu6502;
import com.nordstrom.emulator.expansion.Disk2Controller;
import com.nordstrom.emulator.expansion.LanguageCard;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.emulator.system.SystemClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The card's real firmware under real DOS 3.3: IN#2 starts a session,
 * files go both ways with DOS's headers handled by the adapter, END returns
 * to BASIC with memory -- including the BASIC program in the borrowed
 * region -- as it was, and DOS still catches printed commands.
 * <p>
 * Needs a DOS 3.3 System Master image, named DOS_3_3_System_Master.woz,
 * .dsk or .po, in this package's test resources; skipped without one, like
 * the other tests that need user-supplied images.
 */
class DosTransferIntegrationTest {

    @TempDir
    Path host;

    private MotherboardBus bus;
    private SystemClock clock;
    private final Deque<Integer> keys = new ConcurrentLinkedDeque<>();
    private double seconds;

    private void step() {
        if (!keys.isEmpty() && !bus.keyboardRegister().isStrobeSet()) {
            bus.keyboardRegister().keyPressed(keys.poll());
        }
        seconds += clock.step() / 1_023_000.0;
    }

    private void run(double s) {
        double until = seconds + s;
        while (seconds < until) {
            step();
        }
    }

    private boolean runUntil(String text, double max) {
        double until = seconds + max;
        for (long n = 0; seconds < until; n++) {
            step();
            if (n % 20_000 == 0 && screen().contains(text)) {
                return true;
            }
        }
        return screen().contains(text);
    }

    private void type(String s) {
        for (char c : s.toCharArray()) {
            keys.add(c == '\n' ? 0x0D : (int) c);
        }
    }

    private String screen() {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < 24; row++) {
            int base = 0x400 + 0x80 * (row % 8) + 0x28 * (row / 8);
            for (int col = 0; col < 40; col++) {
                int g = bus.read(base + col) & 0x3F;
                sb.append((char) (g < 0x20 ? g + 0x40 : g));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    void aWholeSessionUnderDos33() throws Exception {
        Path disk = null;
        for (String extension : new String[] {".woz", ".dsk", ".po"}) {
            try (InputStream in = getClass().getResourceAsStream("DOS_3_3_System_Master" + extension)) {
                if (in != null && disk == null) {
                    disk = host.resolve("master" + extension);
                    Files.write(disk, in.readAllBytes());
                }
            }
        }
        assumeTrue(disk != null, "DOS_3_3_System_Master.woz/.dsk/.po not supplied in test resources");
        BlockingQueue<TransferSession> sessions = new LinkedBlockingQueue<>();
        AtomicReference<TransferHost.EndReason> ended = new AtomicReference<>();
        SlotCard[] slots = new SlotCard[8];
        slots[0] = new LanguageCard();
        HostTransferCard card = new HostTransferCard();
        card.setHost(new TransferHost() {
            @Override
            public void sessionStarted(TransferSession session) {
                sessions.add(session);
            }

            @Override
            public void sessionEnded(TransferSession session, EndReason reason) {
                ended.set(reason);
            }
        });
        slots[2] = card;
        Disk2Controller disk2 = new Disk2Controller();
        slots[6] = disk2;
        disk2.removableDrives().get(0).insert(disk);
        try (MotherboardBus b = MotherboardBus.withoutAudio(slots)) {
            bus = b;
            clock = new SystemClock(new Cpu6502(bus, 0xFFFC));
            clock.addCycleListener(bus.videoScanner()::tick);
            clock.addCycleListener(bus.scanlineModes()::tick);
            clock.addCycleListener(disk2::tick);

            assertTrue(runUntil("DOS VERSION 3.3", 30), "DOS 3.3 boots");
            run(25); // the System Master loads Integer BASIC into the Language Card
            type("10 PRINT \"KEEP ME\"\n20 END\n"); // in the borrowed region
            run(2);
            type("IN#2\n");
            TransferSession session = null;
            for (double until = seconds + 15; seconds < until && session == null; ) {
                step();
                session = sessions.poll();
            }
            assertNotNull(session, "IN#2 began a session");
            assertEquals("DOS", session.capabilities().osName());

            TransferOperations ops = new TransferOperations(session);
            Path text = Files.writeString(host.resolve("NOTES.TXT"), "HELLO\nTHERE\n");
            byte[] code = {(byte) 0xA9, (byte) 0xC1, 0x20, (byte) 0xED, (byte) 0xFD, 0x60}; // LDA #'A'; JSR COUT; RTS
            Path binary = Files.write(host.resolve("PROG#B,0300"), code);
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicReference<byte[]> notes = new AtomicReference<>();
            AtomicReference<List<GuestEntry>> listing = new AtomicReference<>();
            AtomicReference<List<String>> volumes = new AtomicReference<>();
            AtomicReference<String> exported = new AtomicReference<>();
            Files.createDirectories(host.resolve("out"));
            TransferSession s = session;
            Thread hostThread = new Thread(() -> {
                try {
                    volumes.set(ops.volumes());
                    List<String> drive = List.of("S6,D1");
                    for (TransferOperations.Result r : List.of(
                            ops.importFile(text, drive, "NOTES", null),
                            ops.importFile(binary, drive, "PROG", null))) {
                        if (r.outcome() != TransferOperations.Outcome.DONE) {
                            failure.set(r.toString());
                        }
                    }
                    notes.set(s.read(List.of("S6,D1", "NOTES")).get().bytes());
                    listing.set(ops.list(drive));
                    GuestEntry prog = listing.get().stream().filter(e -> e.name().equals("PROG")).findFirst().orElseThrow();
                    exported.set(ops.exportFile(List.of("S6,D1", "PROG"), prog, host.resolve("out"), null).name());
                    ops.end();
                } catch (Exception e) {
                    failure.set(e.toString());
                }
            });
            hostThread.start();
            for (double until = seconds + 120; seconds < until && hostThread.isAlive(); ) {
                step();
            }
            run(2);
            hostThread.join(1000);

            assertEquals(null, failure.get());
            assertEquals(TransferHost.EndReason.COMPLETED, ended.get());
            assertTrue(volumes.get().contains("S6,D1"), String.valueOf(volumes.get()));
            byte[] expected = "HELLO\rTHERE\r".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            for (int i = 0; i < expected.length; i++) {
                expected[i] |= (byte) 0x80; // DOS text: high bit set
            }
            assertArrayEquals(expected, notes.get());
            // The listing reads only catalog sectors: a B file's load address is
            // in its first data sector, so it arrives with READ instead.
            GuestEntry prog = listing.get().stream().filter(e -> e.name().equals("PROG")).findFirst().orElseThrow();
            assertEquals("B", prog.type().tag());
            assertTrue(!prog.auxKnown() && !prog.sizeExact());
            assertEquals("PROG#B,0300", exported.get(), "named from the load address READ returned");
            assertArrayEquals(code, Files.readAllBytes(host.resolve("out").resolve("PROG#B,0300")));

            type("RUN\n");
            run(2);
            assertTrue(screen().lines().anyMatch(row -> row.strip().equals("KEEP ME")),
                "the BASIC program survived:\n" + screen());
            type("BLOAD PROG\n");
            run(4);
            for (int i = 0; i < code.length; i++) {
                assertEquals(code[i] & 0xFF, bus.read(0x300 + i), "BLOAD PROG byte " + i);
            }
            type("HOME\n");
            run(1);
            type("PRINT CHR$(4)\"CATALOG\"\n"); // DOS still catches printed commands
            run(5);
            // (the catalog itself pauses once the screen fills; its header is the point here)
            assertTrue(screen().contains("DISK VOLUME"), "DOS ran the printed CATALOG:\n" + screen());
        }
    }
}
