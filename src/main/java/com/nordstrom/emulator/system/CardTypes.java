package com.nordstrom.emulator.system;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Resolves a config file's {@code type} value to an actual {@link SlotCard},
 * constructed via real {@link java.util.ServiceLoader} against an explicit
 * {@link ClassLoader} -- normally one built by {@link PluginLoader}
 * covering both this project's own built-in cards and any externally
 * supplied plugin jars, which is what actually makes adding a new
 * peripheral possible without recompiling ReplicApple2Plus.
 * <p>
 * Two ways a {@code type} value resolves, tried in this order:
 * <ol>
 *   <li>A short name -- {@link SlotCard#getShortName()}, declared as
 *       real code on the card itself, discovered by constructing one
 *       throwaway no-arg instance of every {@code ServiceLoader}-
 *       registered provider and asking it. There is no comment-based or
 *       file-based short-name mechanism at all: the class is the single
 *       source of truth for its own name, so there is nothing separate
 *       for it to drift from.</li>
 *   <li>Otherwise, treated as a fully-qualified class name and loaded via
 *       reflection against the same {@code ClassLoader} -- open to any
 *       {@code SlotCard} it can see, known to this project in advance or
 *       not, and NOT required to be a registered SPI provider at all.
 *       This stays deliberately open, matching how real Apple II+ slots
 *       never require the motherboard's advance knowledge of a card for
 *       it to work.</li>
 * </ol>
 * A card's {@link SlotCard#getSupportedParameters()}, if non-null, is
 * enforced two ways at {@link #create}: any config-file key not on that
 * list fails, and so does the card's OWN {@link SlotCard#configure}
 * querying a key that isn't on the list either -- catching the declared
 * list and the real implementation disagreeing with each other, without
 * needing to know which one is actually wrong.
 */
public final class CardTypes {

    private static ClassLoader cachedClassLoader;
    private static Map<String, Class<? extends SlotCard>> shortNameCache;

    /**
     * Resolves and constructs the named card type -- a short name or a
     * fully-qualified class name -- against {@code classLoader}, then
     * configures it with {@code cardProps}.
     *
     * @param typeName a card's declared short name, or a fully-qualified class name
     * @param cardProps that card's own configuration properties
     * @param classLoader where to look for the card's class (typically one built by {@link PluginLoader})
     * @return the constructed and configured card
     */
    public static SlotCard create(String typeName, Properties cardProps, ClassLoader classLoader) {
        ensureRegistryBuilt(classLoader);
        Class<? extends SlotCard> cardClass = shortNameCache.get(typeName);
        if (cardClass == null) {
            cardClass = loadByClassName(typeName, classLoader);
        }
        return instantiate(cardClass, cardProps);
    }

    /** Builds (once per distinct ClassLoader, then caches) the short-name lookup table by asking every ServiceLoader-registered provider its own name. */
    private static synchronized void ensureRegistryBuilt(ClassLoader classLoader) {
        if (shortNameCache != null && cachedClassLoader == classLoader) {
            return;
        }
        Map<String, Class<? extends SlotCard>> names = new HashMap<>();
        for (SlotCard candidate : CardCatalog.allProviders(classLoader)) {
            String shortName = candidate.getShortName();
            if (shortName == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends SlotCard> candidateClass = (Class<? extends SlotCard>) candidate.getClass();
            Class<? extends SlotCard> existing = names.get(shortName);
            if (existing != null && existing != candidateClass) {
                // A genuine collision -- two different classes claiming the same
                // short name -- fails loudly. The SAME class appearing twice
                // (e.g. listed in two separate service-file resources on the
                // classpath) is harmless and idempotent, not an error.
                throw new IllegalStateException("Short name \"" + shortName + "\" is claimed by both "
                    + existing.getName() + " and " + candidateClass.getName()
                    + " -- short names must be unique across all registered cards");
            }
            names.put(shortName, candidateClass);
        }
        shortNameCache = names;
        cachedClassLoader = classLoader;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends SlotCard> loadByClassName(String className, ClassLoader classLoader) {
        Class<?> cardClass;
        try {
            cardClass = Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unknown card type \"" + className
                + "\" -- not a registered short name (known: " + shortNameCache.keySet()
                + ") and not a class visible to the given classloader", e);
        }
        if (!SlotCard.class.isAssignableFrom(cardClass)) {
            throw new IllegalArgumentException("\"" + className + "\" does not implement SlotCard");
        }
        return (Class<? extends SlotCard>) cardClass;
    }

    private static SlotCard instantiate(Class<? extends SlotCard> cardClass, Properties cardProps) {
        SlotCard card;
        try {
            card = cardClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate \"" + cardClass.getName()
                + "\" -- expected a public no-arg constructor (see SlotCard's Javadoc)", e);
        }

        Set<String> declared = card.getSupportedParameters();
        PropertyUsageTracker tracker = declared != null ? new PropertyUsageTracker(cardProps) : null;
        card.configure(tracker != null ? tracker : cardProps);

        if (declared != null) {
            for (String key : cardProps.stringPropertyNames()) {
                if (!declared.contains(key)) {
                    throw new IllegalArgumentException("\"" + key + "\" is not a recognized parameter for "
                        + cardClass.getName() + " -- known parameters: " + declared);
                }
            }
            for (String queriedKey : tracker.queriedKeys()) {
                if (!declared.contains(queriedKey)) {
                    throw new IllegalStateException(cardClass.getName() + " queried config key \"" + queriedKey
                        + "\" during configure(), but that key is not in its own getSupportedParameters() ("
                        + declared + "). The card's own code is internally inconsistent between the two "
                        + "methods -- fix whichever one is actually wrong.");
                }
            }
        }
        return card;
    }

    private CardTypes() {}
}
