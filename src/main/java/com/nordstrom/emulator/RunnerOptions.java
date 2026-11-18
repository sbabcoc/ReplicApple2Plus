package com.nordstrom.emulator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The command line of {@link HeadlessRunner}. Unlike the application's
 * {@code CliArgs}, flags here can repeat and their order matters: each
 * {@code --type}, {@code --expect}, {@code --wait} and {@code --snap} is a
 * step of the run's script, taken in the order given.
 * <p>
 * Text given to {@code --type} may carry escapes, since a shell can't
 * easily pass a Return or a control key: {@code \r} or {@code \n} is
 * Return, {@code \\} a backslash, and {@code \xHH} the key whose Apple II
 * code is {@code HH} (for keys text can't express, such as {@code \x03}
 * for Ctrl-C).
 */
final class RunnerOptions {

    static final String USAGE = String.join(System.lineSeparator(),
        "Usage: replicapple2plus-run [--d1 BOOT.dsk|.woz] [--d2 DISK.dsk|.woz]",
        "           [--folder DIR] [--config slots.ini] [--plugins DIR[:DIR...]] [--roms DIR]",
        "           [--videoterm] [--language-card] [--real-time] [--prompt TEXT]",
        "           [--type TEXT | --expect TEXT | --wait SECONDS | --snap FILE]... --until TEXT",
        "           [--timeout SECONDS] [--boot-timeout SECONDS] [--screen FILE]",
        "           [--monitor color|white|green|amber]",
        "",
        "Boots drive 1 to the --prompt (default ]), then follows the script: --type",
        "types TEXT (\\r or \\n is Return, \\\\ a backslash, \\xHH the key with Apple II",
        "code HH); --expect waits until TEXT is on screen; --wait runs on for SECONDS,",
        "as a person pauses before typing; --snap saves a PNG of the screen as the",
        "main window shows it, on the --monitor (default color). The run finishes when",
        "the --until TEXT is on screen, and the screen's text goes to --screen FILE,",
        "or stdout.",
        "",
        "The machine is a Disk II in slot 6, plus the VideoTerm (--videoterm) and a",
        "Language Card (--language-card) if asked -- or the cards a slot configuration",
        "names: --config, else the slots.ini of the emulator folder --folder gives.",
        "Plug-in jars come from --plugins, else the emulator folder's plugins/; ROMs",
        "from --roms, else its roms/. --d1 and --d2 go in the drives of the Disk II",
        "the machine boots, in place of any the configuration names.",
        "",
        "Times are seconds of emulated time: --boot-timeout (default 60) to reach the",
        "prompt, --timeout (default 120) for the script and --until after it. The run",
        "is unthrottled unless --real-time paces it at the II+'s 1.023 MHz.",
        "",
        "Exit status: 0 finished; 1 bad arguments or setup; 2 didn't boot;",
        "3 timed out; 4 the machine stopped (the CPU halted).");

    static final double DEFAULT_TIMEOUT = 120;
    static final double DEFAULT_BOOT_TIMEOUT = 60;
    static final String DEFAULT_PROMPT = "]";

    /** One step of the script. */
    sealed interface Step permits Type, Expect, Wait, Snap {}

    /** Types keys: text segments and raw key codes, in order. */
    record Type(List<Object> segments) implements Step {}

    /** Waits until the text is on screen. */
    record Expect(String text) implements Step {}

    /** Runs on for a time before the next step. */
    record Wait(double seconds) implements Step {}

    /** Saves a picture of the screen, as the main window shows it, to a PNG file. */
    record Snap(Path file) implements Step {}

    Path roms;
    Path folder;
    Path config;
    String plugins;
    boolean videoTerm;
    boolean languageCard;
    Path drive1;
    Path drive2;
    final List<Step> steps = new ArrayList<>();
    String until;
    String prompt = DEFAULT_PROMPT;
    boolean realTime;
    double timeout = DEFAULT_TIMEOUT;
    double bootTimeout = DEFAULT_BOOT_TIMEOUT;
    Path screen;
    MonitorType monitor = MonitorType.COLOR;
    boolean help;

    /**
     * @param args the command line
     * @return the options it gives
     * @throws IllegalArgumentException with a message for the user, if it's not a valid command line
     */
    static RunnerOptions parse(String[] args) {
        RunnerOptions options = new RunnerOptions();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--help", "-h" -> options.help = true;
                case "--videoterm" -> options.videoTerm = true;
                case "--language-card" -> options.languageCard = true;
                case "--real-time" -> options.realTime = true;
                case "--roms" -> options.roms = Path.of(value(args, ++i, flag));
                case "--folder" -> options.folder = Path.of(value(args, ++i, flag));
                case "--config" -> options.config = Path.of(value(args, ++i, flag));
                case "--plugins" -> options.plugins = value(args, ++i, flag);
                case "--prompt" -> options.prompt = text(value(args, ++i, flag), flag);
                case "--d1" -> options.drive1 = Path.of(value(args, ++i, flag));
                case "--d2" -> options.drive2 = Path.of(value(args, ++i, flag));
                case "--type" -> options.steps.add(new Type(keys(value(args, ++i, flag))));
                case "--expect" -> options.steps.add(new Expect(text(value(args, ++i, flag), flag)));
                case "--wait" -> options.steps.add(new Wait(seconds(value(args, ++i, flag), flag)));
                case "--snap" -> options.steps.add(new Snap(Path.of(value(args, ++i, flag))));
                case "--monitor" -> options.monitor = MonitorType.named(value(args, ++i, flag));
                case "--until" -> options.until = text(value(args, ++i, flag), flag);
                case "--timeout" -> options.timeout = seconds(value(args, ++i, flag), flag);
                case "--boot-timeout" -> options.bootTimeout = seconds(value(args, ++i, flag), flag);
                case "--screen" -> options.screen = Path.of(value(args, ++i, flag));
                default -> throw new IllegalArgumentException("Unknown argument \"" + flag + "\"");
            }
        }
        if (!options.help) {
            // a configuration can name the boot disk itself; whether it does is known once it's loaded
            if (options.drive1 == null && options.config == null && options.folder == null) {
                throw new IllegalArgumentException("--d1 is required: the disk to boot");
            }
            if (options.until == null) {
                throw new IllegalArgumentException("--until is required: the text that ends the run");
            }
        }
        return options;
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[i];
    }

    private static String text(String value, String flag) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(flag + " needs text to look for"); // empty text is always on screen
        }
        return value;
    }

    private static double seconds(String value, String flag) {
        double seconds;
        try {
            seconds = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " takes a number of seconds, not \"" + value + "\"");
        }
        if (!(seconds > 0) || Double.isInfinite(seconds)) {
            throw new IllegalArgumentException(flag + " must be a positive number of seconds");
        }
        return seconds;
    }

    /**
     * Splits {@code --type} text into what the typing feeder takes: runs of
     * plain text ({@link String}, with Return as {@code \n}) and raw key
     * codes ({@link Integer}).
     */
    static List<Object> keys(String value) {
        List<Object> segments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') {
                text.append(c);
                continue;
            }
            if (++i >= value.length()) {
                throw new IllegalArgumentException("--type text ends with a lone \\ (write \\\\ for a backslash)");
            }
            char escape = value.charAt(i);
            switch (escape) {
                case 'r', 'n' -> text.append('\n');
                case '\\' -> text.append('\\');
                case 'x' -> {
                    int high = i + 1 < value.length() ? Character.digit(value.charAt(i + 1), 16) : -1;
                    int low = i + 2 < value.length() ? Character.digit(value.charAt(i + 2), 16) : -1;
                    if (high < 0 || low < 0) {
                        throw new IllegalArgumentException("\\x in --type text needs two hex digits");
                    }
                    int code = high << 4 | low;
                    if (code > 0x7F) {
                        throw new IllegalArgumentException("\\x" + value.substring(i + 1, i + 3)
                            + " isn't an Apple II key code ($00-$7F)");
                    }
                    if (text.length() > 0) {
                        segments.add(text.toString());
                        text.setLength(0);
                    }
                    segments.add(code);
                    i += 2;
                }
                default -> throw new IllegalArgumentException("Unknown escape \\" + escape
                    + " in --type text (\\r, \\n, \\\\ and \\xHH are understood)");
            }
        }
        if (text.length() > 0) {
            segments.add(text.toString());
        }
        return segments;
    }
}
