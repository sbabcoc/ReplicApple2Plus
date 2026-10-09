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
 * The card's real firmware under real ProDOS 2.4.3 and BASIC.SYSTEM: PR#2
 * starts a session, files go both ways, END returns to BASIC with memory --
 * including a BASIC program in the borrowed region -- as it was.
 * <p>
 * Needs ProDOS_2_4_3.po (the official release image, from prodos8.com) in
 * this package's test resources; skipped without it, like the other tests
 * that need user-supplied images.
 */
class ProDosTransferIntegrationTest {

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
    void aWholeSessionUnderProDos() throws Exception {
        Path disk;
        try (InputStream in = getClass().getResourceAsStream("ProDOS_2_4_3.po")) {
            assumeTrue(in != null, "ProDOS_2_4_3.po not supplied in test resources");
            disk = host.resolve("prodos.po");
            Files.write(disk, in.readAllBytes());
        }
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

            assertTrue(runUntil("QUIT.SYSTEM", 60), "ProDOS boots to Bitsy Bye");
            run(1);
            type("B");
            run(0.5);
            type("B"); // the second B selects BASIC.SYSTEM
            run(0.5);
            type("\n");
            assertTrue(runUntil("]", 30), "BASIC.SYSTEM's prompt");
            run(1);
            type("10 PRINT \"KEEP ME\"\n"); // a program at $0801, inside the borrowed RAM
            run(2);
            type("PR#2\n");
            TransferSession session = null;
            for (double until = seconds + 15; seconds < until && session == null; ) {
                step();
                session = sessions.poll();
            }
            assertNotNull(session, "PR#2 began a session");
            assertEquals("PRODOS", session.capabilities().osName());

            // The host side runs on its own thread while the emulation runs here.
            TransferOperations ops = new TransferOperations(session);
            Path text = Files.writeString(host.resolve("HELLO.TXT"), "HELLO\nTHERE\n");
            byte[] code = new byte[1300]; // more than two 512-byte chunks
            new java.util.Random(3).nextBytes(code);
            // loads at $2000: free memory (a long file at $0300 would cross the text screen,
            // and ProDOS rightly refuses to BLOAD over pages its bit map marks in use)
            Path binary = Files.write(host.resolve("PROG#BIN,2000"), code);
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicReference<byte[]> readBack = new AtomicReference<>();
            AtomicReference<List<GuestEntry>> listing = new AtomicReference<>();
            TransferSession s = session;
            Thread hostThread = new Thread(() -> {
                try {
                    List<String> volume = List.of(ops.volumes().get(0));
                    for (TransferOperations.Result r : List.of(
                            ops.importFile(text, volume, "HELLO", null),
                            ops.importFile(binary, volume, "PROG", null))) {
                        if (r.outcome() != TransferOperations.Outcome.DONE) {
                            failure.set(r.toString());
                        }
                    }
                    listing.set(ops.list(volume));
                    readBack.set(s.read(List.of(volume.get(0), "PROG")).get());
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
            GuestEntry hello = listing.get().stream().filter(e -> e.name().equals("HELLO")).findFirst().orElseThrow();
            assertEquals(new FileType("TXT", 0), hello.type());
            assertEquals(12, hello.size(), "two lines, CR-terminated");
            assertArrayEquals(code, readBack.get());

            assertEquals(0xFDF0, bus.read(0x36) | bus.read(0x37) << 8, "CSW back on the screen");
            assertEquals(0xFDF0, bus.read(0xBE30) | bus.read(0xBE31) << 8, "VECTOUT back on the screen");
            type("RUN\n");
            run(2);
            // RUN's output is a row reading exactly KEEP ME (the program line itself has the quotes)
            assertTrue(screen().lines().anyMatch(row -> row.strip().equals("KEEP ME")),
                "the BASIC program survived:\n" + screen());
            type("BLOAD PROG\n");
            run(4);
            for (int i = 0; i < code.length; i++) {
                assertEquals(code[i] & 0xFF, bus.read(0x2000 + i), "BLOAD PROG byte " + i);
            }
        }
    }
}
