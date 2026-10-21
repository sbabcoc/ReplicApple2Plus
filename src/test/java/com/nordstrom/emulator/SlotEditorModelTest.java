package com.nordstrom.emulator;

import com.nordstrom.emulator.system.CardCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotEditorModelTest {

    private static final ClassLoader LOADER = SlotEditorModelTest.class.getClassLoader();
    private static final CardCatalog.Catalog CATALOG = CardCatalog.of(LOADER);

    private static SlotEditorModel model(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("slots.ini");
        Files.write(file, List.of(lines));
        return new SlotEditorModel(CATALOG, file);
    }

    private static CardCatalog.CardDescription card(String shortName) {
        return CATALOG.find(shortName).orElseThrow();
    }

    @Test
    void aFileThatWouldntLoadStillOpensWithEachProblemOnItsSlot(@TempDir Path dir) throws IOException {
        SlotEditorModel model = model(dir,
            "[0]", "type=languageCard",
            "[2]", "type=hostfiles", "dir=missing", "speed=fast",
            "[4]", "type=softcard9",
            "[5]", "type=videoterm",
            "[6]");

        assertTrue(model.slot(0).problems().isEmpty());
        assertTrue(model.slot(1).empty());
        SlotEditorModel.SlotState hostfiles = model.slot(2);
        assertEquals(List.of("speed"), hostfiles.undeclared());
        assertEquals(2, hostfiles.problems().size(), hostfiles.problems().toString());
        assertTrue(hostfiles.problems().get(0).contains("no folder at"), hostfiles.problems().toString());
        assertNull(model.slot(4).card());
        assertTrue(model.slot(4).problems().get(0).contains("No card named \"softcard9\""));
        assertTrue(model.slot(5).problems().get(0).contains("can't go in slot 5: only slot 3"));
        assertEquals(List.of("No card type is given."), model.slot(6).problems());
        assertTrue(model.hasProblems());
    }

    @Test
    void linesNoSlotCanShowAreFileProblems(@TempDir Path dir) throws IOException {
        SlotEditorModel model = model(dir, "[0]", "type=languageCard", "junk", "[slot9]", "[06]");
        assertEquals(List.of("Line 3: Malformed line (missing '='): \"junk\"",
                "Section [slot9] is not a slot number from 0 to 7", "Section [06] is not a slot number from 0 to 7"),
            model.fileProblems());
    }

    @Test
    void choosingACardForAnEmptySlotAddsItsSectionInSlotOrder(@TempDir Path dir) throws IOException {
        SlotEditorModel model = model(dir, "# Machine", "[0]", "type=languageCard", "", "# Disks", "[6]", "type=disk2");
        model.setCard(2, card("hostfiles"));
        assertEquals(List.of("# Machine", "[0]", "type=languageCard", "", "[2]", "type=hostfiles", "", "# Disks", "[6]",
            "type=disk2"), model.lines());
        assertTrue(model.dirty());
    }

    @Test
    void replacingACardDropsTheOldCardsSettings(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("files"));
        SlotEditorModel model = model(dir, "[2]", "type=hostfiles", "dir=files");
        model.setCard(2, card("hostfiles"));
        assertFalse(model.dirty(), "the same card again changes nothing");
        model.setCard(2, card("disk2"));
        assertEquals(Map.of(), model.slot(2).entries());
        assertEquals("disk2", model.slot(2).type());
        model.setCard(2, null);
        assertTrue(model.slot(2).empty());
    }

    @Test
    void aBlankValueRemovesTheSetting(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("files"));
        SlotEditorModel model = model(dir, "[2]", "type=hostfiles");
        model.setValue(2, "dir", " files ");
        assertEquals(Map.of("dir", "files"), model.slot(2).entries());
        assertTrue(model.slot(2).problems().isEmpty());
        model.setValue(2, "dir", "");
        assertEquals(Map.of(), model.slot(2).entries());
    }

    @Test
    void aChosenPathIsWrittenRelativeToTheConfigurationsFolderWhenInsideIt(@TempDir Path dir) throws IOException {
        SlotEditorModel model = model(dir, "[6]", "type=disk2");
        assertEquals(Path.of("disks", "a.woz").toString(), model.pathValue(dir.resolve("disks/a.woz")));
        Path elsewhere = dir.getParent().resolve("other.woz").toAbsolutePath().normalize();
        assertEquals(elsewhere.toString(), model.pathValue(elsewhere));
        assertEquals(dir.resolve("disks/a.woz").toAbsolutePath().normalize(), model.resolve("disks/a.woz"));
    }

    @Test
    void savingWritesTheEditsAndKeepsTheRestOfTheFile(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("files"));
        SlotEditorModel model = model(dir, "; my machine", "[0]", "type=languageCard");
        model.setCard(2, card("hostfiles"));
        model.setValue(2, "dir", "files");
        model.save(LOADER);
        assertFalse(model.dirty());
        assertEquals(List.of("; my machine", "[0]", "type=languageCard", "", "[2]", "type=hostfiles", "dir=files"),
            Files.readAllLines(dir.resolve("slots.ini")));
    }

    @Test
    void savingIsRefusedWhileThereAreProblems(@TempDir Path dir) throws IOException {
        SlotEditorModel model = model(dir, "[0]", "type=languageCard");
        model.setCard(4, card("videoterm")); // slot 3 only
        assertThrows(IllegalStateException.class, () -> model.save(LOADER));
        assertEquals(List.of("[0]", "type=languageCard"), Files.readAllLines(dir.resolve("slots.ini")));
    }

    @Test
    void savingOverAChangeMadeElsewhereIsRefused(@TempDir Path dir) throws IOException {
        SlotEditorModel model = model(dir, "[0]", "type=languageCard");
        Path file = dir.resolve("slots.ini");
        Files.write(file, List.of("[0]", "type=saturn128"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
        model.setCard(1, card("languageCard"));
        assertThrows(SlotEditorModel.ChangedOnDiskException.class, () -> model.save(LOADER));
        assertEquals(List.of("[0]", "type=saturn128"), Files.readAllLines(file));
    }

    @Test
    void aFileThatDoesntExistYetStartsEmptyAndSavingCreatesIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("new/slots.ini");
        SlotEditorModel model = new SlotEditorModel(CATALOG, file);
        assertFalse(model.hasProblems());
        model.setCard(0, card("languageCard"));
        model.save(LOADER);
        assertEquals(List.of("[0]", "type=languageCard"), Files.readAllLines(file));
    }

    @Test
    void saveAsRewritesRelativePathsToNameTheSameFiles(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("a/files"));
        Files.createDirectories(dir.resolve("b"));
        Path original = dir.resolve("a/slots.ini");
        Files.write(original, List.of("[2]", "type=hostfiles", "dir=files"));
        SlotEditorModel model = new SlotEditorModel(CATALOG, original);

        model.saveAs(dir.resolve("top.ini"), LOADER); // the files are inside the new folder: still relative
        assertEquals(List.of("[2]", "type=hostfiles", "dir=" + Path.of("a", "files")), Files.readAllLines(model.file()));

        model.saveAs(dir.resolve("b/cpm.ini"), LOADER); // outside it: absolute
        assertEquals(dir.resolve("b/cpm.ini").toAbsolutePath().normalize(), model.file());
        assertEquals(List.of("[2]", "type=hostfiles", "dir=" + dir.resolve("a/files").toAbsolutePath().normalize()),
            Files.readAllLines(model.file()));
        assertEquals(List.of("[2]", "type=hostfiles", "dir=files"), Files.readAllLines(original), "the original is untouched");
    }
}
