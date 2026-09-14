package com.nordstrom.emulator.system;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Answers "what SlotCard types are actually available, and what do they
 * need?" -- the question moving short names and parameter lists onto the
 * card class itself (see {@link SlotCard}'s Javadoc) made harder to
 * answer than the old comment-based service file did: that file could be
 * read directly, by a human, with no code execution at all. This class
 * is the replacement for that lost discoverability -- it runs the same
 * kind of enumeration {@link CardTypes} does internally, but to describe
 * every available card rather than resolve one specific one.
 * <p>
 * Runnable directly:
 * <pre>
 *   java -cp ReplicApple2Plus.jar com.nordstrom.emulator.system.CardCatalog [--plugins DIR]
 * </pre>
 * With no argument, lists only this project's own built-in cards. With a
 * plugins directory argument, also lists every card in any {@code .jar}
 * found there (via {@link PluginLoader}), exactly as they'd appear to a
 * real motherboard boot using the same directory.
 */
public final class CardCatalog {

    /** One card's discoverable identity: how to refer to it in a config file, and what it accepts. */
    public record CardDescription(String shortName, String className, Set<String> supportedParameters) {

        /** How this card's config-file {@code type} value should be written -- its short name if it has one, its fully-qualified class name otherwise. */
        public String typeValue() {
            return shortName != null ? shortName : className;
        }
    }

    /** Every {@code SlotCard} visible to {@code classLoader} via real {@link ServiceLoader} discovery, described for a human. */
    public static List<CardDescription> list(ClassLoader classLoader) {
        List<CardDescription> descriptions = new ArrayList<>();
        for (SlotCard card : allProviders(classLoader)) {
            descriptions.add(new CardDescription(
                card.getShortName(), card.getClass().getName(), card.getSupportedParameters()));
        }
        return descriptions;
    }

    /**
     * The actual constructed instances, one per registered provider --
     * shared with {@link CardTypes}, which builds its short-name lookup
     * from this exact same enumeration rather than duplicating it.
     */
    static List<SlotCard> allProviders(ClassLoader classLoader) {
        List<SlotCard> providers = new ArrayList<>();
        for (SlotCard card : ServiceLoader.load(SlotCard.class, classLoader)) {
            providers.add(card);
        }
        return providers;
    }

    /** Prints every available card's config-file type value and recognized parameters to stdout. */
    public static void main(String[] args) throws IOException {
        CliArgs cli = CliArgs.parse(args);

        ClassLoader classLoader = CardCatalog.class.getClassLoader();
        String pluginsDir = cli.get("plugins");
        if (pluginsDir != null) {
            classLoader = PluginLoader.load(Path.of(pluginsDir), classLoader);
        }

        List<CardDescription> cards = list(classLoader);
        if (cards.isEmpty()) {
            System.out.println("No SlotCard types found.");
            return;
        }
        for (CardDescription card : cards) {
            System.out.println("type=" + card.typeValue() + "  (" + card.className() + ")");
            if (card.supportedParameters() != null) {
                System.out.println("  parameters: " + card.supportedParameters());
            } else {
                System.out.println("  parameters: none declared -- any key is accepted, unvalidated");
            }
        }
    }

    private CardCatalog() {}
}
