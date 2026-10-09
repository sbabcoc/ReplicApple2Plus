package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Importing and exporting through a real card and session, with the fake adapter on its own thread. */
class TransferOperationsTest {

    @TempDir
    Path host;

    private final HostTransferCard card = new HostTransferCard();
    private final HostTransferCardTest.RecordingHost recorder = new HostTransferCardTest.RecordingHost();
    private final FakeAdapter adapter = new FakeAdapter(card);
    private AdapterThread adapterThread;
    private TransferOperations ops;

    /** Answers every conflict the same way, and counts the questions. */
    static final class Answer implements TransferOperations.Conflicts {
        final boolean yes;
        final AtomicInteger asked = new AtomicInteger();

        Answer(boolean yes) {
            this.yes = yes;
        }

        @Override
        public boolean replaceGuestFile(String guestName) {
            asked.incrementAndGet();
            return yes;
        }

        @Override
        public boolean overwriteHostFile(Path hostFile) {
            asked.incrementAndGet();
            return yes;
        }
    }

    private void start(byte[] capabilities) {
        card.setHost(recorder);
        adapter.begin(capabilities);
        ops = new TransferOperations(recorder.started.get(0));
        adapterThread = new AdapterThread(adapter);
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (adapterThread != null) {
            adapterThread.close();
        }
    }

    private Path hostFile(String name, String content) throws Exception {
        return Files.writeString(host.resolve(name), content, StandardCharsets.US_ASCII);
    }

    private static final List<String> VOL = List.of("VOL");

    @Test
    void importingADotTxtFileConvertsItsLines() throws Exception {
        start(FakeAdapter.proDosLike());
        Path file = hostFile("NOTES.TXT", "LINE ONE\nLINE TWO\n");
        TransferOperations.Result r = ops.importFile(file, VOL, "NOTES", new Answer(false));
        assertEquals(TransferOperations.Outcome.DONE, r.outcome(), r.detail());
        FakeAdapter.GuestFile f = adapter.files.get(List.of("VOL", "NOTES"));
        assertEquals("TXT", f.tag());
        assertEquals(0xE3, f.attributes(), "default attributes");
        assertArrayEquals("LINE ONE\rLINE TWO\r".getBytes(StandardCharsets.US_ASCII), f.data());
    }

    @Test
    void importingATypedBinaryKeepsItsTypeAuxAndBytes() throws Exception {
        start(FakeAdapter.proDosLike());
        byte[] code = {(byte) 0xA9, 0x0A, 0x20, (byte) 0xED, (byte) 0xFD, 0x60};
        Path file = Files.write(host.resolve("HELLO#BIN,0300,21"), code);
        assertEquals(TransferOperations.Outcome.DONE, ops.importFile(file, VOL, "HELLO", new Answer(false)).outcome());
        FakeAdapter.GuestFile f = adapter.files.get(List.of("VOL", "HELLO"));
        assertEquals("BIN", f.tag());
        assertEquals(0x0300, f.aux());
        assertEquals(0x21, f.attributes());
        assertArrayEquals(code, f.data());
    }

    @Test
    void anUntypedFileIsTextOnlyIfItsContentIsPlainText() throws Exception {
        start(FakeAdapter.proDosLike());
        ops.importFile(hostFile("plain", "A\r\nB\r\n"), VOL, "PLAIN", new Answer(false));
        assertEquals("TXT", adapter.files.get(List.of("VOL", "PLAIN")).tag());
        assertArrayEquals("A\rB\r".getBytes(StandardCharsets.US_ASCII), adapter.files.get(List.of("VOL", "PLAIN")).data());

        byte[] binary = {0, 1, 2, (byte) 0xFF};
        ops.importFile(Files.write(host.resolve("blob"), binary), VOL, "BLOB", new Answer(false));
        assertEquals("BIN", adapter.files.get(List.of("VOL", "BLOB")).tag());
        assertArrayEquals(binary, adapter.files.get(List.of("VOL", "BLOB")).data());
    }

    @Test
    void randomAccessTextIsNeverConverted() throws Exception {
        start(FakeAdapter.proDosLike());
        Path file = Files.write(host.resolve("DATA#TXT,0080"), "R1\nR2\n".getBytes(StandardCharsets.US_ASCII));
        ops.importFile(file, VOL, "DATA", new Answer(false));
        assertArrayEquals("R1\nR2\n".getBytes(StandardCharsets.US_ASCII), adapter.files.get(List.of("VOL", "DATA")).data());
    }

    @Test
    void anExistingGuestFileIsReplacedOnlyIfTheUserSaysSo() throws Exception {
        start(FakeAdapter.proDosLike());
        adapter.files.put(List.of("VOL", "NOTES"), new FakeAdapter.GuestFile("TXT", 0, 0xE3, "OLD\r".getBytes()));
        Path file = hostFile("NOTES.TXT", "NEW\n");

        Answer no = new Answer(false);
        assertEquals(TransferOperations.Outcome.SKIPPED, ops.importFile(file, VOL, "NOTES", no).outcome());
        assertEquals(1, no.asked.get());
        assertArrayEquals("OLD\r".getBytes(), adapter.files.get(List.of("VOL", "NOTES")).data());

        assertEquals(TransferOperations.Outcome.DONE, ops.importFile(file, VOL, "NOTES", new Answer(true)).outcome());
        assertArrayEquals("NEW\r".getBytes(), adapter.files.get(List.of("VOL", "NOTES")).data());
    }

    @Test
    void exportingTextConvertsItAndNamesItDotTxt() throws Exception {
        start(FakeAdapter.proDosLike());
        adapter.files.put(List.of("VOL", "NOTES"), new FakeAdapter.GuestFile("TXT", 0, 0xE3, "A\rB\r".getBytes()));
        GuestEntry entry = ops.list(VOL).get(0);
        TransferOperations.Result r = ops.exportFile(List.of("VOL", "NOTES"), entry, host, new Answer(false));
        assertEquals(TransferOperations.Outcome.DONE, r.outcome(), r.detail());
        assertEquals("NOTES.TXT", r.name());
        assertEquals("A\nB\n", Files.readString(host.resolve("NOTES.TXT")));
    }

    @Test
    void exportingABinaryKeepsEverythingInTheName() throws Exception {
        start(FakeAdapter.proDosLike());
        byte[] code = {1, 2, 3};
        adapter.files.put(List.of("VOL", "GAME"), new FakeAdapter.GuestFile("BIN", 0x2000, 0x21, code));
        GuestEntry entry = ops.list(VOL).get(0);
        ops.exportFile(List.of("VOL", "GAME"), entry, host, new Answer(false));
        assertArrayEquals(code, Files.readAllBytes(host.resolve("GAME#BIN,2000,21")));
    }

    @Test
    void anExportedFileImportsBackIdentically() throws Exception {
        start(FakeAdapter.proDosLike());
        byte[] code = new byte[1000];
        new java.util.Random(5).nextBytes(code);
        adapter.files.put(List.of("VOL", "ORIG"), new FakeAdapter.GuestFile("SYS", 0x2000, 0x21, code));
        adapter.files.put(List.of("VOL", "TEXT"), new FakeAdapter.GuestFile("TXT", 0, 0xE3, "X\rY\r".getBytes()));
        for (GuestEntry e : ops.list(VOL)) {
            ops.exportFile(List.of("VOL", e.name()), e, host, new Answer(false));
        }
        adapter.files.clear();
        try (var files = Files.list(host)) {
            for (Path p : files.toList()) {
                String name = HostNames.decode(p.getFileName().toString()).guestName();
                ops.importFile(p, VOL, name, new Answer(false));
            }
        }
        FakeAdapter.GuestFile orig = adapter.files.get(List.of("VOL", "ORIG"));
        assertEquals("SYS", orig.tag());
        assertEquals(0x2000, orig.aux());
        assertEquals(0x21, orig.attributes());
        assertArrayEquals(code, orig.data());
        assertArrayEquals("X\rY\r".getBytes(), adapter.files.get(List.of("VOL", "TEXT")).data());
    }

    @Test
    void anExistingHostFileIsOverwrittenOnlyIfTheUserSaysSo() throws Exception {
        start(FakeAdapter.proDosLike());
        adapter.files.put(List.of("VOL", "NOTES"), new FakeAdapter.GuestFile("TXT", 0, 0xE3, "NEW\r".getBytes()));
        hostFile("NOTES.TXT", "OLD\n");
        GuestEntry entry = ops.list(VOL).get(0);
        assertEquals(TransferOperations.Outcome.SKIPPED,
            ops.exportFile(List.of("VOL", "NOTES"), entry, host, new Answer(false)).outcome());
        assertEquals("OLD\n", Files.readString(host.resolve("NOTES.TXT")));
        ops.exportFile(List.of("VOL", "NOTES"), entry, host, new Answer(true));
        assertEquals("NEW\n", Files.readString(host.resolve("NOTES.TXT")));
    }

    @Test
    void errorsAreReportedWithTheOsOwnCode() throws Exception {
        start(FakeAdapter.proDosLike());
        TransferOperations.Result r = ops.importFile(hostFile("x", "x"), VOL, "FULL", new Answer(false));
        assertEquals(TransferOperations.Outcome.FAILED, r.outcome());
        assertEquals("DISK FULL (error $48)", r.detail());
    }

    @Test
    void cancellingWhileTheAppleIsSilentReturnsPromptly() throws Exception {
        start(FakeAdapter.proDosLike());
        adapterThread.goSilent();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                return;
            }
            ops.cancel();
        });
        canceller.start();
        long started = System.nanoTime();
        TransferOperations.Result r = ops.importFile(hostFile("x", "x"), VOL, "X", new Answer(false));
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertEquals(TransferOperations.Outcome.CANCELLED, r.outcome());
        assertTrue(millis < 2000, "returned in " + millis + " ms");
        canceller.join();
    }

    @Test
    void aSessionEndedByResetFailsTheOperationInsteadOfHanging() throws Exception {
        start(FakeAdapter.proDosLike());
        adapterThread.goSilent();
        Thread resetter = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                return;
            }
            card.onReset(); // in the application this runs on the emulation thread
        });
        resetter.start();
        TransferOperations.Result r = ops.importFile(hostFile("x", "x"), VOL, "X", new Answer(false));
        assertEquals(TransferOperations.Outcome.CANCELLED, r.outcome());
        assertTrue(r.detail().contains("RESET"), r.detail());
        resetter.join();
    }
}
