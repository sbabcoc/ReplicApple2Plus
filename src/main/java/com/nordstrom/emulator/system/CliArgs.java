package com.nordstrom.emulator.system;

import java.util.HashMap;
import java.util.Map;

/**
 * A deliberately minimal {@code --name value} flag parser, shared by this
 * package's small command-line tools ({@link CardCatalog},
 * {@link SlotConfigTemplate}) and, since it's an equally good fit for any
 * command-line entry point in this project rather than something
 * specific to this package, {@code Apple2Plus} itself. Exists
 * specifically to avoid positional argument ambiguity: a tool with two
 * or more optional arguments and no named flags has no way to let a
 * caller supply the second without the first, short of silently
 * misinterpreting one for the other. Flags have no such problem -- each
 * is independently optional, in any order.
 */
public final class CliArgs {

    private final Map<String, String> values = new HashMap<>();

    /**
     * Parses {@code args} as a sequence of {@code --name value} pairs.
     * An unpaired trailing flag (no value following it) is an error,
     * not a silently-ignored flag.
     *
     * @param args the raw command-line arguments
     * @return the parsed flags
     */
    public static CliArgs parse(String[] args) {
        CliArgs result = new CliArgs();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument \"" + arg + "\" -- expected a --flag");
            }
            String name = arg.substring(2);
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("--" + name + " requires a value");
            }
            result.values.put(name, args[i + 1]);
            i += 2;
        }
        return result;
    }

    /**
     * @param name the flag name, without its leading {@code --}
     * @return the value given for {@code --name}, or null if it wasn't supplied
     */
    public String get(String name) {
        return values.get(name);
    }
}
