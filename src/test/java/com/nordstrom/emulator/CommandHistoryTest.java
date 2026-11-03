package com.nordstrom.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandHistoryTest {

    @Test
    void eachPromptKeepsItsOwnLinesOldestFirst() {
        CommandHistory history = new CommandHistory();
        history.add(']', "PRINT 1");
        history.add('*', "300.30F");
        history.add(']', "LIST");
        assertEquals(List.of("PRINT 1", "LIST"), history.entries(']'));
        assertEquals(List.of("300.30F"), history.entries('*'));
        assertEquals(List.of(), history.entries('>'));
    }

    @Test
    void onlyCommandInterpreterPromptsAreKept() {
        CommandHistory history = new CommandHistory();
        history.add('?', "42"); // an INPUT answer
        history.add((char) 0, "NAME"); // INPUT with its own prompt string
        assertTrue(history.entries('?').isEmpty());
        assertTrue(history.entries((char) 0).isEmpty());
    }

    @Test
    void emptyLinesControlCharactersAndRepeatsAreNotKept() {
        CommandHistory history = new CommandHistory();
        history.add(']', "");
        history.add(']', "PRINT \u0007");
        history.add(']', "RUN");
        history.add(']', "RUN");
        assertEquals(List.of("RUN"), history.entries(']'));
    }

    @Test
    void theOldestEntriesGoFirstPastTheLimit() {
        CommandHistory history = new CommandHistory();
        for (int i = 0; i < CommandHistory.MAX_ENTRIES + 5; i++) {
            history.add(']', "PRINT " + i);
        }
        List<String> entries = history.entries(']');
        assertEquals(CommandHistory.MAX_ENTRIES, entries.size());
        assertEquals("PRINT 5", entries.get(0));
    }

    @Test
    void savedHistoryLoadsBackTheSame(@TempDir Path dir) throws IOException {
        CommandHistory history = new CommandHistory();
        history.add(']', "PRINT 1");
        history.add('*', "300G");
        history.add(']', "LIST 10");
        Path file = dir.resolve(CommandHistory.FILE_NAME);
        history.save(file);
        assertEquals(List.of("*300G", "]PRINT 1", "]LIST 10"), Files.readAllLines(file, StandardCharsets.US_ASCII),
            "each line is the entry as it looked on screen, prompt first");

        CommandHistory loaded = new CommandHistory();
        loaded.load(file);
        assertEquals(history.entries(']'), loaded.entries(']'));
        assertEquals(history.entries('*'), loaded.entries('*'));
    }

    @Test
    void loadingSkipsLinesThatAreNotEntriesAndAMissingFileIsNoHistory(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(CommandHistory.FILE_NAME);
        CommandHistory history = new CommandHistory();
        history.load(file);
        assertTrue(history.entries(']').isEmpty());

        Files.write(file, List.of("]RUN", "", "]", "?42", "XYZ", ">LIST"), StandardCharsets.US_ASCII);
        history.load(file);
        assertEquals(List.of("RUN"), history.entries(']'));
        assertEquals(List.of("LIST"), history.entries('>'));
    }
}
