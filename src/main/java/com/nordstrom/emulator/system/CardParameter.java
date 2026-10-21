package com.nordstrom.emulator.system;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One setting a {@link SlotCard} accepts in its slot configuration
 * section, described well enough for a host UI to offer the right control
 * for it -- a file chooser, a folder chooser, a number field -- and to
 * check a value before a configuration is saved. {@link CardTypes} applies
 * the same check ({@link #problem}) when a configuration is loaded, so a
 * value the UI accepts is one the loader accepts.
 * <p>
 * Made with the factory method for its kind. A setting is always
 * optional: omitting it means {@link #defaultValue()}, or, for a setting
 * with no default, that the card does without it (no disk in that drive,
 * say).
 *
 * @param name         the configuration key
 * @param label        what a host UI calls it
 * @param kind         what sort of value it takes
 * @param defaultValue the value omitting it means, or null if omitting it means "none"
 * @param extensions   the file name extensions offered (without the dot), for {@link Kind#FILE}; empty otherwise
 * @param min          the least allowed value, for {@link Kind#INTEGER}
 * @param max          the greatest allowed value, for {@link Kind#INTEGER}
 */
public record CardParameter(String name, String label, Kind kind, String defaultValue,
                            List<String> extensions, long min, long max) {

    /** What sort of value a setting takes. */
    public enum Kind {
        /** A path to a file, relative to the configuration file's folder or absolute. */
        FILE,
        /** A path to a folder, relative to the configuration file's folder or absolute. */
        FOLDER,
        /** A whole number within a range. */
        INTEGER
    }

    /**
     * Requires a name, label and kind, and takes an unmodifiable copy of the extensions.
     *
     * @param name         the configuration key
     * @param label        what a host UI calls it
     * @param kind         what sort of value it takes
     * @param defaultValue the value omitting it means, or null if omitting it means "none"
     * @param extensions   the file name extensions offered (without the dot), for {@link Kind#FILE}; empty otherwise
     * @param min          the least allowed value, for {@link Kind#INTEGER}
     * @param max          the greatest allowed value, for {@link Kind#INTEGER}
     */
    public CardParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        extensions = List.copyOf(extensions);
    }

    /**
     * @param name       the configuration key
     * @param label      what a host UI calls it
     * @param extensions the file name extensions to offer, without the dot
     * @return a setting naming a file
     */
    public static CardParameter file(String name, String label, String... extensions) {
        return new CardParameter(name, label, Kind.FILE, null, List.of(extensions), 0, 0);
    }

    /**
     * @param name  the configuration key
     * @param label what a host UI calls it
     * @return a setting naming a folder
     */
    public static CardParameter folder(String name, String label) {
        return new CardParameter(name, label, Kind.FOLDER, null, List.of(), 0, 0);
    }

    /**
     * @param name         the configuration key
     * @param label        what a host UI calls it
     * @param defaultValue the value omitting it means; must be within range
     * @param min          the least allowed value
     * @param max          the greatest allowed value
     * @return a setting taking a whole number from {@code min} to {@code max}
     */
    public static CardParameter integer(String name, String label, long defaultValue, long min, long max) {
        if (min > max || defaultValue < min || defaultValue > max) {
            throw new IllegalArgumentException(name + ": default " + defaultValue + " isn't within " + min + "-" + max);
        }
        return new CardParameter(name, label, Kind.INTEGER, Long.toString(defaultValue), List.of(), min, max);
    }

    /**
     * Checks a value against this setting's own constraints: an integer
     * must be a whole number within range. Whether a file or folder exists
     * is left to the card, which resolves the path against the
     * configuration file's folder.
     *
     * @param value the value as written in the configuration
     * @return what's wrong with it, or empty if nothing is
     */
    public Optional<String> problem(String value) {
        if (kind != Kind.INTEGER) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        try {
            long number = Long.parseLong(trimmed);
            if (number >= min && number <= max) {
                return Optional.empty();
            }
        } catch (NumberFormatException e) {
            // reported below, the same as out of range
        }
        return Optional.of(label + " must be a whole number from " + min + " to " + max + ", not \"" + trimmed + "\"");
    }
}
