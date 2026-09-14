package com.nordstrom.emulator.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Generates a one-time, paste-ready starting point for a motherboard slot
 * configuration file, listing every {@link SlotCard} {@link CardCatalog}
 * currently finds.
 * <p>
 * This is deliberately NOT a persisted or auto-synced catalog -- writing
 * one into {@code slots.ini} as comments would relocate, rather than
 * remove, exactly the drift problem this project already eliminated once
 * by moving short names and parameters off of hand-maintained comments
 * and onto real code (see {@link SlotCard}'s Javadoc). This class is a
 * snapshot, timestamped and labeled as such, valid at the moment it's
 * generated and with no promise of staying accurate afterward: install a
 * plugin, remove one, or change a card's declared parameters, and this
 * has to be run again to reflect that -- it will not update itself.
 * <p>
 * Every field it writes is commented out, since it names no slot number
 * -- the user still chooses one, uncomments, and fills in real values.
 * A file built entirely from this output, pasted in unedited, is
 * harmless: {@link IniFile} ignores comment lines entirely, so nothing
 * here can accidentally configure a slot on its own.
 * <p>
 * Usage:
 * <pre>
 *   java -cp ReplicApple2Plus.jar com.nordstrom.emulator.system.SlotConfigTemplate [--plugins DIR] [--output FILE]
 * </pre>
 * Both flags are optional and independent of each other -- with no
 * {@code --output}, writes to stdout instead. An earlier version of this
 * class took two positional arguments instead of named flags; that made
 * it impossible to supply an output file without also supplying a
 * plugins directory, since the first positional argument would have been
 * silently misread as the plugins directory regardless of intent.
 */
public final class SlotConfigTemplate {

    public static void main(String[] args) throws IOException {
        CliArgs cli = CliArgs.parse(args);

        ClassLoader classLoader = SlotConfigTemplate.class.getClassLoader();
        String pluginsDir = cli.get("plugins");
        if (pluginsDir != null) {
            classLoader = PluginLoader.load(Path.of(pluginsDir), classLoader);
        }

        String text = render(CardCatalog.list(classLoader));

        String outputFile = cli.get("output");
        if (outputFile != null) {
            Files.writeString(Path.of(outputFile), text);
            System.out.println("Wrote starter config to " + outputFile);
        } else {
            System.out.print(text);
        }
    }

    static String render(List<CardCatalog.CardDescription> cards) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Starter slot configuration -- SNAPSHOT generated ").append(Instant.now()).append('\n');
        sb.append("# This is a ONE-TIME SNAPSHOT, not a live or auto-synced catalog. If a\n");
        sb.append("# plugin is added, removed, or changes its declared parameters, re-run\n");
        sb.append("# this tool to regenerate it -- nothing here updates itself.\n");
        sb.append("# Every entry below is commented out: pick a real slot number (1-7),\n");
        sb.append("# uncomment its lines, and fill in real values before use.\n\n");

        if (cards.isEmpty()) {
            sb.append("# No SlotCard types were found.\n");
            return sb.toString();
        }

        for (CardCatalog.CardDescription card : cards) {
            sb.append("# --- ").append(card.className()).append(" ---\n");
            sb.append("# [N]                 <- replace N with a slot number, 1-7\n");
            sb.append("# type=").append(card.typeValue()).append('\n');
            if (card.supportedParameters() != null) {
                card.supportedParameters().stream().sorted()
                    .forEach(param -> sb.append("# ").append(param).append("=\n"));
            } else {
                sb.append("# (no parameters declared -- any key is accepted, unvalidated)\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private SlotConfigTemplate() {}
}
