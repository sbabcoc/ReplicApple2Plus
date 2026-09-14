package com.nordstrom.emulator.system;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Wraps a {@link Properties} instance and records every key actually
 * queried through it via {@link #getProperty}. Used by {@link CardTypes}
 * to catch a card's {@link SlotCard#getSupportedParameters()} disagreeing
 * with what its own {@link SlotCard#configure} actually reads -- both
 * live on the same class, but nothing stops them drifting apart from
 * each other as the code changes, so this observes the real behavior
 * directly rather than trusting the two methods agree.
 */
final class PropertyUsageTracker extends Properties {

    private final Properties delegate;
    private final Set<String> queriedKeys = new HashSet<>();

    PropertyUsageTracker(Properties delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getProperty(String key) {
        queriedKeys.add(key);
        return delegate.getProperty(key);
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        queriedKeys.add(key);
        return delegate.getProperty(key, defaultValue);
    }

    Set<String> queriedKeys() {
        return queriedKeys;
    }
}
