package com.nordstrom.emulator.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * An INI file kept line for line, so it can be edited and written back
 * with its comments, blank lines and layout intact -- the configuration
 * files this project reads are meant to stay hand-editable.
 * <p>
 * The format is deliberately minimal, since there is no single INI spec
 * across tools: {@code [section]} headers, flat {@code key=value} entries
 * within a section, and {@code ;} or {@code #} comment lines. No quoting,
 * no multi-line values, no nested sections -- if a configuration ever
 * needs a second level of structure, that can be a key prefix within the
 * section ({@code drive1.path=}), not a new parser feature.
 * <p>
 * Reading never fails on content: a line that isn't any of those, an
 * entry before the first section, or a section header that repeats one
 * above it is kept as written and reported by {@link #problems}, so a
 * broken file can still be opened and repaired. {@link #sections} is the
 * strict view, for loading: it refuses a document with any problem.
 * <p>
 * Editing changes only the lines it must. A changed value keeps its
 * line's key and spacing; a new entry goes after the section's last one;
 * a new section is placed by the caller's ordering. The edit methods act
 * on a section's first header, should a broken file repeat it.
 */
public final class IniDocument {

    /**
     * Something wrong with a line, as {@link #sections} would report it.
     *
     * @param line    the line number, from 1
     * @param text    the line as written
     * @param message what's wrong with it
     */
    public record Problem(int line, String text, String message) {}

    private enum Kind { BLANK, COMMENT, SECTION, ENTRY, MALFORMED }

    /**
     * One line. {@code name} is the section's name for a header, the key for
     * an entry; {@code valueStart} is where an entry's value begins in its
     * text, past the {@code =} and any spaces after it.
     */
    private record Line(Kind kind, String text, String name, int valueStart) {

        String value() {
            return text.substring(valueStart).strip();
        }
    }

    private final List<Line> lines = new ArrayList<>();

    private IniDocument() {}

    /**
     * @param file the INI file to read
     * @return its document
     * @throws IOException if it can't be read
     */
    public static IniDocument read(Path file) throws IOException {
        return parse(Files.readAllLines(file));
    }

    /**
     * Reads INI text already in memory -- what lets a configuration ship as
     * a classpath resource, which is not a {@link Path} once it is inside a
     * jar.
     *
     * @param text the file's lines
     * @return its document
     */
    public static IniDocument parse(List<String> text) {
        IniDocument document = new IniDocument();
        for (String line : text) {
            document.lines.add(classify(line));
        }
        return document;
    }

    private static Line classify(String text) {
        String line = text.strip();
        if (line.isEmpty()) {
            return new Line(Kind.BLANK, text, null, 0);
        }
        if (line.startsWith(";") || line.startsWith("#")) {
            return new Line(Kind.COMMENT, text, null, 0);
        }
        if (line.startsWith("[") && line.endsWith("]")) {
            return new Line(Kind.SECTION, text, line.substring(1, line.length() - 1).strip(), 0);
        }
        int eq = text.indexOf('=');
        if (eq < 0) {
            return new Line(Kind.MALFORMED, text, null, 0);
        }
        int valueStart = eq + 1;
        while (valueStart < text.length() && Character.isWhitespace(text.charAt(valueStart))) {
            valueStart++;
        }
        return new Line(Kind.ENTRY, text, text.substring(0, eq).strip(), valueStart);
    }

    /** @return the document's lines, as they'd be written */
    public List<String> lines() {
        return lines.stream().map(Line::text).toList();
    }

    /**
     * Writes the document to {@code file}, replacing it whole: written
     * beside it first, then moved into place, so a failure partway leaves
     * the original as it was.
     *
     * @param file where to write it
     * @throws IOException if it can't be written
     */
    public void write(Path file) throws IOException {
        Path absolute = file.toAbsolutePath();
        Path temp = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, lines());
            try {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** @return every line {@link #sections} would refuse, in file order */
    public List<Problem> problems() {
        List<Problem> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean inSection = false;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            switch (line.kind()) {
                case SECTION -> {
                    inSection = true;
                    if (!seen.add(line.name())) {
                        problems.add(new Problem(i + 1, line.text(), "Section [" + line.name() + "] appears more than once"));
                    }
                }
                case ENTRY -> {
                    if (!inSection) {
                        problems.add(new Problem(i + 1, line.text(), "Entry outside any section: \"" + line.text() + "\""));
                    }
                }
                case MALFORMED -> problems.add(new Problem(i + 1, line.text(),
                    "Malformed line (missing '='): \"" + line.text() + "\""));
                default -> { }
            }
        }
        return problems;
    }

    /**
     * The strict view, for loading: each section's entries, in file order.
     * Within a section a repeated key's last value wins.
     *
     * @return each section's name mapped to its properties
     * @throws IllegalArgumentException if the document has any {@link #problems problem}
     */
    public Map<String, Properties> sections() {
        List<Problem> problems = problems();
        if (!problems.isEmpty()) {
            Problem first = problems.get(0);
            throw new IllegalArgumentException("Line " + first.line() + ": " + first.message());
        }
        Map<String, Properties> sections = new LinkedHashMap<>();
        Properties current = null;
        for (Line line : lines) {
            if (line.kind() == Kind.SECTION) {
                current = new Properties();
                sections.put(line.name(), current);
            } else if (line.kind() == Kind.ENTRY) {
                current.setProperty(line.name(), line.value());
            }
        }
        return sections;
    }

    /** @return each section's name, in file order, a repeated one only once */
    public List<String> sectionNames() {
        List<String> names = new ArrayList<>();
        for (Line line : lines) {
            if (line.kind() == Kind.SECTION && !names.contains(line.name())) {
                names.add(line.name());
            }
        }
        return names;
    }

    /**
     * A section's entries, read leniently: from its first header to the
     * next header, a repeated key's last value winning.
     *
     * @param section the section's name
     * @return its keys mapped to their values, in file order; empty if there's no such section
     */
    public Map<String, String> entries(String section) {
        Map<String, String> entries = new LinkedHashMap<>();
        int header = header(section);
        if (header >= 0) {
            for (int i = header + 1; i < end(header); i++) {
                Line line = lines.get(i);
                if (line.kind() == Kind.ENTRY) {
                    entries.remove(line.name()); // so a repeated key takes its last position, as well as value
                    entries.put(line.name(), line.value());
                }
            }
        }
        return entries;
    }

    /**
     * @param section the section's name
     * @param key     the key
     * @return its value, or empty if the section has no such entry
     */
    public Optional<String> get(String section, String key) {
        return Optional.ofNullable(entries(section).get(key));
    }

    /**
     * Sets an entry. An existing one keeps its line, key and spacing, with
     * only its value replaced -- and any earlier repeat of the key in the
     * section is removed. A new one goes after the section's last entry, or
     * right after its header if it has none.
     *
     * @param section the section's name; it must exist
     * @param key     the key
     * @param value   the value
     * @throws IllegalArgumentException if there's no such section
     */
    public void set(String section, String key, String value) {
        int header = requireHeader(section);
        int last = -1;
        int lastEntry = header;
        for (int i = header + 1; i < end(header); i++) {
            Line line = lines.get(i);
            if (line.kind() == Kind.ENTRY) {
                lastEntry = i;
                if (line.name().equals(key)) {
                    last = i;
                }
            }
        }
        if (last < 0) {
            lines.add(lastEntry + 1, classify(key + "=" + value));
            return;
        }
        Line line = lines.get(last);
        lines.set(last, classify(line.text().substring(0, line.valueStart()) + value));
        for (int i = last - 1; i > header; i--) {
            if (lines.get(i).kind() == Kind.ENTRY && lines.get(i).name().equals(key)) {
                lines.remove(i);
            }
        }
    }

    /**
     * Removes every entry for a key from a section; nothing if there's none.
     *
     * @param section the section's name
     * @param key     the key
     */
    public void remove(String section, String key) {
        int header = header(section);
        if (header < 0) {
            return;
        }
        for (int i = end(header) - 1; i > header; i--) {
            if (lines.get(i).kind() == Kind.ENTRY && lines.get(i).name().equals(key)) {
                lines.remove(i);
            }
        }
    }

    /**
     * Adds an empty section, before the first existing section that
     * {@code order} puts after it -- and before that section's own leading
     * comments, which belong to it -- or at the end. It's set apart from
     * what's above it by a blank line.
     *
     * @param section the new section's name
     * @param order   how sections are ordered, such as by slot number
     * @throws IllegalArgumentException if the section already exists
     */
    public void addSection(String section, Comparator<String> order) {
        if (header(section) >= 0) {
            throw new IllegalArgumentException("Section [" + section + "] already exists");
        }
        int at = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.kind() == Kind.SECTION && order.compare(line.name(), section) > 0) {
                at = i;
                while (at > 0 && lines.get(at - 1).kind() == Kind.COMMENT) {
                    at--;
                }
                break;
            }
        }
        List<Line> added = new ArrayList<>();
        if (at > 0 && lines.get(at - 1).kind() != Kind.BLANK) {
            added.add(classify(""));
        }
        added.add(classify("[" + section + "]"));
        if (at < lines.size() && lines.get(at).kind() != Kind.BLANK) {
            added.add(classify(""));
        }
        lines.addAll(at, added);
    }

    /**
     * Removes a section: its header, its entries, and the comments and blank
     * lines among them. The comments and blank lines after its last entry
     * stay, since they lead into the next section.
     *
     * @param section the section's name; nothing happens if there's none
     */
    public void removeSection(String section) {
        int header = header(section);
        if (header < 0) {
            return;
        }
        int end = end(header);
        int keepFrom = end;
        while (keepFrom > header + 1 && lines.get(keepFrom - 1).kind() != Kind.ENTRY
                && lines.get(keepFrom - 1).kind() != Kind.MALFORMED) {
            keepFrom--;
        }
        lines.subList(header, keepFrom).clear();
        if (header > 0 && header < lines.size()
                && lines.get(header - 1).kind() == Kind.BLANK && lines.get(header).kind() == Kind.BLANK) {
            lines.remove(header); // don't leave a double gap where the section was
        }
    }

    /** @return the index of a section's first header, or -1 */
    private int header(String section) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).kind() == Kind.SECTION && lines.get(i).name().equals(section)) {
                return i;
            }
        }
        return -1;
    }

    private int requireHeader(String section) {
        int header = header(section);
        if (header < 0) {
            throw new IllegalArgumentException("No section [" + section + "]");
        }
        return header;
    }

    /** @return the index just past a section: the next header, or the end */
    private int end(int header) {
        for (int i = header + 1; i < lines.size(); i++) {
            if (lines.get(i).kind() == Kind.SECTION) {
                return i;
            }
        }
        return lines.size();
    }
}
