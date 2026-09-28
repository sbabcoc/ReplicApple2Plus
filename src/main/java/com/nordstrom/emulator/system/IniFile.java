package com.nordstrom.emulator.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A deliberately minimal INI-format parser. There is no single
 * standardized INI spec across tools, so this implements only the subset
 * this project actually needs: {@code [section]} headers, flat
 * {@code key=value} pairs within a section, and {@code ;} or {@code #}
 * comment lines. No quoting, no multi-line values, no nested sections --
 * if a card's configuration ever needs a second level of structure, that
 * can be expressed the same way the top-level slot grouping is: a key
 * prefix within the section (e.g. {@code drive1.path=}, {@code
 * drive1.readOnly=}), not a new parser feature.
 */
public final class IniFile {

    /**
     * Parses {@code file} into a map of section name to that section's flat properties, in file order.
     *
     * @param file the INI file to read
     * @return each section's name mapped to its properties, in file order
     * @throws IOException if the file can't be read
     * @throws IllegalArgumentException if a line is malformed
     */
    public static Map<String, Properties> parse(Path file) throws IOException {
        return parse(Files.readAllLines(file));
    }

    /**
     * Parses INI-format text that is already in memory -- what lets a
     * configuration ship as a classpath resource, which is not a
     * {@link Path} once it is inside a jar.
     *
     * @param lines the file's lines
     * @return each section's name mapped to its properties, in file order
     * @throws IllegalArgumentException if a line is malformed
     */
    public static Map<String, Properties> parse(List<String> lines) {
        Map<String, Properties> sections = new LinkedHashMap<>();
        Properties current = null;

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String sectionName = line.substring(1, line.length() - 1).strip();
                current = new Properties();
                sections.put(sectionName, current);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0 || current == null) {
                throw new IllegalArgumentException(
                    "Malformed line outside any section (or missing '='): \"" + rawLine + "\"");
            }
            current.setProperty(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return sections;
    }

    private IniFile() {}
}
