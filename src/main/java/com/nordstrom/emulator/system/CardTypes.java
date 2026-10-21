package com.nordstrom.emulator.system;

import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Resolves a configuration's {@code type} value to an actual
 * {@link SlotCard}: the card in the {@link CardCatalog} for an explicit
 * {@link ClassLoader} -- normally one built by {@link PluginLoader},
 * covering both this project's own built-in cards and any plugin jars --
 * whose {@link SlotCard#getShortName() short name} it is. A short name is
 * the only way a configuration names a card.
 * <p>
 * A card's settings ({@link SlotCard#getParameters}) are enforced before
 * it's configured: a key it doesn't declare fails, and so does a value a
 * setting's {@link CardParameter#problem} rejects. After
 * {@link SlotCard#configure}, so does the card having read a key it
 * doesn't declare (the loader's reserved {@link SlotCardLoader#CONFIG_DIRECTORY}
 * excepted) -- catching the declarations and the real implementation
 * disagreeing, without needing to know which one is wrong.
 */
public final class CardTypes {

    /**
     * Constructs the card {@code typeName} names and configures it with {@code cardProps}.
     *
     * @param typeName    a card's short name
     * @param cardProps   that card's own configuration properties
     * @param classLoader where to look for the card (typically one built by {@link PluginLoader})
     * @return the constructed and configured card
     * @throws IllegalArgumentException if no card has that name, or a setting is unknown or invalid
     * @throws IllegalStateException if more than one card claims that name, or the card can't be built
     */
    public static SlotCard create(String typeName, Properties cardProps, ClassLoader classLoader) {
        CardCatalog.Catalog catalog = CardCatalog.of(classLoader);
        String conflict = catalog.conflicts().get(typeName);
        if (conflict != null) {
            throw new IllegalStateException(conflict);
        }
        CardCatalog.CardDescription description = catalog.find(typeName).orElseThrow(() ->
            new IllegalArgumentException("Unknown card type \"" + typeName + "\" -- known: "
                + catalog.cards().stream().map(CardCatalog.CardDescription::shortName)
                    .collect(Collectors.toCollection(TreeSet::new))
                // A plug-in that couldn't be loaded is the likeliest reason a name is missing.
                + catalog.problems().stream().map(problem -> "; left out: " + problem.provider() + ": " + problem.message())
                    .collect(Collectors.joining())));

        Set<String> declared = description.parameters().stream().map(CardParameter::name).collect(Collectors.toSet());
        for (String key : cardProps.stringPropertyNames()) {
            if (SlotCardLoader.CONFIG_DIRECTORY.equals(key)) {
                continue;
            }
            CardParameter parameter = description.parameter(key).orElseThrow(() ->
                new IllegalArgumentException("\"" + key + "\" is not a setting of " + description.displayName()
                    + " -- its settings: " + (declared.isEmpty() ? "none" : new TreeSet<>(declared))));
            parameter.problem(cardProps.getProperty(key)).ifPresent(problem -> {
                throw new IllegalArgumentException(description.displayName() + ": " + problem);
            });
        }

        SlotCard card;
        try {
            card = description.cardClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate \"" + description.cardClass().getName()
                + "\" -- expected a public no-arg constructor (see SlotCard's Javadoc)", e);
        }
        PropertyUsageTracker tracker = new PropertyUsageTracker(cardProps);
        card.configure(tracker);
        for (String queriedKey : tracker.queriedKeys()) {
            if (!declared.contains(queriedKey) && !SlotCardLoader.CONFIG_DIRECTORY.equals(queriedKey)) {
                throw new IllegalStateException(description.cardClass().getName() + " queried config key \""
                    + queriedKey + "\" during configure(), but that key is not among its own getParameters() ("
                    + declared + "). The card's own code is internally inconsistent between the two "
                    + "methods -- fix whichever one is actually wrong.");
            }
        }
        return card;
    }

    private CardTypes() {}
}
