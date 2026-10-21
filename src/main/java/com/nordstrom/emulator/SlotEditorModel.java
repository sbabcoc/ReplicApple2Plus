package com.nordstrom.emulator;

import com.nordstrom.emulator.system.CardCatalog;
import com.nordstrom.emulator.system.CardParameter;
import com.nordstrom.emulator.system.IniDocument;
import com.nordstrom.emulator.system.SlotCardLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * What the slot editor edits: one slot configuration file, read without
 * building any cards, so a file that wouldn't load -- an unknown card type,
 * a card in a slot it can't use, a setting the card doesn't have -- still
 * opens, with each problem reported on the slot it belongs to.
 * <p>
 * The file is read and written through {@link IniDocument}, so comments and
 * layout survive editing. Saving is refused while any problem remains, and
 * then the result is loaded for real ({@link SlotCardLoader}) before it
 * replaces the file -- that load is the final word, so anything the checks
 * here miss still can't be saved. Saving is also refused if the file has
 * changed on disk since it was read, rather than overwriting that change.
 * <p>
 * Not thread-safe: used from the event thread only.
 */
final class SlotEditorModel {

    /** The number of slots, 0-7. */
    static final int SLOTS = 8;

    /** Orders sections by slot number; anything else after, by name. */
    private static final Comparator<String> BY_SLOT = Comparator
        .comparing((String name) -> slotNumber(name) < 0)
        .thenComparingInt(SlotEditorModel::slotNumber)
        .thenComparing(Comparator.naturalOrder());

    private static final String TYPE = "type";

    /**
     * One slot as the file has it.
     *
     * @param slot       its number
     * @param present    whether the file has a section for it
     * @param type       its {@code type} value, or null if it has none
     * @param card       the card that names, or null if none does
     * @param entries    its settings as written, {@code type} excluded
     * @param undeclared the keys among {@code entries} that {@code card} doesn't declare
     * @param problems   everything that would stop it loading
     */
    record SlotState(int slot, boolean present, String type, CardCatalog.CardDescription card,
                     Map<String, String> entries, List<String> undeclared, List<String> problems) {

        /** @return true if the slot is empty in the file */
        boolean empty() {
            return !present;
        }
    }

    /** Thrown by saving when the file changed on disk after it was read. */
    static final class ChangedOnDiskException extends IOException {
        private static final long serialVersionUID = 1L;

        ChangedOnDiskException(Path file) {
            super(file + " has changed since it was opened");
        }
    }

    private final CardCatalog.Catalog catalog;
    private Path file;
    private IniDocument document;
    private FileTime loadedTime;
    private boolean dirty;

    /**
     * @param catalog the cards available
     * @param file    the configuration to edit, or null for a new one not yet saved anywhere;
     *                a file that doesn't exist yet starts empty, and is created by saving
     * @throws IOException if the file exists but can't be read
     */
    SlotEditorModel(CardCatalog.Catalog catalog, Path file) throws IOException {
        this.catalog = catalog;
        load(file);
    }

    /**
     * Reads a configuration, discarding any unsaved edits.
     *
     * @param file the configuration to edit, as for the constructor
     * @throws IOException if the file exists but can't be read
     */
    void load(Path file) throws IOException {
        Path absolute = file != null ? file.toAbsolutePath().normalize() : null;
        if (absolute != null && Files.exists(absolute)) {
            document = IniDocument.read(absolute);
            loadedTime = Files.getLastModifiedTime(absolute);
        } else {
            document = IniDocument.parse(List.of());
            loadedTime = null;
        }
        this.file = absolute;
        dirty = false;
    }

    /** @return the file being edited, absolute, or null if it hasn't been saved anywhere */
    Path file() {
        return file;
    }

    /** @return true if there are edits not yet saved */
    boolean dirty() {
        return dirty;
    }

    /** @return the cards available */
    CardCatalog.Catalog catalog() {
        return catalog;
    }

    /** @return the document's lines, as saving would write them */
    List<String> lines() {
        return document.lines();
    }

    /**
     * Problems no slot can show: lines that aren't INI, and sections that
     * aren't slot numbers. Only editing the file as text can fix these.
     *
     * @return each problem, with its line number where it has one
     */
    List<String> fileProblems() {
        List<String> problems = new ArrayList<>();
        for (IniDocument.Problem problem : document.problems()) {
            problems.add("Line " + problem.line() + ": " + problem.message());
        }
        for (String section : document.sectionNames()) {
            if (slotNumber(section) < 0) {
                problems.add("Section [" + section + "] is not a slot number from 0 to 7");
            }
        }
        return problems;
    }

    /**
     * @param slot the slot number
     * @return the slot as the file has it, with its problems
     */
    SlotState slot(int slot) {
        String section = Integer.toString(slot);
        boolean present = document.sectionNames().contains(section);
        if (!present) {
            return new SlotState(slot, false, null, null, Map.of(), List.of(), List.of());
        }
        Map<String, String> entries = new java.util.LinkedHashMap<>(document.entries(section));
        String type = entries.remove(TYPE);
        List<String> problems = new ArrayList<>();
        List<String> undeclared = new ArrayList<>();
        CardCatalog.CardDescription card = null;
        if (type == null || type.isEmpty()) {
            problems.add("No card type is given.");
            type = null;
        } else {
            card = catalog.find(type).orElse(null);
            if (card == null) {
                String conflict = catalog.conflicts().get(type);
                problems.add(conflict != null ? conflict + "."
                    : "No card named \"" + type + "\" is available" + (catalog.problems().isEmpty()
                        ? "." : " -- a plug-in that couldn't be loaded may provide it."));
            }
        }
        if (card != null) {
            if (card.upperMemory()) {
                for (int other = 0; other < slot; other++) {
                    SlotState earlier = slot(other);
                    if (earlier.card() != null && earlier.card().upperMemory()) {
                        problems.add("Only one card can take over $D000-$FFFF, and the " + earlier.card().displayName()
                            + " in slot " + other + " already does.");
                        break;
                    }
                }
            }
            if (!card.supportedSlots().contains(slot)) {
                problems.add(card.displayName() + " can't go in slot " + slot + ": only slot"
                    + (card.supportedSlots().size() == 1 ? " " : "s ")
                    + new TreeSet<>(card.supportedSlots()).toString().replaceAll("[\\[\\]]", "") + ".");
            }
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                Optional<CardParameter> parameter = card.parameter(entry.getKey());
                if (parameter.isEmpty()) {
                    undeclared.add(entry.getKey());
                    problems.add("\"" + entry.getKey() + "\" is not a setting of " + card.displayName() + ".");
                } else {
                    valueProblem(parameter.get(), entry.getValue()).ifPresent(problems::add);
                }
            }
        }
        return new SlotState(slot, true, type, card, entries, undeclared, problems);
    }

    /** What's wrong with a setting's value: out of range, or a path to nothing. */
    private Optional<String> valueProblem(CardParameter parameter, String value) {
        Optional<String> problem = parameter.problem(value);
        if (problem.isPresent()) {
            return problem.map(message -> message + ".");
        }
        if (parameter.kind() == CardParameter.Kind.FILE && !Files.isRegularFile(resolve(value))) {
            return Optional.of(parameter.label() + ": there's no file at " + resolve(value) + ".");
        }
        if (parameter.kind() == CardParameter.Kind.FOLDER && !Files.isDirectory(resolve(value))) {
            return Optional.of(parameter.label() + ": there's no folder at " + resolve(value) + ".");
        }
        return Optional.empty();
    }

    /** @return true if anything at all would stop the file loading */
    boolean hasProblems() {
        if (!fileProblems().isEmpty()) {
            return true;
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!slot(slot).problems().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts a card in a slot, or empties it. A different card starts with no
     * settings, since the last card's mean nothing to it; the same card again
     * changes nothing.
     *
     * @param slot the slot number
     * @param card the card, or null to empty the slot
     */
    void setCard(int slot, CardCatalog.CardDescription card) {
        String section = Integer.toString(slot);
        SlotState state = slot(slot);
        if (card == null) {
            if (state.present()) {
                document.removeSection(section);
                dirty = true;
            }
            return;
        }
        if (state.present() && card.shortName().equals(state.type())) {
            return;
        }
        if (!state.present()) {
            document.addSection(section, BY_SLOT);
        }
        document.set(section, TYPE, card.shortName());
        for (String key : state.entries().keySet()) {
            document.remove(section, key);
        }
        dirty = true;
    }

    /**
     * Sets one of a slot's settings; a blank value removes it, so the
     * card's default applies.
     *
     * @param slot  the slot number; it must have a card
     * @param key   the setting
     * @param value its value
     */
    void setValue(int slot, String key, String value) {
        String section = Integer.toString(slot);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            if (document.get(section, key).isPresent()) {
                document.remove(section, key);
                dirty = true;
            }
        } else if (!trimmed.equals(document.get(section, key).orElse(null))) {
            document.set(section, key, trimmed);
            dirty = true;
        }
    }

    /**
     * Resolves a path setting as loading does: against the configuration
     * file's folder, unless it's absolute.
     *
     * @param value the setting's value
     * @return the path it names
     */
    Path resolve(String value) {
        Path path = Path.of(value.trim());
        return path.isAbsolute() || file == null ? path : file.getParent().resolve(path).normalize();
    }

    /**
     * How to write a chosen path into the configuration: relative to the
     * configuration file's folder when it's inside it, so the two can be
     * moved together, else absolute.
     *
     * @param chosen the path chosen
     * @return the setting's value
     */
    String pathValue(Path chosen) {
        return pathValue(chosen, file != null ? file.getParent() : null);
    }

    private static String pathValue(Path chosen, Path folder) {
        Path absolute = chosen.toAbsolutePath().normalize();
        if (folder != null && absolute.startsWith(folder)) {
            return folder.relativize(absolute).toString();
        }
        return absolute.toString();
    }

    /** @return true if the file has been created, changed or removed since it was read */
    boolean changedOnDisk() throws IOException {
        if (file == null) {
            return false;
        }
        FileTime now = Files.exists(file) ? Files.getLastModifiedTime(file) : null;
        return !Objects.equals(now, loadedTime);
    }

    /**
     * Saves to the file being edited.
     *
     * @param classLoader where the cards come from, for the final check
     * @throws IllegalStateException if there's no file yet, or there are problems
     * @throws ChangedOnDiskException if the file changed since it was read
     * @throws IOException if it can't be written
     * @throws RuntimeException if the final check -- loading it for real -- fails
     * @throws LinkageError if that check fails in a card's own class (a ROM it can't load, say)
     */
    void save(ClassLoader classLoader) throws IOException {
        if (file == null) {
            throw new IllegalStateException("There's no file to save to yet");
        }
        if (changedOnDisk()) {
            throw new ChangedOnDiskException(file);
        }
        write(classLoader);
    }

    /**
     * Saves to another file, which becomes the one being edited. Relative
     * paths are rewritten so they still name the same files from the new
     * file's folder.
     *
     * @param target      where to save
     * @param classLoader where the cards come from, for the final check
     * @throws IOException if it can't be written
     * @throws RuntimeException if the final check fails
     * @throws LinkageError if that check fails in a card's own class
     */
    void saveAs(Path target, ClassLoader classLoader) throws IOException {
        Path newFile = target.toAbsolutePath().normalize();
        IniDocument before = IniDocument.parse(document.lines());
        Path oldFile = file;
        rebase(newFile.getParent());
        file = newFile;
        try {
            write(classLoader);
        } catch (IOException | RuntimeException | LinkageError e) {
            document = before;
            file = oldFile;
            throw e;
        }
    }

    /** Rewrites relative path settings to name the same paths from {@code folder}. */
    private void rebase(Path folder) {
        for (int slot = 0; slot < SLOTS; slot++) {
            SlotState state = slot(slot);
            if (state.card() == null) {
                continue;
            }
            for (Map.Entry<String, String> entry : state.entries().entrySet()) {
                Optional<CardParameter> parameter = state.card().parameter(entry.getKey());
                boolean isPath = parameter.isPresent() && (parameter.get().kind() == CardParameter.Kind.FILE
                    || parameter.get().kind() == CardParameter.Kind.FOLDER);
                if (isPath && !Path.of(entry.getValue()).isAbsolute()) {
                    document.set(Integer.toString(slot), entry.getKey(), pathValue(resolve(entry.getValue()), folder));
                }
            }
        }
    }

    /** Checks, loads for real from a copy beside the file, then replaces the file. */
    private void write(ClassLoader classLoader) throws IOException {
        if (hasProblems()) {
            throw new IllegalStateException("The configuration still has problems");
        }
        Files.createDirectories(file.getParent());
        // Beside the file, so relative paths resolve exactly as they will from the file itself.
        Path check = Files.createTempFile(file.getParent(), ".slots-check", ".ini");
        try {
            Files.write(check, document.lines());
            SlotCardLoader.load(check, classLoader); // the cards made only to check it are discarded
        } finally {
            Files.deleteIfExists(check);
        }
        document.write(file);
        loadedTime = Files.getLastModifiedTime(file);
        dirty = false;
    }

    /**
     * @return the slot number a section names -- written plainly, as the
     *         editor writes it -- or -1 if it isn't one from 0 to 7
     */
    private static int slotNumber(String section) {
        return section.length() == 1 && section.charAt(0) >= '0' && section.charAt(0) < '0' + SLOTS
            ? section.charAt(0) - '0' : -1;
    }
}
