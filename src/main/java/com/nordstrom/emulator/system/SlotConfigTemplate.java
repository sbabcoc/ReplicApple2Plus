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
 * by moving short names and settings off of hand-maintained comments
 * and onto real code (see {@link SlotCard}'s Javadoc). This class is a
 * snapshot, timestamped and labeled as such, valid at the moment it's
 * generated and with no promise of staying accurate afterward: install a
 * plugin, remove one, or change a card's declared settings, and this
 * has to be run again to reflect that -- it will not update itself.
 * <p>
 * Every field it writes is commented out, since it names no slot number
 * -- the user still chooses one, uncomments, and fills in real values.
 * A file built entirely from this output, pasted in unedited, is
 * harmless: {@link IniDocument} ignores comment lines entirely, so nothing
 * here can accidentally configure a slot on its own.
 * <p>
 * Usage:
 * <pre>
 *   java -cp ReplicApple2Plus.jar com.nordstrom.emulator.system.SlotConfigTemplate [--plugins DIR[:DIR...]] [--output FILE]
 * </pre>
 * Both flags are optional and independent of each other -- with no
 * {@code --output}, writes to stdout instead. Named flags, not
 * positional arguments: this way, supplying only {@code --output} can
 * never be misread as supplying plugins directories instead.
 */
public final class SlotConfigTemplate {

    /**
     * Parses {@code --plugins}/{@code --output} (see class Javadoc), generates the snapshot, and writes it to the given file or stdout.
     *
     * @param args {@code [--plugins DIR[:DIR...]] [--output FILE]}, see class Javadoc
     * @throws IOException if {@code --output} is given and can't be written, or a {@code --plugins} directory can't be scanned
     */
    public static void main(String[] args) throws IOException {
        CliArgs cli = CliArgs.parse(args);

        ClassLoader classLoader = SlotConfigTemplate.class.getClassLoader();
        String pluginsPath = cli.get("plugins");
        if (pluginsPath != null) {
            classLoader = PluginLoader.load(pluginsPath, classLoader);
        }

        String text = render(CardCatalog.of(classLoader).cards());

        String outputFile = cli.get("output");
        if (outputFile != null) {
            Files.writeString(Path.of(outputFile), text);
            System.out.println("Wrote starter config to " + outputFile);
        } else {
            System.out.print(text);
        }
    }

    /** Renders {@code cards} into the commented-out, paste-ready INI text described in this class's Javadoc. */
    static String render(List<CardCatalog.CardDescription> cards) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Starter slot configuration -- SNAPSHOT generated ").append(Instant.now()).append('\n');
        sb.append("# This is a ONE-TIME SNAPSHOT, not a live or auto-synced catalog. If a\n");
        sb.append("# plugin is added, removed, or changes its declared settings, re-run\n");
        sb.append("# this tool to regenerate it -- nothing here updates itself.\n");
        sb.append("# Every entry below is commented out: pick a slot number the card allows,\n");
        sb.append("# uncomment its lines, and fill in real values before use.\n\n");

        if (cards.isEmpty()) {
            sb.append("# No SlotCard types were found.\n");
            return sb.toString();
        }

        for (CardCatalog.CardDescription card : cards) {
            sb.append("# --- ").append(card.displayName()).append(" ---\n");
            sb.append("# [N]                 <- replace N with a slot number: ")
                .append(new java.util.TreeSet<>(card.supportedSlots()).toString().replaceAll("[\\[\\]]", ""))
                .append('\n');
            sb.append("# type=").append(card.shortName()).append('\n');
            for (CardParameter parameter : card.parameters()) {
                // The description on a line of its own: on the setting's line it would become part of the value.
                sb.append("#   ").append(parameter.label()).append(" (").append(CardCatalog.describe(parameter))
                    .append(")\n");
                sb.append("# ").append(parameter.name()).append("=")
                    .append(parameter.defaultValue() != null ? parameter.defaultValue() : "").append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private SlotConfigTemplate() {}
}
