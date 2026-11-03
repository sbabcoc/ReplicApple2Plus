package com.nordstrom.emulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The lines entered at each command prompt, oldest first, kept across
 * Reboot and -- through {@link #load} and {@link #save} -- across launches.
 * {@link CommandRecall} records into it and recalls from it.
 * <p>
 * One list per prompt character, because the prompt says which program is
 * reading the line: a Monitor command recalled at Applesoft's {@code ]}, or
 * a BASIC line at the Monitor's {@code *}, would mean nothing there. Only
 * the prompts of the built-in command interpreters are kept (see
 * {@link #isCommandPrompt}); a program's {@code INPUT} answers are not
 * commands.
 * <p>
 * The file holds one entry per line, each starting with its prompt
 * character -- the line as it looked on the screen -- oldest first.
 * <p>
 * Threading: the emulation thread records and recalls; {@link #save} runs
 * from the shutdown hook once that thread has stopped. Methods are
 * synchronized so a save can't see a list mid-change regardless.
 */
final class CommandHistory {

    /** The most entries kept per prompt; the oldest go first. */
    static final int MAX_ENTRIES = 100;

    /** The file in the emulator folder that keeps history across launches. */
    static final String FILE_NAME = "command-history.txt";

    private final Map<Character, List<String>> entries = new TreeMap<>();

    /**
     * Whether lines entered at {@code prompt} are kept: Applesoft's
     * {@code ]}, Integer BASIC's {@code >}, the Monitor's {@code *}, and the
     * mini-assembler's {@code !}.
     *
     * @param prompt the prompt character, 7-bit ASCII
     * @return true if it's one of the command interpreters' prompts
     */
    static boolean isCommandPrompt(char prompt) {
        return prompt == ']' || prompt == '>' || prompt == '*' || prompt == '!';
    }

    /**
     * Whether {@code line} can be kept: it isn't empty, and it holds no
     * control characters -- recalling one would retype the control key,
     * and the file couldn't hold a line containing a line feed.
     *
     * @param line the line, 7-bit ASCII
     * @return true if it can be recorded
     */
    static boolean isRecordable(String line) {
        if (line.isEmpty()) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * Adds a line as the newest entry for its prompt, unless it repeats the
     * newest entry already there. Lines {@link #isRecordable} rejects, and
     * prompts {@link #isCommandPrompt} rejects, are ignored.
     *
     * @param prompt the prompt the line was entered at
     * @param line   the line entered
     */
    synchronized void add(char prompt, String line) {
        if (!isCommandPrompt(prompt) || !isRecordable(line)) {
            return;
        }
        List<String> list = entries.computeIfAbsent(prompt, p -> new ArrayList<>());
        if (!list.isEmpty() && list.get(list.size() - 1).equals(line)) {
            return;
        }
        list.add(line);
        if (list.size() > MAX_ENTRIES) {
            list.remove(0);
        }
    }

    /**
     * @param prompt the prompt
     * @return a copy of its entries, oldest first; empty if there are none
     */
    synchronized List<String> entries(char prompt) {
        List<String> list = entries.get(prompt);
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * Reads the history kept in {@code file}, if it exists, adding its
     * entries in order. Lines that aren't a command prompt followed by a
     * recordable line are skipped.
     *
     * @param file the history file
     * @throws IOException if the file exists but can't be read
     */
    synchronized void load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
            if (line.length() > 1) {
                add(line.charAt(0), line.substring(1));
            }
        }
    }

    /**
     * Writes every entry to {@code file}, replacing what it held.
     *
     * @param file the history file
     * @throws IOException if it can't be written
     */
    synchronized void save(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        entries.forEach((prompt, list) -> list.forEach(entry -> lines.add(prompt + entry)));
        Files.write(file, lines, StandardCharsets.US_ASCII);
    }
}
