package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IniFileTest {

    @Test
    void parsesSectionsAndKeysSkippingCommentsAndBlankLines() {
        Map<String, Properties> sections = IniFile.parse(List.of(
            "; a comment", "", "[one]", "a = 1", "# another comment", "b=two words", "[two]", "c = 3"));

        assertEquals(List.of("one", "two"), List.copyOf(sections.keySet()), "sections keep file order");
        assertEquals("1", sections.get("one").getProperty("a"));
        assertEquals("two words", sections.get("one").getProperty("b"));
        assertEquals("3", sections.get("two").getProperty("c"));
    }

    @Test
    void anEmptyValueIsKeptAsAnEmptyStringNotDropped() {
        // The input configuration relies on this: "pad =" means "clear the binding".
        Map<String, Properties> sections = IniFile.parse(List.of("[s]", "pad ="));
        assertEquals("", sections.get("s").getProperty("pad"));
    }

    @Test
    void aKeyBeforeAnySectionIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> IniFile.parse(List.of("orphan = 1")));
        assertTrue(e.getMessage().contains("orphan"), "the message should quote the offending line");
    }

    @Test
    void aLineWithoutAnEqualsSignIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> IniFile.parse(List.of("[s]", "not a pair")));
    }

    @Test
    void readingAFileGivesTheSameResultAsReadingItsLines(@TempDir Path dir) throws IOException {
        List<String> lines = List.of("[s]", "k = v");
        Path file = dir.resolve("x.ini");
        Files.write(file, lines);
        assertEquals(IniFile.parse(lines).get("s").getProperty("k"), IniFile.parse(file).get("s").getProperty("k"));
    }
}
