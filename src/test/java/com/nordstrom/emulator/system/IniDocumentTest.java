package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IniDocumentTest {

    private static final Comparator<String> BY_NUMBER = Comparator.comparingInt(Integer::parseInt);

    @Test
    void parsesSectionsAndKeysSkippingCommentsAndBlankLines() {
        Map<String, Properties> sections = IniDocument.parse(List.of(
            "; a comment", "", "[one]", "a = 1", "# another comment", "b=two words", "[two]", "c = 3")).sections();

        assertEquals(List.of("one", "two"), List.copyOf(sections.keySet()), "sections keep file order");
        assertEquals("1", sections.get("one").getProperty("a"));
        assertEquals("two words", sections.get("one").getProperty("b"));
        assertEquals("3", sections.get("two").getProperty("c"));
    }

    @Test
    void anEmptyValueIsKeptAsAnEmptyStringNotDropped() {
        // The input configuration relies on this: "pad =" means "clear the binding".
        assertEquals("", IniDocument.parse(List.of("[s]", "pad =")).sections().get("s").getProperty("pad"));
    }

    @Test
    void aKeyBeforeAnySectionIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> IniDocument.parse(List.of("orphan = 1")).sections());
        assertTrue(e.getMessage().contains("orphan"), "the message should quote the offending line");
    }

    @Test
    void aLineWithoutAnEqualsSignIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> IniDocument.parse(List.of("[s]", "not a pair")).sections());
    }

    @Test
    void aRepeatedSectionIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> IniDocument.parse(List.of("[6]", "type=disk2", "[6]", "type=disk2")).sections());
        assertTrue(e.getMessage().startsWith("Line 3:"), e.getMessage());
    }

    @Test
    void readingAFileGivesTheSameResultAsReadingItsLines(@TempDir Path dir) throws IOException {
        List<String> lines = List.of("[s]", "k = v");
        Path file = dir.resolve("x.ini");
        Files.write(file, lines);
        assertEquals(IniDocument.parse(lines).sections().get("s").getProperty("k"),
            IniDocument.read(file).sections().get("s").getProperty("k"));
    }

    @Test
    void aBrokenFileStillReadsAndReportsEachProblemWithItsLineNumber() {
        IniDocument document = IniDocument.parse(List.of(
            "stray = 1", "[2]", "type=hostfiles", "nonsense", "[2]"));
        List<IniDocument.Problem> problems = document.problems();
        assertEquals(List.of(1, 4, 5), problems.stream().map(IniDocument.Problem::line).toList());
        assertEquals("nonsense", problems.get(1).text());
        assertEquals(List.of("2"), document.sectionNames(), "a repeated section is listed once");
        assertEquals(Map.of("type", "hostfiles"), document.entries("2"));
    }

    @Test
    void anUneditedDocumentWritesBackExactlyAsRead(@TempDir Path dir) throws IOException {
        List<String> lines = List.of("# Slots", "", "[0]", "type=languageCard", "", "[6]", "type = disk2",
            "  ; indented comment", "drive1 =  disks/a.woz");
        Path file = dir.resolve("slots.ini");
        IniDocument.parse(lines).write(file);
        assertEquals(lines, Files.readAllLines(file));
    }

    @Test
    void settingAValueKeepsTheLinesKeyAndSpacing() {
        IniDocument document = IniDocument.parse(List.of("[6]", "type=disk2", "drive1 =  disks/a.woz"));
        document.set("6", "drive1", "disks/b.woz");
        assertEquals(List.of("[6]", "type=disk2", "drive1 =  disks/b.woz"), document.lines());
    }

    @Test
    void settingARepeatedKeyKeepsOnlyItsLastLine() {
        IniDocument document = IniDocument.parse(List.of("[6]", "drive1=a.woz", "type=disk2", "drive1=b.woz"));
        document.set("6", "drive1", "c.woz");
        assertEquals(List.of("[6]", "type=disk2", "drive1=c.woz"), document.lines());
    }

    @Test
    void aNewEntryGoesAfterTheSectionsLastEntryAheadOfTrailingComments() {
        IniDocument document = IniDocument.parse(List.of("[6]", "type=disk2", "", "# Slot 7", "[7]", "type=disk2"));
        document.set("6", "drive1", "a.woz");
        document.set("7", "drive2", "b.woz");
        assertEquals(List.of("[6]", "type=disk2", "drive1=a.woz", "", "# Slot 7", "[7]", "type=disk2", "drive2=b.woz"),
            document.lines());
    }

    @Test
    void settingInAMissingSectionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> IniDocument.parse(List.of("[0]")).set("6", "type", "disk2"));
    }

    @Test
    void removingAKeyRemovesEveryLineForIt() {
        IniDocument document = IniDocument.parse(List.of("[6]", "drive1=a", "type=disk2", "drive1=b", "[7]", "drive1=c"));
        document.remove("6", "drive1");
        assertEquals(List.of("[6]", "type=disk2", "[7]", "drive1=c"), document.lines());
    }

    @Test
    void aNewSectionGoesInOrderAheadOfTheNextSectionsLeadingComments() {
        IniDocument document = IniDocument.parse(List.of("[0]", "type=languageCard", "", "# Disks", "[6]", "type=disk2"));
        document.addSection("3", BY_NUMBER);
        document.set("3", "type", "videoterm");
        assertEquals(List.of("[0]", "type=languageCard", "", "[3]", "type=videoterm", "", "# Disks", "[6]", "type=disk2"),
            document.lines());
    }

    @Test
    void aNewLastSectionIsSetApartByABlankLine() {
        IniDocument document = IniDocument.parse(List.of("[0]", "type=languageCard"));
        document.addSection("6", BY_NUMBER);
        assertEquals(List.of("[0]", "type=languageCard", "", "[6]"), document.lines());
    }

    @Test
    void addingASectionThatExistsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> IniDocument.parse(List.of("[6]")).addSection("6", BY_NUMBER));
    }

    @Test
    void removingASectionKeepsTheCommentsLeadingIntoTheNext() {
        IniDocument document = IniDocument.parse(List.of(
            "[0]", "type=languageCard", "", "[3]", "type=videoterm", "; a note on 3", "dummy=1", "", "# Disks", "[6]",
            "type=disk2"));
        document.removeSection("3");
        assertEquals(List.of("[0]", "type=languageCard", "", "# Disks", "[6]", "type=disk2"), document.lines());
    }

    @Test
    void removingAMissingSectionChangesNothing() {
        IniDocument document = IniDocument.parse(List.of("[0]", "type=languageCard"));
        document.removeSection("6");
        assertEquals(List.of("[0]", "type=languageCard"), document.lines());
    }
}
