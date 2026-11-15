package com.nordstrom.emulator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The headless runner's command line: the script's steps in order, and the escapes in typed text. */
class RunnerOptionsTest {

    @Test
    void stepsKeepTheirOrder() {
        RunnerOptions options = RunnerOptions.parse(new String[] {
            "--d1", "master.woz", "--d2", "test.dsk", "--videoterm",
            "--type", "BRUN CSWTEST,D2\\r", "--expect", "TYPE A WORD", "--type", "HELLO\\r",
            "--until", "PRESS A KEY", "--timeout", "30"});
        assertEquals(Path.of("master.woz"), options.drive1);
        assertEquals(Path.of("test.dsk"), options.drive2);
        assertTrue(options.videoTerm);
        assertEquals(List.of(
            new RunnerOptions.Type(List.of("BRUN CSWTEST,D2\n")),
            new RunnerOptions.Expect("TYPE A WORD"),
            new RunnerOptions.Type(List.of("HELLO\n"))), options.steps);
        assertEquals("PRESS A KEY", options.until);
        assertEquals(30.0, options.timeout);
        assertEquals(RunnerOptions.DEFAULT_BOOT_TIMEOUT, options.bootTimeout);
    }

    @Test
    void escapesBecomeReturnsBackslashesAndKeyCodes() {
        assertEquals(List.of("A\nB\n"), RunnerOptions.keys("A\\rB\\n"));
        assertEquals(List.of("C:\\"), RunnerOptions.keys("C:\\\\"));
        assertEquals(List.of("RUN", 0x03, "\n"), RunnerOptions.keys("RUN\\x03\\r"));
        assertEquals(List.of(0x1B), RunnerOptions.keys("\\x1b"));
    }

    @Test
    void badEscapesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RunnerOptions.keys("A\\"));
        assertThrows(IllegalArgumentException.class, () -> RunnerOptions.keys("\\t"));
        assertThrows(IllegalArgumentException.class, () -> RunnerOptions.keys("\\x4"));
        assertThrows(IllegalArgumentException.class, () -> RunnerOptions.keys("\\x+1"));
        assertThrows(IllegalArgumentException.class, () -> RunnerOptions.keys("\\x80"));
    }

    @Test
    void theBootDiskAndTheEndingTextAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> RunnerOptions.parse(new String[] {"--until", "X"}));
        assertThrows(IllegalArgumentException.class, () -> RunnerOptions.parse(new String[] {"--d1", "m.dsk"}));
        assertThrows(IllegalArgumentException.class,
            () -> RunnerOptions.parse(new String[] {"--d1", "m.dsk", "--until", ""}));
        assertThrows(IllegalArgumentException.class,
            () -> RunnerOptions.parse(new String[] {"--d1", "m.dsk", "--until", "X", "--timeout", "0"}));
        assertThrows(IllegalArgumentException.class,
            () -> RunnerOptions.parse(new String[] {"--d1", "m.dsk", "--until", "X", "--bogus"}));
        assertTrue(RunnerOptions.parse(new String[] {"--help"}).help);
    }
}
