package com.nordstrom.emulator.input;

import com.nordstrom.emulator.system.IniFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How pad inputs map onto the Apple II game connector: which stick axis
 * or D-pad buttons drive each of the four paddles, and which pad buttons
 * press each of the three pushbuttons. Immutable, and identical on every
 * operating system -- this class knows nothing about how a pad is read.
 * <p>
 * The built-in defaults are the classpath resource
 * {@code default-input.ini}, which is also the documented starting point
 * for a custom file: the file the user actually supplies is layered over
 * it key by key, so a custom file only has to say what differs. An empty
 * value clears a binding rather than keeping the default.
 * <p>
 * <b>Strict on purpose.</b> A misspelled key or an unknown name is an
 * error that names the problem and lists what would have been valid,
 * never a silent no-op. A mapping file that quietly ignores a typo looks
 * exactly like a controller that is not working, which is the worst
 * possible failure to debug.
 */
public final class InputMapping {

    /** How many paddle channels the Apple II game connector has. */
    public static final int PADDLES = 4;

    /** How many pushbuttons the Apple II game connector has. */
    public static final int BUTTONS = 3;

    private static final String DEFAULTS_RESOURCE = "default-input.ini";

    private static final String SECTION_INPUT = "input";
    private static final List<String> INPUT_KEYS = List.of("deadzone");
    private static final List<String> PADDLE_KEYS = List.of("axis", "invert", "dpad");
    private static final List<String> BUTTON_KEYS = List.of("pad");

    /**
     * What drives one paddle.
     *
     * @param axis the stick axis, or null if no stick drives this paddle
     * @param invert whether the stick axis is flipped
     * @param dpadLow the button that pushes the paddle to 0, or null
     * @param dpadHigh the button that pushes the paddle to 255, or null
     */
    public record PaddleBinding(PadAxis axis, boolean invert, PadButton dpadLow, PadButton dpadHigh) { }

    private final float deadZone;
    private final List<PaddleBinding> paddles;
    private final List<Set<PadButton>> buttons;

    private InputMapping(float deadZone, List<PaddleBinding> paddles, List<Set<PadButton>> buttons) {
        this.deadZone = deadZone;
        this.paddles = paddles;
        this.buttons = buttons;
    }

    /**
     * How far a stick must move before it registers.
     *
     * @return the dead zone, from 0.0 up to (not including) 1.0
     */
    public float deadZone() {
        return deadZone;
    }

    /**
     * What drives a paddle.
     *
     * @param channel 0-3
     * @return that paddle's binding
     */
    public PaddleBinding paddle(int channel) {
        return paddles.get(channel);
    }

    /**
     * Which pad buttons press an Apple II pushbutton.
     *
     * @param button 0-2
     * @return the pad buttons, any one of which presses it (possibly none)
     */
    public Set<PadButton> button(int button) {
        return buttons.get(button);
    }

    /**
     * The built-in mapping.
     *
     * @return the mapping described by {@code default-input.ini}
     */
    public static InputMapping defaults() {
        return build(loadDefaults());
    }

    /**
     * Reads a custom mapping file, layered over the defaults.
     *
     * @param file the INI file
     * @return the defaults with the file's settings applied
     * @throws IOException if the file can't be read
     * @throws IllegalArgumentException if the file is malformed or names something unknown
     */
    public static InputMapping load(Path file) throws IOException {
        return fromSections(IniFile.parse(file));
    }

    /**
     * Applies parsed INI sections over the defaults.
     *
     * @param custom the sections of a user's file
     * @return the defaults with those settings applied
     * @throws IllegalArgumentException if anything is unknown or invalid
     */
    static InputMapping fromSections(Map<String, Properties> custom) {
        validateNames(custom);
        Map<String, Properties> merged = new LinkedHashMap<>();
        loadDefaults().forEach((name, props) -> merged.put(name, copy(props)));
        custom.forEach((name, props) -> {
            Properties target = merged.computeIfAbsent(name, n -> new Properties());
            props.stringPropertyNames().forEach(key -> target.setProperty(key, props.getProperty(key)));
        });
        return build(merged);
    }

    private static Map<String, Properties> loadDefaults() {
        try (InputStream in = InputMapping.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the classpath -- this is a packaging bug");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return IniFile.parse(reader.lines().collect(Collectors.toList()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + DEFAULTS_RESOURCE, e);
        }
    }

    private static Properties copy(Properties source) {
        Properties copy = new Properties();
        source.stringPropertyNames().forEach(key -> copy.setProperty(key, source.getProperty(key)));
        return copy;
    }

    /** Rejects unknown sections and keys, naming what would have been valid. */
    private static void validateNames(Map<String, Properties> sections) {
        for (Map.Entry<String, Properties> entry : sections.entrySet()) {
            String section = entry.getKey();
            List<String> validKeys = validKeysFor(section);
            if (validKeys == null) {
                throw new IllegalArgumentException("Unknown section [" + section + "] in the input configuration. "
                    + "Valid sections: " + String.join(", ", validSections()));
            }
            for (String key : entry.getValue().stringPropertyNames()) {
                if (!validKeys.contains(key)) {
                    throw new IllegalArgumentException("Unknown key \"" + key + "\" in [" + section + "]. "
                        + "Valid keys there: " + String.join(", ", validKeys));
                }
            }
        }
    }

    private static List<String> validKeysFor(String section) {
        if (section.equals(SECTION_INPUT)) {
            return INPUT_KEYS;
        }
        for (int i = 0; i < PADDLES; i++) {
            if (section.equals("paddle" + i)) {
                return PADDLE_KEYS;
            }
        }
        for (int i = 0; i < BUTTONS; i++) {
            if (section.equals("button" + i)) {
                return BUTTON_KEYS;
            }
        }
        return null;
    }

    private static List<String> validSections() {
        List<String> names = new ArrayList<>();
        names.add(SECTION_INPUT);
        for (int i = 0; i < PADDLES; i++) {
            names.add("paddle" + i);
        }
        for (int i = 0; i < BUTTONS; i++) {
            names.add("button" + i);
        }
        return names;
    }

    private static InputMapping build(Map<String, Properties> sections) {
        float deadZone = parseDeadZone(sections.getOrDefault(SECTION_INPUT, new Properties()));

        List<PaddleBinding> paddles = new ArrayList<>();
        for (int i = 0; i < PADDLES; i++) {
            paddles.add(parsePaddle("paddle" + i, sections.getOrDefault("paddle" + i, new Properties())));
        }

        List<Set<PadButton>> buttons = new ArrayList<>();
        for (int i = 0; i < BUTTONS; i++) {
            Properties props = sections.getOrDefault("button" + i, new Properties());
            buttons.add(Collections.unmodifiableSet(
                parseButtonList("button" + i, "pad", props.getProperty("pad", ""))));
        }
        return new InputMapping(deadZone, List.copyOf(paddles), List.copyOf(buttons));
    }

    private static float parseDeadZone(Properties props) {
        String raw = props.getProperty("deadzone", "0.15").strip();
        float value;
        try {
            value = Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("[input] deadzone must be a number, got \"" + raw + "\"");
        }
        if (!(value >= 0f && value < 1f)) {
            throw new IllegalArgumentException("[input] deadzone must be at least 0.0 and less than 1.0, got " + raw);
        }
        return value;
    }

    private static PaddleBinding parsePaddle(String section, Properties props) {
        String axisRaw = props.getProperty("axis", "").strip();
        PadAxis axis = axisRaw.isEmpty() ? null : parseName(PadAxis.class, section, "axis", axisRaw);

        String invertRaw = props.getProperty("invert", "false").strip().toLowerCase(Locale.ROOT);
        if (!invertRaw.equals("true") && !invertRaw.equals("false")) {
            throw new IllegalArgumentException("[" + section + "] invert must be true or false, got \"" + invertRaw + "\"");
        }

        String dpadRaw = props.getProperty("dpad", "").strip();
        PadButton low = null;
        PadButton high = null;
        if (!dpadRaw.isEmpty()) {
            String[] parts = dpadRaw.split(",");
            if (parts.length != 2) {
                throw new IllegalArgumentException("[" + section + "] dpad needs exactly two buttons, \"low, high\" "
                    + "(the one that pushes the paddle to 0, then the one that pushes it to 255), got \"" + dpadRaw + "\"");
            }
            low = parseName(PadButton.class, section, "dpad", parts[0].strip());
            high = parseName(PadButton.class, section, "dpad", parts[1].strip());
        }
        return new PaddleBinding(axis, invertRaw.equals("true"), low, high);
    }

    private static Set<PadButton> parseButtonList(String section, String key, String raw) {
        Set<PadButton> result = EnumSet.noneOf(PadButton.class);
        if (raw.strip().isEmpty()) {
            return result;
        }
        for (String part : raw.split(",")) {
            result.add(parseName(PadButton.class, section, key, part.strip()));
        }
        return result;
    }

    private static <E extends Enum<E>> E parseName(Class<E> type, String section, String key, String raw) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String valid = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
            throw new IllegalArgumentException("[" + section + "] " + key + ": \"" + raw + "\" is not a valid "
                + type.getSimpleName().replace("Pad", "").toLowerCase(Locale.ROOT)
                + " name. Valid names: " + valid);
        }
    }
}
