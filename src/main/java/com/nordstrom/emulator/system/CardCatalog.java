package com.nordstrom.emulator.system;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * Answers "what SlotCard types are actually available, and what do they
 * need?" A card's short name, display name and settings live in code, on
 * the class itself (see {@link SlotCard}'s Javadoc), so finding them means
 * constructing every registered provider through {@link ServiceLoader} and
 * asking it. {@link CardTypes} resolves a configuration's {@code type}
 * against this catalog, and a host UI offers its cards.
 * <p>
 * A provider that can't be described doesn't take the rest down with it:
 * a jar listing a class it doesn't contain, a constructor that throws, or
 * a card built against an older {@link SlotCard} -- which fails with
 * {@link AbstractMethodError} the first time a method it lacks is called
 * -- is left out and reported as a {@link Problem}, and every other card
 * is still available. So is a card whose own declarations are invalid
 * (a blank name, a setting listed twice). Two different classes claiming
 * the same short name are both left out, since a configuration naming it
 * can't say which it means; {@link CardTypes} reports that conflict, not
 * "unknown type", when a configuration uses the name.
 * <p>
 * Runnable directly:
 * <pre>
 *   java -cp ReplicApple2Plus.jar com.nordstrom.emulator.system.CardCatalog [--plugins DIR[:DIR...]]
 * </pre>
 * With no argument, lists only this project's own built-in cards. With
 * plugins directories, also lists every card in any {@code .jar} found
 * there (via {@link PluginLoader}), exactly as they'd appear to a real
 * motherboard boot using the same directories.
 */
public final class CardCatalog {

    /**
     * One available card.
     *
     * @param cardClass      the card's class, for constructing one
     * @param shortName      its name in a configuration's {@code type} key
     * @param displayName    its name in a host UI
     * @param parameters     the settings it accepts, in display order
     * @param supportedSlots the slots it can occupy
     * @param upperMemory    whether it takes over $D000-$FFFF, which only one card in a machine can
     */
    public record CardDescription(Class<? extends SlotCard> cardClass, String shortName, String displayName,
                                  List<CardParameter> parameters, Set<Integer> supportedSlots,
                                  boolean upperMemory) {

        /**
         * Takes unmodifiable copies of the settings and slots, so a description can't change after it's made.
         *
         * @param cardClass      the card's class, for constructing one
         * @param shortName      its name in a configuration's {@code type} key
         * @param displayName    its name in a host UI
         * @param parameters     the settings it accepts, in display order
         * @param supportedSlots the slots it can occupy
         * @param upperMemory    whether it takes over $D000-$FFFF, which only one card in a machine can
         */
        public CardDescription {
            parameters = List.copyOf(parameters);
            supportedSlots = Set.copyOf(supportedSlots);
        }

        /**
         * @param name a configuration key
         * @return the setting it names, or empty if this card has none by that name
         */
        public Optional<CardParameter> parameter(String name) {
            return parameters.stream().filter(parameter -> parameter.name().equals(name)).findFirst();
        }
    }

    /**
     * A provider left out of the catalog, and why.
     *
     * @param provider what's known of it: its class name, or its short name for a name conflict
     * @param message  what went wrong
     */
    public record Problem(String provider, String message) {}

    /**
     * Every card a class loader offers, and every provider it couldn't.
     *
     * @param cards     the available cards, in discovery order
     * @param problems  the providers left out
     * @param conflicts short names claimed by more than one class, each mapped to the explanation
     */
    public record Catalog(List<CardDescription> cards, List<Problem> problems, Map<String, String> conflicts) {

        /**
         * Takes unmodifiable copies of the cards, problems and conflicts, so a catalog can't change after it's read.
         *
         * @param cards     the available cards, in discovery order
         * @param problems  the providers left out
         * @param conflicts short names claimed by more than one class, each mapped to the explanation
         */
        public Catalog {
            cards = List.copyOf(cards);
            problems = List.copyOf(problems);
            conflicts = Map.copyOf(conflicts);
        }

        /**
         * @param shortName a configuration's {@code type} value
         * @return the card it names, or empty if none does
         */
        public Optional<CardDescription> find(String shortName) {
            return cards.stream().filter(card -> card.shortName().equals(shortName)).findFirst();
        }
    }

    private static ClassLoader cachedClassLoader;
    private static Catalog cachedCatalog;

    /**
     * The catalog for {@code classLoader}, read once per class loader and
     * then reused -- the set of providers a class loader can see doesn't
     * change.
     *
     * @param classLoader where to discover {@code SlotCard} providers
     * @return its catalog
     */
    public static synchronized Catalog of(ClassLoader classLoader) {
        if (cachedCatalog == null || cachedClassLoader != classLoader) {
            cachedCatalog = read(classLoader);
            cachedClassLoader = classLoader;
        }
        return cachedCatalog;
    }

    /**
     * Discovers and describes every provider {@code classLoader} can see,
     * without caching.
     *
     * @param classLoader where to discover {@code SlotCard} providers
     * @return its catalog
     */
    static Catalog read(ClassLoader classLoader) {
        Map<String, CardDescription> byName = new LinkedHashMap<>();
        Map<String, String> conflicts = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        Iterator<SlotCard> providers = ServiceLoader.load(SlotCard.class, classLoader).iterator();
        while (true) {
            SlotCard card;
            try {
                if (!providers.hasNext()) {
                    break;
                }
                card = providers.next();
            } catch (ServiceConfigurationError e) {
                // ServiceLoader goes on to the next provider after one fails.
                problems.add(new Problem("(unknown)", message(e)));
                continue;
            }
            CardDescription description;
            try {
                description = describe(card);
            } catch (RuntimeException | LinkageError e) {
                problems.add(new Problem(card.getClass().getName(), message(e)));
                continue;
            }
            String name = description.shortName();
            if (conflicts.containsKey(name)) {
                conflicts.put(name, conflicts.get(name) + ", " + description.cardClass().getName());
                continue;
            }
            CardDescription existing = byName.get(name);
            if (existing == null) {
                byName.put(name, description);
            } else if (existing.cardClass() != description.cardClass()) {
                // The same class found twice (listed by two service files) is
                // harmless; two different classes claiming one name is not.
                byName.remove(name);
                conflicts.put(name, existing.cardClass().getName() + ", " + description.cardClass().getName());
            }
        }
        conflicts.replaceAll((name, classes) ->
            "Short name \"" + name + "\" is claimed by more than one card (" + classes + ")");
        conflicts.forEach((name, explanation) -> problems.add(new Problem(name, explanation)));
        return new Catalog(List.copyOf(byName.values()), problems, conflicts);
    }

    /** Reads and checks a card's declarations. */
    private static CardDescription describe(SlotCard card) {
        String shortName = card.getShortName();
        if (shortName == null || shortName.isBlank()) {
            throw new IllegalStateException("its short name is blank");
        }
        String displayName = card.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalStateException("its display name is blank");
        }
        List<CardParameter> parameters = card.getParameters();
        if (parameters == null) {
            throw new IllegalStateException("getParameters() returned null");
        }
        Set<String> names = new HashSet<>();
        for (CardParameter parameter : parameters) {
            if (!names.add(parameter.name())) {
                throw new IllegalStateException("it declares the setting \"" + parameter.name() + "\" twice");
            }
        }
        @SuppressWarnings("unchecked")
        Class<? extends SlotCard> cardClass = (Class<? extends SlotCard>) card.getClass();
        return new CardDescription(cardClass, shortName, displayName, parameters, card.supportedSlots(),
            card.wantsUpperMemory());
    }

    /** A problem's message: an AbstractMethodError names only the missing method, so it gets an explanation. */
    private static String message(Throwable e) {
        if (e instanceof AbstractMethodError) {
            return "built for an older ReplicApple2Plus -- " + e.getMessage();
        }
        String message = e.getMessage();
        return message != null ? message : e.toString();
    }

    /**
     * Prints every available card, its settings and slots, then any provider that was left out.
     *
     * @param args {@code [--plugins DIR[:DIR...]]}, see class Javadoc
     * @throws IOException if a plugins directory can't be scanned
     */
    public static void main(String[] args) throws IOException {
        CliArgs cli = CliArgs.parse(args);

        ClassLoader classLoader = CardCatalog.class.getClassLoader();
        String pluginsPath = cli.get("plugins");
        if (pluginsPath != null) {
            classLoader = PluginLoader.load(pluginsPath, classLoader);
        }

        Catalog catalog = read(classLoader);
        if (catalog.cards().isEmpty()) {
            System.out.println("No SlotCard types found.");
        }
        for (CardDescription card : catalog.cards()) {
            System.out.println("type=" + card.shortName() + "  " + card.displayName()
                + "  (" + card.cardClass().getName() + ")");
            System.out.println("  slots: " + new TreeSet<>(card.supportedSlots()).toString().replaceAll("[\\[\\]]", ""));
            if (card.parameters().isEmpty()) {
                System.out.println("  settings: none");
            }
            for (CardParameter parameter : card.parameters()) {
                System.out.println("  " + parameter.name() + "  " + parameter.label() + " (" + describe(parameter) + ")");
            }
        }
        for (Problem problem : catalog.problems()) {
            System.out.println("left out: " + problem.provider() + ": " + problem.message());
        }
    }

    /** A setting's kind and constraints, for the listing. */
    static String describe(CardParameter parameter) {
        return switch (parameter.kind()) {
            case FILE -> parameter.extensions().isEmpty() ? "file" : "file: ." + String.join(", .", parameter.extensions());
            case FOLDER -> "folder";
            case INTEGER -> "whole number, " + parameter.min() + "-" + parameter.max() + ", default " + parameter.defaultValue();
        };
    }

    private CardCatalog() {}
}
