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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The headless runner end to end: DOS 3.3 booted to its prompt, a command
 * typed, the screen written out, and the exit status.
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
}
