package com.nordstrom.emulator.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Starts a transfer session under CP/M the way a user would at the
 * keyboard: runs {@code XFER}, and if the disk lacks it, installs it from
 * the card's reader first (TRANSFER-CARD.md 8.5). The guest runs every
 * program and writes every file; this only types commands and reads what
 * CP/M prints.
 * <p>
 * This is the one place that knows CP/M's commands and their output. It
 * is independent of Swing: the application wires it to its typing and to
 * the console's screen text, and tests wire it to their own machine.
 * {@link #run} blocks until it finishes, so it runs on a thread of its
 * own while the machine runs on another.
 * <p>
 * Each command waits for the CCP prompt to return, with the screen
 * settled, before the next is typed; nothing is typed ahead. What the
 * command printed decides what happens next.
 */
public final class CpmTransferStarter {

    /** How the starter types into the machine. */
    public interface Keyboard {

        /**
         * Queues text to type: printable characters, and line endings as
         * Return.
         *
         * @param text the text
         */
        void type(String text);

        /**
         * Queues one keystroke, as the Apple keyboard produces it.
         *
         * @param key the Apple II key code, e.g. {@code 0x03} for ^C
         */
        void press(int key);

        /** @return true while queued keystrokes remain to be typed */
        boolean isTyping();
    }

    /** What happened. */
    public record Result(boolean started, String message) {
    }

    private static final int CONTROL_C = 0x03;
    private static final Pattern PROMPT = Pattern.compile("[A-P]>");
    private static final String TITLE = "HOST TRANSFER: CP/M";
    private static final String BDOS_ERROR = "Bdos Err";

    private final Keyboard keyboard;
    private final Supplier<String> console;
    private final long commandTimeoutMillis;
    private final long settleMillis;

    /**
     * @param keyboard             types into the machine
     * @param console              the console's text as it stands, lines separated by line endings
     * @param commandTimeoutMillis how long one command may run before the starter gives up
     * @param settleMillis         how long the screen must stay unchanged to count as settled
     */
    public CpmTransferStarter(Keyboard keyboard, Supplier<String> console, long commandTimeoutMillis,
                              long settleMillis) {
        this.keyboard = keyboard;
        this.console = console;
        this.commandTimeoutMillis = commandTimeoutMillis;
        this.settleMillis = settleMillis;
    }

    /**
     * Starts XFER, installing it first if the current disk lacks it.
     *
     * @return whether a session started, and if not, why
     * @throws InterruptedException if interrupted while waiting on the machine
     */
    public Result run() throws InterruptedException {
        if (!isPrompt(lastLine(lines()))) {
            return failed("CP/M isn't waiting at its prompt (A> or the like). Finish what's running, then try again.");
        }
        // A warm boot first: it clears the read-only state BDOS gives a disk
        // changed without one, which would stop the install below.
        Step warmBoot = command(null, "^C", this::promptReturned);
        if (!warmBoot.done()) {
            return failed("CP/M didn't return to its prompt after ^C.");
        }

        Step xfer = xfer();
        if (!xfer.done() || xfer.printed(TITLE)) {
            return outcomeOf(xfer);
        }
        if (!xfer.printed("XFER?")) {
            return failedWith("XFER", xfer);
        }

        // XFER isn't on this disk: install it from the card's reader.
        Step pip = command("PIP XFER.HEX=RDR:", "PIP XFER.HEX=RDR:", this::promptReturned);
        if (!pip.succeededQuietly()) {
            return pip.printed("PIP?")
                ? failed("PIP.COM isn't on the current disk; it's needed to install XFER.")
                : failedWith("PIP", pip);
        }
        Step load = command("LOAD XFER", "LOAD XFER", this::promptReturned);
        if (!load.done() || !load.printed("RECORDS WRITTEN")) {
            return load.printed("LOAD?")
                ? failed("LOAD.COM isn't on the current disk; it's needed to install XFER.")
                : failedWith("LOAD", load);
        }
        Step erase = command("ERA XFER.HEX", "ERA XFER.HEX", this::promptReturned);
        if (erase.printed(BDOS_ERROR)) {
            return failedWith("ERA", erase);
        }
        return outcomeOf(xfer());
    }

    private Step xfer() throws InterruptedException {
        return command("XFER", "XFER", lines -> promptReturned(lines) || lines.stream().anyMatch(l -> l.contains(TITLE)));
    }

    private Result outcomeOf(Step xfer) {
        if (xfer.printed(TITLE)) {
            return new Result(true, "XFER started a transfer session.");
        }
        if (!xfer.done()) {
            return failed("XFER didn't start within " + commandTimeoutMillis / 1000 + " seconds.");
        }
        return failedWith("XFER", xfer);
    }

    private Result failedWith(String what, Step step) {
        String printed = String.join("\n", step.output()).strip();
        if (!step.done()) {
            return failed(what + " didn't finish within " + commandTimeoutMillis / 1000 + " seconds."
                + (printed.isEmpty() ? "" : "\nCP/M printed:\n" + printed));
        }
        return failed(what + " failed. CP/M printed:\n" + (printed.isEmpty() ? "(nothing)" : printed));
    }

    private static Result failed(String message) {
        return new Result(false, message);
    }

    // ---- one command -------------------------------------------------------

    /** A command's outcome: whether it finished, and the lines it printed. */
    private record Step(boolean done, List<String> output) {

        boolean printed(String text) {
            return output.stream().anyMatch(line -> line.contains(text));
        }

        /** Finished, having printed nothing: how PIP reports success. */
        boolean succeededQuietly() {
            return done && output.stream().allMatch(String::isBlank);
        }
    }

    /**
     * Types a command and waits for it to finish.
     *
     * @param line     the command line, typed followed by Return; or null to press ^C alone
     * @param echo     how the command appears after the prompt on screen
     * @param finished when the screen shows the command is done
     */
    private Step command(String line, String echo, Predicate<List<String>> finished) throws InterruptedException {
        String before = console.get();
        boolean changed = false; // an earlier, identical command may still be on screen
        if (line == null) {
            keyboard.press(CONTROL_C);
        } else {
            keyboard.type(line + "\r");
        }
        long deadline = System.nanoTime() + commandTimeoutMillis * 1_000_000;
        String seen = null;
        long settledSince = 0;
        while (System.nanoTime() < deadline) {
            String now = console.get();
            if (!now.equals(seen)) {
                seen = now;
                settledSince = System.nanoTime();
            }
            changed |= !now.equals(before);
            List<String> lines = lines(now);
            List<String> output = outputAfter(lines, echo);
            boolean settled = System.nanoTime() - settledSince >= settleMillis * 1_000_000;
            if (changed && !keyboard.isTyping() && output != null && settled) {
                if (bdosError(output)) {
                    return recover(output);
                }
                if (finished.test(lines)) {
                    return new Step(true, trimPrompt(output));
                }
            }
            Thread.sleep(Math.max(1, settleMillis / 4));
        }
        List<String> output = outputAfter(lines(), echo);
        return new Step(false, output != null ? output : List.of());
    }

    /**
     * After "Bdos Err On d: ...", BDOS waits for a key; ^C warm-boots CP/M
     * back to its prompt. The step reports the error.
     */
    private Step recover(List<String> output) throws InterruptedException {
        keyboard.press(CONTROL_C);
        long deadline = System.nanoTime() + commandTimeoutMillis * 1_000_000;
        while (System.nanoTime() < deadline && !(!keyboard.isTyping() && promptReturned(lines()))) {
            Thread.sleep(Math.max(1, settleMillis / 4));
        }
        return new Step(true, output);
    }

    private static boolean bdosError(List<String> output) {
        return output.stream().anyMatch(line -> line.contains(BDOS_ERROR));
    }

    private boolean promptReturned(List<String> lines) {
        return isPrompt(lastLine(lines));
    }

    private static boolean isPrompt(String line) {
        return PROMPT.matcher(line.strip()).matches();
    }

    /**
     * The lines after the last one showing the command's echo, a prompt
     * followed by it ("A>PIP XFER.HEX=RDR:"); null until the echo appears.
     */
    private static List<String> outputAfter(List<String> lines, String echo) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).strip();
            if (line.length() > 2 && PROMPT.matcher(line.substring(0, 2)).matches()
                    && line.substring(2).equals(echo)) {
                return new ArrayList<>(lines.subList(i + 1, lines.size()));
            }
        }
        return null;
    }

    /** The output without the prompt that ends it. */
    private static List<String> trimPrompt(List<String> output) {
        List<String> trimmed = new ArrayList<>(output);
        if (!trimmed.isEmpty() && isPrompt(trimmed.get(trimmed.size() - 1))) {
            trimmed.remove(trimmed.size() - 1);
        }
        return trimmed;
    }

    private List<String> lines() {
        return lines(console.get());
    }

    /** The console's lines, trailing blank lines dropped. */
    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n|\r", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String lastLine(List<String> lines) {
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
    }
}
