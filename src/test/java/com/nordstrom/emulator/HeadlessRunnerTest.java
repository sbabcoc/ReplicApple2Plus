package com.nordstrom.emulator;

import com.nordstrom.emulator.RequiresRoms.Rom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The headless runner end to end: DOS 3.3 booted to its prompt, a command
 * typed, the screen written out, and the exit status; the machine built
 * from a slot configuration; and real-time pacing.
 * <p>
 * The booting tests need the DOS 3.3 System Master the transfer card's
 * tests use, {@code DOS_3_3_System_Master.woz} or {@code .dsk} in
 * {@code com/nordstrom/emulator/transfer}'s test resources; they're
 * skipped without it.
 */
class HeadlessRunnerTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return HeadlessRunner.run(args, new PrintStream(out, true, StandardCharsets.US_ASCII),
            new PrintStream(err, true, StandardCharsets.US_ASCII));
    }

    private Path master() throws IOException {
        for (String extension : new String[] {".woz", ".dsk"}) {
            try (InputStream in = getClass().getResourceAsStream(
                    "/com/nordstrom/emulator/transfer/DOS_3_3_System_Master" + extension)) {
                if (in != null) {
                    Path disk = dir.resolve("master" + extension);
                    Files.write(disk, in.readAllBytes());
                    return disk;
                }
            }
        }
        assumeTrue(false, "DOS_3_3_System_Master.woz/.dsk not supplied in the transfer tests' resources");
        return null;
    }

    @Test
    @RequiresRoms(Rom.SYSTEM)
    void bootsTypesAndWritesTheScreen() throws IOException {
        Path master = master();
        byte[] before = Files.readAllBytes(master);
        Path screen = dir.resolve("screen.txt");
        int status = run("--d1", master.toString(), "--type", "PRINT 6*7\\r", "--until", "42",
            "--screen", screen.toString());
        assertEquals(HeadlessRunner.FINISHED, status, err.toString(StandardCharsets.US_ASCII));
        String text = Files.readString(screen, StandardCharsets.US_ASCII);
        assertTrue(text.contains("]PRINT 6*7"), text);
        assertTrue(text.lines().anyMatch(line -> line.strip().equals("42")), text);
        assertArrayEquals(before, Files.readAllBytes(master), "the image given is left as it was");
    }

    @Test
    @RequiresRoms(Rom.SYSTEM)
    void textThatNeverAppearsTimesOutWithTheScreenOnStdout() throws IOException {
        int status = run("--d1", master().toString(), "--until", "NEVER SHOWN", "--timeout", "2");
        String report = "stdout:\n" + out.toString(StandardCharsets.US_ASCII)
            + "\nstderr:\n" + err.toString(StandardCharsets.US_ASCII);
        assertEquals(HeadlessRunner.TIMED_OUT, status, report);
        assertTrue(out.toString(StandardCharsets.US_ASCII).contains("]"), "the screen, as it was left\n" + report);
        assertTrue(err.toString(StandardCharsets.US_ASCII).contains("NEVER SHOWN"), report);
    }

    @Test
    @RequiresRoms(Rom.SYSTEM)
    void aWaitPastTheTimeoutTimesOut() throws IOException {
        int status = run("--d1", master().toString(), "--wait", "5", "--until", "]", "--timeout", "1");
        assertEquals(HeadlessRunner.TIMED_OUT, status, err.toString(StandardCharsets.US_ASCII));
        assertTrue(err.toString(StandardCharsets.US_ASCII).contains("--wait"));
    }

    @Test
    void aMissingDiskIsASetupFailure() {
        assertEquals(HeadlessRunner.SETUP_FAILED,
            run("--d1", dir.resolve("absent.dsk").toString(), "--until", "X", "--roms", dir.toString()));
    }

    @Test
    @RequiresRoms(Rom.SYSTEM)
    void aConfigurationNamesTheMachineAndItsBootDisk() throws IOException {
        Path master = master();
        byte[] before = Files.readAllBytes(master);
        Path config = dir.resolve("slots.ini");
        Files.writeString(config, "[6]\ntype=disk2\ndrive1=" + master.getFileName() + "\n");
        int status = run("--config", config.toString(), "--type", "PRINT 6*7\\r", "--until", "42");
        assertEquals(HeadlessRunner.FINISHED, status, err.toString(StandardCharsets.US_ASCII));
        assertTrue(out.toString(StandardCharsets.US_ASCII).lines().anyMatch(line -> line.strip().equals("42")));
        assertArrayEquals(before, Files.readAllBytes(master), "the image the configuration names is left as it was");
    }

    @Test
    @RequiresRoms(Rom.SYSTEM)
    void realTimeTakesTheEmulatedTimeInRealTime() throws IOException {
        Path master = master();
        long start = System.nanoTime();
        int status = run("--d1", master.toString(), "--real-time", "--wait", "3", "--until", "]");
        double seconds = (System.nanoTime() - start) / 1e9;
        assertEquals(HeadlessRunner.FINISHED, status, err.toString(StandardCharsets.US_ASCII));
        assertTrue(seconds >= 4, "a boot and a 3-second wait took " + seconds + " s");
    }

    @Test
    void cardFlagsDontMixWithAConfiguration() throws IOException {
        Path config = dir.resolve("slots.ini");
        Files.writeString(config, "[6]\ntype=disk2\n");
        assertEquals(HeadlessRunner.SETUP_FAILED,
            run("--config", config.toString(), "--videoterm", "--until", "X", "--roms", dir.toString()));
        assertTrue(err.toString(StandardCharsets.US_ASCII).contains("--videoterm"));
    }

    @Test
    void anEmulatorFolderWithoutAConfigurationStillNeedsABootDisk() throws IOException {
        Path folder = Files.createDirectory(dir.resolve("emulator"));
        assertEquals(HeadlessRunner.SETUP_FAILED, run("--folder", folder.toString(), "--until", "X"));
        assertTrue(err.toString(StandardCharsets.US_ASCII).contains("--d1"));
    }

    @Test
    void realTimeSleepsUntilRealTimeCatchesUp() {
        long[] now = {0};
        List<Long> sleeps = new ArrayList<>();
        HeadlessRunner.RealTime pace = new HeadlessRunner.RealTime(() -> now[0], nanos -> {
            sleeps.add(nanos);
            now[0] += nanos;
        });
        pace.keepPace(0);
        now[0] += 400_000_000L; // the host ran 1,023,000 cycles in 0.4 s
        pace.keepPace(1_023_000);
        assertEquals(List.of(600_000_000L), sleeps);
    }

    @Test
    void realTimeStartsAfreshWhenFarBehind() {
        long[] now = {0};
        List<Long> sleeps = new ArrayList<>();
        HeadlessRunner.RealTime pace = new HeadlessRunner.RealTime(() -> now[0], nanos -> {
            sleeps.add(nanos);
            now[0] += nanos;
        });
        pace.keepPace(0);
        now[0] += 2_000_000_000L; // a second of emulated time took two
        pace.keepPace(1_023_000);
        now[0] += 500_000_000L;
        pace.keepPace(2_046_000); // half a second ahead of the fresh start, not half a second behind the old one
        assertEquals(List.of(500_000_000L), sleeps);
    }
}
