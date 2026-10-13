package com.nordstrom.emulator.transfer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menu item's logic against a stand-in for CP/M's CCP that prints what
 * the real one does (the texts come from running the SoftCard II master
 * disk; SoftCard2's tests run the real thing). Each case is one way the
 * start can go.
 */
class CpmTransferStarterTest {

    /** Enough of CP/M's CCP to answer the commands the starter types. */
    private static final class FakeCpm implements CpmTransferStarter.Keyboard {
        boolean pip = true;
        boolean load = true;
        boolean xfer;
        boolean hostWindow = true;
        boolean writeProtected;
        final List<String> commands = new ArrayList<>();
        private final StringBuilder screen = new StringBuilder("A>");
        private final StringBuilder line = new StringBuilder();
        private boolean waitingForKey;
        private boolean running;

        @Override
        public synchronized void type(String text) {
            for (char c : text.toCharArray()) {
                if (c == '\r') {
                    run(line.toString());
                    line.setLength(0);
                } else {
                    line.append(c);
                    screen.append(c);
                }
            }
        }

        @Override
        public synchronized void press(int key) {
            assertEquals(0x03, key, "only ^C is pressed");
            if (waitingForKey) {
                waitingForKey = false; // ^C after a Bdos Err: a warm boot
                screen.append("\r\nA>");
            } else if (!running) {
                commands.add("^C");
                screen.append("^C\r\nA>");
            }
        }

        @Override
        public boolean isTyping() {
            return false;
        }

        synchronized String screen() {
            return screen.toString();
        }

        private void run(String command) {
            commands.add(command);
            screen.append("\r\n");
            switch (command) {
                case "XFER" -> {
                    if (!xfer) {
                        screen.append("XFER?\r\nA>");
                    } else if (hostWindow) {
                        screen.append("HOST TRANSFER: CP/M 2.2\r\n");
                        running = true; // XFER keeps serving: no prompt
                    } else {
                        screen.append("HOST TRANSFER IS NOT AVAILABLE\r\n\r\nA>");
                    }
                }
                case "PIP XFER.HEX=RDR:" -> {
                    if (!pip) {
                        screen.append("PIP?\r\nA>");
                    } else if (writeProtected) {
                        screen.append("Bdos Err On A: R/O");
                        waitingForKey = true;
                    } else {
                        screen.append("\r\nA>");
                    }
                }
                case "LOAD XFER" -> {
                    if (!load) {
                        screen.append("LOAD?\r\nA>");
                    } else {
                        xfer = true;
                        screen.append("\r\nFIRST ADDRESS 0100\r\nLAST  ADDRESS 0888\r\nBYTES READ    0789\r\n"
                            + "RECORDS WRITTEN 10\r\n\r\n\r\nA>");
                    }
                }
                case "ERA XFER.HEX" -> screen.append("A>");
                default -> screen.append(command.split(" ")[0]).append("?\r\nA>");
            }
        }
    }

    private static CpmTransferStarter.Result start(FakeCpm cpm) throws InterruptedException {
        return new CpmTransferStarter(cpm, cpm::screen, 2_000, 20).run();
    }

    @Test
    void installsXferWhenTheDiskLacksItThenStartsIt() throws Exception {
        FakeCpm cpm = new FakeCpm();
        CpmTransferStarter.Result result = start(cpm);
        assertTrue(result.started(), result.message());
        assertEquals(List.of("^C", "XFER", "PIP XFER.HEX=RDR:", "LOAD XFER", "ERA XFER.HEX", "XFER"), cpm.commands);
    }

    @Test
    void startsXferWithoutInstallingWhenItsThere() throws Exception {
        FakeCpm cpm = new FakeCpm();
        cpm.xfer = true;
        assertTrue(start(cpm).started());
        assertEquals(List.of("^C", "XFER"), cpm.commands);
    }

    @Test
    void typesNothingUnlessCpmIsAtItsPrompt() throws Exception {
        FakeCpm cpm = new FakeCpm();
        cpm.type("DIR"); // a half-typed command line
        CpmTransferStarter.Result result = start(cpm);
        assertFalse(result.started());
        assertTrue(result.message().contains("prompt"), result.message());
        assertEquals(List.of(), cpm.commands);
    }

    @Test
    void namesAMissingPip() throws Exception {
        FakeCpm cpm = new FakeCpm();
        cpm.pip = false;
        CpmTransferStarter.Result result = start(cpm);
        assertFalse(result.started());
        assertTrue(result.message().contains("PIP.COM"), result.message());
    }

    @Test
    void namesAMissingLoad() throws Exception {
        FakeCpm cpm = new FakeCpm();
        cpm.load = false;
        CpmTransferStarter.Result result = start(cpm);
        assertFalse(result.started());
        assertTrue(result.message().contains("LOAD.COM"), result.message());
    }

    @Test
    void recoversFromABdosErrorAndReportsIt() throws Exception {
        FakeCpm cpm = new FakeCpm();
        cpm.writeProtected = true;
        CpmTransferStarter.Result result = start(cpm);
        assertFalse(result.started());
        assertTrue(result.message().contains("Bdos Err On A: R/O"), result.message());
        assertTrue(cpm.screen().endsWith("A>"), "^C took CP/M back to its prompt: " + cpm.screen());
        assertFalse(cpm.commands.contains("LOAD XFER"), "nothing after the failure");
    }

    @Test
    void reportsWhatXferSaysWhenItCantStart() throws Exception {
        FakeCpm cpm = new FakeCpm();
        cpm.xfer = true;
        cpm.hostWindow = false;
        CpmTransferStarter.Result result = start(cpm);
        assertFalse(result.started());
        assertTrue(result.message().contains("HOST TRANSFER IS NOT AVAILABLE"), result.message());
    }
}
